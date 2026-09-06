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
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.entity.EntityMigrationCoordinator;
import net.multiforge.runtime.entity.EntityRegistry;
import net.multiforge.runtime.entity.MigratingEntityRef;
import net.multiforge.runtime.globals.CommandDispatchSystem.CommandRequest;
import net.multiforge.runtime.globals.CommandDispatchSystem.CommandScope;
import net.multiforge.runtime.globals.CommandDispatchSystem.TeleportTarget;
import net.multiforge.runtime.region.RegionizedTaskQueue;
import net.multiforge.runtime.region.ThreadedRegionizer;
import org.junit.jupiter.api.Test;

class CommandDispatchSystemTest {

    private static final WorldRef OVERWORLD = WorldRef.of("minecraft:overworld");
    private static final WorldRef GLOBAL = WorldRef.of("multiforge:global");

    private static CrossRegionEffects noopEffects() {
        return (dest, task) -> {};
    }

    /** Records every (world, chunkX, chunkZ) a task was routed to and runs it inline for assertions. */
    private static final class RecordingRouter implements CommandDispatchSystem.ChunkTaskRouter {
        record Route(WorldRef world, int chunkX, int chunkZ) {}

        final List<Route> routes = new ArrayList<>();
        final List<Runnable> pending = new ArrayList<>();

        @Override
        public void queueChunkTask(WorldRef world, int chunkX, int chunkZ, Runnable task) {
            routes.add(new Route(world, chunkX, chunkZ));
            pending.add(task);
        }

        void runAllPending() {
            List<Runnable> toRun = new ArrayList<>(pending);
            pending.clear();
            for (Runnable r : toRun) r.run();
        }
    }

    @Test
    void singleRegionCommandDispatchesToCallersOwnChunk() {
        RecordingRouter router = new RecordingRouter();
        CommandDispatchSystem sys = new CommandDispatchSystem(noopEffects(), router, GLOBAL);

        List<String> ran = new ArrayList<>();
        CommandRequest request = CommandRequest.ofCommand(OVERWORLD, 0, 0, "setblock 0 64 0 minecraft:stone");
        sys.dispatch(request, () -> ran.add("ran"));

        assertThat(router.routes).hasSize(1);
        assertThat(router.routes.get(0)).isEqualTo(new RecordingRouter.Route(OVERWORLD, 0, 0));
        router.runAllPending();
        assertThat(ran).containsExactly("ran");
        assertThat(sys.dispatchCount()).isEqualTo(1L);
    }

    @Test
    void crossRegionTeleportEscalatesToGlobalAndTriggersMigration() {
        ThreadedRegionizer regionizer = new ThreadedRegionizer(OVERWORLD, 0);
        var source = regionizer.addChunk(new net.multiforge.api.world.ChunkPos(0, 0));
        var dest = regionizer.addChunk(
                new net.multiforge.api.world.ChunkPos(313, 313)); // (5000 >> 4) = 312.5 -> 312, use distinct far chunk
        RegionizedTaskQueue taskQueue = RegionizedTaskQueue.of(regionizer);
        EntityRegistry registry = new EntityRegistry();
        EntityMigrationCoordinator coordinator = new EntityMigrationCoordinator(taskQueue, registry);

        MigratingEntityRef playerRef =
                new MigratingEntityRef(UUID.randomUUID(), OVERWORLD, new net.multiforge.api.world.ChunkPos(0, 0));
        registry.add(playerRef, "player-payload");

        RecordingRouter router = new RecordingRouter();
        CommandDispatchSystem sys = new CommandDispatchSystem(noopEffects(), router, GLOBAL, coordinator);

        BlockPos destPos = new BlockPos(313 * 16, 64, 313 * 16);
        TeleportTarget target = new TeleportTarget(playerRef, OVERWORLD, destPos);
        CommandRequest request = CommandRequest.teleport(OVERWORLD, 0, 0, "tp @s 5008 64 5008", target);

        List<String> feedback = new ArrayList<>();
        sys.dispatch(request, () -> feedback.add("teleported"));

        // Escalated to the global region's own inbox, not the caller's chunk.
        assertThat(router.routes).hasSize(1);
        assertThat(router.routes.get(0)).isEqualTo(new RecordingRouter.Route(GLOBAL, 0, 0));

        router.runAllPending();
        assertThat(sys.teleportCount()).isEqualTo(1L);
        assertThat(feedback).containsExactly("teleported");

        // The migration actually landed a task in the destination region's inbox.
        assertThat(taskQueue.drain(dest, Integer.MAX_VALUE)).isEqualTo(1);
    }

    @Test
    void functionCommandDispatchesPerVanillaSemantics() {
        RecordingRouter router = new RecordingRouter();
        CommandDispatchSystem sys = new CommandDispatchSystem(noopEffects(), router, GLOBAL);

        List<String> ran = new ArrayList<>();
        CommandRequest request = CommandRequest.ofFunction(OVERWORLD, 2, 3, "minecraft:tick");
        sys.dispatch(request, () -> ran.add("function-ran"));

        assertThat(router.routes).containsExactly(new RecordingRouter.Route(OVERWORLD, 2, 3));
        router.runAllPending();
        assertThat(ran).containsExactly("function-ran");
    }

    @Test
    void badCommandExceptionIsSwallowedAndOtherPendingCommandsStillExecute() {
        ProbeRegistry.resetForTesting();
        RecordingRouter router = new RecordingRouter();
        CommandDispatchSystem sys = new CommandDispatchSystem(noopEffects(), router, GLOBAL);

        CommandRequest bad = CommandRequest.ofCommand(OVERWORLD, 0, 0, "boom");
        CommandRequest good = CommandRequest.ofCommand(OVERWORLD, 0, 0, "fine");
        List<String> ran = new ArrayList<>();

        sys.dispatch(bad, () -> {
            throw new RuntimeException("bad command");
        });
        sys.dispatch(good, () -> ran.add("good-ran"));

        // No exception propagated out of dispatch() itself.
        router.runAllPending();

        assertThat(ran).containsExactly("good-ran");
        assertThat(ProbeRegistry.get("global.system.command_dispatch.command-command-failure"))
                .isEqualTo(1L);
    }

    @Test
    void multiRegionCommandEscalatesToGlobalRegion() {
        RecordingRouter router = new RecordingRouter();
        CommandDispatchSystem sys = new CommandDispatchSystem(noopEffects(), router, GLOBAL);

        WorldRef nether = WorldRef.of("minecraft:the_nether");
        CommandRequest request = CommandRequest.multiWorld(
                OVERWORLD,
                0,
                0,
                "execute at @a run kill @e[dx=100,dy=100,dz=100]",
                java.util.Set.of(OVERWORLD, nether));

        List<String> ran = new ArrayList<>();
        sys.dispatch(request, () -> ran.add("executed"));

        assertThat(router.routes).containsExactly(new RecordingRouter.Route(GLOBAL, 0, 0));
        router.runAllPending();
        assertThat(ran).containsExactly("executed");
    }

    @Test
    void defaultAnalyzeClassifiesAsDocumented() {
        assertThat(CommandDispatchSystem.defaultAnalyze(CommandRequest.ofCommand(OVERWORLD, 0, 0, "setblock")))
                .isEqualTo(CommandScope.SINGLE_REGION);

        WorldRef nether = WorldRef.of("minecraft:the_nether");
        assertThat(CommandDispatchSystem.defaultAnalyze(
                        CommandRequest.multiWorld(OVERWORLD, 0, 0, "cmd", java.util.Set.of(OVERWORLD, nether))))
                .isEqualTo(CommandScope.MULTI_REGION);

        MigratingEntityRef ref =
                new MigratingEntityRef(UUID.randomUUID(), OVERWORLD, new net.multiforge.api.world.ChunkPos(0, 0));
        TeleportTarget sameChunk = new TeleportTarget(ref, OVERWORLD, new BlockPos(4, 64, 4));
        assertThat(CommandDispatchSystem.defaultAnalyze(CommandRequest.teleport(OVERWORLD, 0, 0, "tp", sameChunk)))
                .isEqualTo(CommandScope.SINGLE_REGION);

        TeleportTarget farChunk = new TeleportTarget(ref, OVERWORLD, new BlockPos(5000, 64, 5000));
        assertThat(CommandDispatchSystem.defaultAnalyze(CommandRequest.teleport(OVERWORLD, 0, 0, "tp", farChunk)))
                .isEqualTo(CommandScope.CROSS_REGION_TELEPORT);
    }

    @Test
    void multiForgeOffFallthroughIsFormingSafeRequests() {
        // MultiForge-off fallthrough itself lives in the fork-side GlobalSystemsBridge guard
        // (commandDispatchReady()) — this class has no notion of "installed or not." What this
        // class must guarantee on its own side is that a CommandRequest built with no
        // CommandDispatchSystem-specific state (default factory methods) always analyzes to a
        // safe, non-throwing scope, so the bridge's fallback path never has to special-case an
        // uninitialized CommandRequest shape.
        CommandRequest request = CommandRequest.ofCommand(OVERWORLD, 0, 0, "help");
        assertThat(CommandDispatchSystem.defaultAnalyze(request)).isEqualTo(CommandScope.SINGLE_REGION);
    }

    @Test
    void nameIsStable() {
        assertThat(new CommandDispatchSystem(noopEffects(), (w, x, z, t) -> {}, GLOBAL).name())
                .isEqualTo("command_dispatch");
    }

    @Test
    void tickIsANoOpAndNeverThrows() {
        CommandDispatchSystem sys = new CommandDispatchSystem(noopEffects(), (w, x, z, t) -> {}, GLOBAL);
        sys.tick(new GlobalTickContext(1L, net.multiforge.runtime.region.RegionId.next()));
        // No exception — nothing else to assert, tick() is intentionally empty.
    }
}
