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
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.SectionPos;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;

/**
 * Produces {@code CHUNK_OWNERSHIP} payloads (protocol §7.7, added in
 * v1.4.0 / protocol version 2) at 4&nbsp;Hz: which region owns each
 * loaded section of one world.
 *
 * <p>This exists because the client's chunk-border overlay had no
 * source of truth. Through v1.3.18 it hash-picked a region id from the
 * chunk coordinates modulo the live region count — the seams it drew
 * were an artifact of the region <em>count</em>, not of ownership, and
 * they re-shuffled every time a region merged or split. {@code
 * docs/debugging-violations.md} nonetheless told operators to debug
 * real ownership bugs with that overlay. This emitter ships the real
 * mapping so the overlay can be honest.
 *
 * <p>Granularity is per-<em>section</em> ({@code 2^sectionChunkShift}
 * chunks per side), which is not an approximation: {@link
 * ThreadedRegionizer} stores ownership keyed by {@link SectionPos}, so
 * every chunk inside a section genuinely shares one owning region. The
 * shift travels with the payload so a client can map an arbitrary
 * chunk to its section origin without knowing the server's {@code
 * regionSize} config.
 *
 * <p>Reads are lock-free: {@link ThreadedRegionizer#regions()} and
 * {@link Region#sections()} are both safe from the shared {@code
 * mf-diag-emitters} thread this runs on, with no region-worker
 * hand-off (CLAUDE.md ground rule 4 is not engaged — nothing blocks).
 * A concurrent merge/split can land between two sections being read,
 * which at worst produces one frame with a momentarily mixed view;
 * the next frame 250&nbsp;ms later corrects it.
 *
 * <p>One instance targets one {@link WorldRef}, mirroring the wire
 * schema's per-world scoping and {@link TpsHistogramEmitter}'s shape;
 * the fork installs one emitter per active world.
 */
public final class OwnershipEmitter {

    public static final long PERIOD_MILLIS = 250L;

    private final MultiThreadedSchedulerHost host;
    private final WorldRef world;
    private final Consumer<DebugPayload> sink;
    private final PermissionFilter permissionFilter;

    public OwnershipEmitter(
            MultiThreadedSchedulerHost host,
            WorldRef world,
            Consumer<DebugPayload> sink,
            PermissionFilter permissionFilter) {
        this.host = Objects.requireNonNull(host, "host");
        this.world = Objects.requireNonNull(world, "world");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.permissionFilter = Objects.requireNonNull(permissionFilter, "permissionFilter");
    }

    /** Packages the bound world's section→region mapping and sinks it. */
    public void emit() {
        List<DebugPayload.SectionOwner> owners = new ArrayList<>();
        ThreadedRegionizer regionizer = host.regionizerForOrNull(world);
        int shift = 0;
        if (regionizer != null) {
            shift = regionizer.sectionChunkShift();
            for (Region region : regionizer.regions()) {
                long regionId = region.id().value();
                for (SectionPos section : region.sections()) {
                    owners.add(new DebugPayload.SectionOwner(section.x() << shift, section.z() << shift, regionId));
                }
            }
        }
        DebugPayload.OwnershipUpdate payload = new DebugPayload.OwnershipUpdate(world.dimensionId(), shift, owners);
        if (permissionFilter.canSee(payload, PlayerRef.ANY)) {
            sink.accept(payload);
        }
    }

    /**
     * Wire an {@link OwnershipEmitter} targeting {@code world} into
     * {@code host}'s shared diagnostics scheduler at 4&nbsp;Hz. Takes
     * {@code world} explicitly for the same reason {@link
     * TpsHistogramEmitter#install} does — the payload is per-world.
     */
    public static AutoCloseable install(
            MultiThreadedSchedulerHost host,
            WorldRef world,
            Consumer<DebugPayload> sink,
            PermissionFilter permissionFilter) {
        OwnershipEmitter emitter = new OwnershipEmitter(host, world, sink, permissionFilter);
        ScheduledFuture<?> future = host.scheduleGlobal(emitter::emit, PERIOD_MILLIS);
        return () -> future.cancel(false);
    }
}
