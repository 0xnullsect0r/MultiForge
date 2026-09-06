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
