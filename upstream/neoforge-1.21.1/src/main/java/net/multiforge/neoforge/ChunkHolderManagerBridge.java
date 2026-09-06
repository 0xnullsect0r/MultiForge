/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.neoforge;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.ChunkLoadLevel;
import net.multiforge.runtime.chunk.NewChunkHolder;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;

// M9-deprecated: shadow observations are no-ops; ticket writes flow through MultiForgeDistanceManager. Kept for observability accessors used by /multiforge diagnostics.
public final class ChunkHolderManagerBridge {
    private ChunkHolderManagerBridge() {}

    /** M9-deprecated no-op; ticket writes flow through {@code MultiForgeDistanceManager}. */
    public static void onTicketLevelUpdated(
            ServerLevel level, long chunkKey, int oldLevel, int newLevel, ChunkHolder holder) {
        ProbeRegistry.bump("bridge.deprecated.onTicketLevelUpdated");
    }

    /** M9-deprecated no-op; {@link RegionizedChunkLifecycle} writes real START tickets. */
    public static void onChunkLoaded(ServerLevel level, int chunkX, int chunkZ, int currentVanillaLevel) {
        ProbeRegistry.bump("bridge.deprecated.onChunkLoaded");
    }

    /** M9-deprecated no-op; {@link RegionizedChunkLifecycle} releases the ticket inline. */
    public static void onChunkUnloaded(ServerLevel level, int chunkX, int chunkZ) {
        ProbeRegistry.bump("bridge.deprecated.onChunkUnloaded");
    }

    /**
     * Best-effort ChunkLoadLevel snapshot for a specific chunk in a
     * specific world; used by observability commands like
     * {@code /multiforge chunks}. Returns null if the runtime isn't
     * installed, the world has no manager yet, or the chunk isn't
     * tracked. Uses the non-creating {@code chunkManagerForOrNull} so a
     * typoed world lookup doesn't permanently allocate an empty
     * manager. Retained through Phase 5.7 because operator diagnostics
     * still read the MultiForge-side effective level via this accessor.
     */
    public static ChunkLoadLevel currentLevel(ServerLevel level, int chunkX, int chunkZ) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return null;
        WorldRef world = RegionizedTickCoordinator.asWorldRef(level);
        ChunkHolderManager manager = host.chunkManagerForOrNull(world);
        if (manager == null) return null;
        NewChunkHolder h = manager.holderAt(new net.multiforge.api.world.ChunkPos(chunkX, chunkZ));
        return h == null ? null : h.level();
    }
}
