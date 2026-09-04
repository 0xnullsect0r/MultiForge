/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.chunk;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The set of {@link Ticket tickets} currently keeping one chunk
 * loaded. The chunk's effective load {@link ChunkLoadLevel} is the
 * one derived from {@link #minDistance()}: the numerically smallest
 * ticket distance across the set.
 */
public final class PerChunkTickets {

    private final Set<Ticket> tickets = new HashSet<>(2);

    /** @return {@code true} if this ticket was not already present. */
    public boolean add(Ticket ticket) {
        return tickets.add(ticket);
    }

    /** @return {@code true} if the ticket was present and removed. */
    public boolean remove(Ticket ticket) {
        return tickets.remove(ticket);
    }

    /** @return {@code true} if a ticket of the given type/key is present at any distance. */
    public boolean contains(TicketType type, Object key) {
        for (Ticket t : tickets) {
            if (t.type().equals(type) && t.key().equals(key)) return true;
        }
        return false;
    }

    public int size() {
        return tickets.size();
    }

    public boolean isEmpty() {
        return tickets.isEmpty();
    }

    /** @return the smallest {@code distance} across the set, or {@link Integer#MAX_VALUE} if empty. */
    public int minDistance() {
        int min = Integer.MAX_VALUE;
        for (Ticket t : tickets) {
            if (t.distance() < min) min = t.distance();
        }
        return min;
    }

    public ChunkLoadLevel effectiveLevel() {
        int min = minDistance();
        if (min == Integer.MAX_VALUE) return ChunkLoadLevel.INACCESSIBLE;
        return ChunkLoadLevel.forDistance(min);
    }

    /** Unmodifiable snapshot for testing / diagnostics. */
    public Set<Ticket> snapshot() {
        return Collections.unmodifiableSet(new HashSet<>(tickets));
    }
}
