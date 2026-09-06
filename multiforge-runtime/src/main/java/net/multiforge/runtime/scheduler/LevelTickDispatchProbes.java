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
 * Pure-Java probe/warn bodies for the three no-fallback skip paths in the
 * fork façade's {@code RegionizedTickCoordinator.dispatchLevelTick}
 * (post-B3.5, {@code docs/design/m13-b3-region-tick.md} §2 / {@code
 * docs/design/global-region.md} §6.4).
 *
 * <p>Pulled out of the façade (which lives under {@code
 * upstream/neoforge-1.21.1/src/main/java/net/multiforge/neoforge/} and
 * depends on {@code net.minecraft.server.level.ServerLevel}) so the
 * probe-bump + rate-limited-warn behaviour of each skip path is directly
 * unit-testable from {@code multiforge-runtime} without a Minecraft
 * classpath — see {@code CLAUDE.md}'s "thin patch, heavy logic in fork
 * glue or runtime" convention. The façade's {@code dispatchLevelTick}
 * calls these three methods instead of inlining the probe/warn pair; the
 * probe names, message shapes, and control flow are otherwise unchanged
 * from the frozen target shape.
 *
 * <p>None of these methods accept or invoke a callback of any kind —
 * unlike the pre-B3.5 {@code vanillaBody.run()} fallback they replace,
 * there is nothing left to run inline once a skip path is taken. Each
 * call site in {@code dispatchLevelTick} does nothing further after
 * calling one of these and returns.
 */
public final class LevelTickDispatchProbes {

    private LevelTickDispatchProbes() {}

    /** Probe key bumped when the MultiForge runtime is not yet installed. */
    public static final String BOOTSTRAP_SKIP_PROBE = "region-tick.bootstrap-skip";

    /** Probe key bumped when {@code level}'s world has no materialised regionizer yet. */
    public static final String NO_REGIONIZER_SKIP_PROBE = "region-tick.no-regionizer-skip";

    /** Probe key bumped when {@code TickRegionScheduler#tickAll} itself throws. */
    public static final String DISPATCH_FAILURE_PROBE = "region-tick.dispatch.failure";

    /**
     * Sentinel set of world ids that have already emitted a {@link
     * #noRegionizerSkip} warn. {@link ViolationLogger}'s rate limiter
     * buckets by site, and every world previously shared the single site
     * {@link #NO_REGIONIZER_SKIP_PROBE} — on an idle server with both
     * {@code the_end} and {@code the_nether} unloaded, the two worlds
     * contended for one shared 5/60s bucket and drowned each other out.
     * Keyed per world so each world gets its own warn-once-then-silent
     * behaviour instead of a shared rate-limit budget.
     */
    private static final ConcurrentHashMap<String, Boolean> warnedNoRegionizer = new ConcurrentHashMap<>();

    /**
     * Bootstrap fallback: the MultiForge runtime is not installed yet
     * (fresh boot, pre-{@code ServerAboutToStart}). Bumps {@link
     * #BOOTSTRAP_SKIP_PROBE} and emits a rate-limited warn. The caller
     * (the façade) returns immediately after this — the server tick
     * counter still advances, but {@code levelLabel} gets no per-region
     * work this pass.
     *
     * @param levelLabel a human-readable identifier for the level being
     *                    ticked (e.g. its dimension location), used only
     *                    in the warn message.
     */
    public static void bootstrapSkip(String levelLabel) {
        ProbeRegistry.bump(BOOTSTRAP_SKIP_PROBE);
        ViolationLogger.warn(
                BOOTSTRAP_SKIP_PROBE,
                "level " + levelLabel + " ticked before MultiForge runtime installed — skipping this tick");
    }

    /**
     * No-regionizer fallback: {@code worldId}'s world has no materialised
     * regionizer yet (no {@code ChunkEvent.Load} has fired, or the world
     * is exiting). Bumps {@link #NO_REGIONIZER_SKIP_PROBE} unconditionally
     * — operators can always see the true per-world skip frequency via
     * the probe counter — but emits a warn at most once per {@code
     * worldId}, ever, after which that world falls silent. The warn site
     * is {@code NO_REGIONIZER_SKIP_PROBE + "::" + worldId} so distinct
     * worlds get distinct {@link ViolationLogger} rate-limit buckets on
     * the rare occasion more than one world's first warn lands close
     * together.
     *
     * @param worldId the world's dimension id, used both as part of the
     *                 per-world warn-once key and in the warn message.
     */
    public static void noRegionizerSkip(String worldId) {
        ProbeRegistry.bump(NO_REGIONIZER_SKIP_PROBE);
        if (warnedNoRegionizer.putIfAbsent(worldId, Boolean.TRUE) == null) {
            ViolationLogger.warn(
                    NO_REGIONIZER_SKIP_PROBE + "::" + worldId,
                    "level " + worldId + " has no materialised regionizer yet — skipping this tick");
        }
    }

    /**
     * Dispatch-side failure fallback: {@code TickRegionScheduler#tickAll}
     * itself threw (never a region worker's own exception, which stays on
     * the worker). Bumps {@link #DISPATCH_FAILURE_PROBE} and emits a
     * rate-limited warn describing {@code failure}. Same shape as {@link
     * #bootstrapSkip} otherwise — there is no inline fallback left to run.
     *
     * @param worldId the world's dimension id, used only in the warn message.
     * @param failure the exception {@code tickAll} threw.
     */
    public static void dispatchFailure(String worldId, Throwable failure) {
        ProbeRegistry.bump(DISPATCH_FAILURE_PROBE);
        ViolationLogger.warn(
                DISPATCH_FAILURE_PROBE,
                "tickAll failed for " + worldId + ": " + failure.getClass().getSimpleName() + ": "
                        + failure.getMessage());
    }

    /** Test-only: clears the per-world warn-once sentinel so per-test state doesn't leak. */
    public static void resetForTesting() {
        warnedNoRegionizer.clear();
    }
}
