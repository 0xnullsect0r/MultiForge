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
package net.multiforge.neoforge;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;

/**
 * On {@code ServerAboutToStartEvent}, iterate every already-loaded
 * {@link ServerLevel} and call {@link MultiThreadedSchedulerHost#regionizerFor}
 * to materialise its {@code ThreadedRegionizer}. Without this,
 * dimensions that never see a {@code ChunkEvent.Load} at boot
 * (typically {@code the_end} and {@code the_nether} on a fresh server)
 * never get a regionizer, and every subsequent {@code dispatchLevelTick}
 * call for them falls through to the {@code region-tick.no-regionizer-skip}
 * fallback — visibly warning per-tick.
 *
 * <p>{@link MultiThreadedSchedulerHost#regionizerFor} is idempotent
 * ({@code computeIfAbsent} on the regionizers map), so re-materialising
 * an already-loaded world is a no-op.
 */
public final class RegionizerEagerInit {
    private RegionizerEagerInit() {}

    /**
     * Materialises a regionizer for every {@link ServerLevel} already
     * loaded on {@code server}. Called from {@code
     * MultiForgeGlobalSystemsInit.install} on {@code
     * ServerAboutToStartEvent}, before the B2 subsystem bindings so
     * those bindings query already-materialised regionizers.
     */
    public static void materialiseAll(MinecraftServer server, MultiThreadedSchedulerHost host) {
        for (ServerLevel level : server.getAllLevels()) {
            WorldRef world = RegionizedTickCoordinator.asWorldRef(level);
            host.regionizerFor(world);
        }
    }
}
