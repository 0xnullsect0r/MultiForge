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
package net.multiforge.runtime.journal;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.multiforge.runtime.region.RegionId;

/**
 * Boot-time replayer for a {@link RegionJournal}. Callers register a
 * handler per {@link JournalEntryKind} and call {@link #replay(RegionJournal)}
 * to walk every committed entry in order.
 *
 * <p>Unknown kinds (a newer journal opened by an older build) are
 * routed to {@link #onUnknown(Consumer)} — the caller decides whether
 * to skip or bail. The default is skip-with-count.
 *
 * <p>This is the crash-recovery entry point: on boot we open each
 * {@code region-<id>.mjl}, replay every entry that was committed
 * before the crash, and then resume ticking. Because
 * {@link RegionJournal#append(JournalEntryKind, byte[])} fsyncs before
 * returning, any entry present in the file was durable at commit time.
 */
public final class JournalReplayHarness {

    private final Map<JournalEntryKind, Consumer<JournalEntry>> handlers = new EnumMap<>(JournalEntryKind.class);
    private int unknownCount;
    private int appliedCount;
    private Consumer<JournalEntry> unknown = e -> {};

    public JournalReplayHarness on(JournalEntryKind kind, Consumer<JournalEntry> handler) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(handler, "handler");
        handlers.put(kind, handler);
        return this;
    }

    public JournalReplayHarness onUnknown(Consumer<JournalEntry> handler) {
        this.unknown = Objects.requireNonNull(handler, "handler");
        return this;
    }

    /** Number of entries with a matching handler that were dispatched. */
    public int appliedCount() {
        return appliedCount;
    }

    /** Number of entries with no matching handler (routed to {@link #onUnknown}). */
    public int unknownCount() {
        return unknownCount;
    }

    /**
     * Boot-time helper: walk every {@code region-*.mjl} file in
     * {@code journalDir} and dispatch its entries through {@code
     * harness}. Files whose name does not parse as a region id are
     * ignored. Returns the total number of entries walked across every
     * file. A missing (or empty) directory returns 0.
     *
     * <p>Ordering is per-file (files themselves are visited in
     * directory order). Callers that need a cross-region total order
     * must re-sort in their own handler (Vanilla worlds don't rely on
     * cross-region ordering; each region is independent).
     *
     * <p>Wired by Phase 5.4 into
     * {@link net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost#replayJournalsFromDir(Path)}
     * as the boot-time recovery entry point.
     */
    public static int replayAll(Path journalDir, JournalReplayHarness harness) throws IOException {
        Objects.requireNonNull(journalDir, "journalDir");
        Objects.requireNonNull(harness, "harness");
        if (!Files.isDirectory(journalDir)) return 0;
        int total = 0;
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(journalDir, "region-*.mjl")) {
            for (Path p : stream) files.add(p);
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            // Expect "region-<id>.mjl"; skip anything the glob let through by shape.
            if (!name.startsWith("region-") || !name.endsWith(".mjl")) continue;
            String idPart = name.substring("region-".length(), name.length() - ".mjl".length());
            long id;
            try {
                id = Long.parseLong(idPart);
            } catch (NumberFormatException e) {
                continue;
            }
            try (RegionJournal j = new RegionJournal(new RegionId(id), file)) {
                total += harness.replay(j);
            }
        }
        return total;
    }

    /**
     * Replay every committed entry in {@code journal} in order.
     *
     * @return the total number of entries walked.
     */
    public int replay(RegionJournal journal) throws IOException {
        Objects.requireNonNull(journal, "journal");
        int total = 0;
        try (var it = journal.replay()) {
            while (it.hasNext()) {
                JournalEntry entry = it.next();
                total++;
                Consumer<JournalEntry> handler = handlers.get(entry.kind());
                if (handler != null) {
                    handler.accept(entry);
                    appliedCount++;
                } else {
                    unknown.accept(entry);
                    unknownCount++;
                }
            }
        }
        return total;
    }
}
