/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.neoforge.entity;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.neoforge.RegionizedTickCoordinator;
import net.multiforge.runtime.entity.PlayerJoinCoordinator;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;

/**
 * Vanilla-{@code Player} adapter for {@link PlayerJoinCoordinator} (M4 Track A3.3). The one call
 * site every {@code multiforge-patches/06-networking/PlayerList.java.patch} hunk uses — thin patch
 * hunk, heavy logic here (CLAUDE.md rule 6).
 *
 * <p>Wires the previously-scaffolded-but-unwired Netty→global→spawn-chunk-owning-region hop
 * ({@link PlayerJoinCoordinator#beginJoin}) end to end, and binds the player's {@link Connection}
 * to its {@link java.util.UUID} in {@link NetworkMigrationBridge} so a later cross-region hop
 * (A3.1/A3.2) can find the right connection to queue/flush packets against.
 *
 * <p>Runs <em>after</em> Vanilla's own {@code PlayerList.placeNewPlayer} has already placed the
 * player in its destination {@code ServerLevel} (via {@code serverlevel1.addNewPlayer(...)}) — this
 * hop is supplementary MultiForge registry bookkeeping, not the mechanism that actually gets the
 * player into the world. See {@link PlayerJoinCoordinator#beginJoin}'s javadoc and the idempotent
 * register-or-refresh handling in {@code PlayerJoinCoordinator#spawnAtDestination} for why the two
 * independent registration paths (Vanilla's own entity-add hook, A2.6, and this hop) don't race
 * each other into an {@code IllegalStateException}.
 */
public final class PlayerJoinBridge {

    private PlayerJoinBridge() {}

    public static void beginJoin(ServerPlayer player, Connection connection) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return; // single-region / not yet booted — vanilla's own placement is authoritative

        NetworkMigrationBridge.registerConnection(connection, player.getUUID());

        if (!(player.level() instanceof ServerLevel serverLevel)) return;
        WorldRef spawnWorld = RegionizedTickCoordinator.asWorldRef(serverLevel);
        net.minecraft.core.BlockPos vp = player.blockPosition();
        BlockPos spawnPos = new BlockPos(vp.getX(), vp.getY(), vp.getZ());

        PlayerJoinCoordinator coordinator = host.playerJoinCoordinator();
        coordinator.beginJoin(player.getUUID(), spawnWorld, spawnPos, "");
    }
}
