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

    /** Add {@code amount} to counter {@code name} (a total, e.g. milliseconds waited). */
    public static void add(String name, long amount) {
        COUNTERS.computeIfAbsent(name, k -> new LongAdder()).add(amount);
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
