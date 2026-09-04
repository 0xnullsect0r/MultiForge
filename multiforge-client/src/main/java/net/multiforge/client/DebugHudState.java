/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.client;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
