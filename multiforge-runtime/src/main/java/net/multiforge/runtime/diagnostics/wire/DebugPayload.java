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
package net.multiforge.runtime.diagnostics.wire;

import java.util.List;
import java.util.Objects;

/**
 * Payload records carried on the {@code multiforge:debug/v1} channel.
 * Each of the six permitted records corresponds to one {@link
 * DebugPacketKind}. Purely data — the server produces them in {@code
 * net.multiforge.runtime.diagnostics} (see the {@code
 * net.multiforge.runtime.diagnostics.emitters} producers), the client
 * mod consumes them in {@code net.multiforge.client.hud}.
 *
 * <p>{@code sealed} (Track C1 / M6 addition) so a single {@code
 * Consumer<DebugPayload>} sink — the shape every emitter in {@code
 * net.multiforge.runtime.diagnostics.emitters} and the fork's {@code
 * PayloadDistributor} are built around — can accept any of the six
 * kinds, and so a future {@code switch} over a {@code DebugPayload}
 * is exhaustiveness-checked by the compiler. This is a Java-level
 * supertype addition only: no field, wire layout, or encode/decode
 * behavior changes, so it does not require a {@code
 * docs/design/client-debug-protocol.md} amendment under §8 (nothing
 * about the wire contract itself changed).
 *
 * <p>{@link RegionStat}, {@link ChunkHeat}, and {@link PinBox} are
 * <em>not</em> permitted subtypes — they are element types nested
 * inside a list-carrying payload ({@link RegionSnapshot}, {@link
 * HeatmapUpdate}, {@link PinList} respectively), not standalone
 * packet kinds in their own right.
 */
public sealed interface DebugPayload {

    /** Handshake reply. */
    record Hello(int protocolVersion, int tickHz, String buildLabel) implements DebugPayload {
        public Hello {
            Objects.requireNonNull(buildLabel, "buildLabel");
            if (buildLabel.length() > 256) throw new IllegalArgumentException("buildLabel too long");
        }
    }

    /** One region's live stats. */
    record RegionStat(long regionId, int sectionCount, double msptP50, double msptP95, int ownedEntities) {}

    /** Periodic dump of every live region. */
    record RegionSnapshot(long tick, List<RegionStat> regions) implements DebugPayload {
        public RegionSnapshot {
            Objects.requireNonNull(regions, "regions");
            regions = List.copyOf(regions);
        }
    }

    /** One chunk's heat sample. */
    record ChunkHeat(int chunkX, int chunkZ, float heatMspt) {}

    /** Per-chunk heat within the client's view radius. */
    record HeatmapUpdate(String worldId, List<ChunkHeat> heats) implements DebugPayload {
        public HeatmapUpdate {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(heats, "heats");
            heats = List.copyOf(heats);
        }
    }

    /** One region pin. Coordinates are inclusive chunk bounds. */
    record PinBox(String id, String worldId, int fromChunkX, int fromChunkZ, int toChunkX, int toChunkZ) {
        public PinBox {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(worldId, "worldId");
        }
    }

    /** Snapshot of every pin defined on the server. */
    record PinList(List<PinBox> pins) implements DebugPayload {
        public PinList {
            Objects.requireNonNull(pins, "pins");
            pins = List.copyOf(pins);
        }
    }

    /** One reroute/warn event surfaced to the side panel. */
    record ViolationEvent(long epochMillis, String modId, String site, String detail) implements DebugPayload {
        public ViolationEvent {
            Objects.requireNonNull(modId, "modId");
            Objects.requireNonNull(site, "site");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** Client-side flag bitset for what streams to receive. */
    record Subscribe(int flags) implements DebugPayload {
        public static final int F_REGIONS = 0x01;
        public static final int F_HEATMAP = 0x02;
        public static final int F_PINS = 0x04;
        public static final int F_VIOLATIONS = 0x08;

        public boolean wants(int flag) {
            return (flags & flag) == flag;
        }
    }
}
