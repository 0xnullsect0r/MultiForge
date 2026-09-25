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

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Keeps the regionizer and each world's {@link ChunkHolderManager} in step
 * with Vanilla's loaded chunks: when a chunk loads ({@link ChunkEvent.Load})
 * its section joins a region (created or merged as needed) and the chunk is
 * recorded as owned by that region; when it unloads it leaves both. Vanilla
 * itself decides what loads and when.
 *
 * <p>Registration is idempotent per JVM: a GameTest server reuses one JVM
 * across server instances.
 */
public final class RegionizedChunkLifecycle {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private RegionizedChunkLifecycle() {}

    public static void installOnEventBus() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        NeoForge.EVENT_BUS.addListener(RegionizedChunkLifecycle::onChunkLoaded);
        NeoForge.EVENT_BUS.addListener(RegionizedChunkLifecycle::onChunkUnloaded);
    }

    private static void onChunkLoaded(final ChunkEvent.Load event) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return; // mode = off
        WorldRef world = worldRefFor(event.getLevel());
        if (world == null) return;
        ChunkPos pos = event.getChunk().getPos();
        Region region = host.registerChunk(world, pos.x, pos.z);
        host.chunkManagerFor(world).createHolder(new net.multiforge.api.world.ChunkPos(pos.x, pos.z), region.id());
        // Work queued for this chunk before it had an owner goes to its region now.
        host.taskQueue().rerouteAtChunk(world, pos.x, pos.z);
    }

    private static void onChunkUnloaded(final ChunkEvent.Unload event) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return;
        WorldRef world = worldRefFor(event.getLevel());
        if (world == null) return;
        ChunkPos pos = event.getChunk().getPos();
        host.unregisterChunk(world, pos.x, pos.z);
        ChunkHolderManager manager = host.chunkManagerForOrNull(world);
        if (manager != null) manager.dropHolder(new net.multiforge.api.world.ChunkPos(pos.x, pos.z));
    }

    private static WorldRef worldRefFor(LevelAccessor level) {
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            return RegionizedTickCoordinator.asWorldRef(serverLevel);
        }
        return null; // client levels are never regionized
    }

    static void resetForTesting() {
        INSTALLED.set(false);
    }
}
