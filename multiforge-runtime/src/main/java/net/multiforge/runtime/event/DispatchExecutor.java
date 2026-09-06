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
package net.multiforge.runtime.event;

import java.util.Optional;
import net.multiforge.runtime.region.RegionId;

/**
 * MC-free abstraction over "where can {@link DomainDispatcher} actually
 * hand off a deferred invocation." Lets {@link DomainDispatcher} be unit
 * tested without a live {@code MultiThreadedSchedulerHost}.
 *
 * <p>The fork bridge (M12.2, {@code net.multiforge.neoforge.event.EventBusBridge})
 * provides the production implementation, backed by {@code
 * RegionizedTaskQueue.queueChunkTask(...)} for {@link #enqueueRegion} /
 * {@link #enqueueGlobal} and a shared async executor (either {@link
 * AsyncEventPool} or the existing {@code ServerDomains.async()} pool — see
 * {@code docs/design/m12-event-routing.md} §5's note on reusing rather than
 * duplicating the async pool) for {@link #enqueueAsync}.
 */
public interface DispatchExecutor {

    /**
     * Hand {@code task} to the region identified by {@code destination}.
     * The task lands in that region's inbox and runs the next time its
     * worker drains it — never blocks the calling thread.
     */
    void enqueueRegion(RegionId destination, Runnable task);

    /**
     * Hand {@code task} to the global region's inbox. Never blocks the
     * calling thread.
     */
    void enqueueGlobal(Runnable task);

    /**
     * Hand {@code task} to the shared async pool. Never blocks the calling
     * thread; the task must not touch region-owned game state.
     */
    void enqueueAsync(Runnable task);

    /**
     * Resolves the region that "owns" {@code event}'s target, per the
     * per-event-shape table in {@code docs/design/m12-event-routing.md}
     * §5.1 (block/entity/chunk position → region). Returns {@link
     * Optional#empty()} when the event has no derivable spatial location
     * (a mod-author error for a {@code REGION}-domain listener, or a
     * genuinely non-spatial event) — {@link DomainDispatcher} treats that
     * defensively rather than throwing.
     */
    Optional<RegionId> resolveEventLocation(Object event);
}
