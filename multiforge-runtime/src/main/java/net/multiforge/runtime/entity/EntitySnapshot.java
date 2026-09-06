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
package net.multiforge.runtime.entity;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;

/**
 * Immutable captured state of an entity during migration.
 *
 * <p>Includes:
 *
 * <ul>
 *   <li>{@code uuid} — stable identifier across region borders.
 *   <li>{@code destWorld} + {@code destPos} — target location the destination region will spawn
 *       at.
 *   <li>{@code payload} — serialized entity data. In the M4 patch this is Vanilla's {@code
 *       CompoundTag} serialized via {@code CompoundTag.write}/NBT I/O to a byte array ({@code
 *       multiforge-runtime} is deliberately Minecraft-free — see {@code docs/design/entity-migration.md}
 *       §6.1 — so the record carries the encoded bytes, not the live {@code CompoundTag}). The
 *       M4 patch's capture/restore call sites bind {@code entity.save(tag)} /
 *       {@code EntityType.loadEntityRecursive(tag, ...)} to this byte array via NBT's standard
 *       stream codec; pure-Java tests use whatever bytes they like as an opaque payload.
 *   <li>{@code passengers} — recursive passenger tree. Preserved as a list of snapshots because
 *       Vanilla's ride relationship is parent → children with ordering that matters (see
 *       {@code docs/design/entity-migration.md} §2.4, §6.2 — each element is captured
 *       independently via {@code entity.save(tag)} without following Vanilla's own nested
 *       passenger NBT, so the coordinator controls re-mount order explicitly instead of trusting
 *       the embedded format).
 * </ul>
 *
 * <p>{@code payload} is defensively copied on construction and on every {@link #payload()} read
 * so no caller can mutate a snapshot's captured bytes after the fact — snapshots are handed
 * across region-worker threads via {@link net.multiforge.runtime.region.RegionizedTaskQueue} and
 * must be safe to read concurrently with whatever the source thread does with its own copy of the
 * array afterward.
 */
public record EntitySnapshot(
        UUID uuid, WorldRef destWorld, BlockPos destPos, byte[] payload, List<EntitySnapshot> passengers) {

    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    public EntitySnapshot {
        if (uuid == null) throw new NullPointerException("uuid");
        if (destWorld == null) throw new NullPointerException("destWorld");
        if (destPos == null) throw new NullPointerException("destPos");
        payload = payload == null || payload.length == 0 ? EMPTY_PAYLOAD : payload.clone();
        passengers = passengers == null ? List.of() : List.copyOf(passengers);
    }

    /** Defensive copy — callers cannot mutate the snapshot's captured bytes through this. */
    @Override
    public byte[] payload() {
        return payload.length == 0 ? EMPTY_PAYLOAD : payload.clone();
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

    /**
     * Record-generated {@code equals}/{@code hashCode} compare array components by reference, not
     * content — overridden here so two independently-captured snapshots with identical bytes
     * compare equal, matching the byte-identical round-trip contract in
     * {@code docs/design/entity-migration.md} §6.4 (test invariant T1/T2).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EntitySnapshot other)) return false;
        return uuid.equals(other.uuid)
                && destWorld.dimensionId().equals(other.destWorld.dimensionId())
                && destPos.equals(other.destPos)
                && Arrays.equals(payload, other.payload)
                && passengers.equals(other.passengers);
    }

    @Override
    public int hashCode() {
        int result = uuid.hashCode();
        result = 31 * result + destWorld.dimensionId().hashCode();
        result = 31 * result + destPos.hashCode();
        result = 31 * result + Arrays.hashCode(payload);
        result = 31 * result + passengers.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "EntitySnapshot[uuid=" + uuid + ", destWorld=" + destWorld.dimensionId() + ", destPos=" + destPos
                + ", payloadBytes=" + payload.length + ", passengers=" + passengers.size() + "]";
    }
}
