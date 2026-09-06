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
package net.multiforge.runtime.globals;

import net.multiforge.runtime.region.RegionId;

/**
 * Per-invocation context handed to every registered {@link
 * GlobalSystem#tick(GlobalTickContext)} call. Carries only what a
 * subsystem cannot derive on its own — no {@code ServerLevel}, no
 * Minecraft type, keeping this class (and the whole {@code globals}
 * package) usable from multiforge-runtime's MC-free test suite.
 *
 * <p>{@code globalTick} is the post-increment counter from {@link
 * GlobalSystems#currentTick()} — the same counter {@link
 * GlobalTicker#tick(long)} already receives, just threaded through a
 * named record instead of a bare {@code long} so a future field (a
 * wall-clock timestamp, a "first tick since boot" flag) can be added
 * without another signature break.
 *
 * <p>{@code globalRegionId} is the {@link RegionId} of the synthetic
 * global region this tick is running against — see
 * {@code docs/design/global-region.md} §2.4.
 */
public record GlobalTickContext(long globalTick, RegionId globalRegionId) {}
