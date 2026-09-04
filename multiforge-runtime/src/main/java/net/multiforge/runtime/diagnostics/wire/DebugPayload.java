/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.diagnostics.wire;

import java.util.List;
import java.util.Objects;

/**
 * Payload records carried on the {@code multiforge:debug/v1} channel.
 * Each corresponds to one {@link DebugPacketKind}. Purely data — the
 * server produces them in {@code net.multiforge.runtime.diagnostics},
 * the client mod consumes them in {@code net.multiforge.client.hud}.
 */
public final class DebugPayload {

    private DebugPayload() {}

    /** Handshake reply. */
    public record Hello(int protocolVersion, int tickHz, String buildLabel) {
        public Hello {
            Objects.requireNonNull(buildLabel, "buildLabel");
            if (buildLabel.length() > 256) throw new IllegalArgumentException("buildLabel too long");
        }
    }

    /** One region's live stats. */
    public record RegionStat(long regionId, int sectionCount, double msptP50, double msptP95, int ownedEntities) {}

    /** Periodic dump of every live region. */
    public record RegionSnapshot(long tick, List<RegionStat> regions) {
        public RegionSnapshot {
            Objects.requireNonNull(regions, "regions");
            regions = List.copyOf(regions);
        }
    }

    /** One chunk's heat sample. */
    public record ChunkHeat(int chunkX, int chunkZ, float heatMspt) {}

    /** Per-chunk heat within the client's view radius. */
    public record HeatmapUpdate(String worldId, List<ChunkHeat> heats) {
        public HeatmapUpdate {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(heats, "heats");
            heats = List.copyOf(heats);
        }
    }

    /** One region pin. Coordinates are inclusive chunk bounds. */
    public record PinBox(String id, String worldId, int fromChunkX, int fromChunkZ, int toChunkX, int toChunkZ) {
        public PinBox {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(worldId, "worldId");
        }
    }

    /** Snapshot of every pin defined on the server. */
    public record PinList(List<PinBox> pins) {
        public PinList {
            Objects.requireNonNull(pins, "pins");
            pins = List.copyOf(pins);
        }
    }

    /** One reroute/warn event surfaced to the side panel. */
    public record ViolationEvent(long epochMillis, String modId, String site, String detail) {
        public ViolationEvent {
            Objects.requireNonNull(modId, "modId");
            Objects.requireNonNull(site, "site");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** Client-side flag bitset for what streams to receive. */
    public record Subscribe(int flags) {
        public static final int F_REGIONS = 0x01;
        public static final int F_HEATMAP = 0x02;
        public static final int F_PINS = 0x04;
        public static final int F_VIOLATIONS = 0x08;

        public boolean wants(int flag) {
            return (flags & flag) == flag;
        }
    }
}
