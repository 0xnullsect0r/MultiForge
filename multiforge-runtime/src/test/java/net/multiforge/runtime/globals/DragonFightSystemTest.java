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
package net.multiforge.runtime.globals;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.ChunkLoadLevel;
import net.multiforge.runtime.chunk.NewChunkHolder;
import net.multiforge.runtime.entity.EntityMigrationCoordinator;
import net.multiforge.runtime.region.RegionId;
import org.junit.jupiter.api.Test;

class DragonFightSystemTest {

    private static final WorldRef THE_END = WorldRef.of("minecraft:the_end");
    private static final ChunkPos ORIGIN_CHUNK = new ChunkPos(0, 0);

    private static CrossRegionEffects recording(List<CrossRegionCall> calls) {
        return (dest, task) -> calls.add(new CrossRegionCall(dest, task));
    }

    private record CrossRegionCall(RegionId dest, Runnable task) {}

    private static DragonFightSystem system(CrossRegionEffects effects) {
        return new DragonFightSystem(effects, (destWorld, destPos, factory) -> {});
    }

    /** Minimal, fully-controllable {@link DragonFightSystem.DragonFightTarget} stub. */
    private static final class StubTarget implements DragonFightSystem.DragonFightTarget {
        RegionId region;
        ChunkHolderManager manager;
        List<ChunkPos> chunks = List.of(ORIGIN_CHUNK);
        boolean shouldPin;
        int tickBodyCalls;
        DragonFightSystem.PendingDragonSpawn pendingSpawn;

        @Override
        public RegionId currentRegion() {
            return region;
        }

        @Override
        public ChunkHolderManager chunkHolderManager() {
            return manager;
        }

        @Override
        public List<ChunkPos> arenaChunks() {
            return chunks;
        }

        @Override
        public boolean shouldPin() {
            return shouldPin;
        }

        @Override
        public void tickBody() {
            tickBodyCalls++;
        }

        @Override
        public DragonFightSystem.PendingDragonSpawn pollPendingSpawn() {
            DragonFightSystem.PendingDragonSpawn p = pendingSpawn;
            pendingSpawn = null;
            return p;
        }
    }

    @Test
    void shouldPinAddsDragonTicketAndPromotesToTicking() {
        ChunkHolderManager manager = new ChunkHolderManager(THE_END);
        RegionId region = RegionId.next();
        StubTarget target = new StubTarget();
        target.region = region;
        target.manager = manager;
        target.shouldPin = true;

        DragonFightSystem sys = system((dest, task) -> {});
        sys.registerWorld(THE_END, target);
        sys.tick(new GlobalTickContext(1L, RegionId.next()));

        NewChunkHolder holder = manager.holderAt(ORIGIN_CHUNK);
        assertThat(holder).as("DRAGON ticket lazily creates the holder").isNotNull();
        assertThat(holder.level())
                .as("TicketType.DRAGON's default distance (TICKING) promotes the arena chunk")
                .isEqualTo(ChunkLoadLevel.TICKING);

        // shouldPin drops back to false (dragon-event players emptied) — the ticket must come off.
        target.shouldPin = false;
        sys.tick(new GlobalTickContext(2L, RegionId.next()));

        assertThat(holder.level())
                .as("releasing the DRAGON ticket demotes the arena chunk back to INACCESSIBLE")
                .isEqualTo(ChunkLoadLevel.INACCESSIBLE);
    }

    @Test
    void unregisterWorldReleasesALeakedDragonTicket() {
        ChunkHolderManager manager = new ChunkHolderManager(THE_END);
        RegionId region = RegionId.next();
        StubTarget target = new StubTarget();
        target.region = region;
        target.manager = manager;
        target.shouldPin = true;

        DragonFightSystem sys = system((dest, task) -> {});
        sys.registerWorld(THE_END, target);
        sys.tick(new GlobalTickContext(1L, RegionId.next()));
        assertThat(manager.holderAt(ORIGIN_CHUNK).level()).isEqualTo(ChunkLoadLevel.TICKING);

        // Simulate LevelEvent.Unload firing while the fight is still "active" (players present) —
        // docs/design/global-region.md §5.2: DragonFightSystem must not leak the ticket.
        sys.unregisterWorld(THE_END);

        assertThat(manager.holderAt(ORIGIN_CHUNK).level())
                .as("unregisterWorld proactively releases a still-held DRAGON ticket")
                .isEqualTo(ChunkLoadLevel.INACCESSIBLE);
        assertThat(sys.isHandling(THE_END)).isFalse();
    }

    @Test
    void tickAdvancesThePhaseStateMachineViaCrossRegionEffect() {
        List<CrossRegionCall> calls = new ArrayList<>();
        RegionId region = RegionId.next();
        StubTarget target = new StubTarget();
        target.region = region;

        DragonFightSystem sys = system(recording(calls));
        sys.registerWorld(THE_END, target);
        sys.tick(new GlobalTickContext(1L, RegionId.next()));

        assertThat(calls).as("tickBody() is dispatched, not run inline").hasSize(1);
        assertThat(target.tickBodyCalls).as("not yet run — only enqueued").isZero();

        calls.get(0).task().run();
        assertThat(target.tickBodyCalls)
                .as("running the enqueued task advances Vanilla's own phase state machine")
                .isEqualTo(1);

        // A second tick() call must advance again — the state machine keeps progressing tick over tick.
        sys.tick(new GlobalTickContext(2L, RegionId.next()));
        calls.get(1).task().run();
        assertThat(target.tickBodyCalls).isEqualTo(2);
    }

    @Test
    void crossRegionEffectTargetsTheArenaOwningRegionNotTheGlobalRegion() {
        List<CrossRegionCall> calls = new ArrayList<>();
        RegionId endRegion = RegionId.next();
        RegionId globalRegion = RegionId.next();
        StubTarget target = new StubTarget();
        target.region = endRegion;

        DragonFightSystem sys = system(recording(calls));
        sys.registerWorld(THE_END, target);
        sys.tick(new GlobalTickContext(1L, globalRegion));

        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).dest())
                .as("dispatch targets the End arena's owning region, never the global region")
                .isEqualTo(endRegion)
                .isNotEqualTo(globalRegion);
    }

    @Test
    void gracefulFallthroughWhenNoWorldIsRegistered() {
        DragonFightSystem sys = system((dest, task) -> {
            throw new AssertionError("must not dispatch any cross-region effect — nothing registered");
        });

        // Mirrors the "MultiForge not installed" / "level not yet registered" case: isHandling()
        // is false and tick() is a safe no-op, matching GlobalSystemsBridge.dragonFightReady()'s
        // fallthrough contract (the patched EndDragonFight.tick() falls back to Vanilla).
        assertThat(sys.isHandling(THE_END)).isFalse();
        assertThatCodeDoesNotThrow(() -> sys.tick(new GlobalTickContext(1L, RegionId.next())));
        assertThat(sys.isHandling(THE_END)).isFalse();
    }

    @Test
    void pendingDragonSpawnRoutesThroughTheInjectedEntitySpawner() {
        List<Object[]> spawns = new ArrayList<>();
        RegionId region = RegionId.next();
        StubTarget target = new StubTarget();
        target.region = region;
        BlockPos spawnPos = new BlockPos(0, 128, 0);
        UUID dragonId = UUID.randomUUID();
        Function<BlockPos, EntityMigrationCoordinator.NewEntitySpec> factory =
                pos -> new EntityMigrationCoordinator.NewEntitySpec(dragonId, "ender_dragon");
        target.pendingSpawn = new DragonFightSystem.PendingDragonSpawn(spawnPos, factory);

        DragonFightSystem sys = new DragonFightSystem(
                (dest, task) -> {}, (destWorld, destPos, f) -> spawns.add(new Object[] {destWorld, destPos, f}));
        sys.registerWorld(THE_END, target);
        sys.tick(new GlobalTickContext(1L, RegionId.next()));

        assertThat(spawns).hasSize(1);
        assertThat(spawns.get(0)[0]).isEqualTo(THE_END);
        assertThat(spawns.get(0)[1]).isEqualTo(spawnPos);
        @SuppressWarnings("unchecked")
        Function<BlockPos, EntityMigrationCoordinator.NewEntitySpec> routedFactory =
                (Function<BlockPos, EntityMigrationCoordinator.NewEntitySpec>) spawns.get(0)[2];
        assertThat(routedFactory.apply(spawnPos).uuid()).isEqualTo(dragonId);
    }

    private static void assertThatCodeDoesNotThrow(Runnable r) {
        r.run();
    }
}
