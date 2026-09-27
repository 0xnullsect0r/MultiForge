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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.region.TickRegionScheduler;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import org.jetbrains.annotations.ApiStatus;

/**
 * Produces {@code RUNTIME_STATUS} payloads at 4&nbsp;Hz: each world's tick
 * mode, the thread that last ticked each region, and the serial lane's
 * rate. It answers "is this area really on its own thread?", which the
 * region list and the seams cannot: they show that regions exist, not
 * where they ran.
 *
 * <p>The lane rates are differenced here from the cumulative {@code
 * serial-lane.handoff} and {@code serial-lane.inline} probes, so a client
 * gets a rate without keeping history. The first emit reports 0.
 */
@ApiStatus.Internal
public final class RuntimeStatusEmitter {

    public static final long PERIOD_MILLIS = 250L;

    static final String HANDOFF_PROBE = "serial-lane.handoff";
    static final String INLINE_PROBE = "serial-lane.inline";

    private final MultiThreadedSchedulerHost host;
    private final Consumer<DebugPayload> sink;
    private final PermissionFilter permissionFilter;
    private final LongSupplier nanoClock;

    private long lastNanos = -1L;
    private long lastHandoffs;
    private long lastInline;

    public RuntimeStatusEmitter(
            MultiThreadedSchedulerHost host, Consumer<DebugPayload> sink, PermissionFilter permissionFilter) {
        this(host, sink, permissionFilter, System::nanoTime);
    }

    RuntimeStatusEmitter(
            MultiThreadedSchedulerHost host,
            Consumer<DebugPayload> sink,
            PermissionFilter permissionFilter,
            LongSupplier nanoClock) {
        this.host = Objects.requireNonNull(host, "host");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.permissionFilter = Objects.requireNonNull(permissionFilter, "permissionFilter");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /** Builds one payload and sinks it. Runs on the diagnostics scheduler, one call at a time. */
    public synchronized void emit() {
        long now = nanoClock.getAsLong();
        long handoffs = ProbeRegistry.get(HANDOFF_PROBE);
        long inline = ProbeRegistry.get(INLINE_PROBE);
        double handoffRate = 0.0;
        double inlineRate = 0.0;
        if (lastNanos >= 0 && now > lastNanos) {
            double seconds = (now - lastNanos) / 1e9;
            handoffRate = Math.max(0L, handoffs - lastHandoffs) / seconds;
            inlineRate = Math.max(0L, inline - lastInline) / seconds;
        }
        lastNanos = now;
        lastHandoffs = handoffs;
        lastInline = inline;

        TickRegionScheduler scheduler = host.scheduler();
        List<DebugPayload.WorldStatus> worlds = new ArrayList<>();
        List<DebugPayload.RegionThread> regions = new ArrayList<>();
        for (Map.Entry<String, ThreadedRegionizer> entry : host.regionizers().entrySet()) {
            String worldId = entry.getKey();
            List<Region> live = List.copyOf(entry.getValue().regions());
            worlds.add(
                    new DebugPayload.WorldStatus(worldId, worldMode(host.tickMode(WorldRef.of(worldId))), live.size()));
            for (Region region : live) {
                String thread = scheduler.lastTickThread(region);
                regions.add(new DebugPayload.RegionThread(
                        region.id().value(),
                        worldId,
                        thread == null ? "" : thread,
                        placement(scheduler.lastTickPlacement(region)),
                        scheduler.lastTickSerialPosts(region)));
            }
        }
        DebugPayload.RuntimeStatus status = new DebugPayload.RuntimeStatus(handoffRate, inlineRate, worlds, regions);
        if (permissionFilter.canSee(status, PlayerRef.ANY)) {
            sink.accept(status);
        }
    }

    static DebugPayload.WorldMode worldMode(MultiThreadedSchedulerHost.WorldTickMode mode) {
        return switch (mode) {
            case SERVER_THREAD_NO_REGIONS -> DebugPayload.WorldMode.SERVER_THREAD_NO_REGIONS;
            case SERVER_THREAD_SINGLE_REGION -> DebugPayload.WorldMode.SERVER_THREAD_SINGLE_REGION;
            case WORKERS -> DebugPayload.WorldMode.WORKERS;
            case WORKERS_AND_SERVER_THREAD -> DebugPayload.WorldMode.WORKERS_AND_SERVER_THREAD;
        };
    }

    static DebugPayload.Placement placement(TickRegionScheduler.TickPlacement placement) {
        if (placement == null) return DebugPayload.Placement.UNKNOWN;
        return switch (placement) {
            case WORKER -> DebugPayload.Placement.WORKER;
            case SERVER_THREAD_SINGLE -> DebugPayload.Placement.SERVER_THREAD_SINGLE;
            case SERVER_THREAD_HOT -> DebugPayload.Placement.SERVER_THREAD_HOT;
        };
    }

    /** Wire a {@link RuntimeStatusEmitter} into {@code host}'s shared diagnostics scheduler at 4Hz. */
    public static AutoCloseable install(
            MultiThreadedSchedulerHost host, Consumer<DebugPayload> sink, PermissionFilter permissionFilter) {
        RuntimeStatusEmitter emitter = new RuntimeStatusEmitter(host, sink, permissionFilter);
        ScheduledFuture<?> future = host.scheduleGlobal(emitter::emit, PERIOD_MILLIS);
        return () -> future.cancel(false);
    }
}
