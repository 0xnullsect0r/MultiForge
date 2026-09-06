/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.RegionId;
import org.junit.jupiter.api.Test;

class ChunkHolderManagerTest {

    private static final WorldRef WORLD = WorldRef.of("minecraft:overworld");

    @Test
    void addingPlayerTicketPromotesHolderToEntityTicking() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId r = new RegionId(1);
        ChunkPos pos = new ChunkPos(0, 0);
        m.createHolder(pos, r);

        m.addTicket(r, pos, Ticket.of(TicketType.PLAYER, "p1"));
        assertThat(m.holderAt(pos).level()).isEqualTo(ChunkLoadLevel.ENTITY_TICKING);
        assertThat(m.regionData(r).pendingFullLoadCount()).isEqualTo(1);
    }

    @Test
    void removingLastTicketDemotesHolderToInaccessible() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId r = new RegionId(2);
        ChunkPos pos = new ChunkPos(5, 5);

        Ticket ticket = Ticket.of(TicketType.PLUGIN, "held");
        m.addTicket(r, pos, ticket);
        assertThat(m.holderAt(pos).level()).isEqualTo(ChunkLoadLevel.BORDER);

        m.removeTicket(r, pos, ticket);
        assertThat(m.holderAt(pos).level()).isEqualTo(ChunkLoadLevel.INACCESSIBLE);
    }

    @Test
    void mergeMovesRegionDataAndReassignsOwnership() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId src = new RegionId(3);
        RegionId tgt = new RegionId(4);
        ChunkPos pos = new ChunkPos(1, 1);

        m.createHolder(pos, src);
        m.addTicket(src, pos, Ticket.of(TicketType.PLUGIN, "k"));
        m.markDirty(src, pos);

        m.onRegionMerged(tgt, src);
        assertThat(m.holderAt(pos).owningRegion()).isEqualTo(tgt);
        assertThat(m.regionData(tgt).autoSaveCount()).isEqualTo(1);
        assertThat(m.ticketsFor(tgt).chunkCount()).isEqualTo(1);
    }

    @Test
    void splitPeelsOffMatchingChunks() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId src = new RegionId(5);
        RegionId tgt = new RegionId(6);
        ChunkPos stay = new ChunkPos(0, 0);
        ChunkPos leave = new ChunkPos(10, 10);

        m.createHolder(stay, src);
        m.createHolder(leave, src);
        m.addTicket(src, stay, Ticket.of(TicketType.PLUGIN, "stay"));
        m.addTicket(src, leave, Ticket.of(TicketType.PLUGIN, "leave"));

        m.onRegionSplit(src, tgt, p -> p.equals(leave));
        assertThat(m.holderAt(stay).owningRegion()).isEqualTo(src);
        assertThat(m.holderAt(leave).owningRegion()).isEqualTo(tgt);
        assertThat(m.ticketsFor(src).chunkCount()).isEqualTo(1);
        assertThat(m.ticketsFor(tgt).chunkCount()).isEqualTo(1);
    }

    // === A1.4 — scheduleWhenHolderAt (docs/design/entity-migration.md §3.2, T8) ===============

    @Test
    void scheduleWhenHolderAtRunsInlineWhenAlreadyAtLevel() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId r = new RegionId(10);
        ChunkPos pos = new ChunkPos(0, 0);
        m.addTicket(r, pos, Ticket.of(TicketType.PLUGIN, "k")); // promotes straight to BORDER

        AtomicBoolean ran = new AtomicBoolean(false);
        m.scheduleWhenHolderAt(pos, ChunkLoadLevel.BORDER, () -> ran.set(true));
        assertThat(ran).isTrue();
    }

    @Test
    void scheduleWhenHolderAtDefersUntilPromoted() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId r = new RegionId(11);
        ChunkPos pos = new ChunkPos(1, 1);
        m.createHolder(pos, r); // holder exists but is still INACCESSIBLE

        AtomicBoolean ran = new AtomicBoolean(false);
        m.scheduleWhenHolderAt(pos, ChunkLoadLevel.BORDER, () -> ran.set(true), 2000, null);
        assertThat(ran).isFalse(); // not yet promoted — must not fire early

        m.addTicket(r, pos, Ticket.of(TicketType.PLUGIN, "k"));
        await().atMost(Duration.ofSeconds(3)).untilTrue(ran);
    }

    @Test
    void scheduleWhenHolderAtInvokesOnTimeoutWhenDeadlineElapses() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId r = new RegionId(12);
        ChunkPos pos = new ChunkPos(2, 2);
        m.createHolder(pos, r); // never promoted

        AtomicBoolean ran = new AtomicBoolean(false);
        AtomicBoolean timedOut = new AtomicBoolean(false);
        m.scheduleWhenHolderAt(pos, ChunkLoadLevel.BORDER, () -> ran.set(true), 50, () -> timedOut.set(true));

        await().atMost(Duration.ofSeconds(3)).untilTrue(timedOut);
        assertThat(ran).isFalse();
    }

    /**
     * Adversarial rapid promote/demote across BORDER (T8): a chunk that flickers across BORDER
     * before settling must never let the caller observe a materialize during a sub-BORDER window
     * — {@code task} fires at most once, and only while the holder is genuinely at/above BORDER.
     */
    @Test
    void scheduleWhenHolderAtNeverFiresDuringATransientSubBorderWindow() {
        ChunkHolderManager m = new ChunkHolderManager(WORLD);
        RegionId r = new RegionId(13);
        ChunkPos pos = new ChunkPos(3, 3);
        Ticket ticket = Ticket.of(TicketType.PLUGIN, "flicker");
        m.createHolder(pos, r);

        AtomicInteger fireCount = new AtomicInteger();
        AtomicBoolean observedSubBorderAtFire = new AtomicBoolean(false);

        // Flicker BORDER on/off a few times before settling on loaded.
        for (int i = 0; i < 3; i++) {
            m.addTicket(r, pos, ticket);
            m.removeTicket(r, pos, ticket);
        }
        m.scheduleWhenHolderAt(pos, ChunkLoadLevel.BORDER, () -> {
            fireCount.incrementAndGet();
            if (!m.holderAt(pos).level().isAtLeast(ChunkLoadLevel.BORDER)) {
                observedSubBorderAtFire.set(true);
            }
        });
        // Not yet fired — holder settled at INACCESSIBLE after the flicker loop above.
        assertThat(fireCount.get()).isEqualTo(0);

        m.addTicket(r, pos, ticket); // settle at BORDER for good
        await().atMost(Duration.ofSeconds(3)).until(() -> fireCount.get() >= 1);

        assertThat(observedSubBorderAtFire).isFalse();
    }
}
