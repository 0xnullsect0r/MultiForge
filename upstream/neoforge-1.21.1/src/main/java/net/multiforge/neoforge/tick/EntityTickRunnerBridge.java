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
package net.multiforge.neoforge.tick;

import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.multiforge.api.world.WorldRef;
import net.multiforge.neoforge.RegionizedTickCoordinator;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.NewChunkHolder;
import net.multiforge.runtime.diagnostics.ViolationLogger;
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
 * <p>Registered exactly once, from {@code MultiForgeGlobalSystemsInit
 * .install} on {@code ServerAboutToStart} (the same lifecycle point
 * every other B2/B3 fork bridge binds at), via {@link
 * MultiThreadedSchedulerHost#setEntityTickRunner}.
 *
 * <p><b>Shape.</b> {@link #tickEntitiesForRegion} resolves {@code
 * region}'s owning {@link WorldRef} (via {@link
 * MultiThreadedSchedulerHost#worldForRegion}), then that world's live
 * {@link ServerLevel} (a linear scan of {@link
 * MinecraftServer#getAllLevels()} — off the tick-hot path in the
 * sense that this runs once per region per tick, not once per entity,
 * matching {@code ChunkHolderManager.holdersOwnedBy}'s own "called a
 * few times per region per tick" cost model), then walks every {@link
 * NewChunkHolder} {@link ChunkHolderManager#holdersOwnedBy(net.multiforge.runtime.region.RegionId)}
 * reports for this region and calls the patched {@code ServerLevel
 * .mfTickEntitiesForChunk(holder)} for each — which ticks that
 * chunk's entities using the exact same {@code checkDespawn} +
 * {@code DistanceManager.inEntityTickingRange}-gated {@code
 * guardEntityTick(tickNonPassenger)} pass Vanilla's {@code
 * entityTickList.forEach} used to run inline (see the {@code
 * 02-region-tick/ServerLevel.java.patch} hunk's javadoc for the
 * extraction). Any entity mid-migration ({@code MigratingEntityRef
 * .migrationState() == MIGRATING}) is skipped by that same patched
 * method — this bridge does not need to re-check it.
 *
 * <p>Every step degrades to "do nothing for this region this tick"
 * rather than throwing (CLAUDE.md rule 5): a region that died between
 * the ENTITY_AI phase starting and this call ({@code worldForRegion}
 * returns {@code null}), a world with no live {@link ServerLevel} yet
 * (bootstrap window), or a {@link ChunkHolderManager} that has not
 * materialised for this world are all "nothing to tick yet," not
 * errors — matching the region→world/chunk-manager lookups every
 * other {@code MultiThreadedSchedulerHost} phase body already treats
 * this way (e.g. {@code phasePollFullLoadUpdate}).
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
        ChunkHolderManager manager = host.chunkManagerForOrNull(world);
        if (manager == null) return;

        for (NewChunkHolder holder : manager.holdersOwnedBy(region.id())) {
            try {
                level.mfTickEntitiesForChunk(holder);
            } catch (Throwable t) {
                // Auto-reroute+warn (CLAUDE.md rule 5): one chunk's entity
                // pass failing (a mod's Entity#tick throwing past Vanilla's
                // own guardEntityTick isolation — should not happen, but a
                // bridge-level defense costs nothing) must not stop the
                // rest of this region's chunks from ticking this pass.
                ViolationLogger.warn(
                        "entity-ai.chunk-tick-failure",
                        "mfTickEntitiesForChunk(" + holder.position() + ") failed for " + region.id() + " in "
                                + world.dimensionId() + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    /**
     * Linear scan of {@link MinecraftServer#getAllLevels()} matching
     * {@code world} by {@link RegionizedTickCoordinator#asWorldRef}.
     * Not cached: the set of loaded levels is small (a handful of
     * dimensions) and essentially static after boot, and this runs at
     * most once per region per tick — not once per chunk or per
     * entity — so the cost is negligible next to the entity iteration
     * itself.
     */
    private ServerLevel resolveLevel(WorldRef world) {
        for (ServerLevel level : server.getAllLevels()) {
            if (RegionizedTickCoordinator.asWorldRef(level).equals(world)) {
                return level;
            }
        }
        return null;
    }
}
