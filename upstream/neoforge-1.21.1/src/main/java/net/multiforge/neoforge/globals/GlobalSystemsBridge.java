/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.neoforge.globals;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.WorldBorder;
import net.multiforge.api.world.WorldRef;
import net.multiforge.neoforge.RegionizedTickCoordinator;
import net.multiforge.runtime.globals.BossEventSystem;
import net.multiforge.runtime.globals.RaidsSystem;
import net.multiforge.runtime.globals.ScoreboardSystem;
import net.multiforge.runtime.globals.TimeSystem;
import net.multiforge.runtime.globals.WeatherSystem;
import net.multiforge.runtime.globals.WorldBorderSystem;

/**
 * Fork-local façade the {@code multiforge-patches/08-globals/} Vanilla
 * patches call into — mirrors the {@link RegionizedTickCoordinator} /
 * {@code OwnershipGuard} facade shape (patched vanilla code references a
 * stable fork-local API; the actual runtime behind it is swapped out
 * from behind without editing any patch file).
 *
 * <p>Bound once by {@link MultiForgeGlobalSystemsInit#install} on a
 * fresh runtime install. Before binding (or after a runtime shutdown),
 * every method here degrades to "not handled" / "not routed" so a
 * patched Vanilla call site always has a safe fallback — never a
 * {@code NullPointerException} (CLAUDE.md rule 5).
 */
public final class GlobalSystemsBridge {
    private static volatile WeatherSystem weather;
    private static volatile TimeSystem time;
    private static volatile WorldBorderSystem worldBorder;
    private static volatile ScoreboardSystem scoreboard;
    private static volatile BossEventSystem bossEvents;
    private static volatile RaidsSystem raids;

    /**
     * Identity set of {@link WorldBorder} instances that currently have
     * a registered {@link WorldBorderSystem} target. {@code WorldBorder}
     * does not carry its owning {@link ServerLevel}/{@link WorldRef}
     * back-reference, so — unlike weather/time, which key off {@link
     * WorldRef} directly — the border-handled check needs its own
     * identity-keyed side table. {@code WorldBorder} does not override
     * {@code equals}/{@code hashCode}, so a plain {@link ConcurrentHashMap}-
     * backed set already gives identity semantics.
     */
    private static final Set<WorldBorder> HANDLED_BORDERS = ConcurrentHashMap.newKeySet();

    private GlobalSystemsBridge() {}

    /** Called once by {@link MultiForgeGlobalSystemsInit#install} on a fresh runtime install. */
    static void bind(WeatherSystem w, TimeSystem t, WorldBorderSystem b, ScoreboardSystem s, BossEventSystem be) {
        weather = w;
        time = t;
        worldBorder = b;
        scoreboard = s;
        bossEvents = be;
    }

    /**
     * Called once by {@link MultiForgeGlobalSystemsInit} (B2.6) when the
     * {@link RaidsSystem} is registered on a fresh runtime install.
     * Separate from {@link #bind} (rather than an added parameter on
     * that method) so this addition doesn't collide with other B2.x
     * landings extending the same {@code bind} call independently.
     */
    static void bindRaids(RaidsSystem r) {
        raids = r;
    }

    /** Called by {@link MultiForgeGlobalSystemsInit} when a level's border target registers/deregisters. */
    static void markBorderHandled(WorldBorder border, boolean handled) {
        if (handled) {
            HANDLED_BORDERS.add(border);
        } else {
            HANDLED_BORDERS.remove(border);
        }
    }

    /**
     * Clears every binding and the border-handled side table. Called
     * from {@code ServerLifecycleHooks.handleServerStopped} so a
     * subsequent fresh install (next {@code GameTestServer} instance,
     * same JVM) starts from a known-clean state; also usable directly
     * from tests.
     */
    public static void unbind() {
        weather = null;
        time = null;
        worldBorder = null;
        scoreboard = null;
        bossEvents = null;
        raids = null;
        HANDLED_BORDERS.clear();
    }

    /**
     * @return {@code true} if {@link net.multiforge.runtime.globals.WeatherSystem}
     *         is bound and has a registered target for {@code level}'s
     *         world — the {@code ServerLevel.advanceWeatherCycle} patch's
     *         no-op guard.
     */
    public static boolean weatherHandled(ServerLevel level) {
        WeatherSystem w = weather;
        return w != null && w.isHandling(worldRefOf(level));
    }

    /**
     * @return {@code true} if {@link net.multiforge.runtime.globals.TimeSystem}
     *         is bound and has a registered target for {@code level}'s
     *         world — consulted by both the {@code ServerLevel.tickTime}
     *         and {@code MinecraftServer.synchronizeTime} patches.
     */
    public static boolean timeHandled(ServerLevel level) {
        TimeSystem t = time;
        return t != null && t.isHandling(worldRefOf(level));
    }

    /**
     * @return {@code true} if {@code border} currently has a registered
     *         {@link WorldBorderSystem} target — the {@code
     *         WorldBorder.tick} patch's no-op guard.
     */
    public static boolean worldBorderHandled(WorldBorder border) {
        return worldBorder != null && HANDLED_BORDERS.contains(border);
    }

    /**
     * Attempts to route a {@code ServerScoreboard} mutation-hook body
     * through {@link ScoreboardSystem}.
     *
     * @return {@code true} if the mutation was accepted for routing (the
     *         patched call site must return without running {@code
     *         mutation} itself); {@code false} if not bound/ready yet —
     *         the patched call site must run {@code mutation} inline.
     */
    public static boolean routeScoreboardMutation(Runnable mutation) {
        ScoreboardSystem s = scoreboard;
        return s != null && s.tryRoute(mutation);
    }

    /**
     * Attempts to route a {@code CustomBossEvents} mutation-hook body
     * through {@link BossEventSystem}. Same contract as {@link
     * #routeScoreboardMutation}.
     */
    public static boolean routeBossEventMutation(Runnable mutation) {
        BossEventSystem be = bossEvents;
        return be != null && be.tryRoute(mutation);
    }

    /**
     * @return {@code true} iff MultiForge is installed <em>and</em>
     *         {@link RaidsSystem} has been registered — the {@code
     *         Raids.tick} patch's delegate guard
     *         (docs/design/global-region.md §4.2/§8.2 integration test
     *         6). Deliberately server-wide rather than per-{@code
     *         ServerLevel} (contrast {@link #weatherHandled}/{@link
     *         #timeHandled}) — {@code Raids} has no per-instance
     *         identity side table the way {@code WorldBorder} does, and
     *         a coarser check keeps the patch hunk itself a single
     *         no-arg call, per docs/design/global-region.md's "thin
     *         hunk" guidance for this patch.
     */
    public static boolean raidsReady() {
        return raids != null;
    }

    /** @return the bound {@link RaidsSystem}, or {@code null} if not yet installed. */
    public static RaidsSystem raids() {
        return raids;
    }

    private static WorldRef worldRefOf(ServerLevel level) {
        return RegionizedTickCoordinator.asWorldRef(level);
    }
}
