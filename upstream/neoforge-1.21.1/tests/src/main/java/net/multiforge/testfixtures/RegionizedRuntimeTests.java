/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
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
}
