/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.diagnostics.emitters;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionMspt;
import net.multiforge.runtime.region.SectionPos;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;

/**
 * Produces {@code HEATMAP_UPDATE} payloads (Track C1 task 1.11) at
 * 4&nbsp;Hz from {@link MultiThreadedSchedulerHost#scheduler()}'s
 * per-region {@link RegionMspt} samples.
 *
 * <p>{@code HEATMAP_UPDATE} is scoped to a single {@code worldId} and
 * to per-<em>chunk</em> samples (§7.3), but this MC-free runtime only
 * tracks tick cost at region granularity (there is no per-chunk MSPT
 * instrumentation yet) and regions themselves are tracked at
 * <em>section</em> granularity ({@code 2^sectionChunkShift} chunks per
 * side, see {@link SectionPos}). This emitter approximates: each
 * section a region owns contributes one {@link
 * DebugPayload.ChunkHeat} sample, using that section's origin chunk
 * as its representative chunk coordinate and the region's rolling
 * average MSPT ({@link RegionMspt#averageMillis()}) as the heat value
 * for every section it owns. This is coarser than true per-chunk
 * heat — replacing it with real per-chunk instrumentation is future
 * work, tracked separately from Track C1 — but it is a well-formed,
 * schema-correct approximation available today from data this module
 * already collects.
 *
 * <p>One instance targets one {@link WorldRef}, mirroring the wire
 * schema's per-world scoping; the fork's production wiring installs
 * one emitter per active world.
 */
public final class TpsHistogramEmitter {

    public static final long PERIOD_MILLIS = 250L;

    private final MultiThreadedSchedulerHost host;
    private final WorldRef world;
    private final Consumer<DebugPayload> sink;
    private final PermissionFilter permissionFilter;

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

    /** Packages the bound world's per-section heat samples and sinks them. */
    public void emit() {
        List<DebugPayload.ChunkHeat> heats = new ArrayList<>();
        ThreadedRegionizer regionizer = host.regionizerForOrNull(world);
        if (regionizer != null) {
            int shift = regionizer.sectionChunkShift();
            for (Region region : regionizer.regions()) {
                RegionMspt mspt = host.scheduler().mspt(region);
                float heat = mspt == null ? 0.0f : (float) mspt.averageMillis();
                for (SectionPos section : region.sections()) {
                    int chunkX = section.x() << shift;
                    int chunkZ = section.z() << shift;
                    heats.add(new DebugPayload.ChunkHeat(chunkX, chunkZ, heat));
                }
            }
        }
        DebugPayload.HeatmapUpdate payload = new DebugPayload.HeatmapUpdate(world.dimensionId(), heats);
        if (permissionFilter.canSee(payload, PlayerRef.ANY)) {
            sink.accept(payload);
        }
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
