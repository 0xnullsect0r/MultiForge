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

import java.util.Set;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.RegionId;

/**
 * A fixed, registration-time-known engine subsystem that must run once
 * every global tick on the synthetic global region. Contrast with
 * {@code net.multiforge.api.scheduler.GlobalDomain}, the mod-facing
 * ad-hoc task scheduler that also executes on the global region but has
 * no fixed identity or read/write-set declaration.
 *
 * <p>Implementations MUST be side-effect-free w.r.t. any region other
 * than one reached through {@link #crossRegionEffect}: never touch a
 * {@code ServerLevel}/entity/block belonging to another region's chunks
 * directly from {@link #tick(GlobalTickContext)} (CLAUDE.md rule 4,
 * applied to the global region).
 *
 * <p>See {@code docs/design/global-region.md} §3 for the full frozen
 * contract this interface implements.
 */
public interface GlobalSystem {

    /**
     * Stable, {@code lower_snake_case} name — the probe/log key (see
     * {@code docs/design/global-region.md} §7.3) and the debug-protocol
     * subsystem identifier the M6 client HUD lists. Must be stable
     * across restarts.
     */
    String name();

    /**
     * Main body, run once per global tick at phase 4 ({@code
     * BLOCK_ENTITIES}), in registration order. Must complete within its
     * share of the region's 50 ms budget (CLAUDE.md rule 4) — no
     * per-system deadline enforcement in M5.
     */
    void tick(GlobalTickContext ctx);

    /**
     * Advisory-only in M5: declares, per {@link WorldRef}, what this
     * subsystem reads. Nothing in {@link GlobalSystems#tickAll} consults
     * this set to serialize, reorder, or reject conflicting subsystems
     * yet — see {@code docs/design/global-region.md} §3.4.
     */
    Set<WorldRef> readSet();

    /**
     * Advisory-only in M5: declares, per {@link WorldRef}, what this
     * subsystem writes. See {@link #readSet()} and {@code
     * docs/design/global-region.md} §3.4.
     */
    Set<WorldRef> writeSet();

    /**
     * The only sanctioned way a global subsystem reaches into a
     * specific region: routed through {@code
     * RegionizedTaskQueue.queueChunkTask}, never a direct reference to
     * a {@code ServerLevel}/{@code Region} captured at construction
     * time (which could be stale by the time {@link #tick} runs, across
     * a merge/split). See {@code docs/design/global-region.md} §3.5.
     */
    void crossRegionEffect(RegionId dest, Runnable task);
}
