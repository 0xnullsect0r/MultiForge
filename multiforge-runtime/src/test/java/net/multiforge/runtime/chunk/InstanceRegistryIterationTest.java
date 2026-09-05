/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Round-5 H6 regression coverage for {@link InstanceRegistry#snapshot()}.
 *
 * <p>{@code Collections.synchronizedMap(WeakHashMap)} — the shape this
 * class wraps — is safe for individual {@code get}/{@code put}/{@code
 * remove} calls but requires the caller hold the wrapper's monitor for
 * the duration of any iteration over the map's views. {@link
 * MultiForgeDistanceManager} and {@link MultiForgeLightEngine} (fork-side,
 * not reachable from this MC-free module) register every live facade
 * instance in exactly this shape; nothing walks the registry today, but
 * the hazard is latent — a future all-instances command would inherit a
 * {@code ConcurrentModificationException} risk with no compile-time
 * warning. {@link InstanceRegistry#snapshot()} closes that gap by taking
 * the monitor once and handing back a private copy the caller can iterate
 * lock-free. These tests pin that contract from the pure-logic side.
 */
class InstanceRegistryIterationTest {

    private record Val(String tag) {}

    @Test
    void snapshot_returns_all_values() {
        InstanceRegistry<String, Val> registry = InstanceRegistry.weak();
        List<String> keys = List.of("k0", "k1", "k2", "k3", "k4", "k5", "k6", "k7", "k8", "k9");

        for (String k : keys) {
            registry.register(k, new Val(k));
        }

        List<Val> snapshot = registry.snapshot();

        assertThat(snapshot).hasSize(10);
        assertThat(snapshot).extracting(Val::tag).containsExactlyInAnyOrderElementsOf(keys);
    }

    @Test
    void snapshot_is_safe_under_concurrent_register() throws InterruptedException {
        InstanceRegistry<String, Val> registry = InstanceRegistry.weak();
        int threads = 4;
        int perThread = 100;

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch registrarsDone = new CountDownLatch(threads);
        AtomicBoolean failed = new AtomicBoolean(false);

        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        try {
            // Registrar threads: keep mutating the registry while the main
            // thread hammers snapshot() concurrently.
            for (int t = 0; t < threads; t++) {
                final int tid = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            String k = "t" + tid + "-k" + i;
                            registry.register(k, new Val(k));
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        failed.set(true);
                    } finally {
                        registrarsDone.countDown();
                    }
                });
            }

            // Reader task: repeatedly snapshot while registration is in flight.
            java.util.concurrent.Future<?> reader = pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        List<Val> snap = registry.snapshot();
                        // Merely iterating must not throw
                        // ConcurrentModificationException even while
                        // registrar threads are mutating the map.
                        for (Val v : snap) {
                            assertThat(v).isNotNull();
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    failed.set(true);
                } catch (RuntimeException e) {
                    failed.set(true);
                    throw e;
                }
            });

            start.countDown();
            assertThat(registrarsDone.await(10, TimeUnit.SECONDS))
                    .as("all registrar threads should complete")
                    .isTrue();
            reader.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            failed.set(true);
            throw new AssertionError("snapshot() under concurrent register threw", e);
        } finally {
            pool.shutdownNow();
        }

        assertThat(failed).isFalse();
        assertThat(registry.size()).isEqualTo(threads * perThread);
        assertThat(registry.snapshot()).hasSize(threads * perThread);
    }

    @Test
    void snapshot_reflects_removals() {
        InstanceRegistry<String, Val> registry = InstanceRegistry.weak();
        registry.register("a", new Val("a"));
        registry.register("b", new Val("b"));
        registry.register("c", new Val("c"));
        registry.register("d", new Val("d"));
        registry.register("e", new Val("e"));

        registry.unregister("b");
        registry.unregister("d");

        List<Val> snapshot = registry.snapshot();

        assertThat(snapshot).hasSize(3);
        assertThat(snapshot).extracting(Val::tag).containsExactlyInAnyOrder("a", "c", "e");
    }
}
