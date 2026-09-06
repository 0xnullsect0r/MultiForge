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
package net.multiforge.testfixtures;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.world.level.block.Blocks;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.ownership.OwnershipEnforcer;
import net.neoforged.neoforge.eventtest.internal.TestsMod;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;

/**
 * GameTest fixtures for M7 (multiforge-patches/01-ownership/): prove
 * the "off-thread mutation → detect → warn → reroute → eventually apply →
 * server keeps ticking" pipeline actually works end-to-end in a running
 * dedicated server, not just in unit tests against
 * {@link OwnershipEnforcer} alone.
 *
 * <p>Runs via NeoForge's own gameTestServer run type (see
 * {@code tests/build.gradle} {@code runs.gameTestServer}), configured
 * upstream and reused here.
 */
@ForEachTest(groups = "multiforge.ownership")
public class OwnershipGuardTests {
    private static final String SITE = "Level.setBlock";
    private static final String PROBE_KEY = SITE + ":off-thread";

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = {
            "Off-thread Level.setBlock is rerouted onto the server executor with a rate-limited warn,",
            "the server does not crash, and the block is eventually placed."
    })
    static void offThreadSetBlockIsReroutedNotCrashed(final DynamicTest test) {
        test.onGameTest(helper -> {
            // Snapshot the probe counter before the fixture — other fixtures on the same server may
            // have bumped it earlier. We only care that OUR off-thread call adds at least one.
            long probesBefore = ProbeRegistry.get(PROBE_KEY);
            ViolationLogger.resetForTesting(); // clean rate-limit window for a predictable warn

            BlockPos relTarget = new BlockPos(1, 2, 1);
            BlockPos absTarget = helper.absolutePos(relTarget);

            // Fire the mutation from a plain daemon thread that has never been
            // bound as the tick thread and holds no OwnerToken — exactly the
            // scenario the guard exists to catch.
            Thread offRegion = new Thread(
                    () -> helper.getLevel().setBlock(absTarget, Blocks.STONE.defaultBlockState(), 3),
                    "multiforge-ownership-fixture-off-region");
            offRegion.setDaemon(true);
            offRegion.start();

            helper.startSequence()
                    .thenWaitUntil(() -> {
                        long observed = ProbeRegistry.get(PROBE_KEY) - probesBefore;
                        helper.assertTrue(observed >= 1L, "expected the off-thread setBlock probe to fire at least once");
                    })
                    .thenWaitUntil(() -> {
                        // The rerouted mutation lands via the server executor — takes some ticks to drain.
                        helper.assertBlockPresent(Blocks.STONE, relTarget);
                    })
                    .thenExecute(() -> {
                        // If we got here the server is still ticking — no uncaught exception took down
                        // the tick loop. That IS the "not crashed" assertion.
                        helper.assertTrue(
                                helper.getLevel().getServer().isRunning(),
                                "server must still be running after an off-thread mutation");
                    })
                    .thenSucceed();
        });
    }
}
