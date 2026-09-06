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
