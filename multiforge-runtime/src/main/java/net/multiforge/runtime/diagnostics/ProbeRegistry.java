/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.diagnostics;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.multiforge.runtime.region.RegionId;

/**
 * Cheap named counters used by ownership probes. Read via
 * {@link #snapshot()}, cleared per-JVM only.
 */
public final class ProbeRegistry {

    private static final ConcurrentMap<String, LongAdder> COUNTERS = new ConcurrentHashMap<>();

    /**
     * Per-(src, dst) region-pair migration latency accumulators — a cheap hand-rolled histogram
     * substitute (count + sum + max nanos) so {@link #recordMigration} has no new dependency.
     * Keyed by the same {@code "src-&gt;dst"} label used for the bump counters.
     */
    private static final ConcurrentMap<String, LongAdder> MIGRATION_LATENCY_COUNT = new ConcurrentHashMap<>();

    private static final ConcurrentMap<String, LongAdder> MIGRATION_LATENCY_SUM_NANOS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, AtomicLong> MIGRATION_LATENCY_MAX_NANOS = new ConcurrentHashMap<>();

    private ProbeRegistry() {}

    public static void bump(String name) {
        COUNTERS.computeIfAbsent(name, k -> new LongAdder()).increment();
    }

    public static long get(String name) {
        LongAdder a = COUNTERS.get(name);
        return a == null ? 0L : a.sum();
    }

    /**
     * Record one entity migration between regions (docs/design/entity-migration.md §7.4 "never
     * silent"): bumps a total counter, a per-{@code (src, dst)} pair counter, and a per-pair
     * latency accumulator. {@code src}/{@code dst} may be {@code null} when the calling region's
     * identity could not be resolved (e.g. no {@code ChunkHolderManager} wired for that world) —
     * the pair label degrades to {@code "unknown"} for that side rather than throwing.
     */
    public static void recordMigration(RegionId src, RegionId dst, long latencyNs) {
        String pair = label(src) + "->" + label(dst);
        bump("entity-migration.total");
        bump("entity-migration.pair." + pair);
        MIGRATION_LATENCY_COUNT.computeIfAbsent(pair, k -> new LongAdder()).increment();
        MIGRATION_LATENCY_SUM_NANOS.computeIfAbsent(pair, k -> new LongAdder()).add(Math.max(0L, latencyNs));
        MIGRATION_LATENCY_MAX_NANOS
                .computeIfAbsent(pair, k -> new AtomicLong())
                .accumulateAndGet(Math.max(0L, latencyNs), Math::max);
    }

    /** Number of migrations recorded for the given region pair (test/diagnostics accessor). */
    public static long migrationCount(RegionId src, RegionId dst) {
        LongAdder a = MIGRATION_LATENCY_COUNT.get(label(src) + "->" + label(dst));
        return a == null ? 0L : a.sum();
    }

    /** Mean latency in nanoseconds for the given region pair, or 0 if never recorded. */
    public static long migrationMeanLatencyNanos(RegionId src, RegionId dst) {
        String pair = label(src) + "->" + label(dst);
        LongAdder count = MIGRATION_LATENCY_COUNT.get(pair);
        LongAdder sum = MIGRATION_LATENCY_SUM_NANOS.get(pair);
        if (count == null || sum == null || count.sum() == 0L) return 0L;
        return sum.sum() / count.sum();
    }

    /** Max latency in nanoseconds observed for the given region pair, or 0 if never recorded. */
    public static long migrationMaxLatencyNanos(RegionId src, RegionId dst) {
        AtomicLong max = MIGRATION_LATENCY_MAX_NANOS.get(label(src) + "->" + label(dst));
        return max == null ? 0L : max.get();
    }

    private static String label(RegionId id) {
        return id == null ? "unknown" : id.toString();
    }

    /** Snapshot as a sorted map for stable readouts. */
    public static NavigableMap<String, Long> snapshot() {
        NavigableMap<String, Long> out = new TreeMap<>();
        for (Map.Entry<String, LongAdder> e : COUNTERS.entrySet()) {
            out.put(e.getKey(), e.getValue().sum());
        }
        return out;
    }

    /** Test-only: zeroes every counter. */
    public static void resetForTesting() {
        COUNTERS.clear();
        MIGRATION_LATENCY_COUNT.clear();
        MIGRATION_LATENCY_SUM_NANOS.clear();
        MIGRATION_LATENCY_MAX_NANOS.clear();
    }
}
