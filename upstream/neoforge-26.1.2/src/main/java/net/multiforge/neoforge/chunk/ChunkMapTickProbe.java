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
package net.multiforge.neoforge.chunk;

import net.multiforge.runtime.diagnostics.ProbeRegistry;
import org.jetbrains.annotations.ApiStatus;

/**
 * Times the two {@code ChunkMap.tick} calls in {@code ServerChunkCache.tick}
 * (server thread, between the region phases) for {@code /multiforge probes}:
 * {@code chunkmap.tick.tracker.*} is the entity tracker pass ({@code
 * ChunkMap.tick()}: every tracked entity's viewers and movement packets), and
 * {@code chunkmap.tick.unload.*} the unload and save pass ({@code
 * ChunkMap.tick(BooleanSupplier)}). Each has a {@code .count} and a total in
 * {@code .nanos}; the data behind a later decision on moving tracking off the
 * server thread.
 */
@ApiStatus.Internal
public final class ChunkMapTickProbe {
    private static final ProbeRegistry.Counter TRACKER_COUNT = ProbeRegistry.counter("chunkmap.tick.tracker.count");
    private static final ProbeRegistry.Counter TRACKER_NANOS = ProbeRegistry.counter("chunkmap.tick.tracker.nanos");
    private static final ProbeRegistry.Counter UNLOAD_COUNT = ProbeRegistry.counter("chunkmap.tick.unload.count");
    private static final ProbeRegistry.Counter UNLOAD_NANOS = ProbeRegistry.counter("chunkmap.tick.unload.nanos");

    private ChunkMapTickProbe() {}

    public static long start() {
        return System.nanoTime();
    }

    public static void endTracker(long start) {
        TRACKER_COUNT.increment();
        TRACKER_NANOS.add(System.nanoTime() - start);
    }

    public static void endUnload(long start) {
        UNLOAD_COUNT.increment();
        UNLOAD_NANOS.add(System.nanoTime() - start);
    }
}
