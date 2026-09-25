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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link LevelEntityGetter} that reads the level's entity storage under
 * the entity manager's lock and hands results to the caller's consumer only
 * after releasing it.
 *
 * <p>Region workers query entities (mob AI, collisions, item merging)
 * while other workers add, move and remove entities. The storage's hash
 * maps and per-section multimaps are not safe to read during a concurrent
 * write, so every query copies its matches under the lock. Consumers run
 * outside it: they are arbitrary game and mod code, and the lock is a leaf
 * that must never be held while such code runs (see {@code
 * PersistentEntitySectionManager.mfLock}). An abortable consumer sees the
 * same entities in the same order Vanilla would; the only difference is
 * that the copy is made before the first callback.
 */
public final class LockingEntityGetter<T extends EntityAccess> implements LevelEntityGetter<T> {
    private final LevelEntityGetter<T> delegate;
    private final Object lock;

    public LockingEntityGetter(LevelEntityGetter<T> delegate, Object lock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.lock = Objects.requireNonNull(lock, "lock");
    }

    @Nullable
    @Override
    public T get(int id) {
        synchronized (this.lock) {
            return this.delegate.get(id);
        }
    }

    @Nullable
    @Override
    public T get(UUID uuid) {
        synchronized (this.lock) {
            return this.delegate.get(uuid);
        }
    }

    /** A snapshot: Vanilla's live view would fail on concurrent modification. */
    @Override
    public Iterable<T> getAll() {
        List<T> out = new ArrayList<>();
        synchronized (this.lock) {
            this.delegate.getAll().forEach(out::add);
        }
        return out;
    }

    @Override
    public <U extends T> void get(EntityTypeTest<T, U> test, AbortableIterationConsumer<U> consumer) {
        List<U> matches = new ArrayList<>();
        synchronized (this.lock) {
            this.delegate.get(test, collect(matches));
        }
        replay(matches, consumer);
    }

    @Override
    public void get(AABB bounds, Consumer<T> consumer) {
        List<T> matches = new ArrayList<>();
        synchronized (this.lock) {
            this.delegate.get(bounds, matches::add);
        }
        matches.forEach(consumer);
    }

    @Override
    public <U extends T> void get(EntityTypeTest<T, U> test, AABB bounds, AbortableIterationConsumer<U> consumer) {
        List<U> matches = new ArrayList<>();
        synchronized (this.lock) {
            this.delegate.get(test, bounds, collect(matches));
        }
        replay(matches, consumer);
    }

    private static <U> AbortableIterationConsumer<U> collect(List<U> into) {
        return value -> {
            into.add(value);
            return AbortableIterationConsumer.Continuation.CONTINUE;
        };
    }

    private static <U> void replay(List<U> matches, AbortableIterationConsumer<U> consumer) {
        for (U value : matches) {
            if (consumer.accept(value).shouldAbort()) return;
        }
    }
}
