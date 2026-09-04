/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.journal;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

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
