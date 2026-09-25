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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.multiforge.api.world.WorldRef;
import net.multiforge.neoforge.RegionizedTickCoordinator;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;

/**
 * Fork-local façade the {@code multiforge-patches/02-region-tick/net/
 * minecraft/world/level/Level.java.patch} hunks call into — mirrors the
 * {@link RegionizedTickCoordinator} facade shape (patched Vanilla code references a stable fork-local API; the
 * actual runtime behind it is swapped out from behind without editing
 * any patch file).
 *
 * <p>Routes every {@code TickingBlockEntity} Vanilla ever adds to a
 * {@link Level}'s {@code blockEntityTickers} list into the owning
 * region's {@code HolderManagerRegionData.blockEntityTickers} slice
 * (B3.1), so B3.4's per-region {@code BLOCK_ENTITIES} phase body
 * (see {@code net.multiforge.runtime.region.BlockEntityTickRunner} and
 * {@code MultiThreadedSchedulerHost#phaseBlockEntitiesTickPerRegion})
 * actually has something to tick (docs/design/m13-b3-region-tick.md
 * §5.3).
 *
 * <p><b>Readiness, and why {@link #isInstalled} exists.</b> A world's
 * block entities become per-region-ticked only once {@link
 * #installOnLevel} has run for it — that call both (a) marks the world
 * "installed" so future {@link #onTickerAdded} calls actually route,
 * and (b) backfills every ticker Vanilla already registered before
 * this bridge attached (chunks that finished loading during an early
 * boot window, before {@code LevelEvent.Load} fired for this level).
 * {@link RegionizedTickCoordinator#regionsHandleBlockEntities} — the
 * guard the patch's {@code tickBlockEntities()} hunk consults every
 * tick — checks this same "installed" flag, so the add-time bridge and
 * the per-tick skip-guard are always consistent with each other: a
 * ticker added before installation stays on the Vanilla-inline
 * fallback path only until installation's backfill sweep picks it up
 * (in the same call), never permanently.
 *
 * <p><b>Split/merge is not this bridge's job.</b> Once a ticker is in
 * some region's {@code HolderManagerRegionData.blockEntityTickers}
 * slice, keeping it correctly assigned across a region split or merge
 * is already handled by {@code ChunkHolderManager}'s own {@code
 * RegionListener} wiring (B3.1's {@code HolderManagerRegionData
 * .split}/{@code .merge}, invoked from {@code ChunkHolderManager
 * .onRegionSplit}/{@code .onRegionsMerging}, both already registered
 * on every world's regionizer by {@code MultiThreadedSchedulerHost
 * .regionizerFor}). Registering a second {@link
 * net.multiforge.runtime.region.RegionListener} here to redo that work
 * would be a second source of truth for the same invariant CLAUDE.md's
 * M9 conventions warn against — this bridge only ever assigns a
 * ticker's <em>initial</em> owning region, at add/backfill time.
 */
public final class BlockEntityTickerBridge {
    /** Worlds this bridge has installed on — see the class javadoc's readiness note. */
    private static final Set<WorldRef> INSTALLED = ConcurrentHashMap.newKeySet();

    private BlockEntityTickerBridge() {}

    /**
     * @return {@code true} if {@link #installOnLevel} has run for
     *         {@code level}'s world — the {@code tickBlockEntities()} patch
     *         hunk's per-tick skip-guard, via {@link
     *         RegionizedTickCoordinator#regionsHandleBlockEntities}.
     */
    public static boolean isInstalled(ServerLevel level) {
        return INSTALLED.contains(RegionizedTickCoordinator.asWorldRef(level));
    }

    /**
     * Called from {@code RegionRuntimeInit} on {@code LevelEvent.Load}: from
     * now on {@code level}'s block entities tick in their regions. Tickers the
     * level already holds are routed by the level's next {@code
     * tickBlockEntities} call ({@link #routePending}).
     */
    public static void installOnLevel(ServerLevel level) {
        INSTALLED.add(RegionizedTickCoordinator.asWorldRef(level));
    }

    /** Called from {@code RegionRuntimeInit} on {@code LevelEvent.Unload}. */
    public static void uninstallLevel(ServerLevel level) {
        INSTALLED.remove(RegionizedTickCoordinator.asWorldRef(level));
    }

    /**
     * Called from the patched {@code Level.addBlockEntityTicker}
     * (`multiforge-patches/02-region-tick/net/minecraft/world/level/
     * Level.java.patch`, hunk A) immediately after Vanilla adds {@code
     * ticker} to its own list. A no-op — the ticker simply stays on the
     * Vanilla-inline fallback path — until {@link #installOnLevel} has
     * run for this level's world.
     */
    public static boolean onTickerAdded(Level level, TickingBlockEntity ticker) {
        if (!(level instanceof ServerLevel serverLevel)) return false; // block entities only tick server-side
        WorldRef world = RegionizedTickCoordinator.asWorldRef(serverLevel);
        if (!INSTALLED.contains(world)) return false;
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return false;
        return registerTicker(serverLevel, ticker, host, world);
    }

    /**
     * Resolves {@code ticker}'s owning region from its block position
     * and registers a {@link VanillaTickingBlockEntityAdapter} wrapping
     * it against that region's {@code HolderManagerRegionData}. A
     * chunk not yet claimed by any region (no {@code ChunkEvent.Load}
     * hook has fired for it yet) or a world with no materialised
     * {@link ChunkHolderManager} both degrade to "not routed this
     * time" rather than an error — the Vanilla-inline fallback still
     * ticks this ticker in the meantime.
     */
    private static boolean registerTicker(
            Level level, TickingBlockEntity ticker, MultiThreadedSchedulerHost host, WorldRef world) {
        try {
            ThreadedRegionizer regionizer = host.regionizerForOrNull(world);
            if (regionizer == null) return false;
            BlockPos pos = ticker.getPos();
            Region region = regionizer.regionAtChunk(pos.getX() >> 4, pos.getZ() >> 4);
            if (region == null) return false;
            ChunkHolderManager manager = host.chunkManagerForOrNull(world);
            if (manager == null) return false;
            manager.regionData(region.id())
                    .addBlockEntityTicker(new VanillaTickingBlockEntityAdapter(level, ticker));
            return true;
        } catch (Throwable t) {
            ProbeRegistry.bump("block-entities.ticker-add.failure");
            ViolationLogger.warn(
                    "BlockEntityTickerBridge.registerTicker",
                    "failed to route ticker at " + ticker.getPos() + " in " + world.dimensionId() + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * Retry routing tickers the level still holds itself — added before their
     * chunk was regionized (a chunk registers its block entities just before
     * {@code ChunkEvent.Load} regionizes it). Server thread only, from {@code
     * Level.tickBlockEntities}; returns the tickers still unroutable, which
     * stay on the level's list for the next attempt.
     */
    public static java.util.List<TickingBlockEntity> routePending(ServerLevel level, java.util.List<TickingBlockEntity> tickers) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return tickers;
        WorldRef world = RegionizedTickCoordinator.asWorldRef(level);
        java.util.List<TickingBlockEntity> left = new java.util.ArrayList<>();
        for (TickingBlockEntity ticker : tickers) {
            if (ticker.isRemoved()) continue;
            if (!registerTicker(level, ticker, host, world)) left.add(ticker);
        }
        return left;
    }

    /**
     * Clears all installed-world state. Called from {@code
     * ServerLifecycleHooks.handleServerStopped} so a subsequent fresh
     * install (next {@code GameTestServer} instance, same JVM) starts
     * from a known-clean state; also usable directly from tests.
     */
    public static void unbind() {
        INSTALLED.clear();
    }
}
