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
