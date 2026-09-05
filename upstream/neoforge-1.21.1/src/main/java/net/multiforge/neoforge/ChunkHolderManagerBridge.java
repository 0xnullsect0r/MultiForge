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

/**
 * <b>Deprecated observation façade (Phase 5.7).</b> Formerly the M9
 * sub-step 1 read-only shadow bridge from Vanilla {@code ChunkMap} into
 * MultiForge's pre-built {@link ChunkHolderManager}. Phase 5.6
 * ({@link RegionizedChunkLifecycle}) and Phase 4.2b
 * ({@link net.multiforge.neoforge.chunk.MultiForgeDistanceManager}) now
 * write MultiForge tickets directly, so the shadow observation is
 * <em>redundant</em> — nothing downstream depends on it any more.
 *
 * <p>The static entry points {@link #onTicketLevelUpdated},
 * {@link #onChunkLoaded}, {@link #onChunkUnloaded} are preserved as
 * no-op {@code bridge.deprecated.*} probes so unrelated compile-time
 * callers (older patches, staged fork glue) keep linking without
 * silently regressing Vanilla ticket behaviour. The read-only
 * {@link #currentLevel(ServerLevel, int, int)} accessor is kept intact
 * — {@code /multiforge chunks} and other diagnostics still call it to
 * report the MultiForge-side load level for a chunk.
 *
 * <p>The bridge is scheduled for full removal once every out-of-tree
 * caller has migrated; the no-op layer is the transitional shim.
 */
public final class ChunkHolderManagerBridge {
    private ChunkHolderManagerBridge() {}

    /**
     * <b>Deprecated (Phase 5.7): no-op.</b> Was the M9 sub-step 1
     * shadow-observation hook fired from
     * {@code ChunkMap.updateChunkScheduling}. Real ticket writes now
     * flow through
     * {@link net.multiforge.neoforge.chunk.MultiForgeDistanceManager}
     * and {@link net.multiforge.neoforge.chunk.MultiForgeChunkMap} —
     * this method no longer touches the holder manager, and the
     * corresponding patch hunk in
     * {@code multiforge-patches/04-chunk-system/.../ChunkMap.java.patch}
     * has been removed. Kept as a no-op-with-probe so a stray caller
     * (older patches, staged glue) still links; the probe bump surfaces
     * the residual usage in {@code /multiforge probes}.
     *
     * @param level    unused
     * @param chunkKey unused
     * @param oldLevel unused
     * @param newLevel unused
     * @param holder   unused
     */
    public static void onTicketLevelUpdated(
            ServerLevel level, long chunkKey, int oldLevel, int newLevel, ChunkHolder holder) {
        ProbeRegistry.bump("bridge.deprecated.onTicketLevelUpdated");
    }

    /**
     * <b>Deprecated (Phase 5.7): no-op.</b> Was the {@code
     * ChunkEvent.Load}-fired shadow seed that populated a holder at
     * BORDER. {@link RegionizedChunkLifecycle#installOnEventBus()} now
     * writes a real {@link net.multiforge.runtime.chunk.TicketType#START}
     * ticket instead — the holder is created and promoted through the
     * per-region ticket map by the ticket write, not by an out-of-band
     * shadow.
     */
    public static void onChunkLoaded(ServerLevel level, int chunkX, int chunkZ, int currentVanillaLevel) {
        ProbeRegistry.bump("bridge.deprecated.onChunkLoaded");
    }

    /**
     * <b>Deprecated (Phase 5.7): no-op.</b> Was the {@code
     * ChunkEvent.Unload}-fired shadow drop; the symmetric ticket
     * release now happens inline in
     * {@link RegionizedChunkLifecycle}. Holders are torn down by the
     * per-region ticket map hitting effective distance
     * {@link ChunkLoadLevel#INACCESSIBLE}.
     */
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
