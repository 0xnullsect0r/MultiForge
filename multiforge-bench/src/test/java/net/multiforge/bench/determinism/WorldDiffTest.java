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
