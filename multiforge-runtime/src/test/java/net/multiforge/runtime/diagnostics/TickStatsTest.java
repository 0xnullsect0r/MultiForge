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
package net.multiforge.runtime.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TickStatsTest {
    private static final long MS = 1_000_000L;
    private static final long SEC = 1_000_000_000L;

    @AfterEach
    void reset() {
        TickStats.reset();
    }

    @Test
    void emptyIsZero() {
        assertThat(TickStats.snapshot().ticks()).isZero();
    }

    @Test
    void maxIsTheTrueMaximumNotAPercentile() {
        for (int i = 0; i < 1000; i++) TickStats.record(i == 500 ? 400 * MS : 2 * MS, i * 50 * MS);
        TickStats.Snapshot s = TickStats.snapshot(1000 * 50 * MS);
        assertThat(s.ticks()).isEqualTo(1000);
        assertThat(s.maxMs()).isEqualTo(400.0);
        assertThat(s.p99Ms()).isEqualTo(2.0);
        assertThat(s.p50Ms()).isEqualTo(2.0);
    }

    @Test
    void tpsCountsTicksInTheLastTenMinutes() {
        // 15 minutes of ticks: the first 5 at 20 TPS, the last 10 at 10 TPS.
        long t = 0;
        for (int i = 0; i < 5 * 60 * 20; i++) TickStats.record(MS, t += 50 * MS);
        for (int i = 0; i < 10 * 60 * 10; i++) TickStats.record(MS, t += 100 * MS);
        TickStats.Snapshot s = TickStats.snapshot(t);
        assertThat(s.tpsWindowSeconds()).isCloseTo(600.0, within(0.01));
        assertThat(s.tps()).isCloseTo(10.0, within(0.02));
    }

    @Test
    void shortRunsMeasureFromTheFirstTick() {
        long t = 0;
        for (int i = 0; i <= 200; i++) TickStats.record(MS, t += 50 * MS);
        TickStats.Snapshot s = TickStats.snapshot(t);
        assertThat(s.tpsWindowSeconds()).isCloseTo(10.0, within(0.01));
        assertThat(s.tps()).isCloseTo(20.0, within(0.01));
    }

    @Test
    void fastRunsThatOverflowTheRingStillReportTheirRate() {
        long t = 0;
        for (int i = 0; i < TickStats.WINDOW * 2; i++) TickStats.record(MS, t += 10 * MS);
        TickStats.Snapshot s = TickStats.snapshot(t);
        assertThat(s.tps()).isCloseTo(100.0, within(0.1));
        assertThat(s.ticks()).isEqualTo(TickStats.WINDOW * 2L);
        assertThat(t).isLessThan(600 * SEC);
    }
}
