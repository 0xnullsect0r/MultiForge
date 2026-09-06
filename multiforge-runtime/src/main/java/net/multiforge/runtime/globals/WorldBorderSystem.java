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
 * B2.3 — migrates {@code WorldBorder.tick} (border-size lerp
 * interpolation advance, {@code this.extent = this.extent.update();})
 * off the Vanilla-inline per-level tick and onto the synthetic global
 * region's phase-4 tick slot (docs/design/global-region.md §6.3).
 *
 * <p>{@code WorldBorder} state (extent, damage settings, warning
 * distance) is per-{@code ServerLevel} and self-contained — it neither
 * reads nor writes chunk/entity data directly, so no {@link
 * #crossRegionEffect} use is needed here; {@link BorderTarget#advance()}
 * runs the original {@code WorldBorder.tick} body directly on the global
 * region thread.
 */
public final class WorldBorderSystem extends AbstractGlobalSystem {

    @FunctionalInterface
    public interface BorderTarget {
        /** Runs the original {@code WorldBorder.tick} body. */
        void advance();
    }

    private final Map<WorldRef, BorderTarget> targets = new ConcurrentHashMap<>();

    public WorldBorderSystem(CrossRegionEffects effects) {
        super(effects);
    }

    public void registerWorld(WorldRef world, BorderTarget target) {
        targets.put(
                java.util.Objects.requireNonNull(world, "world"), java.util.Objects.requireNonNull(target, "target"));
    }

    public void unregisterWorld(WorldRef world) {
        targets.remove(world);
    }

    /** Consulted by the {@code WorldBorder.tick} patch's delegate check. */
    public boolean isHandling(WorldRef world) {
        return targets.containsKey(world);
    }

    @Override
    public String name() {
        return "world_border";
    }

    @Override
    public void tick(GlobalTickContext ctx) {
        for (Map.Entry<WorldRef, BorderTarget> entry : targets.entrySet()) {
            WorldRef world = entry.getKey();
            try {
                entry.getValue().advance();
            } catch (Throwable t) {
                ProbeRegistry.bump("global.system.world_border.failure." + world.dimensionId());
                ViolationLogger.warn(
                        "global.system.world_border",
                        "WorldBorder.tick failed for " + world.dimensionId() + ": "
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
