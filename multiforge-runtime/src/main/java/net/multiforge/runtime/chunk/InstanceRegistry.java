/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.chunk;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * A tiny generic key-to-instance registry backing the Vanilla-adjacent
 * facades in {@code net.multiforge.neoforge.chunk.*} that need to be
 * discoverable by an observability-seam call site holding only the
 * upstream anchor (typically a {@code ServerLevel}). Extracted from the
 * facades in Phase 4.1d so the pure registry behaviour is unit-testable
 * from the MC-free runtime module.
 *
 * <p><b>Storage semantics:</b> keys are held weakly (a {@link
 * WeakHashMap}), values strongly. Once the caller drops its own strong
 * reference to a key, the entry becomes eligible for reclamation and
 * disappears on the next GC — the facade "unregister on world unload"
 * path is a hint, not a leak-prevention requirement.
 *
 * <p><b>Threading:</b> every operation is safe from any thread; the
 * backing map is wrapped in {@link Collections#synchronizedMap}.
 * Iteration is not exposed.
 */
public final class InstanceRegistry<K, V> {

    private final Map<K, V> map;

    private InstanceRegistry(Map<K, V> backing) {
        this.map = backing;
    }

    /** A new registry with weakly-held keys. */
    public static <K, V> InstanceRegistry<K, V> weak() {
        return new InstanceRegistry<>(Collections.synchronizedMap(new WeakHashMap<>()));
    }

    /**
     * Register {@code value} under {@code key}. Idempotent: a second call
     * with the same key replaces the previously registered value. A
     * {@code null} key is silently ignored (matches the "no anchor, no
     * observer" invariant of the facades).
     */
    public void register(K key, V value) {
        if (key == null) {
            return;
        }
        map.put(key, value);
    }

    /** Drop the registration for {@code key}. Idempotent; ignores null. */
    public void unregister(K key) {
        if (key == null) {
            return;
        }
        map.remove(key);
    }

    /** Look up the value registered for {@code key}. Never null. */
    public Optional<V> of(K key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(map.get(key));
    }

    /** Current entry count. Consistent-at-a-moment; may race with concurrent GC. */
    public int size() {
        return map.size();
    }

    /** Wipe every entry. Present for test hygiene. */
    public void clear() {
        map.clear();
    }
}
