/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
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

    // /67 review finding #13: .mca region files carry per-sector timestamps that differ across runs.
    @Test
    void mcaFilesIgnoreTimestampHeader(@TempDir Path tmp) throws IOException {
        Path a = Files.createDirectory(tmp.resolve("a"));
        Path b = Files.createDirectory(tmp.resolve("b"));
        // Build a minimal 8 KiB MCA: 4 KiB "offsets" (all zero), 4 KiB "timestamps".
        // Then 4 more KiB of "chunk payload" (whatever bytes) that must match between files.
        byte[] payload = new byte[4096];
        java.util.Arrays.fill(payload, (byte) 0x42);

        byte[] regionA = new byte[12288];
        byte[] regionB = new byte[12288];
        // Timestamps differ across runs (bytes 4096..8191).
        for (int i = 4096; i < 8192; i++) regionA[i] = (byte) 0x11;
        for (int i = 4096; i < 8192; i++) regionB[i] = (byte) 0x99;
        // Chunk payload matches (bytes 8192..12287).
        System.arraycopy(payload, 0, regionA, 8192, 4096);
        System.arraycopy(payload, 0, regionB, 8192, 4096);

        Files.createDirectories(a.resolve("region"));
        Files.createDirectories(b.resolve("region"));
        Files.write(a.resolve("region/r.0.0.mca"), regionA);
        Files.write(b.resolve("region/r.0.0.mca"), regionB);

        WorldDiff.Result r = WorldDiff.compare(a, b);
        assertThat(r.matches()).as("MCA hash must strip the timestamps table").isTrue();
    }

    // Sanity: a real chunk payload difference in an MCA still trips the diff.
    @Test
    void mcaPayloadDifferenceStillTripsTheDiff(@TempDir Path tmp) throws IOException {
        Path a = Files.createDirectory(tmp.resolve("a"));
        Path b = Files.createDirectory(tmp.resolve("b"));
        byte[] regionA = new byte[12288];
        byte[] regionB = new byte[12288];
        // Same timestamps, different payload.
        regionA[10000] = (byte) 1;
        regionB[10000] = (byte) 2;
        Files.createDirectories(a.resolve("region"));
        Files.createDirectories(b.resolve("region"));
        Files.write(a.resolve("region/r.0.0.mca"), regionA);
        Files.write(b.resolve("region/r.0.0.mca"), regionB);

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
