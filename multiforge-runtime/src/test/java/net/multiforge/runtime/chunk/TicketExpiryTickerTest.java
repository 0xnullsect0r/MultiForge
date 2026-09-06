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

import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.RegionId;
import org.junit.jupiter.api.Test;

class TicketExpiryTickerTest {

    private static final WorldRef WORLD = WorldRef.of("test:world");

    @Test
    void ticketsWithoutTimeoutSurvive() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId r = RegionId.next();
        ChunkPos pos = new ChunkPos(0, 0);
        m.createHolder(pos, r);
        m.addTicket(r, pos, Ticket.of(TicketType.PLAYER, "p1", 0L));

        TicketExpiryTicker ticker = new TicketExpiryTicker(m);
        assertThat(ticker.runOnce(100L)).isZero();
        assertThat(m.ticketsFor(r).ticketsAt(pos).size()).isEqualTo(1);
    }

    @Test
    void expiredTicketRemovedAfterTimeout() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId r = RegionId.next();
        ChunkPos pos = new ChunkPos(0, 0);
        m.createHolder(pos, r);
        // POST_TELEPORT has a 5-tick timeout.
        m.addTicket(r, pos, Ticket.of(TicketType.POST_TELEPORT, "player-a", 0L));

        TicketExpiryTicker ticker = new TicketExpiryTicker(m);
        assertThat(ticker.runOnce(4L)).isZero();
        assertThat(m.ticketsFor(r).ticketsAt(pos).size()).isEqualTo(1);
        assertThat(ticker.runOnce(5L)).isEqualTo(1);
        assertThat(m.ticketsFor(r).ticketsAt(pos)).isNull();
    }

    @Test
    void unknownCreationTickTicketNeverExpires() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId r = RegionId.next();
        ChunkPos pos = new ChunkPos(0, 0);
        m.createHolder(pos, r);
        m.addTicket(r, pos, Ticket.of(TicketType.ENDER_PEARL, "pearl-1")); // no createdAtTick → -1

        TicketExpiryTicker ticker = new TicketExpiryTicker(m);
        assertThat(ticker.runOnce(9999L)).isZero();
    }

    @Test
    void expiryAcrossMultipleHoldersInSameRegion() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId r = RegionId.next();
        ChunkPos a = new ChunkPos(0, 0);
        ChunkPos b = new ChunkPos(1, 0);
        m.createHolder(a, r);
        m.createHolder(b, r);
        m.addTicket(r, a, Ticket.of(TicketType.POST_TELEPORT, "a", 0L));
        m.addTicket(r, b, Ticket.of(TicketType.POST_TELEPORT, "b", 0L));

        TicketExpiryTicker ticker = new TicketExpiryTicker(m);
        assertThat(ticker.runOnce(5L)).isEqualTo(2);
    }
}
