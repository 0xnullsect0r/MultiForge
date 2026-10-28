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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionId;
import net.multiforge.runtime.region.RegionMspt;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import org.jetbrains.annotations.ApiStatus;

/**
 * Produces {@code REGION_SNAPSHOT} payloads (Track C1 task 1.8) at
 * 4&nbsp;Hz from {@link MultiThreadedSchedulerHost#regionizers()} and
 * {@link MultiThreadedSchedulerHost#scheduler()}'s per-region {@link
 * RegionMspt} samples.
 *
 * <p>{@code REGION_SNAPSHOT} carries no per-world scoping (see {@code
 * DebugPayload.RegionSnapshot} — just a tick and a flat region list),
 * so a single emitter instance aggregates every region across every
 * world (including the synthetic {@code multiforge:global} world) into
 * one payload per tick.
 *
 * <p>{@code ownedEntities} is the number of entities the region's last
 * entity pass ticked ({@link Region#entitiesTicked()}, recorded by the
 * fork's entity tick runner); tests may supply their own counter.
 */
@ApiStatus.Internal
public final class RegionMapEmitter {

    public static final long PERIOD_MILLIS = 250L;

    private final MultiThreadedSchedulerHost host;
    private final java.util.function.Function<Region, Integer> ownedEntityCounter;
    private final Consumer<DebugPayload> sink;
    private final PermissionFilter permissionFilter;

    public RegionMapEmitter(
            MultiThreadedSchedulerHost host,
            ToIntFunction<RegionId> ownedEntityCounter,
            Consumer<DebugPayload> sink,
            PermissionFilter permissionFilter) {
        this(host, perRegion(ownedEntityCounter), sink, permissionFilter);
    }

    private RegionMapEmitter(
            MultiThreadedSchedulerHost host,
            java.util.function.Function<Region, Integer> ownedEntityCounter,
            Consumer<DebugPayload> sink,
            PermissionFilter permissionFilter) {
        this.host = Objects.requireNonNull(host, "host");
        this.ownedEntityCounter = ownedEntityCounter;
        this.sink = Objects.requireNonNull(sink, "sink");
        this.permissionFilter = Objects.requireNonNull(permissionFilter, "permissionFilter");
    }

    private static java.util.function.Function<Region, Integer> perRegion(ToIntFunction<RegionId> byId) {
        Objects.requireNonNull(byId, "ownedEntityCounter");
        return region -> byId.applyAsInt(region.id());
    }

    /** Packages every live region across every world into one payload and sinks it. */
    public void emit() {
        List<DebugPayload.RegionStat> stats = new ArrayList<>();
        long tick = 0L;
        for (ThreadedRegionizer regionizer : host.regionizers().values()) {
            for (Region region : regionizer.regions()) {
                RegionMspt mspt = host.scheduler().mspt(region);
                double p50 = mspt == null ? 0.0 : mspt.percentileMillis(0.5);
                double p95 = mspt == null ? 0.0 : mspt.percentileMillis(0.95);
                int owned = ownedEntityCounter.apply(region);
                stats.add(new DebugPayload.RegionStat(region.id().value(), region.sectionCount(), p50, p95, owned));
                tick = Math.max(tick, region.currentTick());
            }
        }
        DebugPayload.RegionSnapshot snapshot = new DebugPayload.RegionSnapshot(tick, stats);
        if (permissionFilter.canSee(snapshot, PlayerRef.ANY)) {
            sink.accept(snapshot);
        }
    }

    /** Wire a {@link RegionMapEmitter} into {@code host}'s shared diagnostics scheduler at 4Hz. */
    public static AutoCloseable install(
            MultiThreadedSchedulerHost host, Consumer<DebugPayload> sink, PermissionFilter permissionFilter) {
        return schedule(host, new RegionMapEmitter(host, Region::entitiesTicked, sink, permissionFilter));
    }

    /** As {@link #install(MultiThreadedSchedulerHost, Consumer, PermissionFilter)}, with an explicit entity counter. */
    public static AutoCloseable install(
            MultiThreadedSchedulerHost host,
            ToIntFunction<RegionId> ownedEntityCounter,
            Consumer<DebugPayload> sink,
            PermissionFilter permissionFilter) {
        return schedule(host, new RegionMapEmitter(host, ownedEntityCounter, sink, permissionFilter));
    }

    private static AutoCloseable schedule(MultiThreadedSchedulerHost host, RegionMapEmitter emitter) {
        ScheduledFuture<?> future = host.scheduleGlobal(emitter::emit, PERIOD_MILLIS);
        return () -> future.cancel(false);
    }
}
