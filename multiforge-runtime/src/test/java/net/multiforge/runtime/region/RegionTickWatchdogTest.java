/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RegionTickWatchdogTest {

    private static final WorldRef WORLD = WorldRef.of("test:world");

    @AfterEach
    void reset() {
        RegionTickWatchdog.resetForTesting();
        ProbeRegistry.resetForTesting();
        ViolationLogger.resetForTesting();
    }

    @Test
    void fastTickDoesNotFireOverrun() {
        Region region = new ThreadedRegionizer(WORLD, 0).addChunk(new ChunkPos(0, 0));
        RegionTickWatchdog.setWarnMsForTesting(10_000);

        RegionTickWatchdog.enterTick(region);
        // Body does nothing observable.
        RegionTickWatchdog.exitTick(region);

        assertThat(ProbeRegistry.get("region-tick.overrun")).isZero();
    }

    @Test
    void slowTickInWarnModeLogsButDoesNotThrow() {
        Region region = new ThreadedRegionizer(WORLD, 0).addChunk(new ChunkPos(0, 0));
        RegionTickWatchdog.setWarnMsForTesting(0); // any positive elapsed is a violation

        RegionTickWatchdog.enterTick(region);
        // Force at least 1 ns elapsed
        try {
            Thread.sleep(1);
        } catch (InterruptedException ignored) {
        }
        RegionTickWatchdog.exitTick(region);

        assertThat(ProbeRegistry.get("region-tick.overrun")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void slowTickInStrictModeThrows() {
        Region region = new ThreadedRegionizer(WORLD, 0).addChunk(new ChunkPos(0, 0));
        RegionTickWatchdog.setWarnMsForTesting(0);
        RegionTickWatchdog.setModeForTesting(RegionTickWatchdog.Mode.STRICT);

        RegionTickWatchdog.enterTick(region);
        try {
            Thread.sleep(1);
        } catch (InterruptedException ignored) {
        }

        assertThatThrownBy(() -> RegionTickWatchdog.exitTick(region))
                .isInstanceOf(RegionTickWatchdog.RegionTickOverrunException.class)
                .hasMessageContaining("tick overran");
        assertThat(ProbeRegistry.get("region-tick.overrun")).isEqualTo(1);
    }

    @Test
    void enterExitStateClearsAcrossTicks() {
        Region region = new ThreadedRegionizer(WORLD, 0).addChunk(new ChunkPos(0, 0));
        RegionTickWatchdog.setWarnMsForTesting(10_000);

        for (int i = 0; i < 3; i++) {
            RegionTickWatchdog.enterTick(region);
            RegionTickWatchdog.exitTick(region);
        }
        // No violations across three fast ticks.
        assertThat(ProbeRegistry.get("region-tick.overrun")).isZero();
    }

    @Test
    void exitAfterThrowClearsStateWithoutFiring() {
        Region region = new ThreadedRegionizer(WORLD, 0).addChunk(new ChunkPos(0, 0));
        // Use warnMs=0 during the throw phase to prove exitTickAfterThrow
        // doesn't itself fire a violation even under the most eager warn
        // setting.
        RegionTickWatchdog.setWarnMsForTesting(0);

        RegionTickWatchdog.enterTick(region);
        // Simulate: body threw; scheduler calls exitTickAfterThrow instead of exitTick.
        RegionTickWatchdog.exitTickAfterThrow();

        // The scheduler recovery path doesn't fire a violation (the body's own exception
        // already carries the info). Assert IMMEDIATELY — before doing anything else that
        // could touch the probe — so a regression in exitTickAfterThrow is caught cleanly.
        assertThat(ProbeRegistry.get("region-tick.overrun")).isZero();

        // Now separately verify per-thread state is properly cleared: the next tick starts
        // clean so a fast enter/exit under a generous warnMs must NOT emit anything.
        // (Under the previous warnMs=0 this assertion was always-green — /67 round-4 fix.)
        RegionTickWatchdog.setWarnMsForTesting(10_000);
        RegionTickWatchdog.enterTick(region);
        RegionTickWatchdog.exitTick(region);
        assertThat(ProbeRegistry.get("region-tick.overrun")).isZero();
    }

    @Test
    void modeParserAcceptsCommonForms() {
        assertThat(RegionTickWatchdog.parseMode("on")).isEqualTo(RegionTickWatchdog.Mode.STRICT);
        assertThat(RegionTickWatchdog.parseMode("STRICT")).isEqualTo(RegionTickWatchdog.Mode.STRICT);
        assertThat(RegionTickWatchdog.parseMode("true")).isEqualTo(RegionTickWatchdog.Mode.STRICT);
        assertThat(RegionTickWatchdog.parseMode("off")).isEqualTo(RegionTickWatchdog.Mode.WARN);
        assertThat(RegionTickWatchdog.parseMode("nonsense")).isEqualTo(RegionTickWatchdog.Mode.WARN);
        assertThat(RegionTickWatchdog.parseMode(null)).isEqualTo(RegionTickWatchdog.Mode.WARN);
    }
}
