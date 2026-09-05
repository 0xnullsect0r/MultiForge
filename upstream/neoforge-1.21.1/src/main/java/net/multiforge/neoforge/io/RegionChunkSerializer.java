/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.neoforge.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

/**
 * M9 Phase 3 task 3.5: fork-glue facade around Vanilla
 * {@link ChunkSerializer} that turns a {@link LevelChunk} into a
 * compressed-NBT {@code byte[]} (and back) so a region worker thread can
 * hand the bytes to
 * {@link net.multiforge.neoforge.io.RegionFileWriter} without touching
 * any other region's chunk state.
 *
 * <p>Both {@link #serialize} and {@link #deserialize} must run on the
 * owning region's worker thread: {@link ChunkSerializer#write} walks the
 * chunk's block/biome containers and block-entity nbt, and
 * {@link ChunkSerializer#read} rebuilds section palettes and queues
 * light data — neither is safe to invoke while another region owns the
 * chunk.
 *
 * <p>Signatures diverge from the initial task sketch because the real
 * Vanilla API is:
 *
 * <ul>
 *   <li>{@code ChunkSerializer.write(ServerLevel, ChunkAccess)
 *       -> CompoundTag} — {@link LevelChunk} implements {@code ChunkAccess}
 *       so it slots in directly.</li>
 *   <li>{@code ChunkSerializer.read(ServerLevel, PoiManager,
 *       RegionStorageInfo, ChunkPos, CompoundTag) -> ProtoChunk} —
 *       returns a {@code ProtoChunk} (or {@code ImposterProtoChunk})
 *       directly, no wrapper struct; the caller must forward the
 *       target level's {@link PoiManager} and its chunk-source's
 *       {@link RegionStorageInfo} so relocated-chunk warnings and POI
 *       consistency checks happen against the right storage.</li>
 * </ul>
 *
 * <p>Covered by Phase 5 integration + gameTestServer regression;
 * no direct unit tests because a fake {@link ServerLevel} is not
 * tractable.
 */
public final class RegionChunkSerializer {
    private RegionChunkSerializer() {}

    /**
     * Serialize a {@link LevelChunk} to compressed NBT bytes suitable
     * for writing via {@code RegionFileWriter}. Must run on the owning
     * region's worker thread.
     */
    public static byte[] serialize(ServerLevel level, LevelChunk chunk) throws IOException {
        CompoundTag tag = ChunkSerializer.write(level, chunk);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, bytes);
        return bytes.toByteArray();
    }

    /**
     * Deserialize compressed NBT bytes into a {@link ProtoChunk} which
     * can be promoted to a {@link LevelChunk} by {@code ChunkStorage}.
     * Must run on the owning region's worker thread.
     *
     * <p>{@code poiManager} and {@code storageInfo} are the ones that
     * belong to the target level's chunk-source; passing another
     * level's would corrupt POI state on load.
     */
    public static ProtoChunk deserialize(
            ServerLevel level, PoiManager poiManager, RegionStorageInfo storageInfo, ChunkPos pos, byte[] bytes)
            throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        CompoundTag tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        return ChunkSerializer.read(level, poiManager, storageInfo, pos, tag);
    }

    /**
     * Cheap probe: does the {@code byte[]} parse as valid compressed
     * NBT? Only checks framing/inflation — a {@code true} return does
     * not guarantee the payload is a well-formed chunk.
     */
    public static boolean isValid(byte[] bytes) {
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(bytes);
            NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
