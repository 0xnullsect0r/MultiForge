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

import java.util.concurrent.ConcurrentHashMap;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;

/**
 * Operator visibility for {@code RegionizedTickCoordinator.dispatchLevelTick}
 * (fork module) — kept here, MC-free, so it is unit-testable.
 *
 * <p>The only non-regionized path left in a level tick is a level with no
 * materialised regionizer yet (no chunk of it loaded): its Vanilla level
 * tick then runs everything inline on the server thread, because the
 * {@code regionsHandle*} guards report {@code false} for it. That is correct
 * behaviour, not a failure, but worth seeing: {@link #noRegionizerInline}
 * counts every occurrence under {@link #NO_REGIONIZER_INLINE_PROBE} and warns
 * once per world.
 */
public final class LevelTickDispatchProbes {

    private LevelTickDispatchProbes() {}

    /** Probe key bumped each tick a level runs inline for lack of a regionizer. */
    public static final String NO_REGIONIZER_INLINE_PROBE = "region-tick.no-regionizer-inline";

    /** World ids that already warned — one warning per world, ever. */
    private static final ConcurrentHashMap<String, Boolean> warnedNoRegionizer = new ConcurrentHashMap<>();

    /**
     * {@code worldId}'s level ticked with no materialised regionizer, so it
     * ran inline on the server thread. Bumps {@link #NO_REGIONIZER_INLINE_PROBE}
     * every time; warns once per world (site {@code
     * NO_REGIONIZER_INLINE_PROBE + "::" + worldId}, so distinct worlds never
     * share a rate-limit bucket).
     */
    public static void noRegionizerInline(String worldId) {
        ProbeRegistry.bump(NO_REGIONIZER_INLINE_PROBE);
        if (warnedNoRegionizer.putIfAbsent(worldId, Boolean.TRUE) == null) {
            ViolationLogger.warn(
                    NO_REGIONIZER_INLINE_PROBE + "::" + worldId,
                    "level " + worldId + " has no regionizer yet — ticking it inline on the server thread");
        }
    }

    /** Test-only: forget which worlds already warned. */
    public static void resetForTesting() {
        warnedNoRegionizer.clear();
    }
}
