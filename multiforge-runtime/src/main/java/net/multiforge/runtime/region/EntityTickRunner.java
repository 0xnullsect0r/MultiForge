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

/**
 * MC-free abstraction for the B3.3 {@code ENTITY_AI} phase body
 * (docs/design/m13-b3-region-tick.md §5.2): given a region, tick every
 * entity that region currently owns.
 *
 * <p>{@code multiforge-runtime} has no dependency on Minecraft classes,
 * so this interface says nothing about how "the entities this region
 * owns" is resolved or what ticking one actually does — that is the
 * Vanilla-backed implementation's job. In production, {@code
 * net.multiforge.neoforge.tick.EntityTickRunnerBridge} (the fork
 * bridge, in {@code upstream/neoforge-1.21.1}) implements this by
 * walking {@link Region#ownedChunkSnapshot()}, resolving each owned
 * chunk's holder, and invoking the patched {@code
 * ServerLevel.mfTickEntitiesForChunk} for it. Tests use a fake
 * implementation that records invocations without touching any MC
 * type.
 *
 * <p>Bound onto {@link net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost}
 * via {@code setEntityTickRunner}, and invoked from the {@code
 * ENTITY_AI} phase body ({@code phaseEntityAiTick}) — which is
 * responsible for the {@link net.multiforge.runtime.ownership.OwnerToken}
 * correctness guard (docs/design/m13-b3-region-tick.md §5.2's second
 * concurrency invariant: no entity is ticked on a thread that isn't
 * its owning region's worker) before this method is ever called, and
 * for a defensive try/catch around the call so one region's entity-ai
 * failure never strands another phase in the same tick (CLAUDE.md rule
 * 5 — auto-reroute + warn is the default, never let a thrown exception
 * escape a region worker's tick loop).
 */
@FunctionalInterface
public interface EntityTickRunner {

    /**
     * Tick every entity {@code region} currently owns. Implementations
     * must not touch any chunk or entity outside {@code
     * region.ownedChunkSnapshot()} (docs/design/m13-b3-region-tick.md
     * §5.2/§6 invariant 1 — no entity is ticked twice per server tick,
     * no entity is ticked on a foreign region's behalf) and must not
     * block (CLAUDE.md rule 4) — this is called from the region
     * worker's own tick loop.
     *
     * @param region the region whose owned entities should be ticked;
     *               never {@code null}.
     */
    void tickEntitiesForRegion(Region region);
}
