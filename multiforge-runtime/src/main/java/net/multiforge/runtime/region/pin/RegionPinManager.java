/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
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
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;

/**
 * Operator-facing store of pinned region rectangles. Persisted to
 * {@code multiforge-regions.toml} next to the server config. The
 * adaptive sizer consults {@link #pinContaining(WorldRef, ChunkPos)}
 * before merge/split — a chunk covered by a pin is exempt from
 * automatic topology changes.
 *
 * <p>Thread-safe for concurrent {@code add}/{@code remove}/{@code
 * pinContaining}; the {@link #save()} method serializes the current
 * snapshot atomically. Callers that want live-update behavior should
 * subscribe via a {@code MultiForgeConfigStore}-shaped hook (M6).
 */
public final class RegionPinManager {

    private final Path file;
    private final ConcurrentMap<String, RegionPin> byId = new ConcurrentHashMap<>();

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
        return pin;
    }

    public RegionPin remove(String id) {
        return byId.remove(id);
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
