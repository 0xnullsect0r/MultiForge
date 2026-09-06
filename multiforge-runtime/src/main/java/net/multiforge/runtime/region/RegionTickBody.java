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
package net.multiforge.runtime.region;

/**
 * The unit of work a {@link TickRegionScheduler} runs each tick per
 * region. In production this is bound by the M2 patch to Vanilla's
 * per-region tick body (block/fluid ticks, entity AI, block entities,
 * etc.). For M2 pre-vendored builds, the tick body is a synthetic
 * workload from the bench harness or a no-op.
 */
@FunctionalInterface
public interface RegionTickBody {
    /** Run one tick for {@code region}. Must not block. */
    void tickOnce(Region region);
}
