/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

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
        TomlParseResult r = Toml.parse(toml);
        if (r.hasErrors()) {
            String err = r.errors().stream().map(Object::toString).findFirst().orElse("<unknown>");
            throw new IllegalArgumentException("Invalid multiforge-server.toml: " + err);
        }
        MultiForgeConfig d = MultiForgeConfig.defaults();
        int cores = intOr(r, "mtserver.cores", d.cores());
        int tpc = intOr(r, "mtserver.threadsPerCore", d.threadsPerCore());
        MultiForgeConfig.Mode mode = enumOr(r, "mtserver.mode", MultiForgeConfig.Mode.class, d.mode());
        int regionSize = intOr(r, "region.size", d.regionSize());
        MultiForgeConfig.RegionMode regionMode =
                enumOr(r, "region.mode", MultiForgeConfig.RegionMode.class, d.regionMode(), s -> s.replace('-', '_')
                        .toUpperCase(Locale.ROOT));
        double splitT = doubleOr(r, "region.msptSplitThreshold", d.msptSplitThreshold());
        double mergeT = doubleOr(r, "region.msptMergeThreshold", d.msptMergeThreshold());
        MultiForgeConfig.ViolationPolicy vp = enumOr(
                r, "violations.policy", MultiForgeConfig.ViolationPolicy.class, d.violationPolicy(), s -> s.replace(
                                '-', '_')
                        .toUpperCase(Locale.ROOT));
        int warnPerMin = intOr(r, "violations.warnPerMin", d.warnPerMin());
        return new MultiForgeConfig(cores, tpc, mode, regionSize, regionMode, splitT, mergeT, vp, warnPerMin);
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
        sb.append("size = ").append(c.regionSize()).append("\n");
        sb.append("mode = \"")
                .append(c.regionMode().name().toLowerCase(Locale.ROOT).replace('_', '-'))
                .append("\"\n");
        sb.append("msptSplitThreshold = ").append(c.msptSplitThreshold()).append("\n");
        sb.append("msptMergeThreshold = ").append(c.msptMergeThreshold()).append("\n\n");
        sb.append("[violations]\n");
        sb.append("policy = \"")
                .append(c.violationPolicy().name().toLowerCase(Locale.ROOT).replace('_', '-'))
                .append("\"\n");
        sb.append("warnPerMin = ").append(c.warnPerMin()).append("\n");
        return sb.toString();
    }

    public static void save(Path file, MultiForgeConfig c) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, render(c), StandardCharsets.UTF_8);
    }

    private static int intOr(TomlParseResult r, String key, int fallback) {
        Long v = r.getLong(key);
        return v == null ? fallback : Math.toIntExact(v);
    }

    private static double doubleOr(TomlParseResult r, String key, double fallback) {
        Double d = r.getDouble(key);
        if (d != null) return d;
        Long l = r.getLong(key);
        return l == null ? fallback : l.doubleValue();
    }

    private static <E extends Enum<E>> E enumOr(TomlParseResult r, String key, Class<E> type, E fallback) {
        return enumOr(r, key, type, fallback, s -> s.toUpperCase(Locale.ROOT));
    }

    private static <E extends Enum<E>> E enumOr(
            TomlParseResult r,
            String key,
            Class<E> type,
            E fallback,
            java.util.function.Function<String, String> norm) {
        String s = r.getString(key);
        if (s == null) return fallback;
        try {
            return Enum.valueOf(type, norm.apply(s));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid " + type.getSimpleName() + " for key '" + key + "': " + s
                    + " (valid: " + java.util.Arrays.toString(type.getEnumConstants()) + ")");
        }
    }
}
