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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.Ticket;
import net.multiforge.runtime.chunk.TicketType;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.entity.EntityMigrationCoordinator;
import net.multiforge.runtime.entity.EntitySpawner;
import net.multiforge.runtime.region.RegionId;

/**
 * B2.7 — migrates {@code EndDragonFight.tick()} off the Vanilla-inline
 * per-level tick and onto the synthetic global region's phase-4 tick slot
 * (docs/design/global-region.md §6.3), pinning the End-podium/arena
 * chunks with the new {@link TicketType#DRAGON} (§5) for the duration of
 * an active fight.
 *
 * <p><b>Why this differs from {@link WeatherSystem}/{@link TimeSystem}.</b>
 * Those B2low subsystems call their fork-supplied target directly from
 * {@link #tick(GlobalTickContext)} because the Vanilla bodies they wrap
 * only mutate scalar {@code ServerLevel} fields — nothing chunk- or
 * entity-owned. {@code EndDragonFight.tick()} is materially different: it
 * spawns/reads {@code EnderDragon}/{@code EndCrystal} entities, places
 * blocks (the exit portal, the dragon egg), and reads block entities —
 * all resources genuinely owned by whichever region holds the End arena's
 * chunks, not by the global region. Per {@link GlobalSystem}'s contract
 * ("never touch a ServerLevel/entity/block belonging to another region's
 * chunks directly from tick()", CLAUDE.md rule 4), this class never calls
 * {@link DragonFightTarget#tickBody()} inline — every invocation is
 * dispatched through {@link #crossRegionEffect} onto the region that
 * currently owns the fight's arena chunks, landing on that region's own
 * worker thread via {@code RegionizedTaskQueue}. This is the highest-risk
 * of the eight B2.x subsystems (complex cross-chunk state machine); every
 * public method here is defensive — a throw from fork glue, a
 * not-yet-resolved region, or a missing collaborator degrades to a
 * rate-limited warning + probe bump and never propagates (CLAUDE.md rule
 * 5).
 *
 * <p>One instance is registered for the whole server; it multiplexes over
 * every currently-loaded End-dimension level via {@link #registerWorld}/
 * {@link #unregisterWorld}, mirroring {@link WeatherSystem}'s per-world
 * target map shape. {@link DragonFightTarget#tickBody()} itself is
 * <em>not</em> reimplemented here — the original, unmodified Vanilla
 * method body (extracted verbatim by the {@code EndDragonFight.java.patch}
 * hunk into {@code EndDragonFight.mfTickBody()}) still computes the
 * actual state machine; what migrates is <em>where</em> and <em>on which
 * thread</em> that call happens, plus the {@link TicketType#DRAGON} pin
 * lifecycle this class now owns instead of Vanilla's own {@code
 * addRegionTicket}/{@code removeRegionTicket} calls inside that body
 * (docs/design/global-region.md §5.2's "DragonFightSystem is solely
 * responsible for removing the DRAGON ticket" corollary).
 */
public final class DragonFightSystem extends AbstractGlobalSystem {

    /**
     * Fork-supplied bridge to one End level's live {@code EndDragonFight}
     * instance. Every method here is called from the global region's
     * tick thread — implementations must not block (CLAUDE.md rule 4)
     * and must tolerate being called on a level whose fight state is
     * transiently unresolved (return {@code null}/{@code false}/an empty
     * list rather than throwing; a throw is still caught defensively by
     * this class, but a graceful "not ready yet" is strictly better for
     * fight-progression latency).
     */
    public interface DragonFightTarget {

        /**
         * @return the {@link RegionId} that currently owns this level's
         *         arena chunks, or {@code null} if not yet resolvable
         *         (e.g. the level is still loading). {@code null}
         *         defers this tick's {@code tickBody()} dispatch and
         *         ticket-pin bookkeeping — never a crash.
         */
        RegionId currentRegion();

        /**
         * @return the {@link ChunkHolderManager} for this level's world,
         *         used to add/remove the {@link TicketType#DRAGON}
         *         pin. May return {@code null} if not wired yet — ticket
         *         bookkeeping is skipped for this tick in that case.
         */
        ChunkHolderManager chunkHolderManager();

        /**
         * @return the chunk positions that should carry the {@link
         *         TicketType#DRAGON} pin while {@link #shouldPin()} is
         *         {@code true} — the end-podium plus the vanilla
         *         {@code ARENA_SIZE_CHUNKS} radius around the fight
         *         origin. Fork glue computes the actual set from the
         *         live {@code EndDragonFight}'s origin; an empty list is
         *         treated as "nothing to pin yet."
         */
        List<ChunkPos> arenaChunks();

        /**
         * @return {@code true} if the arena should currently be pinned
         *         — mirrors Vanilla's own {@code
         *         !dragonEvent.getPlayers().isEmpty()} gate
         *         ({@code EndDragonFight.tick()}, line 160).
         */
        boolean shouldPin();

        /**
         * Runs the original, unmodified {@code EndDragonFight.tick()}
         * body (extracted to {@code mfTickBody()} by the patch). Called
         * only via {@link #crossRegionEffect} from {@link #tick}, never
         * inline — see this class's javadoc.
         */
        void tickBody();

        /**
         * Polls (and clears) a pending fresh-dragon-entity spawn — set
         * by fork glue when {@code EndDragonFight}'s respawn animation
         * has just completed (Vanilla's {@code setRespawnStage(END)} →
         * {@code createNewDragon()}) and the new {@code EnderDragon}
         * still needs to be added to the level. Returning non-null twice
         * for the "same" spawn would double-spawn a dragon — fork glue
         * must clear its own pending-spawn flag before returning it
         * here. Default {@code null} (nothing pending) for targets that
         * don't yet wire respawn detection.
         */
        default PendingDragonSpawn pollPendingSpawn() {
            return null;
        }
    }

    /**
     * A fresh {@code EnderDragon} entity waiting to be materialized into
     * the arena's owning region. {@code factory} is the fork-supplied
     * closure that actually constructs the dragon, calls {@code
     * ServerLevel.addFreshEntity}, and returns its identity — dispatched
     * via the injected {@link EntitySpawner} (production: {@link
     * EntityMigrationCoordinator#spawnInDestRegion}) exactly the way
     * {@link RaidsSystem#spawnRaider} routes a raider spawn, so it runs
     * on the destination region's own worker thread, never on the global
     * region thread that observed the pending spawn.
     */
    public record PendingDragonSpawn(
            BlockPos spawnPos, Function<BlockPos, EntityMigrationCoordinator.NewEntitySpec> factory) {
        public PendingDragonSpawn {
            Objects.requireNonNull(spawnPos, "spawnPos");
            Objects.requireNonNull(factory, "factory");
        }
    }

    private final EntitySpawner spawner;
    private final Map<WorldRef, DragonFightTarget> targets = new ConcurrentHashMap<>();

    /** Per-world pin state — {@code true} once {@link TicketType#DRAGON} has been added and not yet removed. */
    private final Map<WorldRef, Boolean> pinned = new ConcurrentHashMap<>();

    public DragonFightSystem(CrossRegionEffects effects, EntitySpawner spawner) {
        super(effects);
        this.spawner = Objects.requireNonNull(spawner, "spawner");
    }

    /**
     * Registers (or replaces) the dragon-fight target for {@code world}.
     * Called by fork glue on {@code LevelEvent.Load} when the loaded
     * level's dimension is the End. Idempotent — a second call for the
     * same world overwrites the previous target.
     */
    public void registerWorld(WorldRef world, DragonFightTarget target) {
        targets.put(Objects.requireNonNull(world, "world"), Objects.requireNonNull(target, "target"));
    }

    /**
     * Deregisters {@code world} — called on {@code LevelEvent.Unload}.
     * Per docs/design/global-region.md §5.2, a leaked {@code DRAGON}
     * ticket pins its chunks forever, so this proactively releases the
     * pin (best-effort — a failure here is warned, not thrown) before
     * forgetting the target.
     */
    public void unregisterWorld(WorldRef world) {
        Objects.requireNonNull(world, "world");
        DragonFightTarget target = targets.remove(world);
        Boolean wasPinned = pinned.remove(world);
        if (target == null || !Boolean.TRUE.equals(wasPinned)) {
            return;
        }
        try {
            unpin(world, target);
        } catch (Throwable t) {
            ProbeRegistry.bump("global.system.dragon_fight.unregister-unpin-failure." + world.dimensionId());
            ViolationLogger.warn(
                    "global.system.dragon_fight",
                    "failed to release DRAGON ticket on unload for " + world.dimensionId() + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * @return {@code true} if {@code world} currently has a registered
     *         dragon-fight target — the {@code EndDragonFight.tick()}
     *         patch's no-op guard (via {@code
     *         GlobalSystemsBridge.dragonFightReady()}).
     */
    public boolean isHandling(WorldRef world) {
        return targets.containsKey(world);
    }

    @Override
    public String name() {
        return "dragon_fight";
    }

    @Override
    public void tick(GlobalTickContext ctx) {
        for (Map.Entry<WorldRef, DragonFightTarget> entry : targets.entrySet()) {
            WorldRef world = entry.getKey();
            DragonFightTarget target = entry.getValue();
            try {
                tickOne(world, target, ctx);
            } catch (Throwable t) {
                // Defensive per-world isolation on top of GlobalSystems.tickAll's own
                // per-subsystem catch (docs/design/global-region.md §7) — one End
                // level's fight throwing must not skip every other loaded End
                // level within the same tick() call.
                ProbeRegistry.bump("global.system.dragon_fight.failure." + world.dimensionId());
                ViolationLogger.warn(
                        "global.system.dragon_fight",
                        "tick failed for " + world.dimensionId() + ": "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    private void tickOne(WorldRef world, DragonFightTarget target, GlobalTickContext ctx) {
        managePin(world, target);

        RegionId owner = target.currentRegion();
        if (owner == null) {
            // Arena chunks not yet resolvable (level still loading, or a
            // transient regionizer gap) — auto-reroute+warn: skip this
            // tick's phase advance rather than touching a stale/absent
            // region reference (CLAUDE.md rule 5).
            ProbeRegistry.bump("global.system.dragon_fight.no-region." + world.dimensionId());
            return;
        }
        crossRegionEffect(owner, () -> runTickBodySafely(world, target));

        PendingDragonSpawn spawn = target.pollPendingSpawn();
        if (spawn != null) {
            dispatchPendingSpawn(world, spawn);
        }
    }

    /** Runs on the destination region's own worker thread, delivered via {@link #crossRegionEffect}. */
    private void runTickBodySafely(WorldRef world, DragonFightTarget target) {
        try {
            target.tickBody();
        } catch (Throwable t) {
            ProbeRegistry.bump("global.system.dragon_fight.tick-body-failure." + world.dimensionId());
            ViolationLogger.warn(
                    "global.system.dragon_fight",
                    "tickBody() threw for " + world.dimensionId() + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void dispatchPendingSpawn(WorldRef world, PendingDragonSpawn spawn) {
        try {
            spawner.spawnInDestRegion(world, spawn.spawnPos(), spawn.factory());
            ProbeRegistry.bump("global.system.dragon_fight.spawn-routed." + world.dimensionId());
        } catch (Throwable t) {
            ProbeRegistry.bump("global.system.dragon_fight.spawn-failure." + world.dimensionId());
            ViolationLogger.warn(
                    "global.system.dragon_fight",
                    "failed to dispatch dragon respawn for " + world.dimensionId() + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Adds/removes the {@link TicketType#DRAGON} pin for {@code world}
     * as {@link DragonFightTarget#shouldPin()} transitions, per
     * docs/design/global-region.md §5.1/§5.3. Ticket writes go straight
     * to {@link ChunkHolderManager} (not through {@link
     * #crossRegionEffect}) — {@code addTicket}/{@code removeTicket} are
     * explicitly designed to be called from any thread (the round-5 H4
     * regionizer-read-lock fix), the same way {@code
     * EntityMigrationCoordinator} calls them directly.
     */
    private void managePin(WorldRef world, DragonFightTarget target) {
        boolean shouldPin;
        try {
            shouldPin = target.shouldPin();
        } catch (Throwable t) {
            ProbeRegistry.bump("global.system.dragon_fight.should-pin-failure." + world.dimensionId());
            ViolationLogger.warn(
                    "global.system.dragon_fight",
                    "shouldPin() threw for " + world.dimensionId() + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return;
        }
        boolean wasPinned = Boolean.TRUE.equals(pinned.get(world));
        if (shouldPin && !wasPinned) {
            if (pin(world, target)) {
                pinned.put(world, Boolean.TRUE);
            }
        } else if (!shouldPin && wasPinned) {
            unpin(world, target);
            pinned.put(world, Boolean.FALSE);
        }
    }

    /** @return {@code true} if the pin was applied (or there was nothing to pin yet, treated as a deferred success). */
    private boolean pin(WorldRef world, DragonFightTarget target) {
        ChunkHolderManager manager = target.chunkHolderManager();
        RegionId owner = target.currentRegion();
        if (manager == null || owner == null) {
            // Not wired yet — defer to a later tick rather than marking pinned
            // with nothing actually added (would desync the pin/unpin state).
            return false;
        }
        List<ChunkPos> chunks = target.arenaChunks();
        if (chunks == null || chunks.isEmpty()) {
            return false;
        }
        Ticket ticket = dragonTicket(world);
        for (ChunkPos pos : chunks) {
            manager.addTicket(owner, pos, ticket);
        }
        return true;
    }

    private void unpin(WorldRef world, DragonFightTarget target) {
        ChunkHolderManager manager = target.chunkHolderManager();
        RegionId owner = target.currentRegion();
        if (manager == null || owner == null) {
            ProbeRegistry.bump("global.system.dragon_fight.unpin-no-manager." + world.dimensionId());
            ViolationLogger.warn(
                    "global.system.dragon_fight",
                    "could not release DRAGON ticket for " + world.dimensionId()
                            + " — no ChunkHolderManager/region resolvable this tick");
            return;
        }
        List<ChunkPos> chunks = target.arenaChunks();
        if (chunks == null) {
            return;
        }
        Ticket ticket = dragonTicket(world);
        for (ChunkPos pos : chunks) {
            manager.removeTicket(owner, pos, ticket);
        }
    }

    /**
     * Builds the {@link TicketType#DRAGON} ticket for {@code world}.
     * Keyed by {@link WorldRef#dimensionId()} (§5.3 — at most one active
     * fight per End dimension) and constructed via {@link Ticket#of}
     * (unknown creation tick, {@code -1}) rather than a live tick number
     * so repeated calls across many ticks — and the eventual matching
     * {@code removeTicket} — always produce an equal {@link Ticket}
     * (record equality is over every component, including {@code
     * createdAtTick}; {@code DRAGON}'s {@code timeoutTicks() == 0} means
     * the omitted creation tick never affects expiry either way, see
     * §5.2).
     */
    private static Ticket dragonTicket(WorldRef world) {
        return Ticket.of(TicketType.DRAGON, world.dimensionId());
    }

    @Override
    public Set<WorldRef> readSet() {
        return Collections.unmodifiableSet(targets.keySet());
    }

    @Override
    public Set<WorldRef> writeSet() {
        return readSet();
    }
}
