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

    /** Bumped by {@link #resetForTesting}, so a {@link Counter} handle re-binds to the fresh map. */
    private static volatile int generation;

    private ProbeRegistry() {}

    /**
     * A handle on counter {@code name}, for a hot call site: {@link
     * Counter#increment()} and {@link Counter#add(long)} skip the name lookup
     * {@link #bump} does on every call. Hold it in a {@code static final}.
     */
    public static Counter counter(String name) {
        return new Counter(name);
    }

    /** See {@link #counter(String)}. Same counter as {@code bump(name)} / {@code add(name, n)}. */
    public static final class Counter {
        private final String name;
        private volatile Binding binding;

        private record Binding(int generation, LongAdder adder) {}

        private Counter(String name) {
            this.name = java.util.Objects.requireNonNull(name, "name");
        }

        public String name() {
            return name;
        }

        public void increment() {
            adder().increment();
        }

        public void add(long amount) {
            adder().add(amount);
        }

        private LongAdder adder() {
            int gen = generation;
            Binding b = binding;
            if (b != null && b.generation() == gen) return b.adder();
            LongAdder a = COUNTERS.computeIfAbsent(name, k -> new LongAdder());
            binding = new Binding(gen, a);
            return a;
        }
    }

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
        generation++;
    }
}
