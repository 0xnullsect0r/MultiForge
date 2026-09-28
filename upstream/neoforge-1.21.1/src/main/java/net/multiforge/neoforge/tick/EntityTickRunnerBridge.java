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
package net.multiforge.neoforge.tick;

import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.multiforge.api.world.WorldRef;
import net.multiforge.neoforge.RegionizedTickCoordinator;
import net.multiforge.runtime.region.EntityTickRunner;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;

/**
 * Vanilla-backed {@link EntityTickRunner} — the fork glue B3.3's
 * {@code ENTITY_AI} phase body ({@code MultiThreadedSchedulerHost
 * .phaseEntityAiTick}) invokes for every real region, once that
 * region's {@link net.multiforge.runtime.ownership.OwnerToken} guard
 * has confirmed the calling thread actually owns it (docs/design/
 * m13-b3-region-tick.md §5.2).
 *
 * <p>Registered exactly once, from {@code RegionRuntimeInit
 * .install} on {@code ServerAboutToStart} (the same lifecycle point
 * every other B2/B3 fork bridge binds at), via {@link
 * MultiThreadedSchedulerHost#setEntityTickRunner}.
 *
 * <p><b>Shape.</b> {@link #tickEntitiesForRegion} resolves {@code
 * region}'s owning {@link WorldRef} (via {@link
 * MultiThreadedSchedulerHost#worldForRegion}), then that world's live
 * {@link ServerLevel} (a linear scan of {@link
 * MinecraftServer#getAllLevels()}, once per region per tick), and calls
 * the patched {@code ServerLevel.mfTickEntitiesForRegion}: the region's
 * share of this tick's {@code entityTickList}, split by owning region on
 * the server thread before the regions run, ticked in Vanilla's list
 * order through the same {@code checkDespawn} + {@code
 * DistanceManager.inEntityTickingRange}-gated {@code
 * guardEntityTick(tickNonPassenger)} pass Vanilla ran inline (see the
 * {@code 02-region-tick/ServerLevel.java.patch} hunk's javadoc).
 *
 * <p>Every step degrades to "do nothing for this region this tick"
 * rather than throwing (CLAUDE.md rule 5): a region that died between
 * the ENTITY_AI phase starting and this call ({@code worldForRegion}
 * returns {@code null}) or a world with no live {@link ServerLevel} yet
 * (bootstrap window) is "nothing to tick yet," not an error. Entities
 * a region does not take are ticked by the server thread after the
 * regions ({@code ServerLevel.mfTickEntitiesAfterRegions}).
 */
public final class EntityTickRunnerBridge implements EntityTickRunner {
    private final MultiThreadedSchedulerHost host;
    private final MinecraftServer server;

    public EntityTickRunnerBridge(MultiThreadedSchedulerHost host, MinecraftServer server) {
        this.host = Objects.requireNonNull(host, "host");
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public void tickEntitiesForRegion(Region region) {
        WorldRef world = host.worldForRegion(region.id());
        if (world == null) {
            // Region died since the ENTITY_AI phase started this pass —
            // nothing left to tick, not an error.
            return;
        }
        ServerLevel level = resolveLevel(world);
        if (level == null) return;
        // Entity activation range: throttle this region's distant mobs, with the
        // region's last tick time for load shedding.
        net.multiforge.neoforge.world.EntityActivation.beginRegion(level, host.scheduler().lastTickNanos(region));
        try {
            level.mfTickEntitiesForRegion(region.id().value());
        } finally {
            net.multiforge.neoforge.world.EntityActivation.endRegion();
        }
    }

    private ServerLevel resolveLevel(WorldRef world) {
        for (ServerLevel level : server.getAllLevels()) {
            if (RegionizedTickCoordinator.asWorldRef(level).equals(world)) {
                return level;
            }
        }
        return null;
    }
}
