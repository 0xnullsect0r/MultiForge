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
package net.multiforge.runtime.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AsyncEventPoolTest {

    private static final String SIZE_PROPERTY = "multiforge.event-async-pool.size";

    @Test
    void defaultPoolSizeIsFourWhenNoSystemPropertySet() {
        String prior = System.getProperty(SIZE_PROPERTY);
        System.clearProperty(SIZE_PROPERTY);
        try (AsyncEventPool pool = new AsyncEventPool()) {
            assertThat(pool.poolSize()).isEqualTo(4);
        } finally {
            restore(prior);
        }
    }

    @Test
    void poolSizeHonorsSystemProperty() {
        String prior = System.getProperty(SIZE_PROPERTY);
        System.setProperty(SIZE_PROPERTY, "2");
        try (AsyncEventPool pool = new AsyncEventPool()) {
            assertThat(pool.poolSize()).isEqualTo(2);
        } finally {
            restore(prior);
        }
    }

    @Test
    void submissionRunsOnADedicatedPoolThread() throws InterruptedException {
        Thread testThread = Thread.currentThread();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Thread> ranOn = new AtomicReference<>();

        try (AsyncEventPool pool = new AsyncEventPool(2)) {
            pool.submit(() -> {
                ranOn.set(Thread.currentThread());
                latch.countDown();
            });
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(ranOn.get()).isNotSameAs(testThread);
        assertThat(ranOn.get().getName()).startsWith("multiforge-event-async-");
    }

    @Test
    void closeShutsDownThePoolAndSubmitAfterCloseNeverThrows() {
        AsyncEventPool pool = new AsyncEventPool(1);
        pool.close();

        assertThat(pool.isShutdown()).isTrue();
        // CLAUDE.md rule 5: never throw at the caller — a post-shutdown submission is dropped silently.
        assertThatCode(() -> pool.submit(() -> {})).doesNotThrowAnyException();
    }

    private static void restore(String prior) {
        if (prior == null) {
            System.clearProperty(SIZE_PROPERTY);
        } else {
            System.setProperty(SIZE_PROPERTY, prior);
        }
    }
}
