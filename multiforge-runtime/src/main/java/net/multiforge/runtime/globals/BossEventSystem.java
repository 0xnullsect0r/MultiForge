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
 * B2.5 — {@code CustomBossEvents} (server-wide named bossbars, e.g. the
 * wither's) has no dedicated Vanilla {@code tick()} either; its mutation
 * surface is {@code create}/{@code remove}, callable from whichever
 * region the triggering entity (a wither spawning, a bossbar command
 * running) happens to live on. Same shape as {@link ScoreboardSystem} —
 * a mutation-hook bridge, not a periodic ticker. See that class's
 * javadoc for the rationale; this class duplicates the pattern rather
 * than sharing a base so each subsystem keeps an independent probe/log
 * namespace (docs/design/global-region.md §7.3).
 */
public final class BossEventSystem extends AbstractGlobalSystem {

    private volatile RegionId globalRegionId;

    public BossEventSystem(CrossRegionEffects effects) {
        super(effects);
    }

    /**
     * @return {@code true} once this system has a live global {@link
     *         RegionId} cached from a prior {@link #tick} call. See
     *         {@link ScoreboardSystem#isReady()} for the full rationale.
     */
    public boolean isReady() {
        return globalRegionId != null;
    }

    /**
     * Routes {@code mutation} (the original {@code CustomBossEvents}
     * {@code create}/{@code remove} body) onto the global region's
     * mailbox.
     *
     * @return {@code true} if accepted for routing, {@code false} if not
     *         ready yet (see {@link #isReady()}) — the caller must run
     *         {@code mutation} itself in that case.
     */
    public boolean tryRoute(Runnable mutation) {
        java.util.Objects.requireNonNull(mutation, "mutation");
        RegionId dest = globalRegionId;
        if (dest == null) {
            return false;
        }
        crossRegionEffect(dest, () -> {
            try {
                mutation.run();
            } catch (Throwable t) {
                ProbeRegistry.bump("global.system.boss_events.mutation-failure");
                ViolationLogger.warn(
                        "global.system.boss_events",
                        "routed mutation threw: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
        return true;
    }

    /**
     * Convenience wrapper over {@link #tryRoute} for callers with no
     * synchronous fallback — drops (rate-limited warn + probe) rather
     * than blocking or throwing. Prefer {@link #tryRoute} when a
     * Vanilla-parity inline fallback exists, as the {@code
     * CustomBossEvents} patches do.
     */
    public void route(Runnable mutation) {
        if (!tryRoute(mutation)) {
            ProbeRegistry.bump("global.system.boss_events.route-before-tick");
            ViolationLogger.warn(
                    "global.system.boss_events",
                    "route() called before the global region has ticked once — mutation dropped");
        }
    }

    @Override
    public String name() {
        return "boss_events";
    }

    @Override
    public void tick(GlobalTickContext ctx) {
        try {
            globalRegionId = ctx.globalRegionId();
        } catch (Throwable t) {
            ProbeRegistry.bump("global.system.boss_events.failure");
            ViolationLogger.warn(
                    "global.system.boss_events",
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
