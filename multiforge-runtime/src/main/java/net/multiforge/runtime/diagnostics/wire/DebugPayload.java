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
 * Each of the eight permitted records corresponds to one {@link
 * DebugPacketKind}. Purely data — the server produces them in {@code
 * net.multiforge.runtime.diagnostics} (see the {@code
 * net.multiforge.runtime.diagnostics.emitters} producers), the client
 * mod consumes them in {@code net.multiforge.client.hud}.
 *
 * <p>{@code sealed} (Track C1 / M6 addition) so a single {@code
 * Consumer<DebugPayload>} sink — the shape every emitter in {@code
 * net.multiforge.runtime.diagnostics.emitters} and the fork's {@code
 * PayloadDistributor} are built around — can accept any of the seven
 * kinds, and so a future {@code switch} over a {@code DebugPayload}
 * is exhaustiveness-checked by the compiler. This is a Java-level
 * supertype addition only: no field, wire layout, or encode/decode
 * behavior changes, so it does not require a {@code
 * docs/design/client-debug-protocol.md} amendment under §8 (nothing
 * about the wire contract itself changed).
 *
 * <p>{@link RegionStat}, {@link ChunkHeat}, {@link PinBox}, and {@link
 * SectionOwner} are <em>not</em> permitted subtypes — they are element
 * types nested inside a list-carrying payload ({@link RegionSnapshot},
 * {@link HeatmapUpdate}, {@link PinList}, {@link OwnershipUpdate}
 * respectively), not standalone packet kinds in their own right.
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

    /**
     * One chunk's heat sample.
     *
     * <p>{@code heatMspt} is the chunk's own tick time in ms per tick (protocol
     * 4); {@code regionMspt} is the owning region's average, which is what a
     * client older than protocol 4 is sent in the same wire slot (see {@link
     * DebugPacketCodec#encodeHeatmap(HeatmapUpdate, int)}). Decoded samples
     * carry the wire value in both.
     */
    record ChunkHeat(int chunkX, int chunkZ, float heatMspt, float regionMspt) {
        public ChunkHeat(int chunkX, int chunkZ, float heatMspt) {
            this(chunkX, chunkZ, heatMspt, heatMspt);
        }
    }

    /**
     * Per-chunk heat within the client's view radius.
     *
     * <p>The runtime-side producer ({@code TpsHistogramEmitter}) builds
     * a whole-world list — it is Minecraft-free and has no notion of a
     * player position or a view distance. The view-radius narrowing
     * promised above happens fork-side in {@code
     * DebugChannelServer#broadcast}, which re-encodes a filtered copy
     * per subscribed player (v1.4.0; before that, no filtering existed
     * anywhere and every section in the world went to every client).
     */
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

    /**
     * Region ownership of one section, keyed by the section's <em>origin
     * chunk</em> ({@code section.x() << shift}).
     *
     * <p>MultiForge tracks ownership at section granularity — every
     * chunk inside a section shares one owning region (see {@code
     * ThreadedRegionizer#regionAtChunk}). That is the real ownership
     * boundary, not a coarsening of a finer truth.
     */
    record SectionOwner(int chunkX, int chunkZ, long regionId) {}

    /**
     * Which region owns each loaded section of one world. Added in
     * v1.4.0 (wire {@code CHUNK_OWNERSHIP}, protocol version 2) so the
     * client's chunk-border overlay can draw <em>real</em> region seams;
     * before this the client hash-fabricated an assignment from chunk
     * coordinates.
     *
     * @param sectionChunkShift log2 of the section edge length in
     *     chunks, so a client can map an arbitrary chunk to its section
     *     origin without having to know the server's {@code regionSize}
     *     config.
     */
    record OwnershipUpdate(String worldId, int sectionChunkShift, List<SectionOwner> owners) implements DebugPayload {
        public OwnershipUpdate {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(owners, "owners");
            if (sectionChunkShift < 0 || sectionChunkShift > 16) {
                throw new IllegalArgumentException("sectionChunkShift out of range: " + sectionChunkShift);
            }
            owners = List.copyOf(owners);
        }
    }

    /** How a world's regions ticked last. Codes are wire values; never renumber them. */
    enum WorldMode {
        /** No region: the level ticks on the server thread as in NeoForge. */
        SERVER_THREAD_NO_REGIONS(0),
        /** One region, ticked on the server thread (a worker would gain nothing). */
        SERVER_THREAD_SINGLE_REGION(1),
        /** Every region on a worker. */
        WORKERS(2),
        /** Some regions on workers, the lane-bound ones on the server thread. */
        WORKERS_AND_SERVER_THREAD(3);

        private final int wire;

        WorldMode(int wire) {
            this.wire = wire;
        }

        public int wire() {
            return wire;
        }

        /** Unknown codes (from a newer server) read as {@link #WORKERS}, the neutral answer. */
        public static WorldMode fromWire(int code) {
            for (WorldMode m : values()) if (m.wire == code) return m;
            return WORKERS;
        }
    }

    /** Where one region's last tick ran. Codes are wire values; never renumber them. */
    enum Placement {
        UNKNOWN(0),
        WORKER(1),
        SERVER_THREAD_SINGLE(2),
        SERVER_THREAD_HOT(3);

        private final int wire;

        Placement(int wire) {
            this.wire = wire;
        }

        public int wire() {
            return wire;
        }

        public static Placement fromWire(int code) {
            for (Placement p : values()) if (p.wire == code) return p;
            return UNKNOWN;
        }
    }

    /** One world's tick mode and live region count. */
    record WorldStatus(String worldId, WorldMode mode, int regionCount) {
        public WorldStatus {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(mode, "mode");
        }
    }

    /**
     * Where one region last ticked.
     *
     * @param thread name of the thread that ran the tick ({@code ""} before the first tick)
     * @param serialPosts events the region's last tick posted to the serial lane
     */
    record RegionThread(long regionId, String worldId, String thread, Placement placement, long serialPosts) {
        public RegionThread {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(thread, "thread");
            Objects.requireNonNull(placement, "placement");
        }
    }

    /**
     * Which thread ticks what: per-world tick mode, per-region thread, and the
     * serial lane's rate. Added in v1.8.0 (wire {@code RUNTIME_STATUS},
     * protocol version 3). Worlds without regions are absent: a client reads
     * a missing world as {@link WorldMode#SERVER_THREAD_NO_REGIONS}.
     *
     * @param laneHandoffsPerSecond events a worker handed to the server thread, per second
     * @param laneInlinePerSecond serial-lane events run directly because their region ticked on the
     *     server thread, per second
     */
    record RuntimeStatus(
            double laneHandoffsPerSecond,
            double laneInlinePerSecond,
            List<WorldStatus> worlds,
            List<RegionThread> regions)
            implements DebugPayload {
        public RuntimeStatus {
            Objects.requireNonNull(worlds, "worlds");
            Objects.requireNonNull(regions, "regions");
            worlds = List.copyOf(worlds);
            regions = List.copyOf(regions);
        }
    }

    /**
     * Client-side flag bitset for what streams to receive, and the protocol
     * version the client speaks ({@code 1} for clients that predate the
     * field, which is optional on the wire).
     */
    record Subscribe(int flags, int clientProtocol) implements DebugPayload {
        /** A subscription from a client speaking this build's protocol. */
        public Subscribe(int flags) {
            this(flags, DebugPacketCodec.PROTOCOL_VERSION);
        }

        /** The streams a client speaking {@code protocol} can decode. */
        public static int streamsFor(int protocol) {
            if (protocol >= 3) return F_ALL;
            if (protocol == 2) return F_ALL & ~F_RUNTIME;
            return F_REGIONS | F_HEATMAP | F_PINS | F_VIOLATIONS;
        }

        public static final int F_REGIONS = 0x01;
        public static final int F_HEATMAP = 0x02;
        public static final int F_PINS = 0x04;
        public static final int F_VIOLATIONS = 0x08;

        /** v1.4.0 / protocol 2 — {@link OwnershipUpdate} stream. */
        public static final int F_OWNERSHIP = 0x10;

        /** v1.8.0 / protocol 3 — {@link RuntimeStatus} stream. */
        public static final int F_RUNTIME = 0x20;

        /** Every stream this protocol version defines. */
        public static final int F_ALL = F_REGIONS | F_HEATMAP | F_PINS | F_VIOLATIONS | F_OWNERSHIP | F_RUNTIME;

        public boolean wants(int flag) {
            return (flags & flag) == flag;
        }
    }
}
