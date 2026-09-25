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
package net.multiforge.runtime.scheduler;

import net.multiforge.runtime.diagnostics.ProbeRegistry;

/**
 * Operator visibility for {@code RegionizedTickCoordinator.dispatchLevelTick}
 * (fork module) — kept here, MC-free, so it is unit-testable.
 *
 * <p>The only non-regionized path left in a level tick is a level with no
 * materialised regionizer (no chunk of it loaded — an empty Nether or End):
 * its Vanilla level tick then runs inline on the server thread, because the
 * {@code regionsHandle*} guards report {@code false} for it. With no chunks
 * there is nothing a region could tick, so this is normal, not a violation;
 * {@link #noRegionizerInline} only counts it under {@link
 * #NO_REGIONIZER_INLINE_PROBE}.
 */
public final class LevelTickDispatchProbes {

    private LevelTickDispatchProbes() {}

    /** Probe key bumped each tick a level runs inline for lack of a regionizer. */
    public static final String NO_REGIONIZER_INLINE_PROBE = "region-tick.no-regionizer-inline";

    /** {@code worldId}'s level ticked with no materialised regionizer, so it ran inline on the server thread. */
    public static void noRegionizerInline(String worldId) {
        ProbeRegistry.bump(NO_REGIONIZER_INLINE_PROBE);
        ProbeRegistry.bump(NO_REGIONIZER_INLINE_PROBE + "." + worldId);
    }
}
