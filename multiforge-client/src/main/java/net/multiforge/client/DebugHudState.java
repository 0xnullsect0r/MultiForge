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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;

/**
 * Pure-Java model backing the HUD panels (summary, region list,
 * violations), chunk-border colouring, heatmap, and pin selection boxes
 * in the client debug mod. Wire packets flow through
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
     * v1.4.0: real section→region ownership from {@code
     * CHUNK_OWNERSHIP}, keyed by packed section-<em>origin</em> chunk.
     * Empty until the server sends a frame; {@link ChunkBorderRenderer}
     * draws nothing rather than falling back to the pre-v1.4.0
     * hash-fabricated assignment.
     */
    private final Map<Long, Long> ownership = new HashMap<>();

    private String ownershipWorldId;
    private int ownershipShift;

    /**
     * v1.4.0: set when the server's {@code HELLO.protocolVersion} is
     * outside this build's supported range, in which case protocol §8
     * forbids subscribing. The HUD explains this instead of silently
     * showing nothing.
     */
    private boolean protocolUnsupported;

    private int serverProtocol;

    /**
     * v1.3.15: master on/off for every overlay + HUD line. Toggled by
     * the user's keybind (default F6, remappable via Options → Controls →
     * "MultiForge Debug"). Default ON — the debug mod stays as visible as
     * pre-v1.3.15 unless the user explicitly hides it.
     */
    private final AtomicBoolean overlaysEnabled = new AtomicBoolean(true);

    public void apply(DebugPayload.Hello hello) {
        this.hello = hello;
        this.serverProtocol = hello.protocolVersion();
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

    public void apply(DebugPayload.OwnershipUpdate update) {
        ownershipWorldId = update.worldId();
        ownershipShift = update.sectionChunkShift();
        ownership.clear();
        for (DebugPayload.SectionOwner owner : update.owners()) {
            ownership.put(pack(owner.chunkX(), owner.chunkZ()), owner.regionId());
        }
    }

    /**
     * Drop everything learned from the server we were connected to.
     *
     * <p>Called on client disconnect. Before v1.4.0 nothing reset this
     * model, so after leaving a MultiForge server the HUD kept drawing
     * that server's build label, region count and TPS, and — because
     * the heatmap and pin renderers guard only on a world-id
     * <em>string</em>, and {@code minecraft:overworld} matches
     * everywhere — its heat tiles and pin boxes rendered on top of the
     * player's own singleplayer world.
     *
     * <p>{@link #overlaysEnabled()} deliberately survives: that is a
     * user preference, not server state.
     */
    public void clear() {
        hello = null;
        latestSnapshot = null;
        latestHeatmap = null;
        pins = new DebugPayload.PinList(List.of());
        violations.clear();
        ownership.clear();
        ownershipWorldId = null;
        ownershipShift = 0;
        protocolUnsupported = false;
        serverProtocol = 0;
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

    /** @return true once a {@code CHUNK_OWNERSHIP} frame for {@code worldId} has arrived. */
    public boolean hasOwnershipFor(String worldId) {
        return !ownership.isEmpty() && ownershipWorldId != null && ownershipWorldId.equals(worldId);
    }

    /**
     * @return the region id owning {@code (chunkX, chunkZ)}, or {@code
     *     null} if the server has not told us. Ownership is stored per
     *     section, so this shifts the chunk down to its section origin
     *     first — the same arithmetic-shift floor the server's {@code
     *     SectionPos.ofChunk} uses, which is why it stays correct for
     *     negative coordinates.
     */
    public Long regionIdAtChunk(int chunkX, int chunkZ) {
        if (ownershipWorldId == null) return null;
        int originX = (chunkX >> ownershipShift) << ownershipShift;
        int originZ = (chunkZ >> ownershipShift) << ownershipShift;
        return ownership.get(pack(originX, originZ));
    }

    /** Marks the server as speaking a protocol version we cannot talk. */
    public void markProtocolUnsupported(int serverVersion) {
        this.protocolUnsupported = true;
        this.serverProtocol = serverVersion;
    }

    public boolean protocolUnsupported() {
        return protocolUnsupported;
    }

    public int serverProtocol() {
        return serverProtocol;
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
     * Build the compact map that the region-list panel renders one line
     * per region: {@code "region-<id> mspt=X.X/Y.Y owned=N sections=M"}.
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

    private static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
