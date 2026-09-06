/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.multiforge.bench.harness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Accumulates MSPT samples from the two sources the vanilla {@code /tick}
 * command family exposes (see {@code net.minecraft.server.commands.TickCommand}
 * in the vendored source at
 * {@code upstream/neoforge-1.21.1/projects/base/src/main/java}):
 *
 * <ul>
 *   <li>the {@code /tick query} RCON reply. Exact wording was confirmed
 *       against a live NeoForge 1.21.1 dev server during Phase 7.4a
 *       implementation (the source only has translation <em>keys</em>,
 *       not the rendered text), e.g.:
 *       <pre>
 *       The game is running normally
 *       Target tick rate: 20.0 per second.
 *       Average time per tick: 0.3ms (Target: 50.0ms)
 *       Percentiles: P50: 0.3ms P95: 0.6ms P99: 0.6ms, sample: 100
 *       </pre>
 *       ({@code sample: 100} is the server's rolling tick-time buffer
 *       size — a query always reflects only the last ~100 ticks, never
 *       the whole run.)</li>
 *   <li>the {@code MinecraftServer} log line printed when a
 *       {@code /tick sprint <n>} run finishes, e.g. {@code "Sprint
 *       completed with 34405 ticks per second, or 0.03 ms per tick"} —
 *       this is the authoritative average over the <em>entire</em>
 *       sprint, not just the tail-end rolling buffer.</li>
 * </ul>
 *
 * <p>Vanilla exposes P50/P95/P99 but no true per-tick max. {@link
 * #maxMspt()} is a documented approximation: the highest P99 sample
 * observed across every query, or the highest "Can't keep up" lag-spike
 * duration parsed from the log, whichever is larger.
 */
public final class MetricsCollector {

    private static final Pattern SPRINT_COMPLETED =
            Pattern.compile("Sprint completed with (\\d+) ticks per second, or ([0-9.]+) ms per tick");
    private static final Pattern TICK_QUERY_AVERAGE = Pattern.compile("Average time per tick:\\s*([0-9.]+)\\s*ms");
    private static final Pattern TICK_QUERY_PERCENTILES = Pattern.compile(
            "Percentiles:\\s*P50:\\s*([0-9.]+)\\s*ms\\s*P95:\\s*([0-9.]+)\\s*ms\\s*P99:\\s*([0-9.]+)\\s*ms,\\s*sample:\\s*(\\d+)");
    // Vanilla's watchdog-adjacent overload warning: "Can't keep up! Is the
    // server overloaded? Running 542ms or 10 ticks behind, skipping ...".
    private static final Pattern LAG_OVERRUN = Pattern.compile("Running (\\d+)ms or \\d+ ticks behind");

    private final List<Double> avgSamplesMs = new ArrayList<>();
    private final List<Double> p50SamplesMs = new ArrayList<>();
    private final List<Double> p95SamplesMs = new ArrayList<>();
    private final List<Double> p99SamplesMs = new ArrayList<>();
    private final List<Double> lagSpikesMs = new ArrayList<>();

    private Double sprintAvgMsPerTick;
    private Long sprintTicksPerSecond;

    /** Feeds one {@code /tick query} RCON reply. Safe to call repeatedly; unmatched text is ignored. */
    public synchronized void recordTickQueryReply(String reply) {
        Matcher avg = TICK_QUERY_AVERAGE.matcher(reply);
        if (avg.find()) {
            avgSamplesMs.add(Double.parseDouble(avg.group(1)));
        }
        Matcher pct = TICK_QUERY_PERCENTILES.matcher(reply);
        if (pct.find()) {
            p50SamplesMs.add(Double.parseDouble(pct.group(1)));
            p95SamplesMs.add(Double.parseDouble(pct.group(2)));
            p99SamplesMs.add(Double.parseDouble(pct.group(3)));
        }
    }

    /** Feeds one line tailed from the server's boot/console log. Safe to call for every line. */
    public synchronized void recordLogLine(String line) {
        Matcher sprint = SPRINT_COMPLETED.matcher(line);
        if (sprint.find()) {
            sprintTicksPerSecond = Long.parseLong(sprint.group(1));
            sprintAvgMsPerTick = Double.parseDouble(sprint.group(2));
            return;
        }
        Matcher lag = LAG_OVERRUN.matcher(line);
        if (lag.find()) {
            lagSpikesMs.add(Double.parseDouble(lag.group(1)));
        }
    }

    /** Whether a "Sprint completed" (or equivalent) log line has been observed yet. */
    public synchronized boolean sawSprintCompleted() {
        return sprintAvgMsPerTick != null;
    }

    public synchronized double avgMspt() {
        if (sprintAvgMsPerTick != null) {
            return sprintAvgMsPerTick;
        }
        return mean(avgSamplesMs).orElse(0.0);
    }

    public synchronized double p50Mspt() {
        return median(p50SamplesMs).orElseGet(this::avgMspt);
    }

    public synchronized double p95Mspt() {
        return max(p95SamplesMs).orElseGet(() -> avgMspt() * 2.0);
    }

    public synchronized double p99Mspt() {
        return max(p99SamplesMs).orElseGet(() -> avgMspt() * 3.0);
    }

    public synchronized double maxMspt() {
        double fromPercentiles = p99Mspt();
        double fromLag = max(lagSpikesMs).orElse(0.0);
        return Math.max(fromPercentiles, fromLag);
    }

    public synchronized long sprintTicksPerSecond() {
        return sprintTicksPerSecond == null ? 0L : sprintTicksPerSecond;
    }

    /** Number of distinct {@code /tick query} + sprint-completion samples folded in so far. */
    public synchronized int sampleCount() {
        return avgSamplesMs.size() + (sprintAvgMsPerTick != null ? 1 : 0);
    }

    private static Optional<Double> mean(List<Double> values) {
        if (values.isEmpty()) {
            return Optional.empty();
        }
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return Optional.of(sum / values.size());
    }

    private static Optional<Double> median(List<Double> values) {
        if (values.isEmpty()) {
            return Optional.empty();
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 0) {
            return Optional.of((sorted.get(mid - 1) + sorted.get(mid)) / 2.0);
        }
        return Optional.of(sorted.get(mid));
    }

    private static Optional<Double> max(List<Double> values) {
        return values.stream().max(Double::compareTo);
    }
}
