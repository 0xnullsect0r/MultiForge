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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** {@link HangReporter} on a fake clock: when it reports, and that a new tick re-arms it. */
class HangReporterTest {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private final AtomicLong clock = new AtomicLong(1_000 * SECOND);
    private final AtomicLong nextTickTime = new AtomicLong();
    private final AtomicLong tickStart = new AtomicLong(-1L);
    private final List<String> reports = new ArrayList<>();
    private final HangReporter reporter = new HangReporter(
            Thread.currentThread(),
            clock::get,
            nextTickTime::get,
            tickStart::get,
            List::of,
            (server, busy, now) -> "DUMP " + server.getName(),
            reports::add,
            HangReporter.DEFAULT_FIRST_NANOS,
            HangReporter.DEFAULT_REPEAT_NANOS);

    private void startTick() {
        tickStart.set(clock.get());
        nextTickTime.set(clock.get() + 50 * TimeUnit.MILLISECONDS.toNanos(1));
    }

    /** Advance the fake clock in half-second polls, as the daemon does. @return reports made */
    private int runFor(long nanos) {
        int made = 0;
        long end = clock.get() + nanos;
        while (clock.get() - end < 0) {
            clock.addAndGet(SECOND / 2);
            if (reporter.poll()) made++;
        }
        return made;
    }

    @Test
    void quietWhileTicksAreShort() {
        for (int i = 0; i < 200; i++) {
            startTick();
            assertThat(runFor(SECOND)).isZero();
        }
        assertThat(reports).isEmpty();
    }

    @Test
    void reportsAtTenSecondsThenEveryFive() {
        startTick();
        assertThat(runFor(9 * SECOND)).isZero();
        assertThat(runFor(SECOND + SECOND / 2)).isEqualTo(1); // 10.05 s past the tick's start
        assertThat(reports.get(0))
                .contains("stalled for 10.")
                .contains("DUMP " + Thread.currentThread().getName());
        assertThat(runFor(4 * SECOND)).isZero();
        assertThat(runFor(SECOND)).isEqualTo(1);
        assertThat(runFor(10 * SECOND)).isEqualTo(2);
        assertThat(reports).hasSize(4);
        assertThat(reports.get(3)).contains("report 4");
    }

    @Test
    void aNewTickRearms() {
        startTick();
        assertThat(runFor(12 * SECOND)).isEqualTo(1);
        startTick();
        assertThat(runFor(9 * SECOND)).isZero();
        assertThat(runFor(2 * SECOND)).isEqualTo(1);
        assertThat(reports.get(1)).contains("report 1");
    }

    @Test
    void measuresFromTheHeartbeatWhileLagging() {
        // Far behind schedule: nextTickTime is minutes in the past, but the tick just started.
        tickStart.set(clock.get());
        nextTickTime.set(clock.get() - 300 * SECOND);
        assertThat(runFor(9 * SECOND)).isZero();
        assertThat(runFor(2 * SECOND)).isEqualTo(1);
    }

    @Test
    void quietBeforeTheFirstTick() {
        nextTickTime.set(clock.get() - 100 * SECOND); // server start-up, before the loop runs
        assertThat(runFor(30 * SECOND)).isZero();
    }

    @Test
    void realDumpTagsTheServerThread() {
        String dump = HangReporter.threadDump(Thread.currentThread(), List.of(), System.nanoTime());
        assertThat(dump)
                .contains("\"" + Thread.currentThread().getName() + "\" [server thread]")
                .contains("realDumpTagsTheServerThread");
    }
}
