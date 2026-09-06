/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.globals;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;

/**
 * B2.1 — migrates {@code ServerLevel.advanceWeatherCycle} off the
 * Vanilla-inline per-level tick and onto the synthetic global region's
 * phase-4 tick slot (docs/design/global-region.md §6.3).
 *
 * <p>This class is intentionally pure Java: it holds no {@code
 * ServerLevel} reference and computes nothing about weather itself.
 * Vanilla weather advance is still Vanilla code — {@link WeatherTarget}
 * is a fork-supplied callback (bound at {@code LevelEvent.Load} time in
 * {@code net.multiforge.neoforge.globals}) that invokes the original
 * {@code advanceWeatherCycle} body directly. The migration this class
 * performs is <em>where</em> that call happens (once per global tick, on
 * the global region thread) rather than <em>what</em> it computes — see
 * {@code docs/design/global-region.md} §8.2 integration test 5.
 *
 * <p>One instance is registered for the whole server
 * ({@code GlobalSystems.register(new WeatherSystem(effects))}); it
 * multiplexes over every currently-loaded world via {@link
 * #registerWorld}/{@link #unregisterWorld}, mirroring Vanilla's own
 * per-{@code ServerLevel} weather state (one clock per dimension with
 * sky light).
 */
public final class WeatherSystem extends AbstractGlobalSystem {

    /**
     * Fork-supplied callback that runs the original (unmodified)
     * {@code ServerLevel.advanceWeatherCycle} body for one world. Never
     * called concurrently with itself for the same world — {@link #tick}
     * runs single-threaded on the global region.
     */
    @FunctionalInterface
    public interface WeatherTarget {
        void advanceWeatherCycle();
    }

    private final Map<WorldRef, WeatherTarget> targets = new ConcurrentHashMap<>();

    public WeatherSystem(CrossRegionEffects effects) {
        super(effects);
    }

    /**
     * Registers (or replaces) the weather target for {@code world}.
     * Called by fork glue when a {@code ServerLevel} loads. Idempotent —
     * a second call for the same world overwrites the previous target
     * rather than double-registering.
     */
    public void registerWorld(WorldRef world, WeatherTarget target) {
        targets.put(
                java.util.Objects.requireNonNull(world, "world"), java.util.Objects.requireNonNull(target, "target"));
    }

    /** Deregisters {@code world} — called on {@code LevelEvent.Unload}. */
    public void unregisterWorld(WorldRef world) {
        targets.remove(world);
    }

    /**
     * @return {@code true} if {@code world} currently has a registered
     *         weather target — the guard predicate the {@code
     *         ServerLevel.advanceWeatherCycle} patch consults to decide
     *         whether Vanilla's inline call should no-op (MultiForge is
     *         driving) or fall through to Vanilla (not yet registered,
     *         e.g. very first tick of a freshly-loading level).
     */
    public boolean isHandling(WorldRef world) {
        return targets.containsKey(world);
    }

    @Override
    public String name() {
        return "weather";
    }

    @Override
    public void tick(GlobalTickContext ctx) {
        for (Map.Entry<WorldRef, WeatherTarget> entry : targets.entrySet()) {
            WorldRef world = entry.getKey();
            try {
                entry.getValue().advanceWeatherCycle();
            } catch (Throwable t) {
                // Defensive per-target isolation on top of GlobalSystems.tickAll's
                // own per-subsystem catch (docs/design/global-region.md §7) — one
                // world's weather target throwing must not skip every other
                // world's weather advance within the same tick() call.
                ProbeRegistry.bump("global.system.weather.failure." + world.dimensionId());
                ViolationLogger.warn(
                        "global.system.weather",
                        "advanceWeatherCycle failed for " + world.dimensionId() + ": "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    @Override
    public Set<WorldRef> readSet() {
        return Collections.unmodifiableSet(targets.keySet());
    }

    @Override
    public Set<WorldRef> writeSet() {
        return readSet();
    }
}
