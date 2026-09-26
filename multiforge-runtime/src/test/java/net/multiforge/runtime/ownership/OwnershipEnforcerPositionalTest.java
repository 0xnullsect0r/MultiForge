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
package net.multiforge.runtime.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.multiforge.api.world.WorldRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link OwnershipEnforcer#canMutateAt}/{@link OwnershipEnforcer#rerouteAt}: per-chunk ownership. */
class OwnershipEnforcerPositionalTest {

    private static final WorldRef WORLD = WorldRef.of("test:positional");

    /** Chunks with x < 0 belong to region 1, x >= 100 are unowned, the rest to region 2. */
    private final List<String> queued = new ArrayList<>();

    private final OwnershipEnforcer.PositionRouter router = new OwnershipEnforcer.PositionRouter() {
        @Override
        public long ownerOf(WorldRef world, int chunkX, int chunkZ) {
            if (chunkX >= 100) return UNOWNED;
            return chunkX < 0 ? 1L : 2L;
        }

        @Override
        public boolean queueOnOwner(WorldRef world, int chunkX, int chunkZ, Runnable mutation) {
            long owner = ownerOf(world, chunkX, chunkZ);
            if (owner == UNOWNED) return false;
            queued.add("region-" + owner);
            return true;
        }

        @Override
        public long globalRegionId() {
            return 99L;
        }
    };

    @BeforeEach
    void bind() {
        OwnershipEnforcer.setModeForTesting(OwnershipEnforcer.Mode.REROUTE);
        OwnershipEnforcer.bindPositionRouter(router);
    }

    @AfterEach
    void unbind() {
        OwnershipEnforcer.unbindPositionRouter();
        OwnershipEnforcer.setModeForTesting(OwnershipEnforcer.Mode.REROUTE);
        OwnershipEnforcer.unbindTickThreadAndRerouteTarget();
    }

    private static boolean asRegion(long id, int chunkX) {
        AtomicBoolean out = new AtomicBoolean();
        OwnerToken.runAs(
                OwnerToken.forRegion(id), () -> out.set(OwnershipEnforcer.canMutateAt("site", WORLD, chunkX, 0)));
        return out.get();
    }

    @Test
    void ownRegionMayMutateItsChunk() {
        assertThat(asRegion(1, -5)).isTrue();
        assertThat(asRegion(2, 5)).isTrue();
    }

    @Test
    void foreignRegionIsRefused() {
        assertThat(asRegion(1, 5)).isFalse();
        assertThat(asRegion(2, -5)).isFalse();
    }

    @Test
    void unownedChunkIsRefusedForARegionWorker() {
        assertThat(asRegion(1, 150)).isFalse();
    }

    @Test
    void globalRegionAndTickThreadAreAllowed() {
        AtomicBoolean global = new AtomicBoolean();
        OwnerToken.runAs(OwnerToken.GLOBAL, () -> global.set(OwnershipEnforcer.canMutateAt("site", WORLD, 5, 0)));
        assertThat(global.get()).isTrue();
        OwnershipEnforcer.bindTickThread(Thread.currentThread());
        assertThat(OwnershipEnforcer.canMutateAt("site", WORLD, 5, 0)).isTrue();
    }

    @Test
    void theGlobalRegionsOwnTokenMayWriteAnywhere() {
        // On a server the global region ticks under a REGION token for its own id.
        assertThat(asRegion(99, 5)).isTrue();
        assertThat(asRegion(99, -5)).isTrue();
        assertThat(asRegion(99, 150)).isTrue();
    }

    @Test
    void strictModeThrowsOnCrossRegion() {
        OwnershipEnforcer.setModeForTesting(OwnershipEnforcer.Mode.STRICT);
        assertThatThrownBy(() -> asRegion(1, 5)).isInstanceOf(OwnershipViolationException.class);
    }

    @Test
    void offModeAllowsEverything() {
        OwnershipEnforcer.setModeForTesting(OwnershipEnforcer.Mode.OFF);
        assertThat(asRegion(1, 5)).isTrue();
    }

    @Test
    void rerouteGoesToOwnerOrFallsBackToTheServerTarget() {
        AtomicInteger serverTarget = new AtomicInteger();
        OwnershipEnforcer.bindRerouteTarget(r -> serverTarget.incrementAndGet());
        OwnershipEnforcer.rerouteAt("site", WORLD, 5, 0, () -> {});
        OwnershipEnforcer.rerouteAt("site", WORLD, -5, 0, () -> {});
        OwnershipEnforcer.rerouteAt("site", WORLD, 150, 0, () -> {});
        assertThat(queued).containsExactly("region-2", "region-1");
        assertThat(serverTarget.get()).isEqualTo(1);
    }

    @Test
    void withoutARouterTheCheckDegradesToTheThreadCheck() {
        OwnershipEnforcer.unbindPositionRouter();
        assertThat(asRegion(1, 5)).isTrue();
    }
}
