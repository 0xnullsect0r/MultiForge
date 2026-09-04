/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.journal;

import java.util.Objects;
import net.multiforge.runtime.region.RegionId;

/**
 * A single write-ahead-log entry. Ordered per region by
 * {@link #sequence}. The {@code payload} is an opaque byte array the
 * M6 patch fills with a serialized {@code CompoundTag} (or similar);
 * pure-Java code treats it as a UTF-8 blob for tests.
 */
public record JournalEntry(RegionId region, long sequence, JournalEntryKind kind, byte[] payload) {

    public JournalEntry {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(payload, "payload");
    }

    public int payloadSize() {
        return payload.length;
    }
}
