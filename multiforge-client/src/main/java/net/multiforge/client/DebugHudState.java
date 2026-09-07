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
package net.multiforge.client;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;

/**
 * Pure-Java model backing the F3 overlay, chunk-border colouring,
 * heatmap, pin selection boxes, and violation side panel in the
 * client debug mod. Wire packets flow through
 * {@link net.multiforge.runtime.diagnostics.wire.DebugPacketCodec},
 * decode into {@link DebugPayload} records, then land here.
 *
 * <p>Instances are single-threaded: the NeoForge network handler
 * enqueues on the client thread before applying updates.
 */
public final class DebugHudState {

    /** How many violation events to retain in the side panel. */
    private static final int VIOLATION_HISTORY = 200;

    private DebugPayload.Hello hello;
    private DebugPayload.RegionSnapshot latestSnapshot;
    private DebugPayload.HeatmapUpdate latestHeatmap;
    private DebugPayload.PinList pins = new DebugPayload.PinList(List.of());
    private final Deque<DebugPayload.ViolationEvent> violations = new ArrayDeque<>();

    /**
     * v1.3.15: master on/off for every overlay + HUD line. Toggled by
     * the user's keybind (default F6, remappable via Options → Controls →
     * "MultiForge Debug"). Default ON — the debug mod stays as visible as
     * pre-v1.3.15 unless the user explicitly hides it.
     */
    private final AtomicBoolean overlaysEnabled = new AtomicBoolean(true);

    public void apply(DebugPayload.Hello hello) {
        this.hello = hello;
    }

    public void apply(DebugPayload.RegionSnapshot snapshot) {
        this.latestSnapshot = snapshot;
    }

    public void apply(DebugPayload.HeatmapUpdate heatmap) {
        this.latestHeatmap = heatmap;
    }

    public void apply(DebugPayload.PinList pins) {
        this.pins = pins;
    }

    public void apply(DebugPayload.ViolationEvent event) {
        violations.addFirst(event);
        while (violations.size() > VIOLATION_HISTORY) violations.removeLast();
    }

    public DebugPayload.Hello hello() {
        return hello;
    }

    public DebugPayload.RegionSnapshot latestSnapshot() {
        return latestSnapshot;
    }

    public DebugPayload.HeatmapUpdate latestHeatmap() {
        return latestHeatmap;
    }

    public List<DebugPayload.PinBox> pins() {
        return pins.pins();
    }

    public List<DebugPayload.ViolationEvent> recentViolations() {
        return List.copyOf(violations);
    }

    /** Every renderer / HUD-line producer checks this before drawing. */
    public boolean overlaysEnabled() {
        return overlaysEnabled.get();
    }

    /**
     * Flip the overlay flag and return the new value. Called from
     * {@link KeyInputHandler} on the F6 (or whatever the user has
     * bound) key press.
     */
    public boolean toggleOverlays() {
        boolean next = !overlaysEnabled.get();
        overlaysEnabled.set(next);
        return next;
    }

    /**
     * Build the compact map that the F3 overlay renders one line per
     * region: {@code "region-<id>: mspt=X.X owned=N"}.
     */
    public Map<Long, String> f3Lines() {
        DebugPayload.RegionSnapshot s = latestSnapshot;
        if (s == null) return Collections.emptyMap();
        Map<Long, String> out = new LinkedHashMap<>();
        for (DebugPayload.RegionStat r : s.regions()) {
            out.put(
                    r.regionId(),
                    String.format(
                            "region-%d mspt=%.1f/%.1f owned=%d sections=%d",
                            r.regionId(), r.msptP50(), r.msptP95(), r.ownedEntities(), r.sectionCount()));
        }
        return out;
    }
}
