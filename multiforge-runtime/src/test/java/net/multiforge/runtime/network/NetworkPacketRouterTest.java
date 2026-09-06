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
package net.multiforge.runtime.network;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.entity.MigratingEntityRef;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionizedTaskQueue;
import net.multiforge.runtime.region.ThreadedRegionizer;
import org.junit.jupiter.api.Test;

class NetworkPacketRouterTest {

    private static final WorldRef OW = WorldRef.of("minecraft:overworld");
    private static final WorldRef GLOBAL = WorldRef.of("multiforge:global");

    @Test
    void gameplayPacketLandsOnPlayerRegion() {
        ThreadedRegionizer overworld = new ThreadedRegionizer(OW, 0);
        Region regionA = overworld.addChunk(new ChunkPos(0, 0));
        RegionizedTaskQueue tq = new RegionizedTaskQueue((w, x, z) -> overworld.regionAtChunk(x, z));
        NetworkPacketRouter router = new NetworkPacketRouter(tq, GLOBAL);
        MigratingEntityRef player = new MigratingEntityRef(UUID.randomUUID(), OW, new ChunkPos(0, 0));

        AtomicInteger ran = new AtomicInteger();
        router.routeToPlayer(player, ran::incrementAndGet);
        assertThat(tq.inboxSize(regionA)).isEqualTo(1);
        tq.drain(regionA, Integer.MAX_VALUE);
        assertThat(ran.get()).isEqualTo(1);
    }

    @Test
    void playerCrossingBorderCausesNextPacketToLandOnNewRegion() {
        ThreadedRegionizer overworld = new ThreadedRegionizer(OW, 0);
        Region regionA = overworld.addChunk(new ChunkPos(0, 0));
        Region regionB = overworld.addChunk(new ChunkPos(100, 100));
        RegionizedTaskQueue tq = new RegionizedTaskQueue((w, x, z) -> overworld.regionAtChunk(x, z));
        NetworkPacketRouter router = new NetworkPacketRouter(tq, GLOBAL);
        MigratingEntityRef player = new MigratingEntityRef(UUID.randomUUID(), OW, new ChunkPos(0, 0));

        AtomicInteger ranOnA = new AtomicInteger();
        AtomicInteger ranOnB = new AtomicInteger();
        router.routeToPlayer(player, ranOnA::incrementAndGet);

        // Simulate the player crossing the border.
        player.beginMigration();
        player.completeMigration(OW, new ChunkPos(100, 100));
        router.routeToPlayer(player, ranOnB::incrementAndGet);

        assertThat(tq.inboxSize(regionA)).isEqualTo(1);
        assertThat(tq.inboxSize(regionB)).isEqualTo(1);
        tq.drain(regionA, Integer.MAX_VALUE);
        tq.drain(regionB, Integer.MAX_VALUE);
        assertThat(ranOnA.get()).isEqualTo(1);
        assertThat(ranOnB.get()).isEqualTo(1);
    }

    @Test
    void globalPacketLandsOnGlobalRegion() {
        ThreadedRegionizer overworld = new ThreadedRegionizer(OW, 0);
        ThreadedRegionizer globalRz = new ThreadedRegionizer(GLOBAL, 0);
        Region global = globalRz.addChunk(new ChunkPos(0, 0));
        RegionizedTaskQueue tq =
                new RegionizedTaskQueue((w, x, z) -> GLOBAL.dimensionId().equals(w.dimensionId())
                        ? globalRz.regionAtChunk(x, z)
                        : overworld.regionAtChunk(x, z));
        NetworkPacketRouter router = new NetworkPacketRouter(tq, GLOBAL);

        AtomicInteger ran = new AtomicInteger();
        router.routeToGlobal(ran::incrementAndGet);
        assertThat(tq.inboxSize(global)).isEqualTo(1);
        tq.drain(global, Integer.MAX_VALUE);
        assertThat(ran.get()).isEqualTo(1);
    }
}
