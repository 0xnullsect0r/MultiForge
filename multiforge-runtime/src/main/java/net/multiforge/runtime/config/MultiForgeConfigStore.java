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
package net.multiforge.runtime.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Thread-safe holder for the current {@link MultiForgeConfig} plus a
 * subscribe/notify path for subsystems (scheduler pool size, region
 * sizer thresholds, violation logger budget) that need to react to
 * live changes.
 *
 * <p>Every mutation atomically publishes a fresh snapshot and fires
 * every registered listener with that snapshot. Listeners run on the
 * calling thread — if they need to be async, they should hop through
 * {@code ServerDomains.async()}.
 */
public final class MultiForgeConfigStore {

    private final Path file;
    private final AtomicReference<MultiForgeConfig> current;
    private final CopyOnWriteArrayList<Consumer<MultiForgeConfig>> listeners = new CopyOnWriteArrayList<>();

    public MultiForgeConfigStore(Path file, MultiForgeConfig initial) {
        this.file = Objects.requireNonNull(file, "file");
        this.current = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    public static MultiForgeConfigStore load(Path file) throws IOException {
        return new MultiForgeConfigStore(file, ConfigCodec.load(file));
    }

    public MultiForgeConfig get() {
        return current.get();
    }

    /** Register a listener; fires immediately with the current snapshot. */
    public void subscribe(Consumer<MultiForgeConfig> listener) {
        listeners.add(listener);
        listener.accept(current.get());
    }

    public void unsubscribe(Consumer<MultiForgeConfig> listener) {
        listeners.remove(listener);
    }

    /**
     * Apply {@code mutator} to the current snapshot, persist the result
     * to disk, and notify subscribers.
     */
    public synchronized MultiForgeConfig update(java.util.function.UnaryOperator<MultiForgeConfig> mutator)
            throws IOException {
        MultiForgeConfig next = Objects.requireNonNull(mutator.apply(current.get()), "mutator returned null");
        current.set(next);
        ConfigCodec.save(file, next);
        for (Consumer<MultiForgeConfig> l : listeners) l.accept(next);
        return next;
    }
}
