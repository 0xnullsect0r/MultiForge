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
package net.multiforge.neoforge.chunk;

import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import org.jetbrains.annotations.ApiStatus;

/**
 * Phase 4 task 4.3: observability-only bridge from Vanilla
 * {@link ServerChunkCache} into MultiForge's probe registry.
 *
 * <p>Every entry point here is a thin observer — it bumps a probe
 * counter and swallows any {@link Throwable} through
 * {@link ViolationLogger}. No state, no side effects on Vanilla
 * control flow, no cross-region blocking calls. The four
 * {@code observeXxx} / {@code beforeXxx} / {@code afterXxx} methods
 * are wired up by the {@code ServerChunkCache.java.patch} under
 * {@code multiforge-patches/04-chunk-system/} at the four choke
 * points identified by the M9 patch strategy doc — {@code getChunk},
 * the chunk-tick pass, {@code save}, and {@code close} — so Phase 5
 * (per-region tick fan-out + {@code MainThreadExecutor} excision)
 * can flip them into the real behaviour without editing another
 * Vanilla file.
 *
 * <p><b>Threading:</b> the observers may fire on the server main
 * thread ({@code getChunk} / {@code save} / {@code close} / {@code
 * tick}) or on any thread that reaches {@code getChunk} through a
 * mod-driven cross-thread call. Every operation done here — a
 * probe bump, a rate-limited warn — is lock-free and safe from any
 * thread, per the runtime's diagnostics contract.
 *
 * <p><b>Zero behaviour change today.</b> The patch hunks are strictly
 * additive: return values, control flow, and every side effect on
 * Vanilla state are preserved. Phase 5 replaces the observer bodies
 * (not the call sites) with real per-region dispatch.
 */
@ApiStatus.Internal
public final class ServerChunkCacheBridge {
    /** Probe name for {@link #observeGet}. */
    private static final String PROBE_GET = "mfservercc.observe.get";

    /** Probe name for {@link #afterTickChunks}. */
    private static final String PROBE_TICK_CHUNKS = "mfservercc.observe.tickChunks";

    /** Probe name for {@link #afterSave}. */
    private static final String PROBE_SAVE = "mfservercc.observe.save";

    /** Probe name for {@link #beforeClose}. */
    private static final String PROBE_CLOSE = "mfservercc.observe.close";

    private ServerChunkCacheBridge() {}

    /**
     * Observation hook fired at the entry of
     * {@link ServerChunkCache#getChunk(int, int, ChunkStatus, boolean)}
     * on the main-thread branch, before Vanilla / NeoForge's
     * {@code currentlyLoading} bypass. Never blocks, never throws
     * upward.
     *
     * @param cache        the invoking chunk cache; unused today, threaded
     *                     through so Phase 5 can key per-level state without another
     *                     patch pass.
     * @param chunkX       chunk-x from the caller.
     * @param chunkZ       chunk-z from the caller.
     * @param status       requested {@link ChunkStatus}.
     * @param requireChunk Vanilla's {@code p_8363_}; when true, a
     *                     missing chunk becomes a hard error downstream. Threaded
     *                     through for Phase 5 telemetry.
     */
    public static void observeGet(
            ServerChunkCache cache, int chunkX, int chunkZ, ChunkStatus status, boolean requireChunk) {
        try {
            ProbeRegistry.bump(PROBE_GET);
        } catch (Throwable t) {
            ViolationLogger.warn(
                    PROBE_GET, "observeGet threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Observation hook fired at the exit of
     * {@link ServerChunkCache#tick(BooleanSupplier, boolean)} after
     * the chunk-tick pass has run for one level tick. Never blocks,
     * never throws upward.
     *
     * @param cache       the invoking chunk cache.
     * @param hasTimeLeft Vanilla's {@code p_201913_} tick budget
     *                    supplier; threaded through so Phase 5's per-region
     *                    dispatcher can honour it.
     */
    public static void afterTickChunks(ServerChunkCache cache, BooleanSupplier hasTimeLeft) {
        try {
            ProbeRegistry.bump(PROBE_TICK_CHUNKS);
        } catch (Throwable t) {
            ViolationLogger.warn(
                    PROBE_TICK_CHUNKS,
                    "afterTickChunks threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Observation hook fired at the exit of
     * {@link ServerChunkCache#save(boolean)} after Vanilla has
     * drained its save pipeline. Phase 5 will fold
     * {@code AutoSaveRunner} per-region flushes in here.
     *
     * @param cache the invoking chunk cache.
     * @param flush Vanilla's {@code p_8420_}; when true, a
     *              synchronous flush was requested (shutdown / world save).
     */
    public static void afterSave(ServerChunkCache cache, boolean flush) {
        try {
            ProbeRegistry.bump(PROBE_SAVE);
        } catch (Throwable t) {
            ViolationLogger.warn(
                    PROBE_SAVE, "afterSave threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Observation hook fired at the entry of
     * {@link ServerChunkCache#close()}, before Vanilla's final
     * flush. Phase 5 will fold
     * {@code RegionShutdownCoordinator.awaitAll()} in here so
     * per-region workers drain before Vanilla tears down the
     * chunk map.
     *
     * @param cache the invoking chunk cache.
     */
    public static void beforeClose(ServerChunkCache cache) {
        try {
            ProbeRegistry.bump(PROBE_CLOSE);
        } catch (Throwable t) {
            ViolationLogger.warn(
                    PROBE_CLOSE, "beforeClose threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
