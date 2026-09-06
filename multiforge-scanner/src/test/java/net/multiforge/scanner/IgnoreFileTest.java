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
package net.multiforge.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IgnoreFileTest {

    @TempDir
    Path tempDir;

    private static final Finding SAMPLE = new Finding(
            "R03",
            Severity.ERROR,
            "com.example.mod.ChunkListener",
            "onNeighborChanged(Lnet/minecraft/core/BlockPos;)V",
            42,
            "CompletableFuture.get() called from @RegionThread method onNeighborChanged",
            "R03:com.example.mod.ChunkListener#onNeighborChanged(Lnet/minecraft/core/BlockPos;)V#a1b2c3d4e5f6");

    @Test
    void suppressesOnExactFingerprintMatch() throws IOException {
        Path file = writeIgnoreFile(
                "R03:com.example.mod.ChunkListener#onNeighborChanged(Lnet/minecraft/core/BlockPos;)V#a1b2c3d4e5f6");
        IgnoreFile ignore = IgnoreFile.load(file);

        assertThat(ignore.match(SAMPLE)).isEqualTo(IgnoreFile.MatchResult.SUPPRESSED);
    }

    @Test
    void isStaleWhenKeyMatchesButHashDiffers() throws IOException {
        Path file = writeIgnoreFile(
                "R03:com.example.mod.ChunkListener#onNeighborChanged(Lnet/minecraft/core/BlockPos;)V#000000000000");
        IgnoreFile ignore = IgnoreFile.load(file);

        assertThat(ignore.match(SAMPLE)).isEqualTo(IgnoreFile.MatchResult.STALE);
    }

    @Test
    void isNotMatchedWhenNoEntryShareTheKey() throws IOException {
        Path file = writeIgnoreFile("R05:com.example.mod.Other#foo()V#a1b2c3d4e5f6");
        IgnoreFile ignore = IgnoreFile.load(file);

        assertThat(ignore.match(SAMPLE)).isEqualTo(IgnoreFile.MatchResult.NOT_MATCHED);
    }

    @Test
    void ignoresCommentAndBlankLines() throws IOException {
        Path file = writeIgnoreFile(
                "# audited 2026-09-01, see PR #42",
                "",
                "R03:com.example.mod.ChunkListener#onNeighborChanged(Lnet/minecraft/core/BlockPos;)V#a1b2c3d4e5f6");
        IgnoreFile ignore = IgnoreFile.load(file);

        assertThat(ignore.match(SAMPLE)).isEqualTo(IgnoreFile.MatchResult.SUPPRESSED);
    }

    @Test
    void skipsMalformedLinesWithoutCrashing() throws IOException {
        Path file = writeIgnoreFile("this-is-not-a-fingerprint", "R03noColonOrHash", "R03:missing-hash-parts#onlyOne");
        IgnoreFile ignore = IgnoreFile.load(file);

        assertThat(ignore.match(SAMPLE)).isEqualTo(IgnoreFile.MatchResult.NOT_MATCHED);
    }

    @Test
    void missingFileIsEmptyNotAnError() throws IOException {
        IgnoreFile ignore = IgnoreFile.load(tempDir.resolve("does-not-exist"));

        assertThat(ignore.isEmpty()).isTrue();
        assertThat(ignore.match(SAMPLE)).isEqualTo(IgnoreFile.MatchResult.NOT_MATCHED);
    }

    @Test
    void mergeUnionsEntriesFromBothFiles() throws IOException {
        Path global = writeIgnoreFile("R05:com.example.mod.Other#foo()V#deadbeef0000");
        Path local = writeIgnoreFile(
                "R03:com.example.mod.ChunkListener#onNeighborChanged(Lnet/minecraft/core/BlockPos;)V#a1b2c3d4e5f6");

        IgnoreFile merged = IgnoreFile.load(global).merge(IgnoreFile.load(local));

        assertThat(merged.match(SAMPLE)).isEqualTo(IgnoreFile.MatchResult.SUPPRESSED);
    }

    private Path writeIgnoreFile(String... lines) throws IOException {
        Path file = tempDir.resolve(".multiforgeignore-" + System.nanoTime());
        Files.write(file, List.of(lines));
        return file;
    }
}
