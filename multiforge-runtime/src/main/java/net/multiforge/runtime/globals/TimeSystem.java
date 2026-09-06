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
 * B2.2 — migrates {@code ServerLevel.tickTime} (per-world game-time
 * advance) and {@code MinecraftServer.synchronizeTime} (the every-20-tick
 * client broadcast, {@code MinecraftServer.tickChildren} calling it once
 * per level when {@code tickCount % 20 == 0}) off the Vanilla-inline
 * per-level tick and onto the synthetic global region's phase-4 tick slot
 * (docs/design/global-region.md §6.3).
 *
 * <p>Like {@link WeatherSystem}, this class holds no Minecraft type —
 * {@link TimeTarget#tickTime()} and {@link TimeTarget#broadcastTime()}
 * are fork-supplied callbacks that invoke the original Vanilla method
 * bodies. What migrates is the call site, not the computation.
 *
 * <p>The every-20-tick broadcast is dispatched through {@link
 * #crossRegionEffect}, targeting the global region's own {@link
 * net.multiforge.runtime.region.RegionId} (from {@link
 * GlobalTickContext#globalRegionId()}) — the global region is always a
 * live, resolvable destination (it never splits/merges,
 * docs/design/global-region.md §1.2), so this is a safe "any region"
 * anchor per the B2.2 task note. The broadcast Runnable lands in the
 * global region's own inbound mailbox and runs on the very next tick's
 * {@code INBOUND_MAILBOX} phase — decoupling "compute this tick" from
 * "deliver next tick" the same way every other cross-region effect does.
 */
public final class TimeSystem extends AbstractGlobalSystem {

    /** Vanilla's own broadcast cadence — {@code MinecraftServer.tickCount % 20 == 0}. */
    private static final long BROADCAST_INTERVAL_TICKS = 20L;

    public interface TimeTarget {
        /** Runs the original {@code ServerLevel.tickTime} body. */
        void tickTime();

        /** Runs the original {@code MinecraftServer.synchronizeTime} body for this world. */
        void broadcastTime();
    }

    private final Map<WorldRef, TimeTarget> targets = new ConcurrentHashMap<>();

    public TimeSystem(CrossRegionEffects effects) {
        super(effects);
    }

    public void registerWorld(WorldRef world, TimeTarget target) {
        targets.put(
                java.util.Objects.requireNonNull(world, "world"), java.util.Objects.requireNonNull(target, "target"));
    }

    public void unregisterWorld(WorldRef world) {
        targets.remove(world);
    }

    /**
     * @return {@code true} if {@code world} currently has a registered
     *         time target — consulted by both the {@code
     *         ServerLevel.tickTime} and {@code
     *         MinecraftServer.synchronizeTime} patches.
     */
    public boolean isHandling(WorldRef world) {
        return targets.containsKey(world);
    }

    @Override
    public String name() {
        return "time";
    }

    @Override
    public void tick(GlobalTickContext ctx) {
        for (Map.Entry<WorldRef, TimeTarget> entry : targets.entrySet()) {
            WorldRef world = entry.getKey();
            TimeTarget target = entry.getValue();
            try {
                target.tickTime();
            } catch (Throwable t) {
                ProbeRegistry.bump("global.system.time.failure." + world.dimensionId());
                ViolationLogger.warn(
                        "global.system.time",
                        "tickTime failed for " + world.dimensionId() + ": "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
                continue;
            }
            if (ctx.globalTick() % BROADCAST_INTERVAL_TICKS == 0L) {
                crossRegionEffect(ctx.globalRegionId(), () -> {
                    try {
                        target.broadcastTime();
                    } catch (Throwable t) {
                        ProbeRegistry.bump("global.system.time.broadcast-failure." + world.dimensionId());
                        ViolationLogger.warn(
                                "global.system.time",
                                "broadcastTime failed for " + world.dimensionId() + ": "
                                        + t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                });
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
