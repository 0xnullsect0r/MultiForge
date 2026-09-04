/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime;

/**
 * Runtime metadata and version constants. This is a placeholder-only
 * façade for M0; the M1 milestone lands the public API surface under
 * {@code net.multiforge.api} that wraps this runtime.
 */
public final class MultiForge {

    public static final String NAME = "MultiForge";

    /**
     * Semantic version of the currently-running runtime, populated from
     * the Gradle build at package time. Reads {@code
     * /multiforge-runtime.properties} on the classpath; falls back to
     * {@code "dev"} when running from a raw classpath (tests, IDE).
     */
    public static final String VERSION = readVersion();

    private MultiForge() {}

    private static String readVersion() {
        try (var in = MultiForge.class.getResourceAsStream("/multiforge-runtime.properties")) {
            if (in == null) return "dev";
            var props = new java.util.Properties();
            props.load(in);
            return props.getProperty("version", "dev");
        } catch (java.io.IOException e) {
            return "dev";
        }
    }
}
