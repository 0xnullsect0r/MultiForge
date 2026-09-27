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
package net.multiforge.runtime.event;

import java.util.Objects;
import java.util.function.LongFunction;
import net.multiforge.runtime.diagnostics.ProbeRegistry;

/**
 * Breaks the {@code event.dispatch.serial} count down by what caused it, so an
 * operator can see which event, which mod and which dimension send work to the
 * serial lane ({@code /multiforge probes top event.dispatch.serial}):
 * <ul>
 *   <li>{@code event.dispatch.serial.event.<event class>}</li>
 *   <li>{@code event.dispatch.serial.mod.<module of the listener>}</li>
 *   <li>{@code event.dispatch.serial.world.<dimension of the posting region>}</li>
 * </ul>
 * Probe keys are built once per class and cached.
 */
public final class SerialDispatchProbes {

    private static final String PREFIX = "event.dispatch.serial.";

    private static final ClassValue<String> EVENT_KEYS = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            return PREFIX + "event." + type.getName();
        }
    };

    private static final ClassValue<String> MOD_KEYS = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            return PREFIX + "mod." + moduleOf(type);
        }
    };

    private static final java.util.concurrent.ConcurrentMap<String, String> WORLD_KEYS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static volatile LongFunction<String> worldOfRegion = id -> null;

    private SerialDispatchProbes() {}

    /** Bind the region-id → dimension-id lookup (the host installs it). */
    public static void bindWorldLookup(LongFunction<String> lookup) {
        worldOfRegion = Objects.requireNonNull(lookup, "lookup");
    }

    public static void unbindWorldLookup() {
        worldOfRegion = id -> null;
    }

    /** Count one serial listener invocation of {@code event} by {@code listenerClass}, posted by region {@code regionId}. */
    static void record(Object event, Class<?> listenerClass, long regionId) {
        if (event != null) ProbeRegistry.bump(EVENT_KEYS.get(event.getClass()));
        if (listenerClass != null) ProbeRegistry.bump(MOD_KEYS.get(listenerClass));
        String world = worldOfRegion.apply(regionId);
        if (world != null) ProbeRegistry.bump(WORLD_KEYS.computeIfAbsent(world, w -> PREFIX + "world." + w));
    }

    /** The listener's module name, or for a class outside a named module its top-level package. */
    static String moduleOf(Class<?> type) {
        String module = type.getModule().getName();
        if (module != null) return module;
        String pkg = type.getPackageName();
        int cut = -1;
        for (int i = 0, dots = 0; i < pkg.length(); i++) {
            if (pkg.charAt(i) == '.' && ++dots == 3) {
                cut = i;
                break;
            }
        }
        return pkg.isEmpty() ? "unnamed" : cut < 0 ? pkg : pkg.substring(0, cut);
    }
}
