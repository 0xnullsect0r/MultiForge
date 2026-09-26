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
package net.multiforge.bench.determinism;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every chunk storage form Vanilla 1.21.1 writes hashes the same in
 * {@link WorldDiff.DiffMode#SEMANTIC} mode: gzip (1), deflate (2), none (3),
 * LZ4 (4), and oversized chunks stored in a {@code c.<x>.<z>.mcc} file.
 */
class WorldDiffCompressionTest {

    private static final WorldDiff.NbtCompound CHUNK = new WorldDiff.NbtCompound()
            .put("DataVersion", new WorldDiff.NbtInt(3955))
            .put("Status", new WorldDiff.NbtString("minecraft:full"));

    private interface Compressor {
        OutputStream wrap(OutputStream out) throws IOException;
    }

    private static byte[] compress(int type, byte[] raw) throws IOException {
        Compressor c =
                switch (type) {
                    case 1 -> GZIPOutputStream::new;
                    case 2 -> DeflaterOutputStream::new;
                    case 3 -> out -> out;
                    case 4 -> LZ4BlockOutputStream::new;
                    default -> throw new IllegalArgumentException("type " + type);
                };
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (OutputStream out = c.wrap(bos)) {
            out.write(raw);
        }
        return bos.toByteArray();
    }

    /** A region file holding one chunk in slot 0 with the given compression byte and payload. */
    private static byte[] mca(int compressionByte, byte[] payload) {
        int chunkLen = payload.length + 1;
        int sectors = (4 + chunkLen + 4095) / 4096;
        byte[] file = new byte[(2 + sectors) * 4096];
        int loc = (2 << 8) | sectors;
        file[0] = (byte) (loc >>> 24);
        file[1] = (byte) (loc >>> 16);
        file[2] = (byte) (loc >>> 8);
        file[3] = (byte) loc;
        int off = 2 * 4096;
        file[off] = (byte) (chunkLen >>> 24);
        file[off + 1] = (byte) (chunkLen >>> 16);
        file[off + 2] = (byte) (chunkLen >>> 8);
        file[off + 3] = (byte) chunkLen;
        file[off + 4] = (byte) compressionByte;
        System.arraycopy(payload, 0, file, off + 5, payload.length);
        return file;
    }

    private static String semantic(Path dir, String name, byte[] mcaBytes) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, mcaBytes);
        return WorldDiff.canonicalMcaHash(file, WorldDiff.DiffMode.SEMANTIC);
    }

    @Test
    void everyCompressionTypeHashesTheSameChunkAlike(@TempDir Path tmp) throws IOException {
        byte[] raw = WorldDiff.writeCanonicalRoot(CHUNK);
        String deflate = semantic(Files.createDirectories(tmp.resolve("2")), "r.0.0.mca", mca(2, compress(2, raw)));
        for (int type : new int[] {1, 3, 4}) {
            Path dir = Files.createDirectories(tmp.resolve(String.valueOf(type)));
            assertThat(semantic(dir, "r.0.0.mca", mca(type, compress(type, raw))))
                    .as("compression type %d", type)
                    .isEqualTo(deflate);
        }
    }

    @Test
    void decompressesEachType() throws IOException {
        byte[] raw = "chunk".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int type = 1; type <= 4; type++) {
            byte[] c = compress(type, raw);
            assertThat(WorldDiff.decompressSlotPayload(type, c, 0, c.length))
                    .as("type %d", type)
                    .isEqualTo(raw);
        }
        assertThat(WorldDiff.decompressSlotPayload(127, raw, 0, raw.length)).isNull();
    }

    @Test
    void externalChunkIsReadFromItsMccFile(@TempDir Path tmp) throws IOException {
        byte[] raw = WorldDiff.writeCanonicalRoot(CHUNK);
        Path inline = Files.createDirectories(tmp.resolve("inline"));
        Path external = Files.createDirectories(tmp.resolve("external"));
        String inlineHash = semantic(inline, "r.-1.2.mca", mca(2, compress(2, raw)));
        // Slot 0 of r.-1.2 is chunk (-32, 64); its stream lives in c.-32.64.mcc.
        Files.write(external.resolve("c.-32.64.mcc"), compress(2, raw));
        String externalHash = semantic(external, "r.-1.2.mca", mca(0x80 | 2, new byte[0]));
        assertThat(externalHash).isEqualTo(inlineHash);
    }

    @Test
    void byteIdenticalModeSeesExternalChunkContent(@TempDir Path tmp) throws IOException {
        byte[] region = mca(0x80 | 2, new byte[0]);
        Path file = tmp.resolve("r.0.0.mca");
        Files.write(file, region);
        Files.write(tmp.resolve("c.0.0.mcc"), compress(2, WorldDiff.writeCanonicalRoot(CHUNK)));
        String before = WorldDiff.canonicalMcaHash(file, WorldDiff.DiffMode.BYTE_IDENTICAL);
        Files.write(tmp.resolve("c.0.0.mcc"), compress(2, "changed".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(WorldDiff.canonicalMcaHash(file, WorldDiff.DiffMode.BYTE_IDENTICAL))
                .isNotEqualTo(before);
    }
}
