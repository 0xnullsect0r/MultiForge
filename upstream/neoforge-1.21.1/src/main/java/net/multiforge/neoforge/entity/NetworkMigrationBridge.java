/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.neoforge.entity;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.neoforge.RegionizedTickCoordinator;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.entity.EntityMigrationCoordinator;
import net.multiforge.runtime.entity.EntityRegistry;
import net.multiforge.runtime.entity.MigratingEntityRef;
import net.multiforge.runtime.entity.MigrationState;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;

/**
 * Vanilla networking &lt;-&gt; MultiForge entity-migration adapter (M4 Track A3). Every {@code
 * multiforge-patches/06-networking/} hunk calls into exactly one of these static methods — thin
 * patch hunks, heavy logic here (CLAUDE.md rule 6).
 *
 * <h2>Why players don't go through {@link EntityMigrationBridge}'s NBT-recreate path</h2>
 *
 * <p>{@link EntityMigrationBridge}'s generic entity hop tears the source-side Vanilla {@code
 * Entity} object down ({@code setRemoved}) and materializes a brand-new one at the destination
 * from a captured NBT snapshot — correct and necessary for mobs/items, where the Java object has
 * no external identity anyone holds onto. A {@code ServerPlayer} is different: a live Netty {@link
 * Connection} and {@code ServerGamePacketListenerImpl} hold a direct reference to that exact
 * object, and there is no way to "recreate" it without kicking the client. So a player's
 * cross-region hop keeps the same {@code ServerPlayer} object alive the whole time — only the
 * {@link MigratingEntityRef} bookkeeping (which region currently owns dispatching this player's
 * ticks / entity-registry membership) actually migrates. The two things this class enforces to
 * make that safe:
 *
 * <ul>
 *   <li>Never let Vanilla's own movement math (ultimately {@code Entity.absMoveTo} /
 *       {@code setPosRaw}) run for a move packet while the player's ref is {@code MIGRATING} —
 *       {@link #handleMovePlayer}.
 *   <li>Never let a clientbound packet reach the wire while the player's ref is {@code MIGRATING}
 *       — queue it on the ref's {@code pendingOutbound} deque instead, drained once the ref
 *       settles — {@link #enqueueIfMigrating}.
 * </ul>
 */
public final class NetworkMigrationBridge {

    private NetworkMigrationBridge() {}

    /**
     * {@code Connection -> player UUID}, populated by {@link PlayerJoinBridge} once a connection is
     * bound to a placed player. A {@link java.util.WeakHashMap} would leak-proof this further, but
     * {@code Connection} objects are 1:1 with a player session and are already dropped by Vanilla's
     * own {@code PlayerList} bookkeeping on disconnect; a plain map keyed by object identity is
     * sized by concurrent connection count, not lifetime connection count, and is bounded by the
     * server's own player-count limits.
     */
    private static final Map<Connection, UUID> CONNECTION_PLAYER = new ConcurrentHashMap<>();

    static void registerConnection(Connection connection, UUID playerUuid) {
        CONNECTION_PLAYER.put(Objects.requireNonNull(connection, "connection"), Objects.requireNonNull(playerUuid, "playerUuid"));
    }

    // === A3.1 — ServerGamePacketListenerImpl.handleMovePlayer ====================================

    /**
     * Called from the patched {@code handleMovePlayer} after Vanilla clamps/wraps the packet's
     * requested position but <em>before</em> any of Vanilla's own movement physics or {@code
     * absMoveTo} runs.
     *
     * @return {@code true} iff the caller must return immediately without running any further
     *     Vanilla movement handling for this packet — either because a migration for this player
     *     is already in flight (packet deferred entirely, never touches Vanilla position state) or
     *     because this call just detected a region crossing and began one (the eventual settle
     *     applies the deferred move on the destination region's own worker thread). {@code false}
     *     means: not tracked, same-region, or MultiForge isn't booted — Vanilla proceeds exactly as
     *     it always has.
     */
    public static boolean handleMovePlayer(ServerPlayer player, double x, double y, double z, float yRot, float xRot) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return false; // single-region / not yet booted

        EntityRegistry registry = host.entityRegistry();
        MigratingEntityRef ref = registry.lookup(player.getUUID());
        if (ref == null) return false; // not tracked yet — let vanilla proceed as normal

        MigrationState state = ref.migrationState();
        if (state == MigrationState.MIGRATING) {
            // A hop for this player is already in flight. Never call Vanilla setPos while
            // MIGRATING — defer this packet outright. The client resyncs once the destination
            // settles and (A3.2) the queued outbound packets flush.
            ProbeRegistry.bump("entity-migration.network.move-deferred");
            return true;
        }
        if (state != MigrationState.RESIDENT) {
            return false; // RETIRED — nothing sensible to do at the network layer, let vanilla's own disconnect path run
        }

        if (!(player.level() instanceof ServerLevel serverLevel)) return false;
        WorldRef world = RegionizedTickCoordinator.asWorldRef(serverLevel);
        ThreadedRegionizer regionizer = host.regionizerForOrNull(world);
        if (regionizer == null) return false;

        int destChunkX = ((int) Math.floor(x)) >> 4;
        int destChunkZ = ((int) Math.floor(z)) >> 4;
        Region destRegion = regionizer.regionAtChunk(destChunkX, destChunkZ);
        if (destRegion == null) return false; // destination not yet regionized — let vanilla's move happen, retry next packet

        ChunkPos srcChunk = ref.chunkPos();
        Region srcRegion = regionizer.regionAtChunk(srcChunk.x(), srcChunk.z());
        if (srcRegion != null && destRegion.id().equals(srcRegion.id())) {
            return false; // ordinary in-region movement — cheap fast path
        }

        // Cross-region move detected at the packet layer, ahead of Vanilla's own movement physics
        // running against soon-to-be-foreign region state. Trigger the hop now; apply the actual
        // position update once it settles, on whichever thread legitimately owns the player by
        // then (the destination region worker — see the settled-listener below).
        BlockPos destPos = new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        boolean began = host.entityMigrationCoordinator().beginMigration(ref, world, destPos);
        if (began) {
            ProbeRegistry.bump("entity-migration.network.move-triggered-hop");
            Connection connection = player.connection.getConnection();
            ref.addSettledListener(() -> {
                drainOutbound(connection, ref);
                if (ref.isRetired()) {
                    // Successful hop: this fires from EntityMigrationCoordinator#completeRecursive,
                    // running on the destination region worker's own thread — safe, on-thread
                    // mutation (CLAUDE.md rule 4), not a cross-region touch.
                    player.absMoveTo(x, y, z, yRot, xRot);
                    if (player.level() instanceof ServerLevel destLevel) {
                        destLevel.getChunkSource().move(player);
                    }
                }
                // Else: aborted (destination never reached BORDER in time, or a same-tick CAS
                // race). Leave the player's Vanilla position untouched — the next move packet
                // from the client re-evaluates against current (possibly reverted) region state.
            });
        } else {
            ProbeRegistry.bump("entity-migration.network.move-hop-lost-cas");
        }
        // Either way: never let this specific packet reach Vanilla's absMoveTo this tick once a
        // region crossing was detected — avoids racing Vanilla's own movement/collision checks
        // against a position that is (or just was) crossing a region boundary.
        return true;
    }

    // === A3.2 — Connection.send(Packet) ============================================================

    /** Boxed clientbound send request queued on {@link MigratingEntityRef#pendingOutbound}. */
    private record PendingPacket(Packet<?> packet, PacketSendListener listener, boolean flush) {}

    /**
     * Called from the patched {@code Connection.send(Packet, PacketSendListener, boolean)} before
     * any of Vanilla's own send logic runs.
     *
     * @return {@code true} iff {@code packet} was queued instead of sent — caller must return
     *     immediately. {@code false} means: this connection isn't a tracked player, or the
     *     player's ref isn't {@code MIGRATING} — Vanilla's normal send path runs unmodified.
     */
    public static boolean enqueueIfMigrating(Connection connection, Packet<?> packet, PacketSendListener listener, boolean flush) {
        UUID uuid = CONNECTION_PLAYER.get(connection);
        if (uuid == null) return false;
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return false;
        MigratingEntityRef ref = host.entityRegistry().lookup(uuid);
        if (ref == null || ref.migrationState() != MigrationState.MIGRATING) return false;
        ref.enqueueOutbound(new PendingPacket(packet, listener, flush));
        ProbeRegistry.bump("entity-migration.network.packet-queued");
        return true;
    }

    private static void drainOutbound(Connection connection, MigratingEntityRef ref) {
        for (Object pending : ref.drainOutbound()) {
            PendingPacket pp = (PendingPacket) pending;
            ProbeRegistry.bump("entity-migration.network.packet-flushed");
            connection.send(pp.packet(), pp.listener(), pp.flush());
        }
    }

}
