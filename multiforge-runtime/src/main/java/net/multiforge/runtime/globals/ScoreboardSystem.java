/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.globals;

import java.util.Set;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.region.RegionId;

/**
 * B2.4 — {@code ServerScoreboard} has no dedicated Vanilla {@code tick()}
 * method; the server-wide scoreboard is mutated ad hoc from mutation
 * hooks ({@code onScoreChanged}, {@code onPlayerRemoved}, ...) that can
 * fire from whichever region a scoring event (an entity dying, a
 * command running) happens to originate on. Left inline, two regions
 * could race the same {@code ServerScoreboard}/{@code PlayerList}
 * broadcast state concurrently.
 *
 * <p>This class is a <em>mutation-hook bridge</em>, not a periodic
 * ticker: {@link #route(Runnable)} funnels an arbitrary scoreboard
 * mutation (bound by fork glue to the original Vanilla hook body) onto
 * the single-threaded global region via {@link #crossRegionEffect},
 * using the global region's own {@link RegionId} as the destination —
 * the same "always-live anchor" pattern {@link TimeSystem}'s broadcast
 * uses. {@link #tick} itself does no scoreboard work; it only refreshes
 * the cached global {@link RegionId} so {@link #route} has a
 * destination to enqueue against even when called from outside a
 * {@code tick()} call (mutation hooks fire at arbitrary times, not just
 * during phase 4).
 */
public final class ScoreboardSystem extends AbstractGlobalSystem {

    private volatile RegionId globalRegionId;

    public ScoreboardSystem(CrossRegionEffects effects) {
        super(effects);
    }

    /**
     * @return {@code true} once this system has observed at least one
     *         {@link #tick} call (a live global {@link RegionId} is
     *         cached) — the readiness check the {@code ServerScoreboard}
     *         patches consult via {@code GlobalSystemsBridge} before
     *         calling {@link #tryRoute}. Before this is {@code true} the
     *         patched hook must run the mutation inline itself (exactly
     *         Vanilla's own behavior) rather than routing — there is no
     *         live destination to route to yet, and dropping a
     *         scoreboard mutation outright would silently lose state.
     */
    public boolean isReady() {
        return globalRegionId != null;
    }

    /**
     * Routes {@code mutation} (a fork-supplied callback running the
     * original Vanilla hook body, e.g. {@code super.onScoreChanged(...)}
     * plus the broadcast) onto the global region's mailbox — unless the
     * caller is <em>already</em> running on the global region worker
     * (see {@link GlobalRegionThreadMarker}), in which case {@code
     * mutation} runs inline, synchronously, before this method returns.
     * That reentrant fast path matches Vanilla's own return-after-mutate
     * contract: a command function running via {@code
     * CommandDispatchSystem} that does {@code scoreboard players add @s
     * c 1} followed by {@code execute if score @s c matches 5..} in the
     * same tick must see the incremented value, not a stale one from
     * before the mailbox drains next tick (docs/design/global-region.md
     * §7.3 / round-6 fork B F2). Safe to call from any thread, at any
     * time — not just during {@link #tick}; the off-thread mailbox hop
     * below is still correct, and still used, for every non-reentrant
     * caller.
     *
     * @return {@code true} if the mutation ran (inline or was accepted
     *         for routing), {@code false} if there is no known live
     *         {@link RegionId} yet (see {@link #isReady()}) — callers
     *         must run {@code mutation} themselves in that case rather
     *         than lose it.
     */
    public boolean tryRoute(Runnable mutation) {
        java.util.Objects.requireNonNull(mutation, "mutation");
        RegionId dest = globalRegionId;
        if (dest == null) {
            return false;
        }
        if (GlobalRegionThreadMarker.isCurrent()) {
            runInline(mutation);
            return true;
        }
        crossRegionEffect(dest, () -> runInline(mutation));
        return true;
    }

    private void runInline(Runnable mutation) {
        try {
            mutation.run();
        } catch (Throwable t) {
            ProbeRegistry.bump("global.system.scoreboard.mutation-failure");
            ViolationLogger.warn(
                    "global.system.scoreboard",
                    "routed mutation threw: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Convenience wrapper over {@link #tryRoute} for callers that have
     * no synchronous fallback available and would rather drop the
     * mutation (rate-limited warn + probe, CLAUDE.md rule 5) than block
     * or throw. Prefer {@link #tryRoute} when a Vanilla-parity inline
     * fallback exists, as the {@code ServerScoreboard} patches do.
     */
    public void route(Runnable mutation) {
        if (!tryRoute(mutation)) {
            ProbeRegistry.bump("global.system.scoreboard.route-before-tick");
            ViolationLogger.warn(
                    "global.system.scoreboard",
                    "route() called before the global region has ticked once — mutation dropped");
        }
    }

    @Override
    public String name() {
        return "scoreboard";
    }

    @Override
    public void tick(GlobalTickContext ctx) {
        try {
            globalRegionId = ctx.globalRegionId();
        } catch (Throwable t) {
            // Defensive — ctx is non-null by GlobalSystems.tickAll's own
            // contract, but this system must never be the reason a global
            // tick throws (CLAUDE.md rule 4/5).
            ProbeRegistry.bump("global.system.scoreboard.failure");
            ViolationLogger.warn(
                    "global.system.scoreboard",
                    "tick() failed to observe context: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    @Override
    public Set<WorldRef> readSet() {
        return Set.of();
    }

    @Override
    public Set<WorldRef> writeSet() {
        return Set.of();
    }
}
