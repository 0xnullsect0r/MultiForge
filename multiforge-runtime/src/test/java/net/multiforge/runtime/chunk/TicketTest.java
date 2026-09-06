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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for {@link Ticket#isExpiredAt}. Companion to
 * {@link TicketExpiryTickerTest} (which exercises the same predicate
 * end-to-end through the sweep). Phase 4 fix 4.8 wires Vanilla's
 * {@code createdTick} into MultiForge's {@code createdAtTick}, so the
 * expiry math this suite pins down is exactly what runs against
 * production tickets crossing {@link
 * net.multiforge.runtime.chunk.PerChunkTickets} via
 * {@code DistanceManagerBridge}.
 */
class TicketTest {

    @Test
    void unknownCreationTickNeverExpires() {
        Ticket t = Ticket.of(TicketType.POST_TELEPORT, "player-a"); // createdAtTick = -1
        assertThat(t.isExpiredAt(0L)).isFalse();
        assertThat(t.isExpiredAt(9999L)).isFalse();
        assertThat(t.isExpiredAt(Long.MAX_VALUE)).isFalse();
    }

    @Test
    void permanentTicketTypeNeverExpires() {
        // PLAYER has timeoutTicks == 0 → treat as never-expires even with
        // a real createdAtTick. Matches Vanilla: TicketType.timeout==0
        // suppresses the timedOut branch in DistanceManager.purgeStaleTickets.
        Ticket t = Ticket.of(TicketType.PLAYER, "p", 0L);
        assertThat(t.isExpiredAt(0L)).isFalse();
        assertThat(t.isExpiredAt(Long.MAX_VALUE)).isFalse();
    }

    @Test
    void expiresExactlyAtCreationPlusTimeout() {
        // POST_TELEPORT has a 5-tick timeout. Boundary is (createdAtTick + 5).
        Ticket t = Ticket.of(TicketType.POST_TELEPORT, "player-a", 100L);
        assertThat(t.isExpiredAt(100L)).isFalse();
        assertThat(t.isExpiredAt(104L)).isFalse();
        assertThat(t.isExpiredAt(105L)).isTrue();
        assertThat(t.isExpiredAt(106L)).isTrue();
    }

    @Test
    void expiresRelativeToCreationTick() {
        // Two POST_TELEPORT tickets created at different ticks reach the
        // 5-tick boundary at different "now" values — the shadow bridge
        // must therefore pass through each ticket's own createdTick,
        // not a shared bootstrap value.
        Ticket early = Ticket.of(TicketType.POST_TELEPORT, "e", 10L);
        Ticket late = Ticket.of(TicketType.POST_TELEPORT, "l", 200L);
        assertThat(early.isExpiredAt(15L)).isTrue();
        assertThat(late.isExpiredAt(15L)).isFalse();
        assertThat(late.isExpiredAt(205L)).isTrue();
    }

    @Test
    void enderPearlTicketExpiresAt40Ticks() {
        // Folia keys ender-pearl tickets to prevent global ticket-table
        // saturation; the 40-tick Vanilla timeout must still apply.
        Ticket t = Ticket.of(TicketType.ENDER_PEARL, 42, 1_000L);
        assertThat(t.isExpiredAt(1_039L)).isFalse();
        assertThat(t.isExpiredAt(1_040L)).isTrue();
    }

    @Test
    void zeroCreationTickIsValidNotSentinel() {
        // createdAtTick == 0 is a real tick (worlds start at 0), distinct
        // from -1 which means "unknown". A POST_TELEPORT at tick 0 must
        // expire at tick 5, not "never".
        Ticket t = Ticket.of(TicketType.POST_TELEPORT, "boot", 0L);
        assertThat(t.isExpiredAt(4L)).isFalse();
        assertThat(t.isExpiredAt(5L)).isTrue();
    }
}
