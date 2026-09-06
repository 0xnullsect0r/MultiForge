/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.multiforge.runtime.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionizedTaskQueue;
import net.multiforge.runtime.region.ThreadedRegionizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M4 Track A3.4 regression coverage — MC-free (stub Vanilla {@code Connection}/{@code
 * ServerPlayer} stand-ins, per the Track A3 work order; the real Vanilla-typed glue this mirrors
 * lives in {@code upstream/neoforge-1.21.1/.../net/multiforge/neoforge/entity/
 * NetworkMigrationBridge.java}, which cannot be unit-tested without a full Minecraft classpath).
 *
 * <p>Exercises the networking contract frozen by docs/design/entity-migration.md (networking
 * rules) and the A3 patches:
 *
 * <ul>
 *   <li>{@link #rapidCrossRegionTeleportsNeverDropQueuedPacketsOrDesyncPosition()} — T14: rapid
 *       back-and-forth cross-region hops for one connection, with simulated "heavy chat + inventory
 *       ops" (packet sends) firing while each hop is mid-flight. Asserts every packet enqueued
 *       during {@code MIGRATING} is eventually delivered, in order, exactly once (zero drop, zero
 *       duplicate) — mirroring {@link MigratingEntityRef#enqueueOutbound}/{@link
 *       MigratingEntityRef#drainOutbound} draining via {@link MigratingEntityRef#addSettledListener}
 *       (A3.2), and asserts the stand-in "Vanilla {@code setPos}" call is never made while the
 *       ref is {@code MIGRATING} (A3.1).
 *   <li>{@link #playerJoinHopCompletesUnderConcurrentLoad()} — many concurrent {@link
 *       PlayerJoinCoordinator#beginJoin} calls across several spawn regions land correctly with no
 *       lost or duplicated registrations (A3.3's end-to-end wiring).
 * </ul>
 */
class NetworkingMigrationTest {

    private static final WorldRef OW = WorldRef.of("minecraft:overworld");
    private static final WorldRef GLOBAL = WorldRef.of("multiforge:global");
    private static final ChunkPos SOURCE_CHUNK = new ChunkPos(0, 0);
    private static final ChunkPos DEST_CHUNK = new ChunkPos(1000, 1000);

    private ThreadedRegionizer regionizer;
    private RegionizedTaskQueue taskQueue;
    private EntityRegistry registry;
    private EntityMigrationCoordinator migrator;

    @BeforeEach
    void setup() {
        regionizer = new ThreadedRegionizer(OW, 0);
        taskQueue = RegionizedTaskQueue.of(regionizer);
        registry = new EntityRegistry();
        migrator = new EntityMigrationCoordinator(taskQueue, registry);
    }

    @AfterEach
    void teardown() {
        registry.close();
    }

    // === MC-free stand-ins ==========================================================================

    /** Stand-in for {@code net.minecraft.network.Connection} — records delivered packets in order. */
    private static final class FakeConnection {
        final List<String> delivered = new CopyOnWriteArrayList<>();

        void send(String packet) {
            delivered.add(packet);
        }
    }

    /** Stand-in for {@code ServerPlayer} — records every position mutation and when it happened. */
    private static final class FakePlayer {
        final AtomicInteger absMoveToCalls = new AtomicInteger();
        final AtomicBoolean absMoveToCalledWhileMigrating = new AtomicBoolean(false);
        volatile ChunkPos position;

        FakePlayer(ChunkPos initial) {
            this.position = initial;
        }

        /** Mirrors the real {@code ServerPlayer.absMoveTo} — never legitimately called while MIGRATING. */
        void absMoveTo(MigratingEntityRef refAtCallTime, ChunkPos newPos) {
            absMoveToCalls.incrementAndGet();
            if (refAtCallTime.migrationState() == MigrationState.MIGRATING) {
                absMoveToCalledWhileMigrating.set(true);
            }
            position = newPos;
        }
    }

    /** Mirrors {@code Connection.send(Packet)}'s A3.2 guard: queue while MIGRATING, else deliver. */
    private static void sendPacket(MigratingEntityRef ref, FakeConnection connection, String packet) {
        if (ref.migrationState() == MigrationState.MIGRATING) {
            ref.enqueueOutbound(packet);
        } else {
            connection.send(packet);
        }
    }

    /**
     * Mirrors {@code ServerGamePacketListenerImpl.handleMovePlayer}'s A3.1 guard: while MIGRATING,
     * the packet is deferred (never touches Vanilla position state) — this method returning {@code
     * true} is the caller's signal to return early without calling {@code absMoveTo}.
     */
    private static boolean handleMovePlayer(MigratingEntityRef ref) {
        return ref.migrationState() == MigrationState.MIGRATING;
    }

    /** Mirrors {@code NetworkMigrationBridge.handleMovePlayer}'s cross-region-detected branch. */
    private static boolean beginPlayerHop(
            EntityMigrationCoordinator migrator,
            FakePlayer player,
            FakeConnection connection,
            MigratingEntityRef ref,
            WorldRef destWorld,
            BlockPos destPos) {
        boolean began = migrator.beginMigration(ref, destWorld, destPos);
        if (began) {
            ChunkPos destChunk = destPos.toChunkPos();
            ref.addSettledListener(() -> {
                for (Object pending : ref.drainOutbound()) {
                    connection.send((String) pending);
                }
                if (ref.isRetired()) {
                    // Fires on whatever thread settled the migration — in this single-threaded test
                    // that's the test thread itself, standing in for "the destination region
                    // worker" per the real bridge's contract.
                    player.absMoveTo(ref, destChunk);
                }
            });
        }
        return began;
    }

    // === T14 — rapid cross-region /tp under heavy chat + inventory traffic ========================

    @Test
    void rapidCrossRegionTeleportsNeverDropQueuedPacketsOrDesyncPosition() {
        Region source = regionizer.addChunk(SOURCE_CHUNK);
        Region dest = regionizer.addChunk(DEST_CHUNK);

        final int hopCount = 50;
        final int packetsPerHop = 8; // simulated chat + inventory traffic while mid-flight

        UUID uuid = UUID.randomUUID();
        MigratingEntityRef initialRef = new MigratingEntityRef(uuid, OW, SOURCE_CHUNK);
        registry.add(initialRef, "player-data");

        FakeConnection connection = new FakeConnection();
        FakePlayer player = new FakePlayer(SOURCE_CHUNK);

        MigratingEntityRef currentRef = initialRef;
        List<String> expectedDelivered = new ArrayList<>();
        int deferredMoveAttempts = 0;

        for (int hop = 0; hop < hopCount; hop++) {
            boolean toDest = hop % 2 == 0;
            Region targetRegion = toDest ? dest : source;
            ChunkPos targetChunk = toDest ? DEST_CHUNK : SOURCE_CHUNK;
            BlockPos targetPos = new BlockPos(targetChunk.x() * 16 + 8, 64, targetChunk.z() * 16 + 8);

            boolean began = beginPlayerHop(migrator, player, connection, currentRef, OW, targetPos);
            assertThat(began).as("hop %d beginMigration", hop).isTrue();
            assertThat(currentRef.migrationState()).isEqualTo(MigrationState.MIGRATING);

            // Heavy chat + inventory traffic arriving while the hop is in flight.
            for (int p = 0; p < packetsPerHop; p++) {
                String packetId = "hop" + hop + "-packet" + p;
                sendPacket(currentRef, connection, packetId);
                expectedDelivered.add(packetId);
            }

            // A move packet racing the in-flight hop must be deferred, never touch position.
            int movesBefore = player.absMoveToCalls.get();
            assertThat(handleMovePlayer(currentRef)).isTrue();
            deferredMoveAttempts++;
            assertThat(player.absMoveToCalls.get()).isEqualTo(movesBefore); // no vanilla setPos happened

            // Drain the destination region — synchronously runs completeRecursive, which retires
            // currentRef and fires its settled listener (packet drain + deferred absMoveTo).
            assertThat(taskQueue.drain(targetRegion, Integer.MAX_VALUE)).isEqualTo(1);

            assertThat(currentRef.isRetired()).isTrue();
            EntityRegistry.Entry fresh = registry.get(uuid);
            assertThat(fresh).as("hop %d fresh registry entry", hop).isNotNull();
            assertThat(fresh.ref().migrationState()).isEqualTo(MigrationState.RESIDENT);
            assertThat(fresh.ref().chunkPos()).isEqualTo(targetChunk);
            currentRef = fresh.ref();
        }

        // Zero packet drop: every packet queued across all 50 hops was eventually delivered, in
        // the exact order it was enqueued (FIFO per hop, hops themselves strictly sequential here).
        assertThat(connection.delivered).containsExactlyElementsOf(expectedDelivered);
        assertThat(connection.delivered).doesNotHaveDuplicates();

        // Zero client desync: Vanilla absMoveTo was called exactly once per hop (the deferred
        // apply on settle) and never while the ref it was called against was MIGRATING.
        assertThat(player.absMoveToCalls.get()).isEqualTo(hopCount);
        assertThat(player.absMoveToCalledWhileMigrating.get()).isFalse();
        assertThat(deferredMoveAttempts).isEqualTo(hopCount);
        assertThat(registry.size()).isEqualTo(1); // no duplicate rows accumulated across 50 hops
        assertThat(taskQueue.drain(source, Integer.MAX_VALUE) + taskQueue.drain(dest, Integer.MAX_VALUE))
                .isZero(); // nothing left stuck in either inbox
    }

    // === A3.3 — PlayerJoinCoordinator.beginJoin under concurrent load ==============================

    @Test
    void playerJoinHopCompletesUnderConcurrentLoad() throws InterruptedException {
        ThreadedRegionizer globalRz = new ThreadedRegionizer(GLOBAL, 0);
        Region globalRegion = globalRz.addChunk(new ChunkPos(0, 0));

        final int spawnRegionCount = 4;
        List<Region> spawnRegions = new ArrayList<>(spawnRegionCount);
        for (int i = 0; i < spawnRegionCount; i++) {
            spawnRegions.add(regionizer.addChunk(new ChunkPos(i * 200, i * 200)));
        }

        RegionizedTaskQueue joinQueue = new RegionizedTaskQueue((w, x, z) -> {
            if (w.dimensionId().equals(GLOBAL.dimensionId())) return globalRz.regionAtChunk(x, z);
            return regionizer.regionAtChunk(x, z);
        });
        PlayerJoinCoordinator join = new PlayerJoinCoordinator(joinQueue, registry, GLOBAL, () -> {});

        final int playerCount = 200;
        List<UUID> players = new ArrayList<>(playerCount);
        for (int i = 0; i < playerCount; i++) players.add(UUID.randomUUID());

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch ready = new CountDownLatch(1);
        try {
            for (int i = 0; i < playerCount; i++) {
                UUID playerUuid = players.get(i);
                int regionIdx = i % spawnRegionCount;
                BlockPos spawnPos = new BlockPos(regionIdx * 200 * 16 + 8, 64, regionIdx * 200 * 16 + 8);
                pool.submit(() -> {
                    try {
                        ready.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    join.beginJoin(playerUuid, OW, spawnPos, "join-payload-" + playerUuid);
                });
            }
            ready.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // Drain global first (every join hops through it), then every spawn region, until all
        // playerCount rows have landed — concurrent enqueues from the pool above may still be
        // trickling into the global inbox when the first drain pass runs.
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            joinQueue.drain(globalRegion, Integer.MAX_VALUE);
            for (Region r : spawnRegions) joinQueue.drain(r, Integer.MAX_VALUE);
            return players.stream().allMatch(u -> registry.get(u) != null);
        });

        assertThat(registry.size()).isEqualTo(playerCount); // no lost, no duplicated joins
        for (UUID playerUuid : players) {
            EntityRegistry.Entry entry = registry.get(playerUuid);
            assertThat(entry).isNotNull();
            assertThat(entry.ref().migrationState()).isEqualTo(MigrationState.RESIDENT);
            assertThat(entry.payload()).isEqualTo("join-payload-" + playerUuid);
        }
    }

    // === /67 round-6 fork A HIGH — enqueueOutbound check-then-enqueue race =========================

    /**
     * Mirrors the <em>fixed</em> {@code NetworkMigrationBridge.enqueueIfMigrating} shape: enqueue,
     * then re-check state, and if the ref has already settled, reclaim the packet via {@link
     * MigratingEntityRef#removeOutbound} and deliver it directly instead of trusting a drain that
     * may already have run and missed it. The real Vanilla-typed bridge this mirrors cannot be
     * unit-tested without a full Minecraft classpath (see the class javadoc); this exercises the
     * exact same {@link MigratingEntityRef} public API the bridge calls into.
     */
    private static void sendPacketWithRaceRecovery(MigratingEntityRef ref, FakeConnection connection, String packet) {
        if (ref.migrationState() != MigrationState.MIGRATING) {
            connection.send(packet);
            return;
        }
        ref.enqueueOutbound(packet);
        // Post-enqueue re-check: a concurrent forceTerminalFromMigrating() may have already run
        // fireSettled() (and thus the settled-listener's drainOutbound() below) in the gap
        // between the check above and the enqueue just above. If so, reclaim and deliver
        // ourselves -- removeOutbound()'s identity-based removal is the at-most-once gate against
        // that same drain concurrently taking `packet` out from under us.
        if (ref.migrationState() != MigrationState.MIGRATING && ref.removeOutbound(packet)) {
            connection.send(packet);
        }
    }

    /**
     * N=2000 concurrent {@code enqueueOutbound}-guarded sends race a single {@code
     * forceTerminalFromMigrating()} call. Before the fix, any packet whose enqueue landed just
     * after the settled listener's {@code drainOutbound()} had already run would sit in {@code
     * pendingOutbound} forever -- silently dropped, never delivered. Asserts every packet is
     * delivered exactly once (count, not just presence) and that nothing is left stuck in the
     * deque afterward.
     */
    @Test
    void rapidEnqueueOutboundRacingForceTerminalDeliversEveryPacket() throws InterruptedException {
        MigratingEntityRef ref = new MigratingEntityRef(UUID.randomUUID(), OW, SOURCE_CHUNK);
        assertThat(ref.beginMigration()).isTrue();

        final int packetCount = 2000;
        FakeConnection connection = new FakeConnection();

        // Mirrors NetworkMigrationBridge#handleMovePlayer registering the settle-triggered drain
        // ahead of any packet traffic -- the real production wiring this race exercises.
        ref.addSettledListener(() -> {
            for (Object pending : ref.drainOutbound()) {
                connection.send((String) pending);
            }
        });

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch ready = new CountDownLatch(1);
        try {
            for (int i = 0; i < packetCount; i++) {
                String packetId = "race-packet-" + i;
                pool.submit(() -> {
                    awaitLatch(ready);
                    sendPacketWithRaceRecovery(ref, connection, packetId);
                });
            }
            // The racer: forces MIGRATING -> RETIRED and fires the settled listener above,
            // concurrently with the 2000 sends -- exactly the race this test guards against.
            pool.submit(() -> {
                awaitLatch(ready);
                ref.forceTerminalFromMigrating();
            });

            ready.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                    .as("pool termination")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(ref.drainOutbound()).isEmpty(); // nothing left stuck -- the bug this test guards against
        assertThat(connection.delivered).hasSize(packetCount);
        assertThat(connection.delivered).doesNotHaveDuplicates();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
