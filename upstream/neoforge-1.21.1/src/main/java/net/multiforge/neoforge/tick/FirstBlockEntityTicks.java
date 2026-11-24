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
package net.multiforge.neoforge.tick;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jetbrains.annotations.ApiStatus;

/**
 * Modded block entities' first ticks, moved off the region workers. Mods do one-time setup
 * there, often in a process-wide registry (Mekanism joins its cable network on the
 * first tick). Regions loading cables at the same time would corrupt that registry,
 * so a first tick reached on a worker waits here and runs on the server thread once
 * the level's regions finish, in the same game tick. Vanilla block entities stay in
 * their region's order. Levels tick one at a time, so one queue serves them all.
 */
@ApiStatus.Internal
public final class FirstBlockEntityTicks {
    private static final Queue<VanillaTickingBlockEntityAdapter> QUEUED = new ConcurrentLinkedQueue<>();

    private FirstBlockEntityTicks() {}

    static void defer(VanillaTickingBlockEntityAdapter ticker) {
        QUEUED.add(ticker);
    }

    /**
     * Server thread, after the level's regions finished. A throw propagates to the
     * level tick's crash handling, as it would from Vanilla's inline ticker loop.
     */
    public static void runQueued() {
        VanillaTickingBlockEntityAdapter ticker;
        while ((ticker = QUEUED.poll()) != null) {
            ticker.runFirstTick();
        }
    }

    /** Drops anything left by a server that stopped mid-tick. */
    static void clear() {
        QUEUED.clear();
    }
}
