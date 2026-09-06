/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.bench.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Flat result record for one bench-profile run (vanilla / swarm / atm10),
 * serialised to the JSON shape specified for Phase 7.4a — see
 * {@code docs/design/m9-phase7-runbook.md} §5.
 *
 * <p>This is a hand-rolled JSON writer, not Gson: as of Phase 7.4a
 * {@code multiforge-bench/build.gradle.kts} has no JSON library on its
 * classpath, and CLAUDE.md requires a license-compat + binary-size
 * review before adding any new dependency. For a dozen scalar fields plus
 * a small flat extras map, a ~40-line writer is cheaper than that review
 * and keeps the "no new external deps" constraint from Phase 7.4a's task
 * brief intact.
 */
public record BenchResult(
        String profile,
        int workers,
        long ticks,
        long wallClockMs,
        double avgMspt,
        double p50Mspt,
        double p95Mspt,
        double p99Mspt,
        double maxMspt,
        double tpsSustainedLast10Min,
        long heapPeakMb,
        boolean bootOk,
        boolean cleanStop,
        Map<String, Object> extra) {

    public BenchResult {
        extra = extra == null ? Map.of() : new LinkedHashMap<>(extra);
    }

    /**
     * Builds a result from a {@link MetricsCollector}, deriving {@code
     * tps_sustained_last_10min} from the measured average MSPT: if the
     * average per-tick compute cost stayed at {@code avgMspt} for a
     * sustained real-time window, the server could hold {@code
     * min(20, 1000/avgMspt)} ticks per second. This is a projection, not
     * a measurement of a literal 10-real-minute window — see {@link
     * HeadlessServerRunner#runSprintProfile(long)} javadoc for why the
     * sprint profile intentionally does not run in real time.
     */
    public static BenchResult from(
            String profile,
            int workers,
            long ticks,
            long wallClockMs,
            MetricsCollector metrics,
            long heapPeakMb,
            boolean bootOk,
            boolean cleanStop,
            Map<String, Object> extra) {
        double avg = metrics.avgMspt();
        double sustainedTps = avg <= 0 ? 20.0 : Math.min(20.0, 1000.0 / avg);
        return new BenchResult(
                profile,
                workers,
                ticks,
                wallClockMs,
                avg,
                metrics.p50Mspt(),
                metrics.p95Mspt(),
                metrics.p99Mspt(),
                metrics.maxMspt(),
                sustainedTps,
                heapPeakMb,
                bootOk,
                cleanStop,
                extra);
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        field(sb, "profile", profile, false);
        field(sb, "workers", workers, false);
        field(sb, "ticks", ticks, false);
        field(sb, "wall_clock_ms", wallClockMs, false);
        field(sb, "avg_mspt", round(avgMspt), false);
        field(sb, "p50_mspt", round(p50Mspt), false);
        field(sb, "p95_mspt", round(p95Mspt), false);
        field(sb, "p99_mspt", round(p99Mspt), false);
        field(sb, "max_mspt", round(maxMspt), false);
        field(sb, "tps_sustained_last_10min", round(tpsSustainedLast10Min), false);
        field(sb, "heap_peak_mb", heapPeakMb, false);
        field(sb, "boot_ok", bootOk, false);
        field(sb, "clean_stop", cleanStop, extra.isEmpty());
        int i = 0;
        for (Map.Entry<String, Object> e : extra.entrySet()) {
            i++;
            field(sb, e.getKey(), e.getValue(), i == extra.size());
        }
        sb.append("}\n");
        return sb.toString();
    }

    public void writeTo(Path path) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, toJson(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write bench result to " + path, e);
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static void field(StringBuilder sb, String key, Object value, boolean last) {
        sb.append("  \"").append(key).append("\": ").append(jsonValue(value));
        if (!last) {
            sb.append(',');
        }
        sb.append('\n');
    }

    private static String jsonValue(Object value) {
        if (value instanceof String s) {
            return '"' + escape(s) + '"';
        }
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        return '"' + escape(String.valueOf(value)) + '"';
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
