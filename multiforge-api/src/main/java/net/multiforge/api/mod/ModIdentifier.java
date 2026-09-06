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
package net.multiforge.api.mod;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Opaque handle identifying a mod as the owner of a scheduled task, an
 * event listener, or a network payload. The MultiForge equivalent of
 * Folia's {@code Plugin}.
 *
 * <p>Get one from your mod's entry point via
 * {@code ModIdentifier.of("your_mod_id")}. The id must match your
 * declared id in {@code neoforge.mods.toml}.
 */
public record ModIdentifier(String modId) {

    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9_-]{1,63}$");

    public ModIdentifier {
        Objects.requireNonNull(modId, "modId");
        if (!ID.matcher(modId).matches()) {
            throw new IllegalArgumentException(
                    "modId must match " + ID.pattern() + " (mod id from your neoforge.mods.toml), got: " + modId);
        }
    }

    public static ModIdentifier of(String modId) {
        return new ModIdentifier(modId);
    }
}
