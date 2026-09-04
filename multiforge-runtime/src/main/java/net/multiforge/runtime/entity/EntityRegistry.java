/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.entity;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.multiforge.api.world.WorldRef;

/**
 * Per-server registry keyed by entity {@link UUID}. Bound by the M4
 * patch to Vanilla's {@code EntityLookup}; pure-Java code uses it to
 * hand entities between regions in tests.
 *
 * <p>Registry rows carry the live {@link MigratingEntityRef} plus the
 * entity's opaque payload (Vanilla NBT in the patched world; a String
 * in tests). Adding twice with the same UUID is an error.
 */
public final class EntityRegistry {

    public record Entry(MigratingEntityRef ref, String payload) {}

    private final ConcurrentMap<UUID, Entry> byUuid = new ConcurrentHashMap<>();

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
}
