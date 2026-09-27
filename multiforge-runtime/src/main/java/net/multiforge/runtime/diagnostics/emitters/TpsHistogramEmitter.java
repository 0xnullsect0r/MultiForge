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
package net.multiforge.runtime.diagnostics.emitters;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.NewChunkHolder;
import net.multiforge.runtime.diagnostics.ChunkCost;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionMspt;
import net.multiforge.runtime.region.SectionPos;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;

/**
 * Produces {@code HEATMAP_UPDATE} payloads (Track C1 task 1.11) at
 * 4&nbsp;Hz: each loaded chunk's own tick time, from the per-chunk samples
 * {@link ChunkCost} collects while a debug client watches the heatmap.
 *
 * <p>Every loaded chunk a region owns contributes one {@link
 * DebugPayload.ChunkHeat}: {@code heatMspt} is the chunk's own time in ms per
 * tick (entities, block entities, scheduled and chunk ticks charged to the
 * chunk they ran in), smoothed over about a second; {@code regionMspt} is the
 * region's rolling average ({@link RegionMspt#averageMillis()}), which is
 * what a client older than protocol 4 is sent. Until v1.9.0 every chunk
 * carried its region's average, so a busy base painted every chunk it shared
 * a region with, however far away and however empty.
 *
 * <p>One instance targets one {@link WorldRef}, mirroring the wire
 * schema's per-world scoping; the fork's production wiring installs
 * one emitter per active world.
 */
public final class TpsHistogramEmitter {

    public static final long PERIOD_MILLIS = 250L;

    /** Weight of the newest drain in a chunk's smoothed value (about a one-second time constant at 4 Hz). */
    static final double SMOOTHING = 0.3;

    /** Smoothed values below this (ms/tick) are forgotten. */
    private static final double FORGET_BELOW_MS = 1e-4;

    private final MultiThreadedSchedulerHost host;
    private final WorldRef world;
    private final Consumer<DebugPayload> sink;
    private final PermissionFilter permissionFilter;
    /** Chunk key ({@link ChunkCost#pack}) to smoothed ms per tick. Emitter thread only. */
    private final Map<Long, Double> smoothed = new HashMap<>();

    public TpsHistogramEmitter(
            MultiThreadedSchedulerHost host,
            WorldRef world,
            Consumer<DebugPayload> sink,
            PermissionFilter permissionFilter) {
        this.host = Objects.requireNonNull(host, "host");
        this.world = Objects.requireNonNull(world, "world");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.permissionFilter = Objects.requireNonNull(permissionFilter, "permissionFilter");
    }

    /** Packages the bound world's per-chunk heat samples and sinks them. */
    public synchronized void emit() {
        absorb(ChunkCost.drain(world.dimensionId()));
        List<DebugPayload.ChunkHeat> heats = new ArrayList<>();
        Set<Long> owned = new HashSet<>();
        ThreadedRegionizer regionizer = host.regionizerForOrNull(world);
        if (regionizer != null) {
            int shift = regionizer.sectionChunkShift();
            ChunkHolderManager chunks = host.chunkManagerForOrNull(world);
            for (Region region : regionizer.regions()) {
                RegionMspt mspt = host.scheduler().mspt(region);
                float regionHeat = mspt == null ? 0.0f : (float) mspt.averageMillis();
                if (chunks != null) {
                    for (NewChunkHolder holder : chunks.holdersOwnedBy(region.id())) {
                        int x = holder.position().x();
                        int z = holder.position().z();
                        long key = ChunkCost.pack(x, z);
                        owned.add(key);
                        Double own = smoothed.get(key);
                        heats.add(new DebugPayload.ChunkHeat(x, z, own == null ? 0.0f : own.floatValue(), regionHeat));
                    }
                } else {
                    for (SectionPos section : region.sections()) {
                        heats.add(new DebugPayload.ChunkHeat(
                                section.x() << shift, section.z() << shift, 0.0f, regionHeat));
                    }
                }
            }
        }
        // Forget chunks no region owns any more (unloaded).
        smoothed.keySet().retainAll(owned);
        DebugPayload.HeatmapUpdate payload = new DebugPayload.HeatmapUpdate(world.dimensionId(), heats);
        if (permissionFilter.canSee(payload, PlayerRef.ANY)) {
            sink.accept(payload);
        }
    }

    /**
     * Fold one drain into the smoothed per-chunk values. A drain covering no
     * tick (nothing measured) leaves them as they were; a chunk missing from a
     * drain that covers ticks cost nothing in them, so it decays toward zero.
     */
    private void absorb(ChunkCost.Drained drained) {
        if (drained.ticks() <= 0) return;
        double perTick = 1.0 / drained.ticks() / 1_000_000.0;
        Map<Long, Double> fresh = new HashMap<>(drained.size() * 2);
        for (int i = 0; i < drained.size(); i++) {
            fresh.put(drained.keys()[i], drained.nanos()[i] * perTick);
        }
        for (Iterator<Map.Entry<Long, Double>> it = smoothed.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Double> e = it.next();
            Double measured = fresh.remove(e.getKey());
            double now = measured == null ? 0.0 : measured;
            double next = e.getValue() + SMOOTHING * (now - e.getValue());
            if (next < FORGET_BELOW_MS) it.remove();
            else e.setValue(next);
        }
        // Chunks seen for the first time start at their measured value.
        smoothed.putAll(fresh);
    }

    /**
     * Wire a {@link TpsHistogramEmitter} targeting {@code world} into
     * {@code host}'s shared diagnostics scheduler at 4&nbsp;Hz.
     *
     * <p>Takes {@code world} explicitly — {@code HEATMAP_UPDATE} is
     * per-world (§7.3) and {@link MultiThreadedSchedulerHost} tracks
     * multiple worlds, so a single target must be chosen; this
     * deviates from the other emitters' {@code install(host, sink,
     * permissionFilter)} shape by necessity.
     */
    public static AutoCloseable install(
            MultiThreadedSchedulerHost host,
            WorldRef world,
            Consumer<DebugPayload> sink,
            PermissionFilter permissionFilter) {
        TpsHistogramEmitter emitter = new TpsHistogramEmitter(host, world, sink, permissionFilter);
        ScheduledFuture<?> future = host.scheduleGlobal(emitter::emit, PERIOD_MILLIS);
        return () -> future.cancel(false);
    }
}
