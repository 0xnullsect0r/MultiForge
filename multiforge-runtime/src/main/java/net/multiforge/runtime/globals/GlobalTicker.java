/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.globals;

/**
 * Something that ticks once per global tick on the dedicated global
 * region. Vanilla-side bindings register weather, time-of-day, world
 * border, gamerules, ender-dragon fight state, wither, raid managers,
 * scoreboards, and command dispatch as GlobalTicker instances.
 *
 * <p>Callbacks run on the global region thread; ownership assertions
 * see {@code OwnerToken.GLOBAL} inside {@link #tick(long)}.
 */
@FunctionalInterface
public interface GlobalTicker {
    /**
     * Called once per global tick.
     *
     * @param globalTick monotonically-increasing tick counter shared
     *                   across all workers; useful for periodic work.
     */
    void tick(long globalTick);
}
