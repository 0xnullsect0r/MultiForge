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
package net.multiforge.neoforge.world;

import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;
import net.multiforge.runtime.region.RegionPhase;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The level's {@link LevelEntityGetter}: Vanilla's getter, read under the
 * entity manager's lock whenever another thread could be writing the storage.
 *
 * <p><b>Fast path.</b> On the level's server thread while no region worker is
 * in flight ({@link RegionPhase#workersInFlight()}), nothing else writes the
 * entity storage: region work and server-thread work never overlap, and the
 * server thread is the writer. A query then goes straight to Vanilla's getter,
 * with no lock and no copy, and an abortable consumer stops Vanilla's own
 * iteration early. That covers mode {@code off}, a level with a single region
 * (ticked inline on the server thread), hot regions ticked on the server thread
 * after the workers, and everything the server thread does between ticks. The
 * thread check is required: the Vanilla watchdog thread reads {@link #getAll()}
 * ({@code ServerLevel.getWatchdogStats}) and a diagnostics thread may query at
 * any time. {@code perf.lockFreeOutsidePhase = false} turns the fast path off.
 *
 * <p><b>Locked path.</b> Region workers query entities (mob AI, collisions,
 * item merging) while other workers add, move and remove them. The storage's
 * hash maps and per-section multimaps are not safe to read during a concurrent
 * write, so the query collects its matches under the lock. Consumers run
 * outside it: they are arbitrary game and mod code, and the lock is a leaf that
 * must never be held while such code runs (see {@code
 * PersistentEntitySectionManager.mfLock}). An abortable consumer sees the same
 * entities in the same order Vanilla would; the only difference is that the
 * matches are collected before the first callback. The matches go into a
 * per-thread buffer indexed by call depth, so a consumer that queries again
 * (re-entrancy) gets a buffer of its own, and a query allocates nothing once
 * the buffers have grown; a buffer that grew past {@value #TRIM_ABOVE} entries
 * is released after use.
 */
@ApiStatus.Internal
public final class LockingEntityGetter<T extends EntityAccess> implements LevelEntityGetter<T> {
    /** A buffer that held more matches than this is dropped after the query. */
    static final int TRIM_ABOVE = 4096;

    private static volatile boolean lockFreeOutsidePhase = true;
    private static final ThreadLocal<Buffers> BUFFERS = ThreadLocal.withInitial(Buffers::new);

    private final LevelEntityGetter<T> delegate;
    private final Object lock;
    // The thread that owns the level's storage between region phases; null
    // until bound, which keeps every query on the locked path.
    private volatile Thread serverThread;

    public LockingEntityGetter(LevelEntityGetter<T> delegate, Object lock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.lock = Objects.requireNonNull(lock, "lock");
    }

    /** {@code perf.lockFreeOutsidePhase}: whether the server-thread fast path is on. */
    public static void setLockFreeOutsidePhase(boolean enabled) {
        lockFreeOutsidePhase = enabled;
    }

    public static boolean lockFreeOutsidePhase() {
        return lockFreeOutsidePhase;
    }

    /** The level's server thread (set once, from the {@code ServerLevel} constructor). */
    public void bindServerThread(@Nullable Thread thread) {
        this.serverThread = thread;
    }

    /** Vanilla's getter, unwrapped (tests compare against it). */
    public LevelEntityGetter<T> vanillaGetter() {
        return this.delegate;
    }

    /** Whether a query from the calling thread may read the storage without the lock. */
    public boolean isFastPath() {
        return lockFreeOutsidePhase && Thread.currentThread() == this.serverThread && !RegionPhase.workersInFlight();
    }

    @Nullable
    @Override
    public T get(int id) {
        if (this.isFastPath()) return this.delegate.get(id);
        synchronized (this.lock) {
            return this.delegate.get(id);
        }
    }

    @Nullable
    @Override
    public T get(UUID uuid) {
        if (this.isFastPath()) return this.delegate.get(uuid);
        synchronized (this.lock) {
            return this.delegate.get(uuid);
        }
    }

    /**
     * Vanilla's live view on the fast path; otherwise a snapshot, since the
     * live view would fail on concurrent modification.
     */
    @Override
    public Iterable<T> getAll() {
        if (this.isFastPath()) return this.delegate.getAll();
        ArrayList<T> out = new ArrayList<>();
        synchronized (this.lock) {
            for (T value : this.delegate.getAll()) out.add(value);
        }
        return out;
    }

    @Override
    public <U extends T> void get(EntityTypeTest<T, U> test, AbortableIterationConsumer<U> consumer) {
        if (this.isFastPath()) {
            this.delegate.get(test, consumer);
            return;
        }
        Buffers buffers = BUFFERS.get();
        Buffer matches = buffers.acquire();
        try {
            synchronized (this.lock) {
                this.delegate.get(test, matches.<U>abortable());
            }
            matches.replay(consumer);
        } finally {
            buffers.release(matches);
        }
    }

    @Override
    public void get(AABB bounds, Consumer<T> consumer) {
        if (this.isFastPath()) {
            this.delegate.get(bounds, consumer);
            return;
        }
        Buffers buffers = BUFFERS.get();
        Buffer matches = buffers.acquire();
        try {
            synchronized (this.lock) {
                this.delegate.get(bounds, matches.<T>plain());
            }
            matches.replay(consumer);
        } finally {
            buffers.release(matches);
        }
    }

    @Override
    public <U extends T> void get(EntityTypeTest<T, U> test, AABB bounds, AbortableIterationConsumer<U> consumer) {
        if (this.isFastPath()) {
            this.delegate.get(test, bounds, consumer);
            return;
        }
        Buffers buffers = BUFFERS.get();
        Buffer matches = buffers.acquire();
        try {
            synchronized (this.lock) {
                this.delegate.get(test, bounds, matches.<U>abortable());
            }
            matches.replay(consumer);
        } finally {
            buffers.release(matches);
        }
    }

    /** One thread's buffers, one per nesting depth of queries in progress. */
    private static final class Buffers {
        private Buffer[] byDepth = new Buffer[4];
        private int depth;

        Buffer acquire() {
            if (this.depth == this.byDepth.length) this.byDepth = java.util.Arrays.copyOf(this.byDepth, this.depth * 2);
            Buffer buffer = this.byDepth[this.depth];
            if (buffer == null) {
                buffer = new Buffer();
                this.byDepth[this.depth] = buffer;
            }
            this.depth++;
            return buffer;
        }

        void release(Buffer buffer) {
            int size = buffer.size();
            buffer.clear();
            // Drop a buffer that grew huge instead of pinning its array forever.
            if (size > TRIM_ABOVE) this.byDepth[this.depth - 1] = null;
            this.depth--;
        }
    }

    /**
     * A reusable match list with its collecting consumers made once, so a query
     * allocates neither a list nor a lambda.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static final class Buffer extends ArrayList<Object> {
        private final AbortableIterationConsumer<Object> abortable = this::collect;
        private final Consumer<Object> plain = this::add;

        Buffer() {
            super(32);
        }

        private AbortableIterationConsumer.Continuation collect(Object value) {
            this.add(value);
            return AbortableIterationConsumer.Continuation.CONTINUE;
        }

        <U> AbortableIterationConsumer<U> abortable() {
            return (AbortableIterationConsumer) this.abortable;
        }

        <U> Consumer<U> plain() {
            return (Consumer) this.plain;
        }

        <U> void replay(AbortableIterationConsumer<U> consumer) {
            for (int i = 0, n = this.size(); i < n; i++) {
                if (consumer.accept((U) this.get(i)).shouldAbort()) return;
            }
        }

        <U> void replay(Consumer<U> consumer) {
            for (int i = 0, n = this.size(); i < n; i++) consumer.accept((U) this.get(i));
        }
    }
}
