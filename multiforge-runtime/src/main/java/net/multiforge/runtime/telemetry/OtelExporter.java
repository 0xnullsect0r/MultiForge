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
package net.multiforge.runtime.telemetry;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.NavigableMap;
import net.multiforge.runtime.diagnostics.ProbeRegistry;

/**
 * Hand-rolled OTLP-HTTP metrics exporter. No OpenTelemetry SDK
 * dependency — {@code docs/operator-handbook.md} promises {@code
 * -Dmultiforge.otel.endpoint=<url>} as an operational knob, and pulling
 * in the full OTel Java SDK for one counters-only export loop is a
 * binary-size and dependency-review cost this doesn't justify (see
 * CLAUDE.md "adding a new dependency").
 *
 * <p>When {@link #ENDPOINT_PROPERTY} is set at boot, {@link
 * #startFromSystemProperty()} spins up a single daemon thread that
 * POSTs a small OTLP-HTTP JSON payload (metrics.v1 subset, see
 * <a href="https://opentelemetry.io/docs/specs/otlp/#otlphttp">the OTLP
 * spec</a>) built from {@link ProbeRegistry#snapshot()} to that endpoint
 * every {@link #DEFAULT_INTERVAL_MILLIS} milliseconds. A failed export
 * is logged to stderr and retried on the next tick — it never brings
 * down the exporter thread or the caller.
 *
 * <p>Not on any region worker thread: the exporter thread is a plain
 * daemon thread outside the regionized scheduler, and every export does
 * its own blocking HTTP call on that thread only (CLAUDE.md rule 4).
 */
public final class OtelExporter {

    /** System property read at {@link #startFromSystemProperty()}. */
    public static final String ENDPOINT_PROPERTY = "multiforge.otel.endpoint";

    private static final long DEFAULT_INTERVAL_MILLIS = 10_000L;

    private static final Object LOCK = new Object();
    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final long START_TIME_UNIX_NANOS = System.currentTimeMillis() * 1_000_000L;

    private static volatile long intervalMillis = DEFAULT_INTERVAL_MILLIS;
    private static Thread thread;
    private static volatile boolean running;

    private OtelExporter() {}

    /**
     * Reads {@link #ENDPOINT_PROPERTY}; if set and non-blank, starts (or
     * restarts) the export thread against that endpoint. If unset, this
     * is a no-op — no thread, no export, matching the "opt-in only"
     * contract in {@code docs/operator-handbook.md}.
     */
    public static void startFromSystemProperty() {
        start(System.getProperty(ENDPOINT_PROPERTY));
    }

    /**
     * Starts (or restarts, replacing any previously-running exporter)
     * the export thread against {@code endpoint}. A {@code null} or
     * blank endpoint stops any running exporter and returns without
     * starting a new one.
     */
    public static void start(String endpoint) {
        synchronized (LOCK) {
            stopLocked();
            if (endpoint == null || endpoint.isBlank()) return;
            String target = endpoint.trim();
            running = true;
            Thread t = new Thread(() -> runLoop(target), "multiforge-otel-exporter");
            t.setDaemon(true);
            t.start();
            thread = t;
        }
    }

    /** Stops the export thread, if one is running. Safe to call repeatedly. */
    public static void stop() {
        synchronized (LOCK) {
            stopLocked();
        }
    }

    private static void stopLocked() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    /** Test-only: override the export interval (default 10s). */
    static void setIntervalMillisForTesting(long millis) {
        intervalMillis = millis;
    }

    /** Test-only: restore the default export interval. */
    static void resetIntervalForTesting() {
        intervalMillis = DEFAULT_INTERVAL_MILLIS;
    }

    private static void runLoop(String endpoint) {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                export(endpoint);
            } catch (Exception e) {
                // Never let an export failure kill the daemon thread — retry next tick.
                System.err.println("[multiforge] otel export to " + endpoint + " failed: " + e);
            }
            try {
                //noinspection BusyWait
                Thread.sleep(intervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    static void export(String endpoint) throws IOException, InterruptedException {
        String body = buildPayload(ProbeRegistry.snapshot());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
    }

    /**
     * Builds the OTLP-HTTP JSON body (proto3 JSON mapping of {@code
     * ExportMetricsServiceRequest}) for one export cycle: one cumulative
     * monotonic sum metric per {@link ProbeRegistry} counter, all under a
     * single resource/scope.
     */
    static String buildPayload(NavigableMap<String, Long> probes) {
        long now = System.currentTimeMillis() * 1_000_000L;
        StringBuilder sb = new StringBuilder(256 + probes.size() * 192);
        sb.append("{\"resourceMetrics\":[{");
        sb.append(
                "\"resource\":{\"attributes\":[{\"key\":\"service.name\",\"value\":{\"stringValue\":\"multiforge\"}}]},");
        sb.append("\"scopeMetrics\":[{");
        sb.append("\"scope\":{\"name\":\"net.multiforge.runtime.telemetry\"},");
        sb.append("\"metrics\":[");
        boolean first = true;
        for (Map.Entry<String, Long> probe : probes.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            appendMetric(sb, probe.getKey(), probe.getValue(), now);
        }
        sb.append("]}]}]}");
        return sb.toString();
    }

    private static void appendMetric(StringBuilder sb, String name, long value, long nowUnixNanos) {
        sb.append("{\"name\":\"multiforge.probe.").append(jsonEscape(name)).append('"');
        sb.append(",\"unit\":\"1\"");
        sb.append(",\"sum\":{\"dataPoints\":[{");
        sb.append("\"startTimeUnixNano\":\"").append(START_TIME_UNIX_NANOS).append('"');
        sb.append(",\"timeUnixNano\":\"").append(nowUnixNanos).append('"');
        sb.append(",\"asInt\":\"").append(value).append('"');
        sb.append("}],");
        sb.append("\"aggregationTemporality\":\"AGGREGATION_TEMPORALITY_CUMULATIVE\",");
        sb.append("\"isMonotonic\":true}");
        sb.append('}');
    }

    private static String jsonEscape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
