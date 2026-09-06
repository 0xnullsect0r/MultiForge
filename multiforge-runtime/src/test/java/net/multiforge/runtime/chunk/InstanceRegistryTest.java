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
package net.multiforge.runtime.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InstanceRegistry} — the shared registry helper
 * extracted from {@code MultiForgeChunkMap}, {@code
 * MultiForgeDistanceManager} and {@code MultiForgeLightEngine}'s
 * observability-seam wiring in Phase 4.1d.
 *
 * <p>The facades themselves live on the Minecraft classpath and can't
 * be exercised from this module, so the pure registry logic is tested
 * here instead: happy-path lookups, unregister, null-key tolerance, weak
 * -key reclamation, concurrent registration, and re-register-replaces.
 */
class InstanceRegistryTest {

    /** Simple non-null value type so weak-key GC isn't confounded by value-side references. */
    private record Val(String tag) {}

    @Test
    void register_then_of_returns_value() {
        InstanceRegistry<String, Val> registry = InstanceRegistry.weak();
        String key = new String("key1");
        Val value = new Val("v1");

        registry.register(key, value);

        assertThat(registry.of(key)).contains(value);
    }

    @Test
    void of_unknown_key_returns_empty() {
        InstanceRegistry<String, Val> registry = InstanceRegistry.weak();

        assertThat(registry.of(new String("never-registered"))).isEmpty();
    }

    @Test
    void unregister_removes() {
        InstanceRegistry<String, Val> registry = InstanceRegistry.weak();
        String key = new String("key");
        registry.register(key, new Val("v"));
        assertThat(registry.of(key)).isPresent();

        registry.unregister(key);

        assertThat(registry.of(key)).isEmpty();
    }

    @Test
    void register_null_key_no_op() {
        InstanceRegistry<String, Val> registry = InstanceRegistry.weak();

        // Both null-key register and null-key unregister must be silent no-ops.
        registry.register(null, new Val("v"));
        registry.unregister(null);

        assertThat(registry.size()).isZero();
        assertThat(registry.of(null)).isEmpty();
    }

    @Test
    void weak_keys_gc_removes_entry() throws InterruptedException {
        InstanceRegistry<Object, Val> registry = InstanceRegistry.weak();
        Object key = new Object();
        registry.register(key, new Val("v"));
        assertThat(registry.size()).isEqualTo(1);

        // Drop the only strong reference to the key and coax the collector.
        key = null;
        boolean cleared = false;
        for (int i = 0; i < 40; i++) {
            System.gc();
            if (registry.size() == 0) {
                cleared = true;
                break;
            }
            Thread.sleep(50);
        }

        assertThat(cleared)
                .as("weak-key entry should be reclaimed after key becomes unreachable")
                .isTrue();
        assertThat(registry.size()).isZero();
    }

    @Test
    void concurrent_register_visibility() throws InterruptedException {
        InstanceRegistry<String, Val> registry = InstanceRegistry.weak();
        int threads = 8;
        int perThread = 100;

        // Retain strong refs to every key so weak-key GC can't skew the assertion.
        List<String> keys = new ArrayList<>(threads * perThread);
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < perThread; i++) {
                keys.add("t" + t + "-k" + i);
            }
        }

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            String k = keys.get(tid * perThread + i);
                            registry.register(k, new Val(k));
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS))
                    .as("all registration threads should complete")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(registry.size()).isEqualTo(threads * perThread);
        for (String k : keys) {
            Optional<Val> got = registry.of(k);
            assertThat(got).as("key %s must be visible", k).isPresent();
            assertThat(got.get().tag()).isEqualTo(k);
        }
    }

    @Test
    void re_register_replaces_value() {
        InstanceRegistry<String, Val> registry = InstanceRegistry.weak();
        String key = new String("k");
        Val v1 = new Val("first");
        Val v2 = new Val("second");

        registry.register(key, v1);
        registry.register(key, v2);

        assertThat(registry.of(key)).contains(v2);
        assertThat(registry.size()).isEqualTo(1);
    }
}
