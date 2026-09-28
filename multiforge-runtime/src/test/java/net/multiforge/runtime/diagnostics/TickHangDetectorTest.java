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

import java.util.List;
import java.util.concurrent.TimeUnit;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;

/**
 * {@link TickHangDetector} against a model of {@code MinecraftServer.runServer}'s
 * tick clock: {@code nextTickTime} advances one period per tick and is skipped
 * forward only by the rate-limited "Can't keep up!" catch-up; the tick-start
 * heartbeat is set at the head of every tick; the watchdog samples mid-tick, at
 * the end of a tick and after the wait for the next one.
 */
class TickHangDetectorTest {
    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final long TICK = 50 * MS;
    private static final long MAX_TICK = 60 * SECOND; // server.properties max-tick-time default

    /** Vanilla's loop clock plus a watchdog that records whether either check tripped. */
    private static final class ServerLoop {
        long now;
        long nextTickTime;
        long lastOverloadWarning;
        long tickStart;
        boolean hung;
        boolean vanillaHung;

        ServerLoop(long start) {
            now = start;
            nextTickTime = start;
            tickStart = start;
            lastOverloadWarning = start - 3600 * SECOND;
        }

        void tick(long duration) {
            long lag = now - nextTickTime;
            if (lag > SECOND + 20L * TICK && nextTickTime - lastOverloadWarning >= 10 * SECOND + 100L * TICK) {
                nextTickTime += lag / TICK * TICK;
                lastOverloadWarning = nextTickTime;
            }
            nextTickTime += TICK;
            tickStart = now; // head of tickServer
            sample(now + duration / 2);
            sample(now + duration);
            now += duration;
            if (nextTickTime - now > 0) now = nextTickTime; // waitUntilNextTick
            sample(now);
        }

        void stallBetweenTicks(long duration) {
            sample(now + duration);
            now += duration;
        }

        void sample(long at) {
            hung |= TickHangDetector.isHung(at, nextTickTime, tickStart, MAX_TICK);
            vanillaHung |= at - nextTickTime > MAX_TICK;
        }
    }

    @Example
    void steadyOneSecondTicksForTwoMinutesNeverTrip() {
        ServerLoop loop = new ServerLoop(123_456_789L);
        for (int i = 0; i < 120; i++) loop.tick(SECOND);
        assertThat(loop.hung).isFalse();
        // What the heartbeat fixes: Vanilla's check reads the accumulated lag as one hung tick.
        assertThat(loop.vanillaHung).isTrue();
    }

    @Property(tries = 300)
    void slowTicksNeverTrip(
            @ForAll @Size(min = 120, max = 400) List<@LongRange(min = 0, max = 1000) Long> tickMillis,
            @ForAll long start) {
        ServerLoop loop = new ServerLoop(start);
        for (long ms : tickMillis) loop.tick(ms * MS);
        assertThat(loop.hung).isFalse();
    }

    @Property(tries = 300)
    void oneTickOverTheLimitTrips(
            @ForAll @Size(max = 200) List<@LongRange(min = 0, max = 1000) Long> before,
            @ForAll @LongRange(min = 61_000, max = 600_000) long hungMillis,
            @ForAll long start) {
        ServerLoop loop = new ServerLoop(start);
        for (long ms : before) loop.tick(ms * MS);
        assertThat(loop.hung).isFalse();
        loop.tick(hungMillis * MS);
        assertThat(loop.hung).isTrue();
    }

    @Example
    void oneSixtyOneSecondTickTrips() {
        ServerLoop loop = new ServerLoop(0L);
        for (int i = 0; i < 20; i++) loop.tick(TICK);
        loop.tick(61 * SECOND);
        assertThat(loop.hung).isTrue();
    }

    @Property(tries = 300)
    void aStallBetweenTicksTrips(
            @ForAll @Size(max = 200) List<@LongRange(min = 0, max = 1000) Long> before,
            @ForAll @LongRange(min = 61_000, max = 600_000) long stallMillis,
            @ForAll long start) {
        ServerLoop loop = new ServerLoop(start);
        for (long ms : before) loop.tick(ms * MS);
        loop.stallBetweenTicks(stallMillis * MS);
        assertThat(loop.hung).isTrue();
    }

    @Property(tries = 300)
    void whileKeepingUpItIsVanillasCheck(
            @ForAll @Size(max = 200) List<@LongRange(min = 0, max = 50) Long> tickMillis, @ForAll long start) {
        ServerLoop loop = new ServerLoop(start);
        for (long ms : tickMillis) {
            loop.tick(ms * MS);
            assertThat(TickHangDetector.referenceNanos(loop.nextTickTime, loop.tickStart))
                    .isEqualTo(loop.nextTickTime);
        }
    }

    @Example
    void referenceComparesWrappingTimestamps() {
        long next = Long.MAX_VALUE - 10;
        long start = next + 20; // wrapped past Long.MAX_VALUE, but later
        assertThat(TickHangDetector.referenceNanos(next, start)).isEqualTo(start);
        assertThat(TickHangDetector.overrunNanos(start + 5, next, start)).isEqualTo(5);
    }
}
