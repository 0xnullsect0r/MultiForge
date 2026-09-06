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
 * equal — {@code createdAtTick} is intentionally excluded from equality
 * so re-adding the "same" ticket is idempotent even when the tick
 * counter has advanced.
 *
 * <p>{@code createdAtTick} is set at construction time and read by
 * {@link TicketExpiryTicker} to compute {@code (createdAtTick +
 * type.timeoutTicks()) &lt;= now} for expiry. Value {@code -1} means
 * "unknown creation tick" (constructed outside a tick body — no
 * expiry applied).
 */
public record Ticket(TicketType type, int distance, Object key, long createdAtTick) {

    public Ticket {
        Objects.requireNonNull(type, "type");
        if (distance < 0 || distance > 64) {
            throw new IllegalArgumentException("distance out of range: " + distance);
        }
        Objects.requireNonNull(key, "key");
    }

    /**
     * Ticket at the type's default distance. Creation tick is unknown
     * (use {@link #of(TicketType, Object, long)} for expiring tickets).
     */
    public static Ticket of(TicketType type, Object key) {
        return new Ticket(type, type.defaultDistance(), key, -1L);
    }

    /** Ticket at the type's default distance, with a known creation tick for expiry accounting. */
    public static Ticket of(TicketType type, Object key, long createdAtTick) {
        return new Ticket(type, type.defaultDistance(), key, createdAtTick);
    }

    /** Ticket at an explicit distance. Creation tick is unknown. */
    public static Ticket at(TicketType type, int distance, Object key) {
        return new Ticket(type, distance, key, -1L);
    }

    /** Ticket at an explicit distance with a known creation tick. */
    public static Ticket at(TicketType type, int distance, Object key, long createdAtTick) {
        return new Ticket(type, distance, key, createdAtTick);
    }

    /**
     * @return true if the ticket has a finite timeout and would have
     *         expired by {@code now}. {@code -1} creation tick and
     *         {@code 0} timeout both mean "never expires" and return
     *         false regardless.
     */
    public boolean isExpiredAt(long now) {
        if (createdAtTick < 0 || type.timeoutTicks() == 0) return false;
        return now >= (createdAtTick + type.timeoutTicks());
    }
}
