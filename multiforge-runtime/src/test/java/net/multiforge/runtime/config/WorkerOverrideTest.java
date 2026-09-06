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
package net.multiforge.runtime.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Coverage for the {@code -Dmultiforge.workers=N} system-property
 * short-circuit on {@link MultiForgeConfig#tickWorkerCount()} — see
 * {@code docs/design/m9-phase7-runbook.md} §7 (worker-override
 * blocker, now wired) and the Phase 7.3 N-worker verification run this
 * unblocks.
 */
class WorkerOverrideTest {

    private static final String PROP = "multiforge.workers";

    @AfterEach
    void clearOverride() {
        System.clearProperty(PROP);
    }

    @Test
    void noPropertySetUsesComputedCoresTimesThreadsPerCore() {
        System.clearProperty(PROP);
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(4).withThreadsPerCore(2);
        assertThat(config.tickWorkerCount()).isEqualTo(8);
    }

    @Test
    void validPositivePropertyShortCircuitsComputation() {
        System.setProperty(PROP, "8");
        // Cores/threadsPerCore deliberately set so the computed value would
        // differ from the override — proves the override wins, not a
        // coincidental match.
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(2).withThreadsPerCore(1);
        assertThat(config.tickWorkerCount()).isEqualTo(8);
    }

    @Test
    void invalidPropertyFallsThroughToComputedValueWithoutThrowing() {
        System.setProperty(PROP, "not-a-number");
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(3).withThreadsPerCore(2);
        assertThat(config.tickWorkerCount()).isEqualTo(6);
    }

    @Test
    void zeroPropertyFallsThroughToComputedValue() {
        System.setProperty(PROP, "0");
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(3).withThreadsPerCore(2);
        assertThat(config.tickWorkerCount()).isEqualTo(6);
    }

    @Test
    void negativePropertyFallsThroughToComputedValue() {
        System.setProperty(PROP, "-4");
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(3).withThreadsPerCore(2);
        assertThat(config.tickWorkerCount()).isEqualTo(6);
    }

    @Test
    void blankPropertyFallsThroughToComputedValue() {
        System.setProperty(PROP, "   ");
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(3).withThreadsPerCore(2);
        assertThat(config.tickWorkerCount()).isEqualTo(6);
    }
}
