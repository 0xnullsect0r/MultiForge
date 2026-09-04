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
import java.util.concurrent.atomic.LongAdder;

/**
 * Cheap named counters used by ownership probes. Read via
 * {@link #snapshot()}, cleared per-JVM only.
 */
public final class ProbeRegistry {

    private static final ConcurrentMap<String, LongAdder> COUNTERS = new ConcurrentHashMap<>();

    private ProbeRegistry() {}

    public static void bump(String name) {
        COUNTERS.computeIfAbsent(name, k -> new LongAdder()).increment();
    }

    public static long get(String name) {
        LongAdder a = COUNTERS.get(name);
        return a == null ? 0L : a.sum();
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
    }
}
