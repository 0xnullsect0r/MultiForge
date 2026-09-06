/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.entity;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.multiforge.api.world.WorldRef;

/**
 * Per-server registry keyed by entity {@link UUID}. Bound by the M4 patch to Vanilla's {@code
 * EntityLookup}; pure-Java code uses it to hand entities between regions in tests.
 *
 * <p>Registry rows carry the live {@link MigratingEntityRef} plus the entity's opaque payload
 * (Vanilla NBT in the patched world; a String in tests). Adding twice with the same UUID is an
 * error.
 *
 * <h2>Retired-UUID lookups (docs/design/entity-migration.md §4)</h2>
 *
 * <p>A task in flight — a scheduled effect, a delayed packet, a scoreboard callback — can hold a
 * bare {@link UUID} and resolve it against this registry well after the entity has migrated away
 * or been retired. Rather than returning {@code null} indistinguishably for "never existed" and
 * "just retired," a retired entity's ref is kept resolvable for a bounded window ({@link
 * #RETIRED_TTL_TICKS} game ticks) in {@link #retiredRefs}, so {@link #lookup(UUID)} returns a ref
 * whose {@link MigratingEntityRef#migrationState()} is {@link MigrationState#RETIRED} instead of
 * a bare {@code null} — callers branch on that instead of NPE-ing or resurrecting the entity in
 * the wrong region.
 *
 * <p>No Caffeine or any other new dependency is used for this — per the M4+M5+M6 landing plan's
 * explicit answer and CLAUDE.md's dependency gate — just a {@link ConcurrentHashMap} plus a
 * single fixed-rate {@link ScheduledExecutorService} sweep.
 */
public final class EntityRegistry implements AutoCloseable {

    /** 200 ticks = 10s at 20 TPS — see docs/design/entity-migration.md §4.4 for the sizing rationale. */
    static final long RETIRED_TTL_TICKS = 200L;

    /** Wall-clock cadence of the sweep itself; independent of the tick-based TTL above. */
    private static final long SWEEP_PERIOD_MS = 2000L;

    public record Entry(MigratingEntityRef ref, String payload) {}

    /** {@code retiredRefs} row: the terminal ref plus the tick at which it becomes evictable. */
    private record RetiredEntry(MigratingEntityRef ref, long expiryTick) {}

    private static final AtomicInteger SWEEPER_SEQ = new AtomicInteger();

    private final ConcurrentMap<UUID, Entry> byUuid = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, RetiredEntry> retiredRefs = new ConcurrentHashMap<>();

    /**
     * Game-tick clock this registry expires {@link #retiredRefs} against. Advanced by whatever
     * external tick source owns the server clock (e.g. {@code MultiThreadedSchedulerHost}) via
     * {@link #setCurrentTick(long)}; the registry never runs its own clock, so a debugger-paused
     * server does not silently evict everything the moment it resumes.
     */
    private final AtomicLong currentTick = new AtomicLong();

    private final ScheduledExecutorService retiredSweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mf-entity-registry-sweeper-" + SWEEPER_SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    public EntityRegistry() {
        retiredSweeper.scheduleAtFixedRate(this::sweepRetired, SWEEP_PERIOD_MS, SWEEP_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    public Entry add(MigratingEntityRef ref, String payload) {
        Objects.requireNonNull(ref, "ref");
        Entry entry = new Entry(ref, payload == null ? "" : payload);
        Entry prev = byUuid.putIfAbsent(ref.uuid(), entry);
        if (prev != null) throw new IllegalStateException("Entity already registered: " + ref.uuid());
        return entry;
    }

    public Entry get(UUID uuid) {
        return byUuid.get(uuid);
    }

    public Entry remove(UUID uuid) {
        return byUuid.remove(uuid);
    }

    public boolean containsInWorld(UUID uuid, WorldRef world) {
        Entry e = byUuid.get(uuid);
        return e != null && world.dimensionId().equals(e.ref.world().dimensionId());
    }

    public int size() {
        return byUuid.size();
    }

    // === Retired-ref bookkeeping (docs/design/entity-migration.md §4) =====

    /**
     * Move {@code ref} from the live map into {@link #retiredRefs}, expiring {@link
     * #RETIRED_TTL_TICKS} ticks after {@code nowTick}. Idempotent w.r.t. the live map (a UUID not
     * present there is simply not removed).
     */
    public void retire(MigratingEntityRef ref, long nowTick) {
        Objects.requireNonNull(ref, "ref");
        byUuid.remove(ref.uuid());
        retiredRefs.put(ref.uuid(), new RetiredEntry(ref, nowTick + RETIRED_TTL_TICKS));
    }

    /** Convenience overload using this registry's own tracked {@link #currentTick}. */
    public void retire(MigratingEntityRef ref) {
        retire(ref, currentTick.get());
    }

    /**
     * Cross-region-safe lookup (docs/design/entity-migration.md §4.3): live entries win, then
     * unexpired {@link #retiredRefs} entries (returned ref reports {@code migrationState() ==
     * RETIRED}), else {@code null} for a truly unknown or long-evicted UUID.
     */
    public MigratingEntityRef lookup(UUID uuid) {
        Entry live = byUuid.get(uuid);
        if (live != null) return live.ref();
        RetiredEntry retired = retiredRefs.get(uuid);
        return retired == null ? null : retired.ref();
    }

    /** Count of entries currently held in {@link #retiredRefs} (test/diagnostics accessor). */
    public int retiredCount() {
        return retiredRefs.size();
    }

    /** Advance (or set) the tick clock {@link #retiredRefs} TTLs are measured against. */
    public void setCurrentTick(long tick) {
        currentTick.set(tick);
    }

    public long currentTick() {
        return currentTick.get();
    }

    /** Evicts every {@link #retiredRefs} entry whose expiry tick has passed. Runs on the sweeper thread. */
    private void sweepRetired() {
        long now = currentTick.get();
        retiredRefs.entrySet().removeIf(e -> e.getValue().expiryTick() < now);
    }

    /** Shuts down the sweeper executor. Idempotent. */
    @Override
    public void close() {
        retiredSweeper.shutdownNow();
        try {
            retiredSweeper.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
