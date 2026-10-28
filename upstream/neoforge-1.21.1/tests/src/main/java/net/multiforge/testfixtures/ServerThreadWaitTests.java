/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.multiforge.testfixtures;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import net.neoforged.neoforge.eventtest.internal.TestsMod;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;

/**
 * Region-worker code that needs the server thread, while the server thread waits
 * at the tick barrier. Each used to hang the barrier or count as a cross-region
 * violation.
 */
@ForEachTest(groups = "multiforge.server-thread")
public class ServerThreadWaitTests {
    private static void onRegion(GameTestHelper helper, Runnable task) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        host.taskQueue().queueChunkTask(helper.getLevel().mfWorldRef(), abs.getX() >> 4, abs.getZ() >> 4, task);
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 100)
    @TestHolder(description = {
            "MinecraftServer.executeBlocking on a region worker (Vanilla's disconnect does it)",
            "completes, on the server thread. It used to queue the task on the server's own",
            "queue, which the server thread does not run at the barrier, and hang until the",
            "watchdog stopped the server."
    })
    static void executeBlockingFromARegionWorkerCompletes(final DynamicTest test) {
        test.onGameTest(helper -> {
            if (MultiForgeRegionizedRuntime.current() == null) {
                helper.succeed(); // mode = "off"
                return;
            }
            MinecraftServer server = helper.getLevel().getServer();
            AtomicReference<Thread> ranOn = new AtomicReference<>();
            AtomicBoolean returned = new AtomicBoolean();
            helper.startSequence()
                    .thenExecute(() -> onRegion(helper, () -> {
                        server.executeBlocking(() -> ranOn.set(Thread.currentThread()));
                        returned.set(true);
                    }))
                    .thenWaitUntil(() -> helper.assertTrue(returned.get(), "executeBlocking has not returned"))
                    .thenExecute(() -> helper.assertTrue(
                            ranOn.get() == server.getRunningThread(),
                            "the task ran on " + ranOn.get() + ", not the server thread"))
                    .thenSucceed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 200)
    @TestHolder(description = {
            "A mob changing dimension on a region worker arrives in the other level without",
            "an ownership violation: that level's regions are idle while this level's tick."
    })
    static void mobChangingDimensionOnAWorkerArrivesWithoutAViolation(final DynamicTest test) {
        test.onGameTest(helper -> {
            if (MultiForgeRegionizedRuntime.current() == null) {
                helper.succeed(); // mode = "off"
                return;
            }
            ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
            if (nether == null) {
                helper.succeed(); // no nether in this test world
                return;
            }
            // Load the destination chunk first, on the server thread, and clear a pocket
            // of air on a floor so the pig neither suffocates nor falls.
            Runnable release = TestForcedChunks.force(nether, new net.minecraft.world.level.ChunkPos(0, 0));
            helper.addEndListener(passed -> release.run());
            nether.getChunk(0, 0);
            BlockPos landing = new BlockPos(0, 64, 0);
            nether.setBlockAndUpdate(landing.below(), net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState());
            nether.setBlockAndUpdate(landing, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            nether.setBlockAndUpdate(landing.above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            long violationsBefore = ProbeRegistry.get("ServerLevel.addEntity:cross-region");
            Pig pig = helper.spawn(EntityType.PIG, new BlockPos(1, 1, 1));
            pig.setInvulnerable(true);
            pig.setNoAi(true);
            UUID id = pig.getUUID();
            AtomicReference<Entity> arrivedRef = new AtomicReference<>();
            AtomicReference<String> onWorker = new AtomicReference<>();
            helper.startSequence()
                    .thenExecute(() -> onRegion(helper, () -> {
                        onWorker.set(Thread.currentThread().getName());
                        arrivedRef.set(pig.changeDimension(new DimensionTransition(
                                nether, new Vec3(0.5, 64, 0.5), Vec3.ZERO, 0f, 0f, DimensionTransition.DO_NOTHING)));
                    }))
                    .thenWaitUntil(() -> helper.assertTrue(onWorker.get() != null, "the region task has not run"))
                    .thenExecute(() -> {
                        // The move ran inline on the worker (the nether's regions are idle
                        // while the overworld's tick): the new pig is in the nether's level
                        // right away. Whether it is visible there by UUID depends on that
                        // chunk's entity-ticking status, which a GameTest server does not
                        // reliably reach for a forced nether chunk (in mode off as well).
                        Entity arrived = arrivedRef.get();
                        helper.assertTrue(arrived != null, "changeDimension returned null");
                        helper.assertTrue(arrived != pig && pig.isRemoved(), "the pig did not leave the overworld");
                        helper.assertTrue(
                                arrived.level() == nether && !arrived.isRemoved(),
                                "the pig is not in the nether (" + arrived.level().dimension().location() + ", removed "
                                        + arrived.getRemovalReason() + ")");
                        helper.assertTrue(
                                ProbeRegistry.get("ServerLevel.addEntity:cross-region") == violationsBefore,
                                "the arrival counted as a cross-region write");
                        arrived.discard();
                    })
                    .thenSucceed();
        });
    }
}
