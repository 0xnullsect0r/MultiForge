/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.entity;

import java.util.List;
import java.util.UUID;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;

/**
 * Immutable captured state of an entity during migration.
 *
 * <p>Includes:
 * <ul>
 *   <li>{@code uuid} — stable identifier across region borders.</li>
 *   <li>{@code destWorld} + {@code destPos} — target location the
 *       destination region will spawn at.</li>
 *   <li>{@code payload} — opaque per-entity data (the M4 patch binds
 *       this to Vanilla's {@code CompoundTag}; pure-Java uses a String
 *       for tests).</li>
 *   <li>{@code passengers} — recursive passenger tree. Preserved as a
 *       list of snapshots because Vanilla's ride relationship is
 *       parent → children with ordering that matters.</li>
 * </ul>
 */
public record EntitySnapshot(
        UUID uuid, WorldRef destWorld, BlockPos destPos, String payload, List<EntitySnapshot> passengers) {

    public EntitySnapshot {
        if (uuid == null) throw new NullPointerException("uuid");
        if (destWorld == null) throw new NullPointerException("destWorld");
        if (destPos == null) throw new NullPointerException("destPos");
        if (payload == null) payload = "";
        passengers = passengers == null ? List.of() : List.copyOf(passengers);
    }

    public ChunkPos destChunk() {
        return destPos.toChunkPos();
    }

    public boolean hasPassengers() {
        return !passengers.isEmpty();
    }

    /** Recursive count including this entity. */
    public int totalEntities() {
        int n = 1;
        for (EntitySnapshot p : passengers) n += p.totalEntities();
        return n;
    }
}
