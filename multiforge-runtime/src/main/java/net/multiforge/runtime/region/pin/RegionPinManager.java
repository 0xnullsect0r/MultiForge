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
package net.multiforge.runtime.region.pin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;

/**
 * Operator-facing store of pinned region rectangles, persisted to
 * {@code config/multiforge-region-pins.json}. The regionizer keeps the
 * loaded chunks of each pin in a single region (see {@link RegionPin});
 * {@link #addChangeListener} lets it re-apply pins when one is added or
 * removed.
 *
 * <p>Thread-safe for concurrent {@code add}/{@code remove}/{@code
 * pinContaining}; the {@link #save()} method serializes the current
 * snapshot atomically.
 */
public final class RegionPinManager {

    private final Path file;
    private final ConcurrentMap<String, RegionPin> byId = new ConcurrentHashMap<>();
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    public RegionPinManager(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public static RegionPinManager load(Path file) throws IOException {
        RegionPinManager m = new RegionPinManager(file);
        if (Files.isRegularFile(file)) {
            String contents = Files.readString(file, StandardCharsets.UTF_8);
            for (RegionPin p : PinCodec.parse(contents)) m.byId.put(p.id(), p);
        }
        return m;
    }

    public RegionPin add(RegionPin pin) {
        Objects.requireNonNull(pin, "pin");
        RegionPin prev = byId.putIfAbsent(pin.id(), pin);
        if (prev != null) throw new IllegalStateException("pin id already in use: " + pin.id());
        fireChanged();
        return pin;
    }

    public RegionPin remove(String id) {
        RegionPin removed = byId.remove(id);
        if (removed != null) fireChanged();
        return removed;
    }

    /** Run {@code listener} after every successful {@link #add} / {@link #remove}, on the calling thread. */
    public AutoCloseable addChangeListener(Runnable listener) {
        changeListeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> changeListeners.remove(listener);
    }

    private void fireChanged() {
        for (Runnable l : changeListeners) l.run();
    }

    public RegionPin byId(String id) {
        return byId.get(id);
    }

    public Collection<RegionPin> all() {
        return List.copyOf(byId.values());
    }

    /** @return the pin covering {@code pos} in {@code world}, or null if none. */
    public RegionPin pinContaining(WorldRef world, ChunkPos pos) {
        for (RegionPin p : byId.values()) {
            if (!p.world().dimensionId().equals(world.dimensionId())) continue;
            if (p.contains(pos)) return p;
        }
        return null;
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }

    public int size() {
        return byId.size();
    }

    /** Persist the current snapshot to disk. */
    public synchronized void save() throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, PinCodec.render(byId.values()), StandardCharsets.UTF_8);
    }
}
