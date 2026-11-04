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
package net.multiforge.runtime.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.ownership.OwnerToken;
import net.multiforge.runtime.ownership.OwnershipEnforcer;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionTickBody;
import net.multiforge.runtime.region.TickRegionScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The host's own {@link OwnershipEnforcer.PositionRouter} (the one {@link
 * MultiThreadedSchedulerHost#install()} binds), driven through the enforcer the way
 * patched Vanilla sites drive it. {@code OwnershipEnforcerPositionalTest}
 * covers the enforcer against a stub router; this covers the real lookups:
 * regionizer sections, mailbox delivery, chunks loaded mid-tick, and which
 * level is ticking.
 */
class HostPositionRouterTest {

    private static final WorldRef WORLD = WorldRef.of("test:router");
    private static final WorldRef OTHER = WorldRef.of("test:router-other");
    /** Far enough apart to be separate regions at any allowed region size. */
    private static final int FAR = 4096;

    private MultiThreadedSchedulerHost host;
    private final List<Runnable> serverTarget = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ProbeRegistry.resetForTesting();
        OwnershipEnforcer.setModeForTesting(OwnershipEnforcer.Mode.REROUTE);
    }

    @AfterEach
    void tearDown() {
        if (host != null) host.close();
        OwnershipEnforcer.unbindTickThreadAndRerouteTarget();
        OwnershipEnforcer.setModeForTesting(OwnershipEnforcer.Mode.REROUTE);
    }

    private MultiThreadedSchedulerHost newHost(RegionTickBody body) {
        MultiForgeConfig config = MultiForgeConfig.defaults().withCores(1).withThreadsPerCore(2);
        host = new MultiThreadedSchedulerHost(config, body, TickRegionScheduler.Mode.BARRIER);
        // What install() binds, without also claiming the global ServerDomains slot.
        OwnershipEnforcer.bindPositionRouter(host.positionRouterForTesting());
        OwnershipEnforcer.bindRerouteTarget(serverTarget::add);
        return host;
    }

    private long ownerId(int chunkX) {
        Region r = host.regionizerFor(WORLD).regionAtChunk(chunkX, 0);
        return r == null ? OwnershipEnforcer.PositionRouter.UNOWNED : r.id().value();
    }

    private static boolean mayMutate(long regionId, int chunkX) {
        AtomicBoolean out = new AtomicBoolean();
        OwnerToken.runAs(
                OwnerToken.forRegion(regionId),
                () -> out.set(OwnershipEnforcer.canMutateAt("router-test", WORLD, chunkX, 0)));
        return out.get();
    }

    @Test
    void aRegionMayWriteItsOwnChunksAndNotAnothersOrAnUnownedOne() {
        newHost(region -> {});
        host.chunkLoaded(WORLD, 0, 0);
        host.chunkLoaded(WORLD, FAR, 0);
        long near = ownerId(0);
        long far = ownerId(FAR);
        assertThat(near).isNotEqualTo(far);

        assertThat(mayMutate(near, 0)).isTrue();
        assertThat(mayMutate(far, FAR)).isTrue();
        assertThat(mayMutate(near, FAR)).isFalse();
        assertThat(mayMutate(near, 2 * FAR)).isFalse(); // never loaded: no owner
    }

    @Test
    void aReroutedWriteLandsInTheOwnersMailboxAndAnUnownedOneOnTheServer() {
        newHost(region -> {});
        host.chunkLoaded(WORLD, 0, 0);
        host.chunkLoaded(WORLD, FAR, 0);
        Region far = host.regionizerFor(WORLD).regionAtChunk(FAR, 0);

        OwnerToken.runAs(OwnerToken.forRegion(ownerId(0)), () -> {
            OwnershipEnforcer.rerouteAt("router-test", WORLD, FAR, 0, () -> {});
            OwnershipEnforcer.rerouteAt("router-test", WORLD, 2 * FAR, 0, () -> {});
        });

        assertThat(host.taskQueue().inboxSize(far)).isEqualTo(1);
        assertThat(serverTarget).hasSize(1);
    }

    @Test
    void aChunkLoadedMidTickIsPendingUntilTheBarrierThenOwned() {
        AtomicReference<MultiThreadedSchedulerHost> self = new AtomicReference<>();
        ConcurrentHashMap<String, Boolean> seen = new ConcurrentHashMap<>();
        newHost(region -> {
            MultiThreadedSchedulerHost h = self.get();
            if (h.regionizerFor(WORLD).regionAtChunk(0, 0) != region) return;
            h.chunkLoaded(WORLD, 64, 0); // deferred: regions are ticking
            OwnershipEnforcer.PositionRouter router = h.positionRouterForTesting();
            seen.put("pending", router.isPendingRegistration(WORLD, 64, 0));
            seen.put("unowned", router.ownerOf(WORLD, 64, 0) == OwnershipEnforcer.PositionRouter.UNOWNED);
            seen.put("thisLevelTicking", router.regionsTicking(WORLD));
            seen.put("otherLevelTicking", router.regionsTicking(OTHER));
            seen.put("mayWrite", OwnershipEnforcer.canMutateAt("router-test", WORLD, 64, 0));
        });
        self.set(host);
        host.chunkLoaded(WORLD, 0, 0);

        host.driveRegions(WORLD, System.nanoTime() + 5_000_000_000L, () -> false);

        assertThat(seen)
                .containsEntry("pending", true)
                .containsEntry("unowned", true)
                .containsEntry("thisLevelTicking", true)
                .containsEntry("otherLevelTicking", false)
                .containsEntry("mayWrite", false);
        // Refused quietly: expected, not a cross-region violation.
        assertThat(ProbeRegistry.get("ownership.pending-registration")).isEqualTo(1);
        assertThat(ProbeRegistry.get("router-test:cross-region")).isZero();
        // The barrier registered it.
        OwnershipEnforcer.PositionRouter router = host.positionRouterForTesting();
        assertThat(router.isPendingRegistration(WORLD, 64, 0)).isFalse();
        assertThat(router.ownerOf(WORLD, 64, 0))
                .isEqualTo(ownerId(64))
                .isNotEqualTo(OwnershipEnforcer.PositionRouter.UNOWNED);
        assertThat(router.regionsTicking(OTHER)).isTrue(); // between level ticks: unknown, so conservatively yes
    }

    @Test
    void aChunkUnloadedLosesItsOwner() {
        newHost(region -> {});
        host.chunkLoaded(WORLD, 0, 0);
        long owner = ownerId(0);
        assertThat(mayMutate(owner, 0)).isTrue();

        host.chunkUnloaded(WORLD, 0, 0);

        assertThat(host.positionRouterForTesting().ownerOf(WORLD, 0, 0))
                .isEqualTo(OwnershipEnforcer.PositionRouter.UNOWNED);
        assertThat(mayMutate(owner, 0)).isFalse();
    }
}
