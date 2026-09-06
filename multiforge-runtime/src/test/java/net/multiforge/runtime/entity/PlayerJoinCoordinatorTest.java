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
package net.multiforge.runtime.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionizedTaskQueue;
import net.multiforge.runtime.region.ThreadedRegionizer;
import org.junit.jupiter.api.Test;

class PlayerJoinCoordinatorTest {

    private static final WorldRef OW = WorldRef.of("minecraft:overworld");
    private static final WorldRef GLOBAL = WorldRef.of("multiforge:global");

    @Test
    void playerJoinHopsGlobalThenSpawnRegion() {
        ThreadedRegionizer overworld = new ThreadedRegionizer(OW, 0);
        ThreadedRegionizer globalRz = new ThreadedRegionizer(GLOBAL, 0);
        Region globalRegion = globalRz.addChunk(new ChunkPos(0, 0));
        Region spawnRegion = overworld.addChunk(new ChunkPos(0, 0));

        RegionizedTaskQueue tq = new RegionizedTaskQueue((w, x, z) -> {
            if (w.dimensionId().equals(GLOBAL.dimensionId())) return globalRz.regionAtChunk(x, z);
            return overworld.regionAtChunk(x, z);
        });
        EntityRegistry registry = new EntityRegistry();
        PlayerJoinCoordinator join = new PlayerJoinCoordinator(tq, registry, GLOBAL, () -> {});

        UUID player = UUID.randomUUID();
        join.onPlayerLoginCompleted(player, OW, new BlockPos(0, 64, 0), "player-data");

        // Drain global first — it enqueues the hop to spawn region.
        tq.drain(globalRegion, Integer.MAX_VALUE);
        assertThat(registry.size()).isEqualTo(0); // not yet spawned
        tq.drain(spawnRegion, Integer.MAX_VALUE);

        EntityRegistry.Entry entry = registry.get(player);
        assertThat(entry).isNotNull();
        assertThat(entry.ref().world().dimensionId()).isEqualTo("minecraft:overworld");
        assertThat(entry.payload()).isEqualTo("player-data");
    }
}
