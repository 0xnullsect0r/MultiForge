/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.neoforge;

import net.multiforge.runtime.ownership.OwnershipEnforcer;

/**
 * Thin adapter that patched {@code net.minecraft.*} mutation sites
 * (multiforge-patches/01-ownership/) call into. All real decision logic
 * lives in {@link OwnershipEnforcer} (multiforge-runtime), which this
 * fork depends on via mavenLocal — see projects/neoforge/build.gradle.
 *
 * <p>Kept deliberately as a pass-through: this class exists so patched
 * vanilla call sites reference a stable, fork-local package rather than
 * multiforge-runtime directly, mirroring how NeoForge itself keeps its
 * own hand-written glue under {@code net.neoforged.neoforge} separate
 * from patched vanilla under {@code net.minecraft}.
 */
public final class OwnershipGuard {
    private OwnershipGuard() {}

    /**
     * @param site short symbolic id of the call site, e.g. {@code "Level.setBlock"}.
     * @return {@code true} if the caller may run {@code site}'s mutation body inline right now.
     *         {@code false} means the caller must instead hand its mutation to {@link #reroute}.
     */
    public static boolean canMutate(String site) {
        return OwnershipEnforcer.canMutate(site);
    }

    /**
     * Hands {@code mutation} to MultiForge's configured reroute target.
     * Callers invoke this only after {@link #canMutate} has returned
     * {@code false} for the same site.
     */
    public static void reroute(String site, Runnable mutation) {
        OwnershipEnforcer.reroute(site, mutation);
    }

    /**
     * Same detect-and-log as {@link #canMutate} — bumps the ProbeRegistry
     * and fires the rate-limited warn if the current caller is off-region
     * — but always allows the caller to continue with the inline mutation
     * (except in STRICT mode, where it still throws). Used at patched
     * call sites that return a value the caller genuinely depends on
     * (Level.setBlock, ServerLevel.addFreshEntity): rerouting them would
     * make the sync return value a lie, so we accept the race and log
     * loudly instead — matching Vanilla's pre-M7 behaviour at these sites
     * plus observability. See /67 review finding #4.
     */
    public static void checkOnly(String site) {
        // Discard the boolean — the return-value patch sites don't act on it.
        // canMutate still does the probe bump + violation log for us,
        // and STRICT mode still throws.
        OwnershipEnforcer.canMutate(site);
    }
}
