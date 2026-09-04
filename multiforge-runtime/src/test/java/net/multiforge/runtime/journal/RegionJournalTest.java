/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.multiforge.runtime.region.RegionId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionJournalTest {

    private static final RegionId REGION = new RegionId(42);

    @Test
    void appendAndReplayRoundTrip(@TempDir Path dir) throws IOException {
        try (RegionJournal j = RegionJournal.open(REGION, dir)) {
            j.append(JournalEntryKind.CHUNK_SAVE, "chunk-0".getBytes(StandardCharsets.UTF_8));
            j.append(JournalEntryKind.TICK_MARK, new byte[0]);
            j.append(JournalEntryKind.ENTITY_MIGRATION, "mig".getBytes(StandardCharsets.UTF_8));
        }
        try (RegionJournal j = RegionJournal.open(REGION, dir)) {
            List<JournalEntry> entries = j.readAll();
            assertThat(entries).hasSize(3);
            assertThat(entries.get(0).kind()).isEqualTo(JournalEntryKind.CHUNK_SAVE);
            assertThat(new String(entries.get(0).payload(), StandardCharsets.UTF_8))
                    .isEqualTo("chunk-0");
            assertThat(entries.get(1).kind()).isEqualTo(JournalEntryKind.TICK_MARK);
            assertThat(entries.get(1).payloadSize()).isEqualTo(0);
            assertThat(entries.get(2).kind()).isEqualTo(JournalEntryKind.ENTITY_MIGRATION);
        }
    }

    @Test
    void sequencesAreMonotonicAndSurviveRestart(@TempDir Path dir) throws IOException {
        try (RegionJournal j = RegionJournal.open(REGION, dir)) {
            assertThat(j.append(JournalEntryKind.TICK_MARK, new byte[] {1})).isEqualTo(0L);
            assertThat(j.append(JournalEntryKind.TICK_MARK, new byte[] {2})).isEqualTo(1L);
        }
        try (RegionJournal j = RegionJournal.open(REGION, dir)) {
            // Reopen — next append continues from prior max.
            assertThat(j.append(JournalEntryKind.TICK_MARK, new byte[] {3})).isEqualTo(2L);
        }
        try (RegionJournal j = RegionJournal.open(REGION, dir)) {
            List<JournalEntry> entries = j.readAll();
            assertThat(entries).extracting(JournalEntry::sequence).containsExactly(0L, 1L, 2L);
        }
    }

    @Test
    void corruptCrcRejectsEntry(@TempDir Path dir) throws IOException {
        Path file;
        try (RegionJournal j = RegionJournal.open(REGION, dir)) {
            j.append(JournalEntryKind.CHUNK_SAVE, "ok".getBytes(StandardCharsets.UTF_8));
            file = j.path();
        }
        // Flip the last byte (the CRC trailer) — replay must reject.
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            long last = raf.length() - 1;
            raf.seek(last);
            int b = raf.readUnsignedByte();
            raf.seek(last);
            raf.writeByte(b ^ 0xFF);
        }
        assertThatThrownBy(() -> RegionJournal.open(REGION, dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("CRC");
    }

    @Test
    void shortTrailingBytesAreIgnored(@TempDir Path dir) throws IOException {
        try (RegionJournal j = RegionJournal.open(REGION, dir)) {
            j.append(JournalEntryKind.CHUNK_SAVE, "full".getBytes(StandardCharsets.UTF_8));
        }
        // Fewer than a full header — a torn tail from a crash mid-append.
        // The good entry must still replay.
        Path file = dir.resolve("region-" + REGION.value() + ".mjl");
        Files.write(file, new byte[] {0, 0, 0}, java.nio.file.StandardOpenOption.APPEND);
        try (RegionJournal j = RegionJournal.open(REGION, dir)) {
            List<JournalEntry> entries = j.readAll();
            assertThat(entries).hasSize(1);
            assertThat(new String(entries.get(0).payload(), StandardCharsets.UTF_8))
                    .isEqualTo("full");
        }
    }
}
