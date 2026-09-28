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
        boolean deferVisibility = boolOr(r, "entities.deferVisibility", d.deferVisibility());
        boolean lockFree = boolOr(r, "perf.lockFreeOutsidePhase", d.lockFreeOutsidePhase());
        ActivationConfig a = d.activation();
        ActivationConfig activation = new ActivationConfig(
                boolOr(r, "entities.activation", a.activation()),
                intOr(r, "entities.monsterRange", a.monsterRange()),
                intOr(r, "entities.animalRange", a.animalRange()),
                intOr(r, "entities.villagerRange", a.villagerRange()),
                intOr(r, "entities.flyingRange", a.flyingRange()),
                intOr(r, "entities.raiderRange", a.raiderRange()),
                intOr(r, "entities.waterRange", a.waterRange()),
                intOr(r, "entities.ambientRange", a.ambientRange()),
                intOr(r, "entities.wakeInterval", a.wakeInterval()),
                intOr(r, "entities.maxEntityCollisions", a.maxEntityCollisions()),
                stringsOr(r, "entities.activationExempt", a.exempt()));
        return new MultiForgeConfig(
                cores,
                tpc,
                mode,
                regionSize,
                vp,
                warnPerMin,
                inlineSingle,
                hotWaitMs,
                deferVisibility,
                lockFree,
                activation);
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
        sb.append("serialLaneHotWaitMs = ").append(c.serialLaneHotWaitMs()).append("\n\n");
        sb.append("[entities]\n");
        sb.append("# While region workers run, entity-visibility changes from chunk loads and\n");
        sb.append("# unloads wait for the tick barrier, so a worker moving an entity cannot race\n");
        sb.append("# them. false restores v1.10's behaviour (kill switch).\n");
        sb.append("deferVisibility = ").append(c.deferVisibility()).append("\n");
        ActivationConfig a = c.activation();
        sb.append("# Entity activation range. A mob further than its category's range (blocks)\n");
        sb.append("# from every player runs a full tick only once every wakeInterval ticks; on\n");
        sb.append("# the other ticks it only ages, so it still despawns and grows up. Anything\n");
        sb.append("# that is not a mob (items, projectiles, minecarts, contraptions) always\n");
        sb.append("# ticks, and so do mobs that are riding or ridden, leashed, targeting, hurt\n");
        sb.append("# in the last 5 s, in love, under 1 s old, bosses and multipart mobs,\n");
        sb.append("# falling land mobs and land mobs in water (farms), and the types in the\n");
        sb.append("# #multiforge:activation_exempt tag or activationExempt. A region running\n");
        sb.append("# slower than 40 ms per tick stretches the interval, up to 80 ticks. This\n");
        sb.append("# changes Vanilla behaviour far from players: see docs/compatibility.md.\n");
        sb.append("# A range of 0 never throttles that category. false ticks every mob every\n");
        sb.append("# tick (kill switch). Ignored in mode = \"off\".\n");
        sb.append("activation = ").append(a.activation()).append("\n");
        sb.append("monsterRange = ").append(a.monsterRange()).append("\n");
        sb.append("animalRange = ").append(a.animalRange()).append("\n");
        sb.append("villagerRange = ").append(a.villagerRange()).append("\n");
        sb.append("flyingRange = ").append(a.flyingRange()).append("\n");
        sb.append("raiderRange = ").append(a.raiderRange()).append("\n");
        sb.append("waterRange = ").append(a.waterRange()).append("\n");
        sb.append("ambientRange = ").append(a.ambientRange()).append("\n");
        sb.append("wakeInterval = ").append(a.wakeInterval()).append("\n");
        sb.append("# Entity type ids (\"modid:name\") or tags (\"#modid:tag\") that always tick.\n");
        sb.append("activationExempt = [");
        for (int i = 0; i < a.exempt().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('"')
                    .append(a.exempt().get(i).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        sb.append("]\n");
        sb.append("# A living entity pushes at most this many of the entities overlapping it\n");
        sb.append("# per tick (0 = no cap, Vanilla). Cramming damage still counts them all.\n");
        sb.append("# Ignored in mode = \"off\".\n");
        sb.append("maxEntityCollisions = ").append(a.maxEntityCollisions()).append("\n\n");
        sb.append("[perf]\n");
        sb.append("# Entity queries on the server thread while no region worker runs read the\n");
        sb.append("# entity storage directly, as Vanilla does, instead of under its lock with a\n");
        sb.append("# copy. false takes the lock everywhere (kill switch).\n");
        sb.append("lockFreeOutsidePhase = ").append(c.lockFreeOutsidePhase()).append("\n");
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

    private static java.util.List<String> stringsOr(UnmodifiableConfig r, String key, java.util.List<String> fallback) {
        Object v = r.get(key);
        if (v == null) return fallback;
        if (v instanceof java.util.List<?> list) {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof String s)) {
                    throw new IllegalArgumentException("Key '" + key + "' must be a list of strings, got: " + v);
                }
                out.add(s);
            }
            return out;
        }
        throw new IllegalArgumentException("Key '" + key + "' must be a list of strings, got: " + v);
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
