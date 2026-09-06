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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.multiforge.runtime.chunk.NewChunkHolder;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.region.Region;

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
 * <li>{@code ChunkSerializer.write(ServerLevel, ChunkAccess)
 *       -> CompoundTag} — {@link LevelChunk} implements {@code ChunkAccess}
 * so it slots in directly.</li>
 * <li>{@code ChunkSerializer.read(ServerLevel, PoiManager,
 *       RegionStorageInfo, ChunkPos, CompoundTag) -> ProtoChunk} —
 * returns a {@code ProtoChunk} (or {@code ImposterProtoChunk})
 * directly, no wrapper struct; the caller must forward the
 * target level's {@link PoiManager} and its chunk-source's
 * {@link RegionStorageInfo} so relocated-chunk warnings and POI
 * consistency checks happen against the right storage.</li>
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
     * M9 Phase 5 wave B: the production entry point registered via
     * {@link net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost#setChunkSerializer}
     * from the fork's {@code ServerLifecycleHooks} boot glue. Bridges
     * the MC-free {@code AutoSaveRunner} (which only knows about
     * {@link Region} and {@link NewChunkHolder}) to the MC-dependent
     * {@link #serialize(ServerLevel, LevelChunk)} above.
     *
     * <p>Runs on the owning region's worker thread inside the
     * FLUSH_OUTBOUND phase (CLAUDE.md rule 4: no blocking, no I/O,
     * no future.get() — {@link NbtIo#writeCompressed} is pure
     * in-memory compression). Never throws: a missing/wrong-typed
     * chunk or a {@link ChunkSerializer#write} failure both degrade to
     * {@code byte[0]} (matching the pre-wiring stub's behaviour for
     * that one chunk) with a rate-limited warn + probe bump, rather
     * than propagating into the tick pipeline (CLAUDE.md rule 5).
     *
     * @param region the region the chunk's holder belongs to; used
     *               only for diagnostics (the region id in a warn message).
     * @param holder the chunk's shadow holder. {@link
     *               NewChunkHolder#getCurrentChunk()} is {@code Object}-typed
     *               because this runtime module cannot import {@code
     *     net.minecraft.*} — this method does the cast back to
     *               {@link LevelChunk}.
     * @return compressed NBT bytes, or {@code byte[0]} if the chunk
     *         isn't loaded (null / not yet a {@link LevelChunk}), isn't
     *         attached to a {@link ServerLevel}, or serialization failed.
     */
    public static byte[] serializeForJournal(Region region, NewChunkHolder holder) {
        if (holder == null) return new byte[0];
        Object current = holder.getCurrentChunk();
        if (!(current instanceof LevelChunk chunk)) {
            // Not an error: BORDER-level and not-yet-promoted holders
            // legitimately have no LevelChunk yet. Matches the old
            // stub's behaviour for these chunks exactly.
            return new byte[0];
        }
        try {
            Level level = chunk.getLevel();
            if (!(level instanceof ServerLevel serverLevel)) {
                // Should not happen on a dedicated server, but a null/
                // client-side Level is not this method's problem to
                // throw over — degrade gracefully per CLAUDE.md rule 5.
                ProbeRegistry.bump("autosave.serializer.non-server-level");
                return new byte[0];
            }
            return serialize(serverLevel, chunk);
        } catch (Exception e) {
            // Broad catch is deliberate: ChunkSerializer.write can throw
            // both checked IOException (via NbtIo) and unchecked NBT/
            // block-entity encoding errors, and a serializer bug must
            // never escape into the FLUSH_OUTBOUND phase on a region
            // worker thread (CLAUDE.md rules 4 + 5).
            ProbeRegistry.bump("autosave.serializer.failure");
            ViolationLogger.warn(
                    "RegionChunkSerializer.serializeForJournal",
                    "failed to serialize chunk for region " + region.id() + ": "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
            return new byte[0];
        }
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
