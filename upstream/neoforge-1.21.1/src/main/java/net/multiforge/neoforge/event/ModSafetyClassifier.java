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
package net.multiforge.neoforge.event;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.multiforge.api.event.DispatchDomainKind;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.event.EventTypeDomainMap;
import net.multiforge.runtime.event.ModClassifier;
import net.multiforge.runtime.event.ModSafety;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * Binds {@link ModClassifier}: a listener's class belongs to the mod whose
 * jar (JPMS module) defines it, and that mod's {@link ModSafety} is, in
 * order, its entry in {@code config/multiforge-mods.toml}, its own {@code
 * multiforge_safety} mod property, or {@code hybrid-safe}.
 *
 * <pre>
 * # config/multiforge-mods.toml
 * [mods]
 * examplemod = "legacy"        # legacy | hybrid-safe | strict-safe
 * </pre>
 *
 * A mod declares its own classification in {@code neoforge.mods.toml}:
 * 
 * <pre>
 * [modproperties.examplemod]
 * multiforge_safety = "strict-safe"
 * </pre>
 */
public final class ModSafetyClassifier {
    public static final String FILE = "multiforge-mods.toml";
    public static final String PROPERTY = "multiforge_safety";

    private ModSafetyClassifier() {}

    public static void bind(MinecraftServer server) {
        Path file = server.getServerDirectory().resolve("config").resolve(FILE);
        Map<String, ModSafety> configured = readConfig(file);
        readEventOverrides(file).forEach(EventTypeDomainMap::register);
        Map<String, ModSafety> byModule = new HashMap<>();
        for (IModFileInfo fileInfo : ModList.get().getModFiles()) {
            ModSafety safety = null;
            for (IModInfo mod : fileInfo.getMods()) {
                ModSafety modSafety = configured.get(mod.getModId());
                if (modSafety == null) modSafety = declared(mod);
                // A jar holding several mods takes its most conservative classification.
                if (modSafety != null && (safety == null || modSafety.ordinal() < safety.ordinal())) safety = modSafety;
            }
            if (safety != null) byModule.put(fileInfo.moduleName(), safety);
        }
        Map<Class<?>, ModSafety> cache = new ConcurrentHashMap<>();
        ModClassifier.bind(cls -> cache.computeIfAbsent(
                cls, c -> byModule.getOrDefault(c.getModule().getName(), ModSafety.HYBRID_SAFE)));
    }

    private static ModSafety declared(IModInfo mod) {
        Object value = mod.getModProperties().get(PROPERTY);
        if (!(value instanceof String s)) return null;
        try {
            return ModSafety.parse(s);
        } catch (IllegalArgumentException e) {
            ViolationLogger.warn(
                    "ModSafetyClassifier",
                    "mod " + mod.getModId() + " declares " + PROPERTY + " = \"" + s + "\"; expected legacy, hybrid-safe or strict-safe");
            return null;
        }
    }

    static Map<String, ModSafety> readConfig(Path file) {
        Map<String, ModSafety> out = new HashMap<>();
        try {
            if (!Files.isRegularFile(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, """
                        # Per-mod thread-safety classification for MultiForge event listeners.
                        # Applies to listeners without a @DispatchDomain annotation.
                        #   legacy       every listener runs on the serial lane (one at a time)
                        #   hybrid-safe  block/entity-local events run on region workers,
                        #                the rest on the serial lane (the default)
                        #   strict-safe  every listener runs on the posting thread
                        # See docs/events.md.
                        [mods]
                        # examplemod = "legacy"

                        # Per-event default for listeners of hybrid-safe mods, by event class:
                        #   region  runs on the region worker that posted it
                        #   serial  runs on the serial lane
                        [events]
                        # "net.neoforged.neoforge.event.entity.living.LivingDrownEvent" = "serial"
                        """, StandardCharsets.UTF_8);
                return out;
            }
            UnmodifiableConfig root = new TomlParser().parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(root.get("mods") instanceof UnmodifiableConfig mods)) return out;
            for (UnmodifiableConfig.Entry entry : mods.entrySet()) {
                if (!(entry.getValue() instanceof String s)) continue;
                try {
                    out.put(entry.getKey(), ModSafety.parse(s));
                } catch (IllegalArgumentException e) {
                    ViolationLogger.warn(
                            "ModSafetyClassifier", FILE + ": " + entry.getKey() + " = \"" + s + "\" is not legacy, hybrid-safe or strict-safe");
                }
            }
        } catch (IOException | RuntimeException e) {
            ViolationLogger.warn("ModSafetyClassifier", "could not read " + file + ": " + e.getMessage());
        }
        return out;
    }

    /**
     * The {@code [events]} table: event class name to {@code "region"} or
     * {@code "serial"} (also {@code "global"}, {@code "async"}). Applied through
     * {@link EventTypeDomainMap#register}, so it reaches listeners registered
     * before the server started.
     */
    static Map<String, DispatchDomainKind> readEventOverrides(Path file) {
        Map<String, DispatchDomainKind> out = new HashMap<>();
        if (!Files.isRegularFile(file)) return out;
        try {
            UnmodifiableConfig root = new TomlParser().parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(root.get("events") instanceof UnmodifiableConfig events)) return out;
            for (UnmodifiableConfig.Entry entry : events.entrySet()) {
                String value = entry.getValue() instanceof String s ? s.trim().toLowerCase(java.util.Locale.ROOT) : "";
                DispatchDomainKind kind = switch (value) {
                    case "region" -> DispatchDomainKind.REGION;
                    case "serial", "legacy-serial" -> DispatchDomainKind.LEGACY_SERIAL;
                    case "global" -> DispatchDomainKind.GLOBAL;
                    case "async" -> DispatchDomainKind.ASYNC;
                    default -> null;
                };
                if (kind == null) {
                    ViolationLogger.warn(
                            "ModSafetyClassifier", FILE + ": [events] " + entry.getKey() + " = \"" + entry.getValue() + "\" is not region or serial");
                    continue;
                }
                out.put(entry.getKey(), kind);
            }
        } catch (IOException | RuntimeException e) {
            ViolationLogger.warn("ModSafetyClassifier", "could not read [events] of " + file + ": " + e.getMessage());
        }
        return out;
    }
}
