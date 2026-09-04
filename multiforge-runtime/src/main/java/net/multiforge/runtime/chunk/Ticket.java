/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.chunk;

import java.util.Objects;

/**
 * A single ticket keeping one chunk loaded. Carries a {@link
 * TicketType}, an explicit {@code distance} (which may override the
 * type's default), and an opaque {@code key} used to deduplicate
 * tickets of the same kind — e.g. Folia's ender-pearl ticket keys on
 * entity id.
 *
 * <p>Two tickets are equal iff their type + distance + key are all
 * equal. This lets a {@link PerChunkTickets} set deduplicate and let
 * the caller re-add the "same" ticket idempotently.
 */
public record Ticket(TicketType type, int distance, Object key) {

    public Ticket {
        Objects.requireNonNull(type, "type");
        if (distance < 0 || distance > 64) {
            throw new IllegalArgumentException("distance out of range: " + distance);
        }
        Objects.requireNonNull(key, "key");
    }

    /** Ticket at the type's default distance. */
    public static Ticket of(TicketType type, Object key) {
        return new Ticket(type, type.defaultDistance(), key);
    }

    /** Ticket at an explicit distance. */
    public static Ticket at(TicketType type, int distance, Object key) {
        return new Ticket(type, distance, key);
    }
}
