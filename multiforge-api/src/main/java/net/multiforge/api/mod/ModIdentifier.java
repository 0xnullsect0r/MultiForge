/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
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
