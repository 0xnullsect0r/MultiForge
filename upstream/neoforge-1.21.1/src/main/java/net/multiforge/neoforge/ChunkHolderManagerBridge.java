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
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;

/**
 * M9 sub-step 1 — read-only shadow bridge from Vanilla {@code ChunkMap}
 * into MultiForge's pre-built {@link ChunkHolderManager}. Called from
 * the single line patched into {@link net.minecraft.server.level.ChunkMap#updateChunkScheduling},
 * right after NeoForge's own {@code fireChunkTicketLevelUpdated} event
 * hook.
 *
 * <p>This is deliberately non-invasive: the bridge only OBSERVES —
 * it mirrors Vanilla's ticket-level state into MultiForge's holder
 * manager so operators can query chunk state via {@code /multiforge
 * chunks}, and so future sub-steps can drive per-region chunk work
 * without also having to plumb the observation pipeline. Zero behavior
 * change to Vanilla ticket-level transitions.
 *
 * <p><b>Scope note (session-limited M9):</b> a real M9 chunk system
 * port needs to REPLACE (not just shadow) Vanilla's ChunkMap /
 * DistanceManager / ServerChunkCache with the per-region model
 * described in {@code docs/chunks.md}. That's realistically months
 * of work (~30K lines in Folia's Moonrise). This bridge is the
 * foundational first slice: it establishes the coordinate translation
 * (Vanilla {@code long} chunk key → MultiForge {@code ChunkPos}) and
 * level mapping (Vanilla {@code ChunkLevel} → MultiForge
 * {@link ChunkLoadLevel}), and gives future work a coherent
 * observable state to drive from.
 */
public final class ChunkHolderManagerBridge {
    private ChunkHolderManagerBridge() {}

    /**
     * Called from {@code ChunkMap.updateChunkScheduling} on every
     * ticket-level transition. Mirrors the transition into
     * {@link ChunkHolderManager}. No-ops if the runtime isn't
     * installed yet or if the chunk's owning region hasn't
     * materialized ({@link RegionizedChunkLifecycle} creates the
     * region on {@code ChunkEvent.Load}).
     *
     * @param level    the Vanilla ServerLevel the chunk belongs to
     * @param chunkKey Vanilla chunk long key (packed x/z)
     * @param oldLevel prior Vanilla ticket level; unused here (kept
     *                 in the signature to match {@code EventHooks
     *                 .fireChunkTicketLevelUpdated}'s shape)
     * @param newLevel the new Vanilla ticket level (33 = FULL,
     *                 32 = BLOCK_TICKING, 31 = ENTITY_TICKING, etc.)
     * @param holder   the Vanilla ChunkHolder, possibly null when the
     *                 chunk is being scheduled for drop
     */
    public static void onTicketLevelUpdated(
            ServerLevel level, long chunkKey, int oldLevel, int newLevel, ChunkHolder holder) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return; // runtime not installed yet (bootstrap ordering)

        WorldRef world = RegionizedTickCoordinator.asWorldRef(level);
        ThreadedRegionizer regionizer = host.regionizerForOrNull(world);
        if (regionizer == null) return; // no world regionizer yet (fresh boot)

        // Decode Vanilla's packed long chunk key into (x, z).
        int chunkX = (int) chunkKey;
        int chunkZ = (int) (chunkKey >> 32);

        Region region = regionizer.regionAtChunk(chunkX, chunkZ);
        if (region == null) return; // no region yet — ChunkEvent.Load hasn't fired

        ChunkHolderManager manager = host.chunkManagerFor(world);
        net.multiforge.api.world.ChunkPos mfPos = new net.multiforge.api.world.ChunkPos(chunkX, chunkZ);
        NewChunkHolder mfHolder = manager.holderAt(mfPos);
        if (mfHolder == null) {
            mfHolder = manager.createHolder(mfPos, region.id());
        }
        // Map Vanilla ticket level (0..33+) to MultiForge ChunkLoadLevel.
        // Vanilla uses inverted numbers where lower = more loaded:
        // 31 = ENTITY_TICKING, 32 = BLOCK_TICKING, 33 = FULL/BORDER, 34+ = INACCESSIBLE.
        // MultiForge's ChunkLoadLevel.forDistance uses the same convention.
        ChunkLoadLevel newMfLevel = ChunkLoadLevel.forDistance(newLevel);
        mfHolder.setLevel(newMfLevel);
    }

    /**
     * Best-effort ChunkLoadLevel snapshot for a specific chunk in a
     * specific world; used by observability commands like
     * {@code /multiforge chunks}. Returns null if the runtime isn't
     * installed or the chunk isn't tracked yet.
     */
    public static ChunkLoadLevel currentLevel(ServerLevel level, int chunkX, int chunkZ) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return null;
        WorldRef world = RegionizedTickCoordinator.asWorldRef(level);
        ChunkHolderManager manager = host.chunkManagerFor(world);
        NewChunkHolder h = manager.holderAt(new net.multiforge.api.world.ChunkPos(chunkX, chunkZ));
        return h == null ? null : h.level();
    }

    /**
     * Test-only alias for the observability path that doesn't require
     * a real ServerLevel — takes a WorldRef directly and skips the
     * lookup that {@link #onTicketLevelUpdated} does.
     */
    static void shadowChunkForTesting(WorldRef world, int chunkX, int chunkZ, int vanillaLevel) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return;
        ThreadedRegionizer regionizer = host.regionizerForOrNull(world);
        if (regionizer == null) return;
        Region region = regionizer.regionAtChunk(chunkX, chunkZ);
        if (region == null) return;
        ChunkHolderManager manager = host.chunkManagerFor(world);
        net.multiforge.api.world.ChunkPos mfPos = new net.multiforge.api.world.ChunkPos(chunkX, chunkZ);
        NewChunkHolder mfHolder = manager.holderAt(mfPos);
        if (mfHolder == null) {
            mfHolder = manager.createHolder(mfPos, region.id());
        }
        mfHolder.setLevel(ChunkLoadLevel.forDistance(vanillaLevel));
    }
}
