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

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.pig.Pig;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionWorkerThread;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.region.TickRegionScheduler;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import net.neoforged.neoforge.eventtest.internal.TestsMod;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.GameTest;

/**
 * The GameTest server ticks regions on workers ({@code tests/build.gradle} turns
 * off the server-thread placement of a lone or hot region). Without this the
 * region-tick tests could pass with every region ticking on the server thread,
 * and prove nothing about parallel ticking.
 */
@ForEachTest(groups = "multiforge.placement")
public class RegionPlacementTests {
    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 100)
    @TestHolder(description = {
            "The test's own region ticks on a region worker thread, not on the server thread:",
            "its last placement is WORKER and a task queued on its mailbox runs on a",
            "multiforge-tick worker."
    })
    static void testRegionTicksOnAWorker(final DynamicTest test) {
        test.onGameTest(helper -> {
            MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
            if (host == null) {
                helper.succeed(); // mode = "off"
                return;
            }
            BlockPos abs = helper.absolutePos(BlockPos.ZERO);
            ThreadedRegionizer regionizer = host.regionizerForOrNull(helper.getLevel().mfWorldRef());
            AtomicReference<String> ranOn = new AtomicReference<>();
            helper.startSequence()
                    .thenExecute(() -> host.taskQueue().queueChunkTask(
                            helper.getLevel().mfWorldRef(),
                            abs.getX() >> 4,
                            abs.getZ() >> 4,
                            () -> ranOn.set(Thread.currentThread().getName())))
                    .thenWaitUntil(() -> helper.assertTrue(ranOn.get() != null, "mailbox task has not run yet"))
                    .thenExecute(() -> {
                        Region region = regionizer == null ? null : regionizer.regionAtChunk(abs.getX() >> 4, abs.getZ() >> 4);
                        helper.assertTrue(region != null, "the test's chunk has no region");
                        TickRegionScheduler.TickPlacement placement = host.scheduler().lastTickPlacement(region);
                        helper.assertTrue(
                                placement == TickRegionScheduler.TickPlacement.WORKER,
                                "the test's region last ticked on " + placement + " — the GameTest server must tick"
                                        + " regions on workers (multiforge.inlineSingleRegion=false)");
                        helper.assertTrue(
                                !ranOn.get().equals(helper.getLevel().getServer().getRunningThread().getName()),
                                "the region's mailbox task ran on the server thread (" + ranOn.get() + ")");
                    })
                    .thenSucceed();
        });
    }

    /**
     * Cupboard holds back an entity added from a thread without "server" in its name, where no
     * mob cap can count it. This applies that check on a real worker, around a real add.
     */
    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 100)
    @TestHolder(description = "An entity added on a region worker passes a Cupboard-style thread-name check and is visible at once.")
    static void workerEntityAddPassesACupboardStyleThreadCheck(final DynamicTest test) {
        test.onGameTest(helper -> {
            MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
            if (host == null) {
                helper.succeed(); // mode = "off"
                return;
            }
            ServerLevel level = helper.getLevel();
            BlockPos abs = helper.absolutePos(new BlockPos(1, 2, 1));
            AtomicReference<String> outcome = new AtomicReference<>();
            helper.startSequence()
                    .thenExecute(() -> host.taskQueue().queueChunkTask(level.mfWorldRef(), abs.getX() >> 4, abs.getZ() >> 4, () -> {
                        Thread thread = Thread.currentThread();
                        if (!(thread instanceof RegionWorkerThread)) {
                            outcome.set("the add ran on " + thread.getName() + ", not a region worker");
                        } else if (!thread.getName().toLowerCase(Locale.ROOT).contains("server")) {
                            outcome.set("Cupboard would hold back an add from " + thread.getName());
                        } else {
                            Pig pig = EntityType.PIG.create(level, EntitySpawnReason.TRIGGERED);
                            pig.setPos(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5);
                            boolean added = level.addFreshEntity(pig);
                            boolean visible = level.getEntity(pig.getUUID()) == pig;
                            pig.discard();
                            outcome.set(added && visible ? "ok" : "added=" + added + " visible=" + visible);
                        }
                    }))
                    .thenWaitUntil(() -> helper.assertTrue(outcome.get() != null, "mailbox task has not run yet"))
                    .thenExecute(() -> helper.assertTrue("ok".equals(outcome.get()), outcome.get()))
                    .thenSucceed();
        });
    }
}
