/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.globals;

import net.multiforge.runtime.region.RegionId;

/**
 * Collaborator {@link GlobalSystems} hands to every registered {@link
 * GlobalSystem} via {@link GlobalSystems#effects()}, backing {@link
 * GlobalSystem#crossRegionEffect(RegionId, Runnable)}. See {@code
 * docs/design/global-region.md} §3.5/§3.6 for the full resolution
 * contract.
 *
 * <p>{@link #enqueue} is a no-op (logged, rate-limited) if {@code dest}
 * no longer resolves to a live region — never throws. Matches CLAUDE.md
 * rule 5's auto-reroute+warn default.
 */
public interface CrossRegionEffects {
    void enqueue(RegionId dest, Runnable task);
}
