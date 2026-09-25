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

import net.minecraft.gametest.framework.GameTest;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import net.neoforged.neoforge.eventtest.internal.TestsMod;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;

/**
 * GameTest fixture for M8 sub-step 4: proves the process-wide
 * {@link MultiForgeRegionizedRuntime} is installed and reachable during
 * a real running server, via the {@code ServerLifecycleHooks} bootstrap
 * hook the M8 patch added.
 */
@ForEachTest(groups = "multiforge.regionized-runtime")
public class RegionizedRuntimeTests {
    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = {
            "MultiForgeRegionizedRuntime.current() returns a live host during server runtime,",
            "proving the M8 bootstrap hook fired at handleServerAboutToStart."
    })
    static void runtimeIsInstalledDuringServerRuntime(final DynamicTest test) {
        test.onGameTest(helper -> {
            MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
            helper.assertTrue(host != null, "MultiForgeRegionizedRuntime.current() must be non-null during server runtime");
            helper.assertTrue(
                    host.scheduler() != null,
                    "The installed host must expose a live TickRegionScheduler");
            helper.assertTrue(
                    host.taskQueue() != null,
                    "The installed host must expose a live RegionizedTaskQueue");
            helper.succeed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = {
            "Every loaded chunk is indexed in its world's ChunkHolderManager as owned",
            "by the region the regionizer places it in."
    })
    static void loadedChunksAreIndexedByTheirRegion(final DynamicTest test) {
        test.onGameTest(helper -> {
            MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
            helper.assertTrue(host != null, "runtime must be installed");
            net.multiforge.api.world.WorldRef world = net.multiforge.neoforge.RegionizedTickCoordinator.asWorldRef(helper.getLevel());
            net.minecraft.core.BlockPos origin = helper.absolutePos(net.minecraft.core.BlockPos.ZERO);
            int cx = origin.getX() >> 4;
            int cz = origin.getZ() >> 4;
            net.multiforge.runtime.chunk.NewChunkHolder holder = host.chunkManagerFor(world).holderAt(new net.multiforge.api.world.ChunkPos(cx, cz));
            net.multiforge.runtime.region.Region region = host.regionizerFor(world).regionAtChunk(cx, cz);
            helper.assertTrue(holder != null, "the test's chunk [" + cx + ", " + cz + "] is not indexed");
            helper.assertTrue(region != null, "the test's chunk [" + cx + ", " + cz + "] has no region");
            helper.assertTrue(region.id().equals(holder.owningRegion()),
                    "chunk indexed as owned by " + holder.owningRegion() + " but its region is " + region.id());
            helper.succeed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = {
            "M8 sub-step 6a: ChunkEvent.Load handler auto-registers loaded chunks",
            "with the regionizer, so regions exist for the level the GameTest runs in."
    })
    static void loadedChunksHaveRegions(final DynamicTest test) {
        test.onGameTest(helper -> {
            MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
            helper.assertTrue(host != null, "runtime must be installed");
            net.multiforge.api.world.WorldRef world = net.multiforge.neoforge.RegionizedTickCoordinator.asWorldRef(helper.getLevel());
            net.multiforge.runtime.region.ThreadedRegionizer regionizer = host.regionizerFor(world);
            // The GameTest's own structure is loaded, so at least the structure's chunk must be in the regionizer.
            net.minecraft.core.BlockPos anchor = helper.absolutePos(new net.minecraft.core.BlockPos(0, 0, 0));
            int chunkX = anchor.getX() >> 4;
            int chunkZ = anchor.getZ() >> 4;
            net.multiforge.runtime.region.Region region = regionizer.regionAtChunk(chunkX, chunkZ);
            helper.assertTrue(
                    region != null,
                    "expected a region for chunk (" + chunkX + "," + chunkZ + ") in world " + world.dimensionId()
                            + " — regionizer has " + regionizer.regions().size() + " region(s) live");
            helper.succeed();
        });
    }
}
