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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldDiffTest {

    @Test
    void identicalTreesMatch(@TempDir Path tmp) throws IOException {
        Path a = Files.createDirectory(tmp.resolve("a"));
        Path b = Files.createDirectory(tmp.resolve("b"));
        writeFile(a, "region/r.0.0.mca", "identical-content");
        writeFile(a, "level.dat", "hello");
        writeFile(b, "region/r.0.0.mca", "identical-content");
        writeFile(b, "level.dat", "hello");

        WorldDiff.Result r = WorldDiff.compare(a, b);
        assertThat(r.matches()).isTrue();
        assertThat(r.summary()).contains("MATCH");
    }

    @Test
    void contentDifferenceIsFlagged(@TempDir Path tmp) throws IOException {
        Path a = Files.createDirectory(tmp.resolve("a"));
        Path b = Files.createDirectory(tmp.resolve("b"));
        writeFile(a, "region/r.0.0.mca", "content-baseline");
        writeFile(b, "region/r.0.0.mca", "content-patched");

        WorldDiff.Result r = WorldDiff.compare(a, b);
        assertThat(r.matches()).isFalse();
        assertThat(r.mismatched()).containsKey("region/r.0.0.mca");
        assertThat(r.summary()).contains("MISMATCH").contains("content differs");
    }

    @Test
    void extraOrMissingFileIsFlagged(@TempDir Path tmp) throws IOException {
        Path a = Files.createDirectory(tmp.resolve("a"));
        Path b = Files.createDirectory(tmp.resolve("b"));
        writeFile(a, "level.dat", "x");
        writeFile(a, "region/only-in-baseline.mca", "x");
        writeFile(b, "level.dat", "x");
        writeFile(b, "region/only-in-patched.mca", "x");

        WorldDiff.Result r = WorldDiff.compare(a, b);
        assertThat(r.matches()).isFalse();
        assertThat(r.onlyInBaseline()).contains("region/only-in-baseline.mca");
        assertThat(r.onlyInPatched()).contains("region/only-in-patched.mca");
    }

    @Test
    void nondeterministicNamesAreIgnored(@TempDir Path tmp) throws IOException {
        Path a = Files.createDirectory(tmp.resolve("a"));
        Path b = Files.createDirectory(tmp.resolve("b"));
        writeFile(a, "level.dat", "same");
        writeFile(b, "level.dat", "same");
        // session.lock is on the ignore list; different content must not fail the diff.
        writeFile(a, "session.lock", "aaa");
        writeFile(b, "session.lock", "bbb");

        WorldDiff.Result r = WorldDiff.compare(a, b);
        assertThat(r.matches()).isTrue();
    }

    // /67 review finding #13: .dat_old files legitimately differ across identical-seed runs.
    @Test
    void datOldSuffixIsIgnored(@TempDir Path tmp) throws IOException {
        Path a = Files.createDirectory(tmp.resolve("a"));
        Path b = Files.createDirectory(tmp.resolve("b"));
        writeFile(a, "level.dat", "same");
        writeFile(b, "level.dat", "same");
        writeFile(a, "level.dat_old", "one-content");
        writeFile(b, "level.dat_old", "totally-different-content");
        writeFile(a, "scoreboard.dat_old", "x");
        writeFile(b, "scoreboard.dat_old", "y");

        WorldDiff.Result r = WorldDiff.compare(a, b);
        assertThat(r.matches()).isTrue();
    }

    // /67 round-2 finding: WorldDiff was only stripping the timestamp table (bytes 4096..8191)
    // but the location table (bytes 0..4095) is ALSO non-deterministic — vanilla writes chunks
    // at different sector offsets across identical-seed reruns (defrag, growth). The fixed
    // canonical hash walks the location table and hashes payloads by slot id in fixed order.
    @Test
    void mcaHashIgnoresBothTimestampAndLocationTableVariation(@TempDir Path tmp) throws IOException {
        Path a = Files.createDirectory(tmp.resolve("a"));
        Path b = Files.createDirectory(tmp.resolve("b"));

        // Both files declare one chunk at slot 5, same payload — but at DIFFERENT sector offsets
        // (A puts it at sector 2, B at sector 4). Timestamps also differ.
        byte[] payload = mcaChunkPayload(new byte[] {(byte) 0xAB, (byte) 0xCD, (byte) 0xEF});
        byte[] regionA = mcaFile(new int[] {5}, new byte[][] {payload}, new int[] {2}, 0x11);
        byte[] regionB = mcaFile(new int[] {5}, new byte[][] {payload}, new int[] {4}, 0x99);

        assertThat(regionA).isNotEqualTo(regionB); // raw bytes differ
        Files.createDirectories(a.resolve("region"));
        Files.createDirectories(b.resolve("region"));
        Files.write(a.resolve("region/r.0.0.mca"), regionA);
        Files.write(b.resolve("region/r.0.0.mca"), regionB);

        WorldDiff.Result r = WorldDiff.compare(a, b);
        assertThat(r.matches())
                .as("canonical MCA hash must be stable across sector reordering + timestamp variation")
                .isTrue();
    }

    // Sanity: a real chunk payload difference still trips the diff.
    @Test
    void mcaPayloadDifferenceStillTripsTheDiff(@TempDir Path tmp) throws IOException {
        Path a = Files.createDirectory(tmp.resolve("a"));
        Path b = Files.createDirectory(tmp.resolve("b"));
        byte[] regionA = mcaFile(new int[] {0}, new byte[][] {mcaChunkPayload(new byte[] {1, 2, 3})}, new int[] {2}, 0);
        byte[] regionB = mcaFile(new int[] {0}, new byte[][] {mcaChunkPayload(new byte[] {9, 8, 7})}, new int[] {2}, 0);
        Files.createDirectories(a.resolve("region"));
        Files.createDirectories(b.resolve("region"));
        Files.write(a.resolve("region/r.0.0.mca"), regionA);
        Files.write(b.resolve("region/r.0.0.mca"), regionB);

        WorldDiff.Result r = WorldDiff.compare(a, b);
        assertThat(r.matches()).isFalse();
        assertThat(r.mismatched()).containsKey("region/r.0.0.mca");
    }

    // Sanity: a chunk present in one but absent in the other trips the diff.
    @Test
    void mcaChunkPresenceDifferenceStillTripsTheDiff(@TempDir Path tmp) throws IOException {
        Path a = Files.createDirectory(tmp.resolve("a"));
        Path b = Files.createDirectory(tmp.resolve("b"));
        byte[] chunk = mcaChunkPayload(new byte[] {1, 2, 3});
        byte[] regionA = mcaFile(new int[] {0, 5}, new byte[][] {chunk, chunk}, new int[] {2, 3}, 0);
        byte[] regionB = mcaFile(new int[] {0}, new byte[][] {chunk}, new int[] {2}, 0);
        Files.createDirectories(a.resolve("region"));
        Files.createDirectories(b.resolve("region"));
        Files.write(a.resolve("region/r.0.0.mca"), regionA);
        Files.write(b.resolve("region/r.0.0.mca"), regionB);

        WorldDiff.Result r = WorldDiff.compare(a, b);
        assertThat(r.matches()).isFalse();
        assertThat(r.mismatched()).containsKey("region/r.0.0.mca");
    }

    /**
     * Wrap raw bytes as an Anvil chunk payload: 4-byte big-endian length,
     * 1 compression byte, then the raw bytes. Length in the header
     * INCLUDES the compression byte per the format.
     */
    private static byte[] mcaChunkPayload(byte[] raw) {
        int chunkLen = raw.length + 1; // + 1 compression byte
        byte[] payload = new byte[4 + chunkLen];
        payload[0] = (byte) (chunkLen >>> 24);
        payload[1] = (byte) (chunkLen >>> 16);
        payload[2] = (byte) (chunkLen >>> 8);
        payload[3] = (byte) chunkLen;
        payload[4] = 2; // zlib compression
        System.arraycopy(raw, 0, payload, 5, raw.length);
        return payload;
    }

    /**
     * Build a minimal MCA file: location table pointing each `slots[i]` at
     * `sectorOffsets[i]` with `payloads[i]` written there. `timestampFill`
     * is a byte value written into the entire timestamp table (bytes
     * 4096..8191). Empty slots get all-zero location entries.
     */
    private static byte[] mcaFile(int[] slots, byte[][] payloads, int[] sectorOffsets, int timestampFill) {
        int lastSector = 2;
        for (int i = 0; i < slots.length; i++) {
            int payloadSectors = (payloads[i].length + 4095) / 4096;
            lastSector = Math.max(lastSector, sectorOffsets[i] + payloadSectors);
        }
        int fileSize = lastSector * 4096;
        byte[] file = new byte[fileSize];
        // Fill timestamp table
        for (int i = 4096; i < 8192; i++) file[i] = (byte) timestampFill;
        // Write location entries + payloads
        for (int i = 0; i < slots.length; i++) {
            int slot = slots[i];
            int offset = sectorOffsets[i];
            int payloadSectors = (payloads[i].length + 4095) / 4096;
            int loc = (offset << 8) | (payloadSectors & 0xFF);
            file[slot * 4] = (byte) (loc >>> 24);
            file[slot * 4 + 1] = (byte) (loc >>> 16);
            file[slot * 4 + 2] = (byte) (loc >>> 8);
            file[slot * 4 + 3] = (byte) loc;
            System.arraycopy(payloads[i], 0, file, offset * 4096, payloads[i].length);
        }
        return file;
    }

    // /67 round-3 finding D1: `sectorOffset * 4096` used to overflow signed int for
    // sectorOffset ≥ 524_288, producing a negative payloadStart that defeated the bounds
    // check and crashed the whole diff run on any adversarial/corrupt input.
    @Test
    void mcaCorruptSectorOffsetDoesNotCrash(@TempDir Path tmp) throws IOException {
        Path a = Files.createDirectory(tmp.resolve("a"));
        Path b = Files.createDirectory(tmp.resolve("b"));
        // Slot 0 has a location entry with sectorOffset=0xFFFFFF (24-bit maximum).
        // Layout: bytes 0..2 = 3-byte big-endian sectorOffset, byte 3 = sectorCount.
        // 0xFFFFFF * 4096 = 0xFFF_FFFF_000 = 68_719_472_640, which wraps a signed int
        // to -4096. Prior code read bytes[-4096] and threw
        // ArrayIndexOutOfBoundsException from the whole diff. /67 round-4 corrected the
        // byte pattern — pre-fix `00 FF FF FF` gave only sectorOffset=0xFFFF (65535)
        // which fits in int and did not exercise the wrap.
        byte[] corrupt = new byte[8192];
        // Write loc entry: sectorOffset=0xFFFFFF, sectorCount=1
        corrupt[0] = (byte) 0xFF;
        corrupt[1] = (byte) 0xFF;
        corrupt[2] = (byte) 0xFF;
        corrupt[3] = 0x01;
        Files.createDirectories(a.resolve("region"));
        Files.createDirectories(b.resolve("region"));
        Files.write(a.resolve("region/r.0.0.mca"), corrupt);
        Files.write(b.resolve("region/r.0.0.mca"), new byte[8192]); // slot 0 empty

        // Must not throw; corrupt-vs-empty should MISMATCH via the malformed-slot sentinel.
        WorldDiff.Result r = WorldDiff.compare(a, b);
        assertThat(r.matches()).isFalse();
    }

    // /67 round-3 finding D2: `payloadStart + 4 + chunkLength` also overflows. Adversarial
    // input with chunkLength near Integer.MAX_VALUE would wrap to negative payloadEnd,
    // pass the guard, and trip IllegalArgumentException from MessageDigest.update.
    @Test
    void mcaCorruptChunkLengthDoesNotCrash(@TempDir Path tmp) throws IOException {
        Path a = Files.createDirectory(tmp.resolve("a"));
        Path b = Files.createDirectory(tmp.resolve("b"));
        // Slot 0 has a valid-looking location entry pointing at a chunk with a lie:
        // chunkLength = Integer.MAX_VALUE, way past file end.
        byte[] corrupt = new byte[12288]; // 3 sectors
        // loc entry: sectorOffset=2, sectorCount=1
        corrupt[0] = 0;
        corrupt[1] = 0;
        corrupt[2] = 2;
        corrupt[3] = 1;
        // Chunk header at byte 8192 (sector 2): chunkLength=0x7FFFFFFF, compression=2
        corrupt[8192] = 0x7F;
        corrupt[8193] = (byte) 0xFF;
        corrupt[8194] = (byte) 0xFF;
        corrupt[8195] = (byte) 0xFF;
        corrupt[8196] = 2;
        Files.createDirectories(a.resolve("region"));
        Files.createDirectories(b.resolve("region"));
        Files.write(a.resolve("region/r.0.0.mca"), corrupt);
        Files.write(b.resolve("region/r.0.0.mca"), new byte[12288]);

        WorldDiff.Result r = WorldDiff.compare(a, b);
        assertThat(r.matches()).isFalse();
    }

    // /67 round-3 finding D3: prior code silently `continue`d for malformed slots,
    // making corrupt-slot indistinguishable from empty-slot. Real chunk-loss regressions
    // (a bad byte in the location entry drops the chunk) reported as MATCH.
    @Test
    void mcaEmptySlotVsMalformedSlotMismatches(@TempDir Path tmp) throws IOException {
        Path a = Files.createDirectory(tmp.resolve("a"));
        Path b = Files.createDirectory(tmp.resolve("b"));
        // A: slot 5 = 0 (empty)
        byte[] empty = new byte[8192];
        // B: slot 5 has a nonzero-but-malformed location entry (sectorOffset=1 which is < 2)
        byte[] malformed = new byte[8192];
        malformed[20] = 0;
        malformed[21] = 0;
        malformed[22] = 1;
        malformed[23] = 1;

        Files.createDirectories(a.resolve("region"));
        Files.createDirectories(b.resolve("region"));
        Files.write(a.resolve("region/r.0.0.mca"), empty);
        Files.write(b.resolve("region/r.0.0.mca"), malformed);

        // Real bug: prior code hashed both as "no data" → false MATCH. Now must MISMATCH.
        WorldDiff.Result r = WorldDiff.compare(a, b);
        assertThat(r.matches()).isFalse();
        assertThat(r.mismatched()).containsKey("region/r.0.0.mca");
    }

    @Test
    void nondeterministicDirectoriesAreSkippedWhole(@TempDir Path tmp) throws IOException {
        Path a = Files.createDirectory(tmp.resolve("a"));
        Path b = Files.createDirectory(tmp.resolve("b"));
        writeFile(a, "level.dat", "same");
        writeFile(b, "level.dat", "same");
        // "stats" is on the ignore list as a directory name → its whole subtree is skipped.
        writeFile(a, "stats/uuid-a.json", "aaa");
        writeFile(b, "stats/uuid-b.json", "bbb"); // different filename too, doesn't matter

        WorldDiff.Result r = WorldDiff.compare(a, b);
        assertThat(r.matches()).isTrue();
    }

    private static void writeFile(Path root, String relPath, String content) throws IOException {
        Path target = root.resolve(relPath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
