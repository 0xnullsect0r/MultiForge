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
package net.multiforge.runtime.diagnostics.emitters;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.multiforge.runtime.region.pin.RegionPin;
import net.multiforge.runtime.region.pin.RegionPinManager;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;

/**
 * Produces {@code PIN_LIST} payloads (Track C1 task 1.10) from {@link
 * RegionPinManager#all()}.
 *
 * <p>{@code docs/design/client-debug-protocol.md} §3 specifies this
 * stream as event-driven ("sent once on subscribe... and again
 * whenever [the pin set] changes... not part of the 250ms tick"), but
 * {@link RegionPinManager} does not currently expose a change-listener
 * hook (adding one would touch {@code add}/{@code remove}/{@code save}
 * call sites shared with the {@code /multiforge region pin} command
 * surface, out of scope for this task — see the protocol doc's own
 * fallback: "or 4Hz if event API missing"). This emitter therefore
 * takes that documented fallback: {@link #emit()} unconditionally
 * re-sends the full current snapshot every call, wired at 4&nbsp;Hz via
 * {@link #install}. Pin lists are capped at 4096 entries and change
 * rarely (operator-authored), so the bandwidth cost of a small,
 * infrequent, always-fresh snapshot is negligible, and this trivially
 * satisfies "one immediate frame within 250ms of subscribe."
 */
public final class PinListEmitter {

    public static final long PERIOD_MILLIS = 250L;

    private final RegionPinManager pins;
    private final Consumer<DebugPayload> sink;
    private final PermissionFilter permissionFilter;

    public PinListEmitter(RegionPinManager pins, Consumer<DebugPayload> sink, PermissionFilter permissionFilter) {
        this.pins = Objects.requireNonNull(pins, "pins");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.permissionFilter = Objects.requireNonNull(permissionFilter, "permissionFilter");
    }

    /** Packages the full current pin snapshot and sinks it. */
    public void emit() {
        List<DebugPayload.PinBox> boxes = new ArrayList<>();
        for (RegionPin p : pins.all()) {
            boxes.add(new DebugPayload.PinBox(
                    p.id(), p.world().dimensionId(), p.fromChunkX(), p.fromChunkZ(), p.toChunkX(), p.toChunkZ()));
        }
        DebugPayload.PinList payload = new DebugPayload.PinList(boxes);
        if (permissionFilter.canSee(payload, PlayerRef.ANY)) {
            sink.accept(payload);
        }
    }

    /**
     * Wire a {@link PinListEmitter} into {@code host}'s shared
     * diagnostics scheduler at 4&nbsp;Hz.
     *
     * <p>Takes {@code pins} explicitly (not reachable from {@code
     * host}) — {@link RegionPinManager} is constructed independently
     * of {@link MultiThreadedSchedulerHost} (see {@code
     * MultiForgeCommandDispatcher}), so this deviates from the other
     * emitters' {@code install(host, sink, permissionFilter)} shape by
     * necessity.
     */
    public static AutoCloseable install(
            MultiThreadedSchedulerHost host,
            RegionPinManager pins,
            Consumer<DebugPayload> sink,
            PermissionFilter permissionFilter) {
        Objects.requireNonNull(host, "host");
        PinListEmitter emitter = new PinListEmitter(pins, sink, permissionFilter);
        ScheduledFuture<?> future = host.scheduleGlobal(emitter::emit, PERIOD_MILLIS);
        return () -> future.cancel(false);
    }
}
