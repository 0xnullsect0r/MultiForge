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
package net.multiforge.runtime.region;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.entity.MigratingEntityRef;
import net.multiforge.runtime.entity.MigrationState;
import net.multiforge.runtime.ownership.OwnerToken;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage for the B3.3 {@code ENTITY_AI} phase wiring (docs/design/
 * m13-b3-region-tick.md §5.2): {@link MultiThreadedSchedulerHost
 * #setEntityTickRunner}, the {@code phaseEntityAiTick} body's {@link
 * OwnerToken} correctness guard, and the {@code
 * .append(Phase.ENTITY_AI, ...)} wiring installed by {@link
 * MultiThreadedSchedulerHost#installM9WiredTickBody}.
 *
 * <p>Entity ticking itself (the Vanilla {@code Entity#tick} call) lives
 * in the fork bridge ({@code net.multiforge.neoforge.tick.
 * EntityTickRunnerBridge}), which this MC-free module cannot exercise
 * directly. Instead, {@link RecordingEntityTickRunner} models "a region
 * with N owned chunks and M entities total" using only MC-free types
 * ({@link Region#ownedChunkSnapshot()}, {@link ChunkPos}, {@link
 * MigratingEntityRef}) — close enough to the real fork bridge's shape
 * (walk owned chunks, skip MIGRATING refs) to prove the wiring, guard,
 * and cross-region isolation properties this phase promises, without
 * needing any Minecraft type.
 *
 * <p>Follows the same drive-the-body-by-hand pattern as {@link
 * PhasedRegionTickBodyWiringTest} — the scheduler's worker pool is shut
 * down in {@code @BeforeEach} so tests call {@code body.tickOnce}
 * directly rather than racing the pool.
 */
class PhasedRegionTickBody_EntityAiTest {

    private static final WorldRef WORLD_A = WorldRef.of("test:entity-ai-a");
    private static final WorldRef WORLD_B = WorldRef.of("test:entity-ai-b");

    private MultiThreadedSchedulerHost host;

    @BeforeEach
    void install() {
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(1).withThreadsPerCore(1);
        host = new MultiThreadedSchedulerHost(config);
        // See PhasedRegionTickBodyWiringTest's identical @BeforeEach comment:
        // the scheduler's worker pool starts eagerly and would race a test
        // driving body.tickOnce by hand.
        host.scheduler().close();
    }

    @AfterEach
    void shutdown() {
        host.close();
    }

    /** Runs {@code work} with an {@link OwnerToken} claiming ownership of {@code region}. */
    private static void tickAsOwner(RegionTickBody body, Region region) {
        OwnerToken.runAs(OwnerToken.forRegion(region.id().value()), () -> body.tickOnce(region));
    }

    private static MigratingEntityRef resident(WorldRef world, ChunkPos pos) {
        return new MigratingEntityRef(UUID.randomUUID(), world, pos);
    }

    @Test
    void singleRegionTicksExactlyItsOwnedChunksEntitiesOnce(@TempDir Path journalDir) {
        Region region = host.touchChunk(WORLD_A, 0, 0);
        host.touchChunk(WORLD_A, 1, 0); // adjacent chunk — same region under the default regionSize

        List<ChunkPos> owned = region.ownedChunkSnapshot();
        assertThat(owned).hasSize(2);

        MigratingEntityRef e1 = resident(WORLD_A, owned.get(0));
        MigratingEntityRef e2 = resident(WORLD_A, owned.get(0));
        MigratingEntityRef e3 = resident(WORLD_A, owned.get(1));
        Map<ChunkPos, List<MigratingEntityRef>> entities =
                Map.of(owned.get(0), List.of(e1, e2), owned.get(1), List.of(e3));

        RecordingEntityTickRunner runner = new RecordingEntityTickRunner(entities);
        host.setEntityTickRunner(runner);
        host.installM9WiredTickBody(PhasedRegionTickBody.builder(), null, journalDir);

        RegionTickBody body = host.scheduler().body();
        tickAsOwner(body, region);

        assertThat(runner.invocationCount(region.id())).isEqualTo(1);
        assertThat(runner.tickedUuids(region.id())).containsExactlyInAnyOrder(e1.uuid(), e2.uuid(), e3.uuid());
    }

    @Test
    void crossRegionEntitiesNeverLeakBetweenRegions(@TempDir Path journalDir) {
        // Deliberately distinct chunk coordinates across the two worlds
        // (not both (0, 0)) — RecordingEntityTickRunner's fake entity map
        // is keyed purely by ChunkPos (no world component, matching the
        // record's fields), so two different worlds reusing the same
        // ChunkPos would collide in that map. Real production code never
        // has this issue (ChunkHolderManager is one-per-world), so this
        // is a test-fixture-only concern.
        Region regionA = host.touchChunk(WORLD_A, 0, 0);
        Region regionB = host.touchChunk(WORLD_B, 5, 5);

        ChunkPos posA = region0(regionA);
        ChunkPos posB = region0(regionB);
        MigratingEntityRef eA = resident(WORLD_A, posA);
        MigratingEntityRef eB = resident(WORLD_B, posB);
        Map<ChunkPos, List<MigratingEntityRef>> entities = new ConcurrentHashMap<>();
        entities.put(posA, List.of(eA));
        entities.put(posB, List.of(eB));

        RecordingEntityTickRunner runner = new RecordingEntityTickRunner(entities);
        host.setEntityTickRunner(runner);
        host.installM9WiredTickBody(PhasedRegionTickBody.builder(), null, journalDir);

        RegionTickBody body = host.scheduler().body();
        tickAsOwner(body, regionA);
        tickAsOwner(body, regionB);

        assertThat(runner.tickedUuids(regionA.id())).containsExactly(eA.uuid());
        assertThat(runner.tickedUuids(regionB.id())).containsExactly(eB.uuid());
        // No cross-contamination in either direction.
        assertThat(runner.tickedUuids(regionA.id())).doesNotContain(eB.uuid());
        assertThat(runner.tickedUuids(regionB.id())).doesNotContain(eA.uuid());
    }

    @Test
    void migratingEntitiesAreSkipped(@TempDir Path journalDir) {
        Region region = host.touchChunk(WORLD_A, 0, 0);
        ChunkPos pos = region0(region);

        MigratingEntityRef migrating = resident(WORLD_A, pos);
        assertThat(migrating.beginMigration()).isTrue(); // RESIDENT -> MIGRATING
        MigratingEntityRef stillResident = resident(WORLD_A, pos);

        Map<ChunkPos, List<MigratingEntityRef>> entities = Map.of(pos, List.of(migrating, stillResident));
        RecordingEntityTickRunner runner = new RecordingEntityTickRunner(entities);
        host.setEntityTickRunner(runner);
        host.installM9WiredTickBody(PhasedRegionTickBody.builder(), null, journalDir);

        RegionTickBody body = host.scheduler().body();
        tickAsOwner(body, region);

        assertThat(runner.tickedUuids(region.id())).containsExactly(stillResident.uuid());
        assertThat(runner.tickedUuids(region.id())).doesNotContain(migrating.uuid());
    }

    @Test
    void wrongOwnerGuardWarnsAndSkipsWithoutTicking(@TempDir Path journalDir) {
        Region region = host.touchChunk(WORLD_A, 0, 0);
        ChunkPos pos = region0(region);
        MigratingEntityRef e1 = resident(WORLD_A, pos);
        Map<ChunkPos, List<MigratingEntityRef>> entities = Map.of(pos, List.of(e1));

        RecordingEntityTickRunner runner = new RecordingEntityTickRunner(entities);
        host.setEntityTickRunner(runner);
        host.installM9WiredTickBody(PhasedRegionTickBody.builder(), null, journalDir);

        long warnsBefore = ProbeRegistry.get("entity-ai.wrong-owner");

        RegionTickBody body = host.scheduler().body();
        // Deliberately do NOT wrap in OwnerToken.runAs(forRegion(region.id()), ...) — the
        // calling (JUnit) thread's token is Domain.UNKNOWN, simulating a stale snapshot /
        // wrong-thread invocation.
        body.tickOnce(region);

        assertThat(runner.invocationCount(region.id())).isZero();
        assertThat(runner.tickedUuids(region.id())).isEmpty();
        assertThat(ProbeRegistry.get("entity-ai.wrong-owner")).isGreaterThan(warnsBefore);

        // A second attempt from a DIFFERENT region's token is just as wrong and just as skipped.
        Region other = host.touchChunk(WORLD_B, 0, 0);
        OwnerToken.runAs(OwnerToken.forRegion(other.id().value()), () -> body.tickOnce(region));
        assertThat(runner.invocationCount(region.id())).isZero();
    }

    private static ChunkPos region0(Region region) {
        List<ChunkPos> owned = region.ownedChunkSnapshot();
        assertThat(owned).hasSize(1);
        return owned.get(0);
    }

    /**
     * Fake {@link EntityTickRunner} that models "tick every entity in
     * this region's owned chunks" purely with MC-free types: it walks
     * {@link Region#ownedChunkSnapshot()} (the real B3.1 accessor,
     * production-backed by {@code ChunkHolderManager.holdersOwnedBy})
     * and looks up each chunk's entities in a test-supplied map,
     * skipping any ref whose {@link MigratingEntityRef#migrationState()}
     * is {@link MigrationState#MIGRATING} — mirroring the real fork
     * bridge's {@code EntityTickRunnerBridge} / patched {@code
     * ServerLevel.mfTickOneEntity}'s migration guard.
     */
    private static final class RecordingEntityTickRunner implements EntityTickRunner {
        private final Map<ChunkPos, List<MigratingEntityRef>> entitiesByChunk;
        private final Map<RegionId, AtomicInteger> invocations = new ConcurrentHashMap<>();
        private final Map<RegionId, List<UUID>> tickedByRegion = new ConcurrentHashMap<>();

        RecordingEntityTickRunner(Map<ChunkPos, List<MigratingEntityRef>> entitiesByChunk) {
            this.entitiesByChunk = entitiesByChunk;
        }

        @Override
        public void tickEntitiesForRegion(Region region) {
            invocations.computeIfAbsent(region.id(), id -> new AtomicInteger()).incrementAndGet();
            List<UUID> ticked = tickedByRegion.computeIfAbsent(region.id(), id -> new ArrayList<>());
            for (ChunkPos pos : region.ownedChunkSnapshot()) {
                for (MigratingEntityRef ref : entitiesByChunk.getOrDefault(pos, List.of())) {
                    if (ref.migrationState() == MigrationState.MIGRATING) continue;
                    ticked.add(ref.uuid());
                }
            }
        }

        int invocationCount(RegionId id) {
            AtomicInteger c = invocations.get(id);
            return c == null ? 0 : c.get();
        }

        List<UUID> tickedUuids(RegionId id) {
            return tickedByRegion.getOrDefault(id, List.of());
        }
    }
}
