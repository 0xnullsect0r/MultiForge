/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.bootstrap;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import net.multiforge.license.LicenseGate;
import net.multiforge.license.LicenseToken;
import net.multiforge.runtime.MultiForge;

/**
 * M0 stand-in entry point: verifies the license and, on success, blocks
 * until the JVM is signaled. Real server bootstrap replaces this in M2
 * once the tick pipeline is wired to a NeoForge server jar.
 *
 * <p>Exists so the Docker image is functional end-to-end before the
 * installer artifact is available: an operator can already validate
 * their license and container plumbing.
 */
public final class LicenseOnlyMain {

    private LicenseOnlyMain() {}

    public static void main(String[] args) {
        LicenseGate gate = new LicenseGate();
        LicenseToken token = gate.bootstrap();
        System.out.println("[MultiForge] " + MultiForge.NAME + " " + MultiForge.VERSION
                + " (license-only mode; server jar not yet built).");
        System.out.println("[MultiForge] Customer: " + token.subject() + " · features: " + token.features());

        // Block until signaled. `docker stop` sends SIGTERM which triggers the shutdown hook.
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(stop::countDown, "multiforge-shutdown"));
        try {
            //noinspection ResultOfMethodCallIgnored
            stop.await(Duration.ofDays(3650).toNanos() / 1_000_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("[MultiForge] Shutting down.");
    }
}
