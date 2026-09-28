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

import org.jetbrains.annotations.ApiStatus;

/**
 * The server hang check behind Vanilla's {@code ServerWatchdog}, with a
 * heartbeat.
 *
 * <p>Vanilla measures {@code now - nextTickTime}. {@code MinecraftServer.runServer}
 * advances {@code nextTickTime} by one tick period (50 ms) per tick and skips
 * it forward only in its "Can't keep up!" catch-up, which fires at most once
 * per 15 s of scheduled time. A server running steady 1 s ticks therefore falls
 * about 0.95 s behind per tick with nothing to reset it, and after about a
 * minute the watchdog reads the accumulated lag as one 60 s tick and kills a
 * server that is slow but alive.
 *
 * <p>The reference point here is the later of {@code nextTickTime} and the
 * start of the tick in progress ({@code MinecraftServer.mfTickStartNanos}, set
 * at the head of {@code tickServer}). While the server keeps up, {@code
 * nextTickTime} is the later one and the check is exactly Vanilla's. While it
 * lags, every completed tick moves the reference forward, so accumulated lag
 * no longer counts; a tick that itself runs past the limit still does, and so
 * does a stall between ticks ({@code waitUntilNextTick}, the autosave, a task),
 * since the heartbeat then stops moving.
 *
 * <p>Timestamps are {@link System#nanoTime()} values, compared by subtraction
 * so they may wrap.
 */
@ApiStatus.Internal
public final class TickHangDetector {
    private TickHangDetector() {}

    /** The later of {@code nextTickTimeNanos} and {@code tickStartNanos}: what a tick's overrun is measured from. */
    public static long referenceNanos(long nextTickTimeNanos, long tickStartNanos) {
        return tickStartNanos - nextTickTimeNanos > 0 ? tickStartNanos : nextTickTimeNanos;
    }

    /** How long past the reference the server is at {@code nowNanos}; negative while it is early. */
    public static long overrunNanos(long nowNanos, long nextTickTimeNanos, long tickStartNanos) {
        return nowNanos - referenceNanos(nextTickTimeNanos, tickStartNanos);
    }

    /** Whether the watchdog should consider the server hung ({@code overrun > maxTickNanos}, as Vanilla). */
    public static boolean isHung(long nowNanos, long nextTickTimeNanos, long tickStartNanos, long maxTickNanos) {
        return overrunNanos(nowNanos, nextTickTimeNanos, tickStartNanos) > maxTickNanos;
    }
}
