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
 * <p>The JSON writer is hand-rolled: a dozen scalars and a flat extras map
 * do not need a JSON library, and the one on the classpath (Gson, via
 * MCProtocolLib) is a transitive dependency this class should not lean on.
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
        long rssPeakMb,
        boolean bootOk,
        boolean cleanStop,
        Map<String, Object> extra) {

    public BenchResult {
        extra = extra == null ? Map.of() : new LinkedHashMap<>(extra);
    }

    /**
     * Builds a result from a {@link MetricsCollector}. Every figure is a
     * measurement (see {@link MetricsCollector}); {@code
     * tps_sustained_last_10min} is {@link MetricsCollector#sustainedTps()},
     * written as JSON {@code null} when nothing was measured. The timing source
     * goes into the extras as {@code timing_source}. For a {@code pacing=sprint}
     * run the measured rate moves to {@code sprint_tps} and the sustained TPS
     * field is {@code null}: an unpaced sprint says nothing about holding 20 TPS.
     */
    public static BenchResult from(
            String profile,
            int workers,
            long ticks,
            long wallClockMs,
            MetricsCollector metrics,
            long rssPeakMb,
            boolean bootOk,
            boolean cleanStop,
            Map<String, Object> extra) {
        double avg = metrics.avgMspt();
        double sustainedTps = metrics.sustainedTps();
        Map<String, Object> allExtra = new LinkedHashMap<>();
        allExtra.put("timing_source", metrics.timingSource());
        if (extra != null && "sprint".equals(extra.get("pacing"))) {
            // An unpaced sprint's tick rate is a throughput figure, not sustained TPS.
            allExtra.put("sprint_tps", Double.isNaN(sustainedTps) ? null : Math.round(sustainedTps));
            sustainedTps = Double.NaN;
        }
        metrics.tickStats().ifPresent(t -> allExtra.put("ticks_measured", t.ticks()));
        if (extra != null) allExtra.putAll(extra);
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
                rssPeakMb,
                bootOk,
                cleanStop,
                allExtra);
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
        field(sb, "rss_peak_mb", rssPeakMb, false);
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

    private static Object round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
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
        if (value == null) {
            return "null";
        }
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
