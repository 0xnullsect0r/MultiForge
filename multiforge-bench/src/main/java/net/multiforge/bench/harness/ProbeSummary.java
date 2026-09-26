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
package net.multiforge.bench.harness;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The {@code /multiforge probes} reply, parsed and grouped the way a bench
 * result needs it:
 *
 * <ul>
 *   <li><b>violations</b> — ownership breaches the runtime caught: {@code
 *       <site>:off-thread}, {@code :wrong-owner}, {@code :wrong-region},
 *       {@code :not-global}, {@code :not-tick-thread} and the
 *       {@code *.wrong-owner} / {@code *.off-thread} phase probes;</li>
 *   <li><b>reroutes</b> — cross-region writes handed to the owning region
 *       ({@code reroute.*}, {@code <site>:cross-region}): expected under
 *       load, not errors;</li>
 *   <li><b>overruns</b> — region ticks past the watchdog threshold ({@code
 *       region-tick.overrun}, {@code region-tick.dispatch.overrun}).</li>
 * </ul>
 */
public record ProbeSummary(Map<String, Long> counters) {
    private static final List<String> VIOLATION_MARKERS =
            List.of(":off-thread", "wrong-owner", ":wrong-region", ":not-global", ":not-tick-thread", ".off-thread");

    public static ProbeSummary parse(String reply) {
        Map<String, Long> counters = new TreeMap<>();
        for (String line : reply.split("\n")) {
            int eq = line.lastIndexOf(" = ");
            if (eq < 0) continue;
            try {
                counters.put(
                        line.substring(0, eq).strip(),
                        Long.parseLong(line.substring(eq + 3).strip()));
            } catch (NumberFormatException ignored) {
                // not a counter line
            }
        }
        return new ProbeSummary(counters);
    }

    public long violations() {
        return sum(k -> VIOLATION_MARKERS.stream().anyMatch(k::contains));
    }

    public long reroutes() {
        return sum(k -> k.startsWith("reroute.") && !k.endsWith(".mismatch") || k.endsWith(":cross-region"));
    }

    /** Reroutes whose predicted Vanilla return value differed from what the owner then did. */
    public long rerouteMismatches() {
        return sum(k -> k.startsWith("reroute.") && k.endsWith(".mismatch"));
    }

    public long overruns() {
        return sum(k -> k.startsWith("region-tick.") && k.contains("overrun"));
    }

    /** The counters that make up {@link #violations()}, for a failure message. */
    public Map<String, Long> violationCounters() {
        Map<String, Long> out = new TreeMap<>();
        counters.forEach((k, v) -> {
            if (VIOLATION_MARKERS.stream().anyMatch(k::contains)) out.put(k, v);
        });
        return out;
    }

    private long sum(java.util.function.Predicate<String> key) {
        return counters.entrySet().stream()
                .filter(e -> key.test(e.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
    }
}
