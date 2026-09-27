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
package net.multiforge.runtime.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.toml.TomlParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * TOML read/write for {@link MultiForgeConfig}. Deliberately minimal —
 * defaults come from the record, this class only overrides keys that
 * are explicitly present in the file, so upgrading MultiForge never
 * silently changes existing operators' settings unless they've
 * customised the affected key.
 */
public final class ConfigCodec {

    private ConfigCodec() {}

    public static MultiForgeConfig load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return MultiForgeConfig.defaults();
        String contents = Files.readString(file, StandardCharsets.UTF_8);
        return parse(contents);
    }

    public static MultiForgeConfig parse(String toml) {
        UnmodifiableConfig r = parseToml(toml, "multiforge-server.toml");
        MultiForgeConfig d = MultiForgeConfig.defaults();
        int cores = intOr(r, "mtserver.cores", d.cores());
        int tpc = intOr(r, "mtserver.threadsPerCore", d.threadsPerCore());
        MultiForgeConfig.Mode mode = enumOr(r, "mtserver.mode", MultiForgeConfig.Mode.class, d.mode());
        int regionSize = intOr(r, "region.size", d.regionSize());
        MultiForgeConfig.ViolationPolicy vp = enumOr(
                r, "violations.policy", MultiForgeConfig.ViolationPolicy.class, d.violationPolicy(), s -> s.replace(
                                '-', '_')
                        .toUpperCase(Locale.ROOT));
        int warnPerMin = intOr(r, "violations.warnPerMin", d.warnPerMin());
        boolean inlineSingle = boolOr(r, "tick.inlineSingleRegion", d.inlineSingleRegion());
        // v1.8 counted serial-lane posts (`serialLaneInlineThreshold`); the knob is now
        // the time the hand-offs cost. An old file's 0 ("never") still means never;
        // any other old count is not a time and gives way to the default.
        int hotWaitMs = d.serialLaneHotWaitMs();
        if (r.get("tick.serialLaneHotWaitMs") != null) hotWaitMs = intOr(r, "tick.serialLaneHotWaitMs", hotWaitMs);
        else if (r.get("tick.serialLaneInlineThreshold") != null && intOr(r, "tick.serialLaneInlineThreshold", 1) <= 0)
            hotWaitMs = 0;
        return new MultiForgeConfig(cores, tpc, mode, regionSize, vp, warnPerMin, inlineSingle, hotWaitMs);
    }

    public static String render(MultiForgeConfig c) {
        StringBuilder sb = new StringBuilder();
        sb.append("# MultiForge server configuration.\n");
        sb.append("# In-game commands (`/multiforge config …`, `/multiforge region …`) rewrite\n");
        sb.append("# this file atomically; edits made while the server is stopped are picked\n");
        sb.append("# up at next boot.\n\n");
        sb.append("[mtserver]\n");
        sb.append("cores = ").append(c.cores()).append("\n");
        sb.append("threadsPerCore = ").append(c.threadsPerCore()).append("\n");
        sb.append("mode = \"").append(c.mode().name().toLowerCase(Locale.ROOT)).append("\"\n\n");
        sb.append("[region]\n");
        sb.append("# Sections are 2^size chunks on a side (4 = 16). `/multiforge region size <chunks>`\n");
        sb.append("# takes the edge in chunks instead.\n");
        sb.append("size = ").append(c.regionSize()).append("\n\n");
        sb.append("[violations]\n");
        sb.append("policy = \"")
                .append(c.violationPolicy().name().toLowerCase(Locale.ROOT).replace('_', '-'))
                .append("\"\n");
        sb.append("warnPerMin = ").append(c.warnPerMin()).append("\n\n");
        sb.append("[tick]\n");
        sb.append("# A level with a single region ticks it on the server thread: a worker would\n");
        sb.append("# add nothing but a hand-off for every serial-lane event and chunk load.\n");
        sb.append("inlineSingleRegion = ").append(c.inlineSingleRegion()).append("\n");
        sb.append("# A region whose serial-lane hand-offs cost it more than this many\n");
        sb.append("# milliseconds per tick ticks on the server thread after the others,\n");
        sb.append("# until it quiets down. 0 disables.\n");
        sb.append("serialLaneHotWaitMs = ").append(c.serialLaneHotWaitMs()).append("\n");
        return sb.toString();
    }

    public static void save(Path file, MultiForgeConfig c) throws IOException {
        AtomicFiles.writeString(file, render(c));
    }

    /**
     * Parse TOML with night-config — the TOML library NeoForge itself ships, so
     * the runtime adds no parser of its own to the server.
     */
    static UnmodifiableConfig parseToml(String toml, String fileName) {
        try {
            return new TomlParser().parse(toml);
        } catch (ParsingException e) {
            throw new IllegalArgumentException("Invalid " + fileName + ": " + e.getMessage(), e);
        }
    }

    private static Number number(UnmodifiableConfig r, String key) {
        Object v = r.get(key);
        if (v == null) return null;
        if (v instanceof Number n && !(v instanceof Double) && !(v instanceof Float)) return n;
        throw new IllegalArgumentException("Key '" + key + "' must be an integer, got: " + v);
    }

    private static boolean boolOr(UnmodifiableConfig r, String key, boolean fallback) {
        Object v = r.get(key);
        if (v == null) return fallback;
        if (v instanceof Boolean b) return b;
        throw new IllegalArgumentException("Key '" + key + "' must be true or false, got: " + v);
    }

    private static int intOr(UnmodifiableConfig r, String key, int fallback) {
        Number v = number(r, key);
        return v == null ? fallback : Math.toIntExact(v.longValue());
    }

    private static <E extends Enum<E>> E enumOr(UnmodifiableConfig r, String key, Class<E> type, E fallback) {
        return enumOr(r, key, type, fallback, s -> s.toUpperCase(Locale.ROOT));
    }

    private static <E extends Enum<E>> E enumOr(
            UnmodifiableConfig r,
            String key,
            Class<E> type,
            E fallback,
            java.util.function.Function<String, String> norm) {
        Object raw = r.get(key);
        if (raw == null) return fallback;
        if (!(raw instanceof String s)) {
            throw new IllegalArgumentException("Key '" + key + "' must be a string, got: " + raw);
        }
        try {
            return Enum.valueOf(type, norm.apply(s));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid " + type.getSimpleName() + " for key '" + key + "': " + s
                    + " (valid: " + java.util.Arrays.toString(type.getEnumConstants()) + ")");
        }
    }
}
