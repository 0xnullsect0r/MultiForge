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
package net.multiforge.runtime.globals;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.entity.EntityMigrationCoordinator;
import net.multiforge.runtime.entity.EntitySpawner;
import net.multiforge.runtime.region.RegionId;

/**
 * B2.6 — migrates {@code Raids.tick()} (wave scheduling / spawn
 * decisions, one instance per {@code ServerLevel}) off the Vanilla-inline
 * per-level tick and onto the synthetic global region's phase-4 tick slot
 * (docs/design/global-region.md §6.3).
 *
 * <h2>What migrates and what doesn't</h2>
 *
 * <p>Only the <em>entry point</em> — where {@code Raids.tick()}'s body
 * runs — migrates. The wave-timer state machine itself
 * ({@code Raid.tick()}, wave spawning, omen-level bookkeeping) stays
 * byte-identical Vanilla code, invoked via the fork-supplied {@link
 * RaidsTarget#tickRaidsBody()} callback exactly like {@code WeatherSystem}
 * (B2.1) and {@code WorldBorderSystem} (B2.3) already do for their own
 * Vanilla bodies (CLAUDE.md rule 3 — vanilla parity first). This class is
 * intentionally pure Java: it holds no {@code ServerLevel} or {@code
 * Raid} reference and computes no raid logic of its own.
 *
 * <h2>Cross-region raider spawn — the one place this class differs from
 * a plain B2low-style wrapper</h2>
 *
 * <p>Raids are not confined to a single region: a raid's center village
 * and its raider spawn rings can straddle a region boundary once the
 * regionizer splits the area around them. {@link #spawnRaider} is
 * therefore the <em>only</em> sanctioned way this class (or anything
 * calling into it) materializes a raider — it always routes through the
 * injected {@link EntitySpawner} (backed in production by {@link
 * EntityMigrationCoordinator#spawnInDestRegion}), which itself only ever
 * touches the destination region's entity registry from that region's
 * own worker thread, reached via {@code RegionizedTaskQueue.queueChunkTask}.
 * Nothing in this class ever holds or dereferences a destination
 * region's entity list directly (docs/design/global-region.md §8.2
 * integration test 6).
 *
 * <p>Other per-region raid side effects that are not entity creation —
 * ringing the village bell, broadcasting a raid-victory message to
 * nearby players — go through the inherited {@link #crossRegionEffect}
 * instead, via {@link #raidEffect(RegionId, Runnable)}.
 *
 * <p>One instance is registered for the whole server
 * ({@code GlobalSystems.register(new RaidsSystem(effects, spawner))});
 * it multiplexes over every currently-loaded world via {@link
 * #registerWorld}/{@link #unregisterWorld}, mirroring Vanilla's own
 * one-{@code Raids}-instance-per-{@code ServerLevel} shape.
 */
public final class RaidsSystem extends AbstractGlobalSystem {

    /**
     * Fork-supplied callback that runs the original (unmodified) {@code
     * Raids.mfTickBody()} for one world's {@code Raids} instance and
     * reports back whatever per-raid state it can observe. Never called
     * concurrently with itself for the same world — {@link #tick} runs
     * single-threaded on the global region.
     *
     * @return snapshots of every raid this world's {@code Raids}
     *     instance is currently tracking, or an empty list / {@code
     *     null} if the fork glue does not (yet) expose per-raid
     *     observation for this world — both are treated as "nothing to
     *     record this tick," never an error.
     */
    @FunctionalInterface
    public interface RaidsTarget {
        List<RaidStateSnapshot> tickRaidsBody();
    }

    private final EntitySpawner spawner;
    private final Map<WorldRef, RaidsTarget> targets = new ConcurrentHashMap<>();
    private final Map<RaidKey, RaidStateSnapshot> raidStates = new ConcurrentHashMap<>();
    private final AtomicLong globalTicks = new AtomicLong();

    public RaidsSystem(CrossRegionEffects effects, EntitySpawner spawner) {
        super(effects);
        this.spawner = Objects.requireNonNull(spawner, "spawner");
    }

    /**
     * Registers (or replaces) the raids target for {@code world}. Called
     * by fork glue when a {@code ServerLevel} loads. Idempotent — a
     * second call for the same world overwrites the previous target
     * rather than double-registering.
     */
    public void registerWorld(WorldRef world, RaidsTarget target) {
        targets.put(Objects.requireNonNull(world, "world"), Objects.requireNonNull(target, "target"));
    }

    /**
     * Deregisters {@code world} — called on {@code LevelEvent.Unload}.
     * Also drops any {@link RaidStateSnapshot}s this class was holding
     * for that world, since Vanilla's own {@code Raids} instance (and
     * its {@code raidMap}) is discarded along with the level.
     */
    public void unregisterWorld(WorldRef world) {
        targets.remove(world);
        String dimensionId = world.dimensionId();
        raidStates.keySet().removeIf(key -> key.worldId().equals(dimensionId));
    }

    /**
     * @return {@code true} if {@code world} currently has a registered
     *         raids target — the guard predicate the {@code Raids.tick}
     *         patch's delegate check is conceptually built on (see
     *         {@code GlobalSystemsBridge.raidsReady()}, which uses a
     *         coarser server-wide check rather than this per-world one —
     *         a deliberate simplification for M5, since {@code Raids}
     *         has no per-instance identity table the way {@code
     *         WorldBorder} does).
     */
    public boolean isHandling(WorldRef world) {
        return targets.containsKey(world);
    }

    @Override
    public String name() {
        return "raids";
    }

    @Override
    public void tick(GlobalTickContext ctx) {
        globalTicks.incrementAndGet();
        for (Map.Entry<WorldRef, RaidsTarget> entry : targets.entrySet()) {
            WorldRef world = entry.getKey();
            try {
                List<RaidStateSnapshot> snapshots = entry.getValue().tickRaidsBody();
                if (snapshots == null) continue;
                for (RaidStateSnapshot snapshot : snapshots) {
                    if (snapshot == null) continue;
                    raidStates.put(new RaidKey(world.dimensionId(), snapshot.raidId()), snapshot);
                }
            } catch (Throwable t) {
                // Defensive per-target isolation on top of GlobalSystems.tickAll's own
                // per-subsystem catch (docs/design/global-region.md §7) — one world's
                // raid state machine throwing must not skip every other world's raid
                // tick within the same tick() call, nor corrupt previously-observed
                // RaidStateSnapshots for that world.
                ProbeRegistry.bump("global.system.raids.failure." + world.dimensionId());
                ViolationLogger.warn(
                        "global.system.raids",
                        "Raids.mfTickBody failed for " + world.dimensionId() + ": "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    /**
     * @return the most recently observed {@link RaidStateSnapshot} for
     *     {@code raidId} in {@code world}, or {@code null} if none has
     *     been reported (never registered, not yet ticked, or the raid
     *     id/world pair is unknown).
     */
    public RaidStateSnapshot stateOf(WorldRef world, int raidId) {
        return raidStates.get(new RaidKey(world.dimensionId(), raidId));
    }

    /** Total number of {@link #tick} calls observed so far — test/diagnostics accessor. */
    public long globalTickCount() {
        return globalTicks.get();
    }

    /**
     * Raider spawn — ALWAYS routes via the injected {@link EntitySpawner}
     * (production: {@link EntityMigrationCoordinator#spawnInDestRegion}).
     * Never touches a destination region's entity list directly from
     * this thread; see this class's javadoc for the rationale
     * (docs/design/global-region.md §8.2 integration test 6).
     *
     * <p>Defensive: a throwing {@code raiderFactory} or spawner failure
     * is isolated to this one spawn attempt (CLAUDE.md rule 5) — it
     * never propagates into {@link #tick}'s caller.
     *
     * @param destWorld world the raider is created in.
     * @param destPos block position the raider is created at (a spawn
     *     ring point around the raid's village center).
     * @param raiderFactory invoked on the destination region's own
     *     worker thread (never on the calling — global-region — thread)
     *     to produce the new raider's identity/payload.
     */
    public void spawnRaider(
            WorldRef destWorld,
            BlockPos destPos,
            Function<BlockPos, EntityMigrationCoordinator.NewEntitySpec> raiderFactory) {
        Objects.requireNonNull(destWorld, "destWorld");
        Objects.requireNonNull(destPos, "destPos");
        Objects.requireNonNull(raiderFactory, "raiderFactory");
        try {
            spawner.spawnInDestRegion(destWorld, destPos, raiderFactory);
            ProbeRegistry.bump("global.system.raids.raider-spawn-routed");
        } catch (Throwable t) {
            ProbeRegistry.bump("global.system.raids.raider-spawn-failure");
            ViolationLogger.warn(
                    "global.system.raids",
                    "raider spawn routing failed for " + destWorld.dimensionId() + ": "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Per-region raid effect (village-bell-ring, raid-victory-message,
     * ...) — routed via the inherited {@link #crossRegionEffect}, never
     * a direct reference to a {@code ServerLevel}/{@code Region}
     * captured at registration time. {@code effect} is wrapped in its
     * own try/catch so a throwing effect gets its own probe/log bucket
     * (§7.3) rather than only relying on the destination region's
     * generic mailbox-drain catch.
     */
    public void raidEffect(RegionId destRegionId, Runnable effect) {
        Objects.requireNonNull(destRegionId, "destRegionId");
        Objects.requireNonNull(effect, "effect");
        crossRegionEffect(destRegionId, () -> {
            try {
                effect.run();
            } catch (Throwable t) {
                ProbeRegistry.bump("global.system.raids.effect-failure");
                ViolationLogger.warn(
                        "global.system.raids",
                        "raid effect threw: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        });
    }

    @Override
    public Set<WorldRef> readSet() {
        return Collections.unmodifiableSet(targets.keySet());
    }

    @Override
    public Set<WorldRef> writeSet() {
        return readSet();
    }

    /** Scopes a raw Vanilla raid id (per-{@code Raids}-instance, i.e. per-world) to its world. */
    private record RaidKey(String worldId, int raidId) {}
}
