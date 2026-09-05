/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.chunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * backing map is wrapped in {@link Collections#synchronizedMap}. Direct
 * iteration over the backing map is intentionally not exposed —
 * {@code Collections.synchronizedMap}'s javadoc requires the caller hold
 * the wrapper's monitor for the whole iteration, which every value-side
 * caller of this class would otherwise have to know and get right.
 * {@link #snapshot()} is the safe substitute: it takes the monitor once,
 * copies the live values, and hands the caller a plain {@link List} it
 * can walk lock-free (round-5 H6).
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

    /**
     * A point-in-time copy of every currently-registered value, safe to
     * iterate on any thread without holding this registry's internal
     * lock. Takes the {@code synchronizedMap} monitor once, for the
     * duration of the copy, then releases it — the returned {@link List}
     * is a private snapshot the caller owns outright.
     *
     * <p>Round-5 H6: added so a {@code WeakHashMap}-backed registry can
     * be walked (e.g. a future {@code /multiforge distancemanagers}-style
     * command) without the caller re-deriving the "iteration needs the
     * wrapper's monitor" rule from the {@link Collections#synchronizedMap}
     * javadoc itself. Filters out any {@code null} value defensively —
     * weak keys can be reclaimed mid-copy, but {@link WeakHashMap} never
     * surfaces a null value for a live entry, so this is a belt-and-
     * braces guard rather than an expected path.
     */
    public List<V> snapshot() {
        synchronized (map) {
            List<V> out = new ArrayList<>(map.size());
            for (V v : map.values()) {
                if (v != null) {
                    out.add(v);
                }
            }
            return out;
        }
    }

    /** Wipe every entry. Present for test hygiene. */
    public void clear() {
        map.clear();
    }
}
