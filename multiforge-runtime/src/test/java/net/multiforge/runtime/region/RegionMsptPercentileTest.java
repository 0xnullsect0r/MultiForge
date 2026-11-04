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
package net.multiforge.runtime.region;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link RegionMspt#percentileMillis}: nearest rank, so p99 is not just the maximum. */
class RegionMsptPercentileTest {

    @Test
    void nearestRankOverAHundredSamples() {
        RegionMspt mspt = new RegionMspt(100);
        // 1 ms … 100 ms.
        for (int i = 1; i <= 100; i++) mspt.recordNanos(i * 1_000_000L);
        assertThat(mspt.percentileMillis(0.99)).isEqualTo(99.0);
        assertThat(mspt.percentileMillis(0.95)).isEqualTo(95.0);
        assertThat(mspt.percentileMillis(0.5)).isEqualTo(50.0);
    }

    @Test
    void oneSlowTickIsNotTheP99() {
        RegionMspt mspt = new RegionMspt(100);
        for (int i = 0; i < 99; i++) mspt.recordNanos(2_000_000L);
        mspt.recordNanos(500_000_000L);
        assertThat(mspt.percentileMillis(0.99)).isEqualTo(2.0);
    }
}
