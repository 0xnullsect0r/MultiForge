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
