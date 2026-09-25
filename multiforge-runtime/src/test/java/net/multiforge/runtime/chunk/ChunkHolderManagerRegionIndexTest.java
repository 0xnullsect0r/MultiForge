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

/**
 * The per-region holder index behind {@link ChunkHolderManager#holdersOwnedBy}:
 * it follows every owner change — re-creation after an unload, merges,
 * splits and drops.
 */
class ChunkHolderManagerRegionIndexTest {

    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");

    @Test
    void recreatingAHolderReownsIt() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId first = RegionId.next();
        RegionId second = RegionId.next();
        ChunkPos pos = new ChunkPos(3, 4);
        m.createHolder(pos, first);
        // The chunk unloads without its holder being dropped, then loads into another region.
        m.createHolder(pos, second);

        assertThat(m.holdersOwnedBy(first)).isEmpty();
        assertThat(m.holdersOwnedBy(second))
                .extracting(NewChunkHolder::position)
                .containsExactly(pos);
        assertThat(m.holderAt(pos).owningRegion()).isEqualTo(second);
    }

    @Test
    void mergeMovesEveryHolderToTheSurvivor() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId target = RegionId.next();
        RegionId source = RegionId.next();
        m.createHolder(new ChunkPos(0, 0), target);
        m.createHolder(new ChunkPos(1, 0), source);
        m.createHolder(new ChunkPos(2, 0), source);

        m.onRegionMerged(target, source);

        assertThat(m.holdersOwnedBy(source)).isEmpty();
        assertThat(m.holdersOwnedBy(target))
                .extracting(NewChunkHolder::position)
                .containsExactlyInAnyOrder(new ChunkPos(0, 0), new ChunkPos(1, 0), new ChunkPos(2, 0));
    }

    @Test
    void splitMovesOnlyTheLeavingChunks() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId source = RegionId.next();
        RegionId child = RegionId.next();
        m.createHolder(new ChunkPos(0, 0), source);
        m.createHolder(new ChunkPos(100, 0), source);

        m.onRegionSplit(source, child, pos -> pos.x() >= 100);

        assertThat(m.holdersOwnedBy(source))
                .extracting(NewChunkHolder::position)
                .containsExactly(new ChunkPos(0, 0));
        assertThat(m.holdersOwnedBy(child)).extracting(NewChunkHolder::position).containsExactly(new ChunkPos(100, 0));
    }

    @Test
    void droppingAnUnticketedHolderRemovesItFromTheIndex() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId region = RegionId.next();
        ChunkPos pos = new ChunkPos(5, 5);
        m.createHolder(pos, region);

        assertThat(m.dropHolderIfUnticketed(pos)).isTrue();
        assertThat(m.holderAt(pos)).isNull();
        assertThat(m.holdersOwnedBy(region)).isEmpty();
    }

    @Test
    void aTicketedHolderIsKept() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId region = RegionId.next();
        ChunkPos pos = new ChunkPos(6, 6);
        m.addTicket(region, pos, Ticket.of(TicketType.PLUGIN, "keep"));

        assertThat(m.dropHolderIfUnticketed(pos)).isFalse();
        assertThat(m.holdersOwnedBy(region))
                .extracting(NewChunkHolder::position)
                .containsExactly(pos);
    }
}
