/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.multiforge.runtime.region.RegionId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalReplayHarnessTest {

    private static final RegionId REGION = new RegionId(7);

    @Test
    void dispatchesToRegisteredHandlersInOrder(@TempDir Path dir) throws IOException {
        try (RegionJournal j = RegionJournal.open(REGION, dir)) {
            j.append(JournalEntryKind.CHUNK_SAVE, "a".getBytes(StandardCharsets.UTF_8));
            j.append(JournalEntryKind.ENTITY_MIGRATION, "b".getBytes(StandardCharsets.UTF_8));
            j.append(JournalEntryKind.CHUNK_SAVE, "c".getBytes(StandardCharsets.UTF_8));
        }

        List<String> chunks = new ArrayList<>();
        List<String> migrations = new ArrayList<>();
        JournalReplayHarness harness = new JournalReplayHarness()
                .on(JournalEntryKind.CHUNK_SAVE, e -> chunks.add(new String(e.payload(), StandardCharsets.UTF_8)))
                .on(
                        JournalEntryKind.ENTITY_MIGRATION,
                        e -> migrations.add(new String(e.payload(), StandardCharsets.UTF_8)));

        try (RegionJournal j = RegionJournal.open(REGION, dir)) {
            int total = harness.replay(j);
            assertThat(total).isEqualTo(3);
        }

        assertThat(chunks).containsExactly("a", "c");
        assertThat(migrations).containsExactly("b");
        assertThat(harness.appliedCount()).isEqualTo(3);
        assertThat(harness.unknownCount()).isZero();
    }

    @Test
    void unknownKindGoesToFallback(@TempDir Path dir) throws IOException {
        try (RegionJournal j = RegionJournal.open(REGION, dir)) {
            j.append(JournalEntryKind.TICK_MARK, new byte[0]);
            j.append(JournalEntryKind.CHUNK_SAVE, "keep".getBytes(StandardCharsets.UTF_8));
        }

        List<JournalEntry> fallback = new ArrayList<>();
        JournalReplayHarness harness = new JournalReplayHarness()
                .on(JournalEntryKind.CHUNK_SAVE, e -> {})
                .onUnknown(fallback::add);

        try (RegionJournal j = RegionJournal.open(REGION, dir)) {
            harness.replay(j);
        }
        assertThat(fallback).hasSize(1);
        assertThat(fallback.get(0).kind()).isEqualTo(JournalEntryKind.TICK_MARK);
        assertThat(harness.appliedCount()).isEqualTo(1);
        assertThat(harness.unknownCount()).isEqualTo(1);
    }
}
