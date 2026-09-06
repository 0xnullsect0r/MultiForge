/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OtelExporterTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        OtelExporter.stop();
        OtelExporter.resetIntervalForTesting();
        if (server != null) server.stop(0);
        ProbeRegistry.resetForTesting();
        System.clearProperty(OtelExporter.ENDPOINT_PROPERTY);
    }

    @Test
    void postsOtlpPayloadToConfiguredEndpoint() throws Exception {
        ProbeRegistry.resetForTesting();
        ProbeRegistry.bump("test.metric");

        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/metrics", exchange -> {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            body.set(new String(bytes, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            received.countDown();
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/metrics";

        OtelExporter.setIntervalMillisForTesting(50);
        OtelExporter.start(endpoint);

        assertThat(received.await(5, TimeUnit.SECONDS))
                .as("exporter POSTed within 5s")
                .isTrue();

        String json = body.get();
        assertThat(json).contains("\"resourceMetrics\"");
        assertThat(json).contains("\"service.name\"");
        assertThat(json).contains("\"multiforge.probe.test.metric\"");
        assertThat(json).contains("\"asInt\":\"1\"");
        assertThat(json).contains("\"aggregationTemporality\":\"AGGREGATION_TEMPORALITY_CUMULATIVE\"");
        assertThat(json).contains("\"isMonotonic\":true");
    }

    @Test
    void startFromSystemPropertyIsNoOpWhenUnset() {
        System.clearProperty(OtelExporter.ENDPOINT_PROPERTY);
        OtelExporter.startFromSystemProperty();
        // No exception, no thread left running for a subsequent start() to fight over.
        OtelExporter.start(null);
    }

    @Test
    void startFromSystemPropertyHonorsProperty() throws Exception {
        ProbeRegistry.resetForTesting();
        ProbeRegistry.bump("sys.prop.metric");

        CountDownLatch received = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            received.countDown();
        });
        server.start();

        System.setProperty(
                OtelExporter.ENDPOINT_PROPERTY,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/");
        OtelExporter.setIntervalMillisForTesting(50);
        OtelExporter.startFromSystemProperty();

        assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
    }
}
