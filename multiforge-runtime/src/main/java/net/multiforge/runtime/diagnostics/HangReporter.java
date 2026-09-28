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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.multiforge.runtime.region.RegionTickWatchdog;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Early warning for a stalled server tick, well before Vanilla's watchdog
 * (60 s by default) kills the server with only the server thread's stack.
 *
 * <p>Once the tick in progress is {@link #DEFAULT_FIRST_NANOS 10 s} past its
 * reference point (the one {@link TickHangDetector} uses), and every {@link
 * #DEFAULT_REPEAT_NANOS 5 s} after that until a new tick starts, it logs the
 * stacks of the server thread and of every thread ticking a region right now,
 * each tagged with its region, phase and how long it has been in that region
 * tick. A worker stuck on a lock, or the server thread stuck at the barrier
 * waiting for one, then shows up in the log while the server is still alive.
 *
 * <p>It runs on its own daemon thread ({@link #startDaemon}), reads only
 * volatile fields and takes the stacks through {@link ThreadMXBean} (a
 * safepoint, as Vanilla's watchdog dump): it never takes a lock the server or
 * a worker holds, and never waits on either. {@code
 * -Dmultiforge.hangReport=false} turns it off.
 */
@ApiStatus.Internal
public final class HangReporter {
    private static final Logger LOG = LoggerFactory.getLogger("multiforge.hang");

    public static final long DEFAULT_FIRST_NANOS = TimeUnit.SECONDS.toNanos(10);
    public static final long DEFAULT_REPEAT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long POLL_MILLIS = 500L;
    private static final int MAX_WORKERS = 32;
    private static final int MAX_FRAMES = 128;

    /** Takes the stacks of the stalled threads. */
    @FunctionalInterface
    public interface Dumper {
        String dump(Thread serverThread, List<RegionTickWatchdog.ActiveTick> busy, long nowNanos);
    }

    private final Thread serverThread;
    private final LongSupplier clock;
    private final LongSupplier nextTickTimeNanos;
    private final LongSupplier tickStartNanos;
    private final Supplier<List<RegionTickWatchdog.ActiveTick>> busy;
    private final Dumper dumper;
    private final Consumer<String> sink;
    private final long firstNanos;
    private final long repeatNanos;

    /** The heartbeat before the first tick: nothing is reported until it moves (startup is not a tick). */
    private final long initialTickStart;

    // Reporter thread only.
    private boolean tracking;
    private long stalledReference;
    private long nextReportAt;
    private int reports;

    public HangReporter(
            Thread serverThread,
            LongSupplier clock,
            LongSupplier nextTickTimeNanos,
            LongSupplier tickStartNanos,
            Supplier<List<RegionTickWatchdog.ActiveTick>> busy,
            Dumper dumper,
            Consumer<String> sink,
            long firstNanos,
            long repeatNanos) {
        this.serverThread = serverThread;
        this.clock = clock;
        this.nextTickTimeNanos = nextTickTimeNanos;
        this.tickStartNanos = tickStartNanos;
        this.busy = busy;
        this.dumper = dumper;
        this.sink = sink;
        this.firstNanos = firstNanos;
        this.repeatNanos = Math.max(1L, repeatNanos);
        this.initialTickStart = tickStartNanos.getAsLong();
    }

    /** The production reporter: real clock, {@link ThreadMXBean} stacks, logged as a warning. */
    public static HangReporter forServer(
            Thread serverThread, LongSupplier nextTickTimeNanos, LongSupplier tickStartNanos) {
        return new HangReporter(
                serverThread,
                System::nanoTime,
                nextTickTimeNanos,
                tickStartNanos,
                RegionTickWatchdog::activeTicks,
                HangReporter::threadDump,
                HangReporter::log,
                DEFAULT_FIRST_NANOS,
                DEFAULT_REPEAT_NANOS);
    }

    /** Whether {@code -Dmultiforge.hangReport} leaves the reporter on (the default). */
    public static boolean enabled() {
        return !"false"
                .equalsIgnoreCase(
                        System.getProperty("multiforge.hangReport", "true").trim());
    }

    /**
     * Check once. Reports if the current tick is past the first threshold and a
     * report is due.
     *
     * @return whether a report was made
     */
    public boolean poll() {
        long start = tickStartNanos.getAsLong();
        if (start == initialTickStart) return false;
        long now = clock.getAsLong();
        long reference = TickHangDetector.referenceNanos(nextTickTimeNanos.getAsLong(), start);
        if (!tracking || reference != stalledReference) {
            // A new tick (or the first poll): re-arm for it.
            tracking = true;
            stalledReference = reference;
            nextReportAt = reference + firstNanos;
            reports = 0;
        }
        if (now - nextReportAt < 0) return false;
        while (now - nextReportAt >= 0) nextReportAt += repeatNanos;
        reports++;
        long stalled = now - reference;
        List<RegionTickWatchdog.ActiveTick> workers = busy.get();
        StringBuilder sb = new StringBuilder(256);
        sb.append(String.format(
                Locale.ROOT,
                "Server tick stalled for %.1f s (report %d; Vanilla's watchdog stops the server at its max-tick-time). "
                        + "Server thread and %d thread(s) ticking a region:%n",
                stalled / 1e9,
                reports,
                workers.size()));
        sb.append(dumper.dump(serverThread, workers, now));
        ProbeRegistry.bump("server.hang.report");
        sink.accept(sb.toString());
        return true;
    }

    /** Poll every half second on a daemon thread until {@code running} turns false. */
    public Thread startDaemon(String name, BooleanSupplier running) {
        Thread t = new Thread(
                () -> {
                    while (running.getAsBoolean()) {
                        try {
                            poll();
                        } catch (Throwable e) {
                            LOG.warn("hang reporter check failed", e);
                        }
                        try {
                            Thread.sleep(POLL_MILLIS);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                },
                name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void log(String report) {
        LOG.warn(report);
    }

    /** The stacks of the server thread and the busy region threads, each tagged with what it is ticking. */
    static String threadDump(Thread serverThread, List<RegionTickWatchdog.ActiveTick> busy, long nowNanos) {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        List<Long> ids = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        RegionTickWatchdog.ActiveTick serverTick = null;
        for (RegionTickWatchdog.ActiveTick a : busy) {
            if (a.thread() == serverThread) serverTick = a;
        }
        if (serverThread != null) {
            ids.add(serverThread.threadId());
            tags.add("server thread" + (serverTick == null ? "" : ", " + tag(serverTick, nowNanos)));
        }
        int n = 0;
        for (RegionTickWatchdog.ActiveTick a : busy) {
            Thread t = a.thread();
            if (t == null || t == serverThread) continue;
            if (n++ >= MAX_WORKERS) break;
            ids.add(t.threadId());
            tags.add(tag(a, nowNanos));
        }
        long[] idArray = ids.stream().mapToLong(Long::longValue).toArray();
        ThreadInfo[] infos = mx.getThreadInfo(idArray, MAX_FRAMES);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < infos.length; i++) {
            ThreadInfo info = infos[i];
            if (info == null) continue;
            format(sb, info, tags.get(i));
        }
        if (n > MAX_WORKERS) sb.append("... ").append(n - MAX_WORKERS).append(" more region thread(s)\n");
        return sb.toString();
    }

    private static String tag(RegionTickWatchdog.ActiveTick a, long nowNanos) {
        String phase = a.phase();
        return String.format(
                Locale.ROOT,
                "region %d, phase %s, %.1f s into its tick",
                a.regionId(),
                phase == null ? "MAILBOX" : phase,
                (nowNanos - a.startNanos()) / 1e9);
    }

    static void format(StringBuilder sb, ThreadInfo info, String tag) {
        sb.append('"')
                .append(info.getThreadName())
                .append("\" [")
                .append(tag)
                .append("] ")
                .append(info.getThreadState());
        if (info.getLockName() != null) {
            sb.append(" on ").append(info.getLockName());
            if (info.getLockOwnerName() != null)
                sb.append(" owned by \"").append(info.getLockOwnerName()).append('"');
        }
        sb.append('\n');
        for (StackTraceElement e : info.getStackTrace())
            sb.append("\tat ").append(e).append('\n');
        sb.append('\n');
    }
}
