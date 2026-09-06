/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.multiforge.bench.determinism;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Acceptance suite for {@link WorldDiff.DiffMode#SEMANTIC} — the M8
 * sub-step 8b hashing mode. Companion to {@link WorldDiffTest} which
 * pins the byte-identical baseline; this file exercises the paths that
 * only semantic mode covers.
 *
 * <p>Fixtures build chunk NBT in memory using the package-private
 * {@link WorldDiff.NbtCompound} / {@link WorldDiff.NbtList} model, then
 * wrap the deflate-compressed payload in a minimal MCA file so both
 * hash modes see the same on-disk layout. This avoids any dependency
 * on Vanilla NBT and matches the design doc's fail-closed contract:
 * a semantic-mode hash is computed from a canonical NBT re-serialise,
 * not from the compressed bytes.
 */
class WorldDiffSemanticTest {

    // -----------------------------------------------------------------
    // 1. Byte-identical mode still matches when the bytes really match.
    // -----------------------------------------------------------------
    @Test
    void byte_identical_mode_matches_when_bytes_match(@TempDir Path tmp) throws IOException {
        Path a = tmp.resolve("a.mca");
        Path b = tmp.resolve("b.mca");
        byte[] mca = mcaWithChunk(entitiesChunk(entity(1, 2, 3, 4), entity(5, 6, 7, 8)));
        Files.write(a, mca);
        Files.write(b, mca);

        assertThat(WorldDiff.canonicalMcaHash(a, WorldDiff.DiffMode.BYTE_IDENTICAL))
                .isEqualTo(WorldDiff.canonicalMcaHash(b, WorldDiff.DiffMode.BYTE_IDENTICAL));
    }

    // -----------------------------------------------------------------
    // 2. Reordering entities changes the compressed payload → byte
    //    mode MUST diverge. Guards against the semantic mode being
    //    conflated with byte mode by a bug.
    // -----------------------------------------------------------------
    @Test
    void byte_identical_mode_diverges_on_reordered_entities(@TempDir Path tmp) throws IOException {
        Path a = tmp.resolve("a.mca");
        Path b = tmp.resolve("b.mca");
        Files.write(a, mcaWithChunk(entitiesChunk(entity(1, 2, 3, 4), entity(5, 6, 7, 8))));
        Files.write(b, mcaWithChunk(entitiesChunk(entity(5, 6, 7, 8), entity(1, 2, 3, 4))));

        assertThat(WorldDiff.canonicalMcaHash(a, WorldDiff.DiffMode.BYTE_IDENTICAL))
                .isNotEqualTo(WorldDiff.canonicalMcaHash(b, WorldDiff.DiffMode.BYTE_IDENTICAL));
    }

    // -----------------------------------------------------------------
    // 3. Semantic mode canonicalises entity list order → MATCH.
    // -----------------------------------------------------------------
    @Test
    void semantic_mode_matches_on_reordered_entities(@TempDir Path tmp) throws IOException {
        Path a = tmp.resolve("a.mca");
        Path b = tmp.resolve("b.mca");
        WorldDiff.NbtCompound e1 = entity(1, 2, 3, 4);
        WorldDiff.NbtCompound e2 = entity(5, 6, 7, 8);
        WorldDiff.NbtCompound e3 = entity(9, 10, 11, 12);
        Files.write(a, mcaWithChunk(entitiesChunk(e1, e2, e3)));
        Files.write(b, mcaWithChunk(entitiesChunk(e3, e1, e2)));

        assertThat(WorldDiff.canonicalMcaHash(a, WorldDiff.DiffMode.SEMANTIC))
                .isEqualTo(WorldDiff.canonicalMcaHash(b, WorldDiff.DiffMode.SEMANTIC));
    }

    // -----------------------------------------------------------------
    // 4. Semantic mode drops the volatile drift fields (Air etc.) →
    //    two entities that differ only in Air/HurtTime/DeathTime/
    //    PortalCooldown hash equal.
    // -----------------------------------------------------------------
    @Test
    void semantic_mode_matches_on_ignored_field_drift(@TempDir Path tmp) throws IOException {
        Path a = tmp.resolve("a.mca");
        Path b = tmp.resolve("b.mca");
        WorldDiff.NbtCompound ea = entity(1, 2, 3, 4)
                .put("Air", new WorldDiff.NbtShort((short) 299))
                .put("HurtTime", new WorldDiff.NbtShort((short) 0))
                .put("DeathTime", new WorldDiff.NbtShort((short) 0))
                .put("PortalCooldown", new WorldDiff.NbtInt(0));
        WorldDiff.NbtCompound eb = entity(1, 2, 3, 4)
                .put("Air", new WorldDiff.NbtShort((short) 300))
                .put("HurtTime", new WorldDiff.NbtShort((short) 1))
                .put("DeathTime", new WorldDiff.NbtShort((short) 2))
                .put("PortalCooldown", new WorldDiff.NbtInt(3));
        Files.write(a, mcaWithChunk(entitiesChunk(ea)));
        Files.write(b, mcaWithChunk(entitiesChunk(eb)));

        assertThat(WorldDiff.canonicalMcaHash(a, WorldDiff.DiffMode.SEMANTIC))
                .isEqualTo(WorldDiff.canonicalMcaHash(b, WorldDiff.DiffMode.SEMANTIC));
    }

    // -----------------------------------------------------------------
    // 5. A real state change (extra chest with items) MUST NOT
    //    normalise away. Guards against the ignore-list swallowing a
    //    load-bearing field.
    // -----------------------------------------------------------------
    @Test
    void semantic_mode_diverges_on_real_state_change(@TempDir Path tmp) throws IOException {
        Path a = tmp.resolve("a.mca");
        Path b = tmp.resolve("b.mca");

        WorldDiff.NbtCompound chunkA = new WorldDiff.NbtCompound()
                .put("DataVersion", new WorldDiff.NbtInt(3465))
                .put("block_entities", listOf(WorldDiff.TAG_COMPOUND, chest(0, 64, 0, "minecraft:diamond")));
        WorldDiff.NbtCompound chunkB = new WorldDiff.NbtCompound()
                .put("DataVersion", new WorldDiff.NbtInt(3465))
                .put(
                        "block_entities",
                        listOf(
                                WorldDiff.TAG_COMPOUND,
                                chest(0, 64, 0, "minecraft:diamond"),
                                chest(1, 64, 0, "minecraft:iron_ingot"))); // extra chest

        Files.write(a, mcaWithChunk(chunkA));
        Files.write(b, mcaWithChunk(chunkB));

        assertThat(WorldDiff.canonicalMcaHash(a, WorldDiff.DiffMode.SEMANTIC))
                .isNotEqualTo(WorldDiff.canonicalMcaHash(b, WorldDiff.DiffMode.SEMANTIC));
    }

    // -----------------------------------------------------------------
    // 6. Ticker list order is queue-order-non-deterministic under
    //    parallelism. Sort key (x,y,z,t,p) collapses that.
    // -----------------------------------------------------------------
    @Test
    void semantic_mode_matches_on_reordered_tickers(@TempDir Path tmp) throws IOException {
        Path a = tmp.resolve("a.mca");
        Path b = tmp.resolve("b.mca");

        WorldDiff.NbtCompound t1 = tick("minecraft:water", 0, 64, 0, 1, 0);
        WorldDiff.NbtCompound t2 = tick("minecraft:lava", 1, 64, 0, 1, 0);
        WorldDiff.NbtCompound t3 = tick("minecraft:water", 0, 65, 0, 2, 0);

        WorldDiff.NbtCompound chunkA =
                new WorldDiff.NbtCompound().put("block_ticks", listOf(WorldDiff.TAG_COMPOUND, t1, t2, t3));
        WorldDiff.NbtCompound chunkB =
                new WorldDiff.NbtCompound().put("block_ticks", listOf(WorldDiff.TAG_COMPOUND, t3, t1, t2));

        Files.write(a, mcaWithChunk(chunkA));
        Files.write(b, mcaWithChunk(chunkB));

        assertThat(WorldDiff.canonicalMcaHash(a, WorldDiff.DiffMode.SEMANTIC))
                .isEqualTo(WorldDiff.canonicalMcaHash(b, WorldDiff.DiffMode.SEMANTIC));
    }

    // -----------------------------------------------------------------
    // 7. Motion drift under the |v|² < 1e-6 threshold → canonicalised
    //    to a zero vector → semantic MATCH. Larger motion values
    //    (test #8 below) MUST still be preserved for real-motion
    //    divergence to be caught.
    // -----------------------------------------------------------------
    @Test
    void semantic_mode_matches_on_ignored_motion_drift(@TempDir Path tmp) throws IOException {
        Path a = tmp.resolve("a.mca");
        Path b = tmp.resolve("b.mca");
        WorldDiff.NbtCompound ea = entity(1, 2, 3, 4).put("Motion", motionList(1e-8, 0.0, 0.0));
        WorldDiff.NbtCompound eb = entity(1, 2, 3, 4).put("Motion", motionList(2e-8, -3e-9, 4e-9));
        Files.write(a, mcaWithChunk(entitiesChunk(ea)));
        Files.write(b, mcaWithChunk(entitiesChunk(eb)));

        assertThat(WorldDiff.canonicalMcaHash(a, WorldDiff.DiffMode.SEMANTIC))
                .isEqualTo(WorldDiff.canonicalMcaHash(b, WorldDiff.DiffMode.SEMANTIC));
    }

    // Sanity peer to #7: an entity that's really moving (|v|² well
    // above the idle threshold) keeps its motion vector and a real
    // difference MUST divergence.
    @Test
    void semantic_mode_diverges_on_real_motion_difference(@TempDir Path tmp) throws IOException {
        Path a = tmp.resolve("a.mca");
        Path b = tmp.resolve("b.mca");
        WorldDiff.NbtCompound ea = entity(1, 2, 3, 4).put("Motion", motionList(1.0, 0.0, 0.0));
        WorldDiff.NbtCompound eb = entity(1, 2, 3, 4).put("Motion", motionList(2.0, 0.0, 0.0));
        Files.write(a, mcaWithChunk(entitiesChunk(ea)));
        Files.write(b, mcaWithChunk(entitiesChunk(eb)));

        assertThat(WorldDiff.canonicalMcaHash(a, WorldDiff.DiffMode.SEMANTIC))
                .isNotEqualTo(WorldDiff.canonicalMcaHash(b, WorldDiff.DiffMode.SEMANTIC));
    }

    // -----------------------------------------------------------------
    // Fixture helpers
    // -----------------------------------------------------------------

    /** Chunk NBT that carries only an {@code entities} list (matches the region-chunk shape). */
    private static WorldDiff.NbtCompound entitiesChunk(WorldDiff.NbtCompound... entities) {
        return new WorldDiff.NbtCompound()
                .put("DataVersion", new WorldDiff.NbtInt(3465))
                .put("entities", listOf(WorldDiff.TAG_COMPOUND, entities));
    }

    /** Build an entity compound with a modern 4-int UUID and a canonical id. */
    private static WorldDiff.NbtCompound entity(int u0, int u1, int u2, int u3) {
        return new WorldDiff.NbtCompound()
                .put("id", new WorldDiff.NbtString("minecraft:zombie"))
                .put("UUID", new WorldDiff.NbtIntArray(new int[] {u0, u1, u2, u3}));
    }

    private static WorldDiff.NbtCompound chest(int x, int y, int z, String itemId) {
        WorldDiff.NbtCompound item = new WorldDiff.NbtCompound()
                .put("id", new WorldDiff.NbtString(itemId))
                .put("Count", new WorldDiff.NbtByte((byte) 1))
                .put("Slot", new WorldDiff.NbtByte((byte) 0));
        return new WorldDiff.NbtCompound()
                .put("id", new WorldDiff.NbtString("minecraft:chest"))
                .put("x", new WorldDiff.NbtInt(x))
                .put("y", new WorldDiff.NbtInt(y))
                .put("z", new WorldDiff.NbtInt(z))
                .put("Items", listOf(WorldDiff.TAG_COMPOUND, item));
    }

    private static WorldDiff.NbtCompound tick(String blockId, int x, int y, int z, int delay, int priority) {
        return new WorldDiff.NbtCompound()
                .put("i", new WorldDiff.NbtString(blockId))
                .put("x", new WorldDiff.NbtInt(x))
                .put("y", new WorldDiff.NbtInt(y))
                .put("z", new WorldDiff.NbtInt(z))
                .put("t", new WorldDiff.NbtInt(delay))
                .put("p", new WorldDiff.NbtInt(priority));
    }

    private static WorldDiff.NbtList motionList(double vx, double vy, double vz) {
        List<WorldDiff.NbtTag> els = new ArrayList<>(3);
        els.add(new WorldDiff.NbtDouble(vx));
        els.add(new WorldDiff.NbtDouble(vy));
        els.add(new WorldDiff.NbtDouble(vz));
        return new WorldDiff.NbtList(WorldDiff.TAG_DOUBLE, els);
    }

    private static WorldDiff.NbtList listOf(byte elementType, WorldDiff.NbtTag... elements) {
        List<WorldDiff.NbtTag> els = new ArrayList<>(elements.length);
        for (WorldDiff.NbtTag t : elements) els.add(t);
        return new WorldDiff.NbtList(elementType, els);
    }

    /**
     * Wrap a chunk NBT tree in a minimal MCA file at slot 0. The
     * payload is deflate-compressed (Vanilla compression id 2), then
     * length-prefixed per {@code docs/design/mca-format.md}. Sector 2
     * is the earliest legal payload position (sectors 0 + 1 are the
     * location + timestamp tables).
     */
    private static byte[] mcaWithChunk(WorldDiff.NbtCompound chunk) throws IOException {
        byte[] nbtBytes = WorldDiff.writeCanonicalRoot(chunk);
        byte[] compressed;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                DeflaterOutputStream dos = new DeflaterOutputStream(bos)) {
            dos.write(nbtBytes);
            dos.finish();
            compressed = bos.toByteArray();
        }
        int chunkLen = compressed.length + 1; // +1 compression byte
        int payloadSize = 4 + chunkLen;
        int payloadSectors = (payloadSize + 4095) / 4096;
        int fileSize = (2 + payloadSectors) * 4096;
        byte[] file = new byte[fileSize];

        // Location entry at slot 0: sectorOffset=2, sectorCount=payloadSectors.
        int loc = (2 << 8) | (payloadSectors & 0xFF);
        file[0] = (byte) (loc >>> 24);
        file[1] = (byte) (loc >>> 16);
        file[2] = (byte) (loc >>> 8);
        file[3] = (byte) loc;

        // Timestamp table stays zero — WorldDiff strips it anyway.

        // Chunk payload at sector 2.
        int off = 2 * 4096;
        file[off] = (byte) (chunkLen >>> 24);
        file[off + 1] = (byte) (chunkLen >>> 16);
        file[off + 2] = (byte) (chunkLen >>> 8);
        file[off + 3] = (byte) chunkLen;
        file[off + 4] = 2; // zlib/deflate
        System.arraycopy(compressed, 0, file, off + 5, compressed.length);
        return file;
    }
}
