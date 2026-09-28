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
package net.multiforge.runtime.region;

import org.jetbrains.annotations.ApiStatus;

/**
 * A {@link TickRegionScheduler} worker thread. Carries per-worker state that
 * would otherwise need a {@link ThreadLocal} lookup on a hot path (see {@link
 * WorkerChunkCache}).
 */
@ApiStatus.Internal
public final class RegionWorkerThread extends Thread {

    private final WorkerChunkCache chunkCache = new WorkerChunkCache();

    public RegionWorkerThread(Runnable target, String name) {
        super(target, name);
    }

    public WorkerChunkCache chunkCache() {
        return chunkCache;
    }
}
