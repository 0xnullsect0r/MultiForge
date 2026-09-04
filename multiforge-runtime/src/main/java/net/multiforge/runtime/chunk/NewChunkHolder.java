/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.chunk;

import java.util.concurrent.atomic.AtomicReference;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.RegionId;

/**
 * MultiForge's per-chunk holder — the moral equivalent of Vanilla's
 * {@code ChunkHolder} plus Moonrise's {@code NewChunkHolder}. Owns:
 *
 * <ul>
 *   <li>the chunk's world + position,</li>
 *   <li>the region currently responsible for ticking it,</li>
 *   <li>the effective load {@link ChunkLoadLevel},</li>
 *   <li>dirty and pending-full-load flags read by the autosave queue
 *       and the promotion pipeline in {@link ChunkHolderManager}.</li>
 * </ul>
 *
 * <p>The M3 patch binds one holder per {@code (level, chunkPos)} and
 * replaces Vanilla's holder in {@code ServerChunkCache}.
 */
public final class NewChunkHolder {

    private final WorldRef world;
    private final ChunkPos position;
    private final AtomicReference<RegionId> owningRegion = new AtomicReference<>();
    private volatile ChunkLoadLevel level = ChunkLoadLevel.INACCESSIBLE;
    private volatile boolean dirty;
    private volatile boolean pendingFullLoadUpdate;

    public NewChunkHolder(WorldRef world, ChunkPos position) {
        this.world = world;
        this.position = position;
    }

    public WorldRef world() {
        return world;
    }

    public ChunkPos position() {
        return position;
    }

    public RegionId owningRegion() {
        return owningRegion.get();
    }

    /**
     * Publish a new owner. Returns {@code true} iff the owner actually
     * changed — callers use this to decide whether to enqueue a
     * migration hook.
     */
    public boolean setOwningRegion(RegionId id) {
        RegionId prev = owningRegion.getAndSet(id);
        return prev == null || !prev.equals(id);
    }

    public ChunkLoadLevel level() {
        return level;
    }

    public void setLevel(ChunkLoadLevel level) {
        this.level = level;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void clearDirty() {
        this.dirty = false;
    }

    public boolean pendingFullLoadUpdate() {
        return pendingFullLoadUpdate;
    }

    public void setPendingFullLoadUpdate(boolean value) {
        this.pendingFullLoadUpdate = value;
    }

    @Override
    public String toString() {
        return "NewChunkHolder[" + world.dimensionId() + " " + position + " level=" + level + " owner="
                + owningRegion.get() + "]";
    }
}
