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

import java.util.Locale;

/**
 * How much of a mod's event-listener code may run on region workers in
 * parallel. Applies to listeners without a {@code @DispatchDomain}
 * annotation; an annotation always wins.
 *
 * <p>Set per mod in {@code config/multiforge-mods.toml}, or declared by the
 * mod in its {@code neoforge.mods.toml} as {@code
 * [modproperties.<modid>] multiforge_safety = "strict-safe"}. The config file
 * overrides the declaration; the default is {@link #HYBRID_SAFE}.
 */
public enum ModSafety {
    /** Every unannotated listener runs on the serial lane. For mods with unsynchronised global state. */
    LEGACY,
    /**
     * Unannotated listeners of events MultiForge knows to be local to one
     * block, chunk or entity (see {@link EventTypeDomainMap}) run on the
     * posting region worker; all others run on the serial lane. The default.
     */
    HYBRID_SAFE,
    /** Every unannotated listener runs on the posting thread. For mods that are thread-safe. */
    STRICT_SAFE;

    /** Parse {@code legacy}, {@code hybrid-safe} or {@code strict-safe} (case-insensitive). */
    public static ModSafety parse(String value) {
        return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }

    /** The config-file spelling: {@code legacy}, {@code hybrid-safe}, {@code strict-safe}. */
    public String configName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
