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
package net.multiforge.runtime.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PerChunkTicketsTest {

    @Test
    void minDistanceReflectsBestTicket() {
        PerChunkTickets t = new PerChunkTickets();
        assertThat(t.minDistance()).isEqualTo(Integer.MAX_VALUE);
        assertThat(t.effectiveLevel()).isEqualTo(ChunkLoadLevel.INACCESSIBLE);

        t.add(Ticket.of(TicketType.PLUGIN, "a")); // BORDER (33)
        assertThat(t.minDistance()).isEqualTo(33);
        assertThat(t.effectiveLevel()).isEqualTo(ChunkLoadLevel.BORDER);

        t.add(Ticket.of(TicketType.PLAYER, "player-1")); // ENTITY_TICKING (31)
        assertThat(t.minDistance()).isEqualTo(31);
        assertThat(t.effectiveLevel()).isEqualTo(ChunkLoadLevel.ENTITY_TICKING);
    }

    @Test
    void ticketDeduplicatedByTypeDistanceAndKey() {
        PerChunkTickets t = new PerChunkTickets();
        Ticket a = Ticket.of(TicketType.PLUGIN, "same-key");
        Ticket b = Ticket.of(TicketType.PLUGIN, "same-key");
        assertThat(t.add(a)).isTrue();
        assertThat(t.add(b)).isFalse();
        assertThat(t.size()).isEqualTo(1);
    }

    @Test
    void enderPearlPerEntityKeyIsolated() {
        PerChunkTickets t = new PerChunkTickets();
        t.add(Ticket.of(TicketType.ENDER_PEARL, 42));
        t.add(Ticket.of(TicketType.ENDER_PEARL, 43));
        assertThat(t.size()).isEqualTo(2);
        t.remove(Ticket.of(TicketType.ENDER_PEARL, 42));
        assertThat(t.size()).isEqualTo(1);
        assertThat(t.contains(TicketType.ENDER_PEARL, 43)).isTrue();
        assertThat(t.contains(TicketType.ENDER_PEARL, 42)).isFalse();
    }
}
