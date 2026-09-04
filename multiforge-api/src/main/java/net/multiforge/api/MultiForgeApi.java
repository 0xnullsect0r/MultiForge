/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.api;

import java.io.IOException;

/**
 * Metadata for the MultiForge public API surface a mod is compiled
 * against. Read {@link #VERSION} at mod init if you need to refuse to
 * run against an incompatible runtime.
 */
public final class MultiForgeApi {

    /** Human-readable API version, semver, e.g. {@code "0.1.0"}. */
    public static final String VERSION = readVersion();

    private MultiForgeApi() {}

    private static String readVersion() {
        try (var in = MultiForgeApi.class.getResourceAsStream("/multiforge-api.properties")) {
            if (in == null) return "dev";
            var props = new java.util.Properties();
            props.load(in);
            return props.getProperty("version", "dev");
        } catch (IOException e) {
            return "dev";
        }
    }
}
