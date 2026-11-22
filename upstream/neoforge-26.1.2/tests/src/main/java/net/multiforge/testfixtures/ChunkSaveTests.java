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

import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.multiforge.runtime.region.RegionWorkerThread;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import net.neoforged.neoforge.eventtest.internal.TestsMod;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.GameTest;

/**
 * {@code LevelChunk.markUnsaved} reaches {@code ChunkMap.chunksToEagerlySave}, a plain set the
 * server thread iterates. A worker writing it corrupted the set and crashed the server.
 */
@ForEachTest(groups = "multiforge.chunksave")
public class ChunkSaveTests {
    @GameTest(template = TestsMod.TEMPLATE_3x3, timeoutTicks = 200)
    @TestHolder(description = "A chunk marked unsaved on a region worker is handed to the server thread, not written to its eager-save set.")
    static void workerUnsavedMarkGoesThroughTheHandoff(final DynamicTest test) {
        test.onGameTest(helper -> {
            MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
            if (host == null) {
                helper.succeed(); // mode = "off"
                return;
            }
            ServerLevel level = helper.getLevel();
            BlockPos abs = helper.absolutePos(BlockPos.ZERO);
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            LevelChunk chunk = level.getChunkAt(abs);
            long key = chunk.getPos().pack();
            AtomicReference<String> outcome = new AtomicReference<>();
            helper.startSequence()
                    .thenExecute(() -> host.taskQueue().queueChunkTask(level.mfWorldRef(), abs.getX() >> 4, abs.getZ() >> 4, () -> {
                        if (!(Thread.currentThread() instanceof RegionWorkerThread)) {
                            outcome.set("the mark ran on " + Thread.currentThread().getName());
                            return;
                        }
                        // In one task on the owning region: the listener only fires on the saved -> unsaved edge.
                        chunk.tryMarkSaved();
                        chunk.markUnsaved();
                        outcome.set(chunkMap.mfHasPendingWorkerSave(key) ? "ok" : "the worker's mark bypassed the handoff queue");
                    }))
                    .thenWaitUntil(() -> helper.assertTrue(outcome.get() != null, "worker task has not run yet"))
                    .thenExecute(() -> helper.assertTrue("ok".equals(outcome.get()), outcome.get()))
                    .thenWaitUntil(() -> helper.assertFalse(chunkMap.mfHasPendingWorkerSave(key), "the server thread has not taken the mark in yet"))
                    .thenSucceed();
        });
    }
}
