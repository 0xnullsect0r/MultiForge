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

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.level.ServerLevel;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.neoforge.RegionizedTickCoordinator;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.NewChunkHolder;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.ownership.Domain;
import net.multiforge.runtime.ownership.OwnerToken;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.ScheduledTickRunner;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Vanilla-backed {@link ScheduledTickRunner} implementation (B3.2, docs/
 * design/m13-b3-region-tick.md §5.1). Walks {@link
 * Region#ownedChunkSnapshot()} for the region being ticked and, for each
 * owned chunk with a live {@link NewChunkHolder}, drains that chunk's
 * scheduled block/fluid ticks via {@code
 * ServerLevel.mfTickBlockFluidTicksForChunk} — the wrap-and-rename
 * extraction the {@code 02-region-tick/net/minecraft/server/level/
 * ServerLevel.java.patch} / {@code LevelTicks.java.patch} hunks expose.
 *
 * <p>Registered once from {@code MultiForgeGlobalSystemsInit.install} on
 * {@code ServerAboutToStart} via {@link
 * MultiThreadedSchedulerHost#setBlockFluidRunner}. {@link #installOnEventBus}
 * additionally wires the {@code LevelEvent.Load}/{@code Unload} listeners
 * this bridge needs to resolve a {@link WorldRef} back to its live {@link
 * ServerLevel} — the {@code multiforge-runtime} module is MC-free and has
 * no such mapping of its own (only {@code WorldRef → RegionId} via {@code
 * MultiThreadedSchedulerHost#worldForRegion}), so the fork bridge owns
 * this side table itself, the same registration shape {@code
 * MultiForgeGlobalSystemsInit} already uses for the B2low/B2high per-level
 * targets.
 */
public final class ScheduledTickRunnerBridge implements ScheduledTickRunner {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    // Keyed by WorldRef#dimensionId() (a plain String) rather than WorldRef
    // itself — WorldRef is an interface with multiple possible
    // implementations, and dimensionId() equality is the one contract the
    // interface itself documents (see WorldRef's javadoc).
    private static final ConcurrentMap<String, ServerLevel> LEVELS = new ConcurrentHashMap<>();

    public ScheduledTickRunnerBridge() {}

    /**
     * Register {@link LevelEvent.Load}/{@link LevelEvent.Unload} listeners
     * on {@link NeoForge#EVENT_BUS} that keep {@link #LEVELS} in sync with
     * Vanilla's own level lifecycle. Idempotent — a second and later call
     * does nothing (mirrors {@code RegionizedChunkLifecycle#installOnEventBus}'s
     * shape, needed for the same reason: a dedi {@code GameTestServer}
     * reuses one JVM across successive server instances).
     */
    public static void installOnEventBus() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        NeoForge.EVENT_BUS.addListener((LevelEvent.Load event) -> {
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            LEVELS.put(RegionizedTickCoordinator.asWorldRef(level).dimensionId(), level);
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            LEVELS.remove(RegionizedTickCoordinator.asWorldRef(level).dimensionId());
        });
    }

    /** Test-only: reset the installed flag so a fresh test JVM can re-install. */
    static void resetForTesting() {
        INSTALLED.set(false);
        LEVELS.clear();
    }

    @Override
    public void runBlockFluidTicks(Region region) {
        // Guard (docs/design/m13-b3-region-tick.md §5's part 4): confirm this
        // call is actually running on the owning region's own worker thread
        // before touching any ServerLevel state. In production this always
        // holds — TickRegionScheduler wraps every phase body in
        // OwnerToken.runAs(OwnerToken.forRegion(region.id())) — but a stale
        // Region reference reused off-thread (a caller bug, not a runtime
        // one) must reroute+warn rather than tick foreign state
        // (CLAUDE.md rule 5).
        OwnerToken token = OwnerToken.current();
        if (token.domain() != Domain.REGION || token.regionId() != region.id().value()) {
            ProbeRegistry.bump("region-tick.block-fluid.off-thread");
            ViolationLogger.warn(
                    "ScheduledTickRunnerBridge.runBlockFluidTicks",
                    "called for " + region.id() + " off its owning worker thread (current=" + token + ") — skipping");
            return;
        }

        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return; // runtime not installed — nothing to resolve against
        WorldRef world = host.worldForRegion(region.id());
        if (world == null) return; // region died since this phase was scheduled
        ServerLevel level = LEVELS.get(world.dimensionId());
        if (level == null) return; // level not (yet) registered — LevelEvent.Load hasn't fired
        ChunkHolderManager manager = host.chunkManagerForOrNull(world);
        if (manager == null) return; // no chunk shadowed for this world yet

        List<ChunkPos> owned = region.ownedChunkSnapshot();
        for (ChunkPos pos : owned) {
            NewChunkHolder holder = manager.holderAt(pos);
            if (holder == null) continue; // holder unloaded between snapshot and this loop turn
            level.mfTickBlockFluidTicksForChunk(holder);
        }
    }
}
