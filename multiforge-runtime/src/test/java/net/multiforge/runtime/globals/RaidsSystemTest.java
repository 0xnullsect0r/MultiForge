/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.globals;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.entity.EntityMigrationCoordinator;
import net.multiforge.runtime.entity.EntitySpawner;
import net.multiforge.runtime.globals.RaidStateSnapshot.RaidPhase;
import net.multiforge.runtime.region.RegionId;
import org.junit.jupiter.api.Test;

class RaidsSystemTest {

    private static final WorldRef OVERWORLD = WorldRef.of("minecraft:overworld");
    private static final WorldRef NETHER = WorldRef.of("minecraft:the_nether");

    private static CrossRegionEffects noopEffects() {
        return (dest, task) -> {};
    }

    private static EntitySpawner noopSpawner() {
        return (world, pos, factory) -> {};
    }

    @Test
    void tickAdvancesRaidStateAcrossCalls() {
        RaidsSystem sys = new RaidsSystem(noopEffects(), noopSpawner());
        List<RaidStateSnapshot> firstTick = List.of(new RaidStateSnapshot(1, RaidPhase.PRE_RAID, 0, 1, true));
        List<RaidStateSnapshot> secondTick = List.of(new RaidStateSnapshot(1, RaidPhase.IN_PROGRESS, 2, 1, true));
        List<List<RaidStateSnapshot>> queue = new ArrayList<>(List.of(firstTick, secondTick));

        sys.registerWorld(OVERWORLD, () -> queue.remove(0));

        sys.tick(new GlobalTickContext(1L, RegionId.next()));
        assertThat(sys.stateOf(OVERWORLD, 1).phase()).isEqualTo(RaidPhase.PRE_RAID);
        assertThat(sys.stateOf(OVERWORLD, 1).groupsSpawned()).isZero();

        sys.tick(new GlobalTickContext(2L, RegionId.next()));
        assertThat(sys.stateOf(OVERWORLD, 1).phase()).isEqualTo(RaidPhase.IN_PROGRESS);
        assertThat(sys.stateOf(OVERWORLD, 1).groupsSpawned()).isEqualTo(2);
        assertThat(sys.globalTickCount()).isEqualTo(2L);
    }

    @Test
    void raiderSpawnRoutesThroughSpawnerWithExpectedArgs() {
        record Call(WorldRef world, BlockPos pos, EntityMigrationCoordinator.NewEntitySpec spec) {}
        List<Call> calls = new ArrayList<>();
        EntitySpawner spy = (world, pos, factory) -> calls.add(new Call(world, pos, factory.apply(pos)));

        RaidsSystem sys = new RaidsSystem(noopEffects(), spy);
        BlockPos spawnPos = new BlockPos(100, 65, -40);
        java.util.UUID raiderId = java.util.UUID.randomUUID();

        sys.spawnRaider(OVERWORLD, spawnPos, pos -> new EntityMigrationCoordinator.NewEntitySpec(raiderId, "pillager"));

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).world()).isEqualTo(OVERWORLD);
        assertThat(calls.get(0).pos()).isEqualTo(spawnPos);
        assertThat(calls.get(0).spec().uuid()).isEqualTo(raiderId);
        assertThat(calls.get(0).spec().payload()).isEqualTo("pillager");
    }

    @Test
    void raiderSpawnFailureIsIsolatedAndNeverThrows() {
        RaidsSystem sys = new RaidsSystem(noopEffects(), (world, pos, factory) -> {
            throw new RuntimeException("spawner boom");
        });

        sys.spawnRaider(
                OVERWORLD,
                new BlockPos(0, 64, 0),
                pos -> new EntityMigrationCoordinator.NewEntitySpec(java.util.UUID.randomUUID(), "vindicator"));
        // No exception propagated — the try/catch inside spawnRaider() must have caught it.
    }

    @Test
    void raidEffectTargetsExpectedRegion() {
        List<RegionId> destinations = new ArrayList<>();
        List<Runnable> enqueued = new ArrayList<>();
        RaidsSystem sys = new RaidsSystem(
                (dest, task) -> {
                    destinations.add(dest);
                    enqueued.add(task);
                },
                noopSpawner());

        RegionId villageRegion = RegionId.next();
        List<String> ran = new ArrayList<>();
        sys.raidEffect(villageRegion, () -> ran.add("bell-rung"));

        assertThat(destinations).containsExactly(villageRegion);
        assertThat(enqueued).hasSize(1);
        enqueued.get(0).run();
        assertThat(ran).containsExactly("bell-rung");
    }

    @Test
    void noRegisteredWorldTicksGracefullyWithoutThrowing() {
        RaidsSystem sys = new RaidsSystem(noopEffects(), noopSpawner());

        sys.tick(new GlobalTickContext(1L, RegionId.next()));

        assertThat(sys.globalTickCount()).isEqualTo(1L);
        assertThat(sys.isHandling(OVERWORLD)).isFalse();
        assertThat(sys.stateOf(OVERWORLD, 1)).isNull();
    }

    @Test
    void oneWorldThrowingDoesNotPreventOtherWorldsStateFromAdvancing() {
        RaidsSystem sys = new RaidsSystem(noopEffects(), noopSpawner());
        sys.registerWorld(OVERWORLD, () -> {
            throw new RuntimeException("boom");
        });
        sys.registerWorld(NETHER, () -> List.of(new RaidStateSnapshot(7, RaidPhase.VICTORY, 5, 4, false)));

        sys.tick(new GlobalTickContext(1L, RegionId.next()));

        assertThat(sys.stateOf(OVERWORLD, 1)).isNull();
        assertThat(sys.stateOf(NETHER, 7).phase()).isEqualTo(RaidPhase.VICTORY);
    }

    @Test
    void nameIsStable() {
        assertThat(new RaidsSystem(noopEffects(), noopSpawner()).name()).isEqualTo("raids");
    }
}
