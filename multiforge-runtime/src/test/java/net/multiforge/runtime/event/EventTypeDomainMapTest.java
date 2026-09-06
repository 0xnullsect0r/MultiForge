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
package net.multiforge.runtime.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.multiforge.api.event.DispatchDomainKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EventTypeDomainMapTest {

    @AfterEach
    void resetMap() {
        EventTypeDomainMap.resetForTesting();
    }

    @Test
    void lookupExactMatchReturnsRegisteredDomain() {
        EventTypeDomainMap.register(FakeEvent.class.getName(), DispatchDomainKind.REGION);

        assertThat(EventTypeDomainMap.lookup(FakeEvent.class)).contains(DispatchDomainKind.REGION);
    }

    @Test
    void lookupWalksSuperclassHierarchyWhenSubclassHasNoOwnEntry() {
        EventTypeDomainMap.register(FakeBaseEvent.class.getName(), DispatchDomainKind.GLOBAL);

        assertThat(EventTypeDomainMap.lookup(FakeSubEvent.class)).contains(DispatchDomainKind.GLOBAL);
    }

    @Test
    void lookupPrefersSubclassEntryOverAncestorEntry() {
        EventTypeDomainMap.register(FakeBaseEvent.class.getName(), DispatchDomainKind.GLOBAL);
        EventTypeDomainMap.register(FakeSubEvent.class.getName(), DispatchDomainKind.REGION);

        assertThat(EventTypeDomainMap.lookup(FakeSubEvent.class)).contains(DispatchDomainKind.REGION);
        assertThat(EventTypeDomainMap.lookup(FakeBaseEvent.class)).contains(DispatchDomainKind.GLOBAL);
    }

    @Test
    void lookupUnknownEventReturnsEmpty() {
        assertThat(EventTypeDomainMap.lookup(UnknownEvent.class)).isEmpty();
    }

    @Test
    void registerRoundTrips() {
        assertThat(EventTypeDomainMap.lookup(FakeAsyncEvent.class)).isEmpty();

        EventTypeDomainMap.register(FakeAsyncEvent.class.getName(), DispatchDomainKind.ASYNC);

        assertThat(EventTypeDomainMap.lookup(FakeAsyncEvent.class)).contains(DispatchDomainKind.ASYNC);
    }

    @Test
    void concurrentLookupsDuringLazyInitAreThreadSafeAndConsistent() throws InterruptedException {
        EventTypeDomainMap.register(FakeAsyncEvent.class.getName(), DispatchDomainKind.ASYNC);

        int threadCount = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        List<Optional<DispatchDomainKind>> results = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(EventTypeDomainMap.lookup(FakeAsyncEvent.class));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(results).hasSize(threadCount).allSatisfy(result -> assertThat(result)
                .contains(DispatchDomainKind.ASYNC));
    }

    private static class FakeEvent {}

    private static class FakeBaseEvent {}

    private static class FakeSubEvent extends FakeBaseEvent {}

    private static class FakeAsyncEvent {}

    private static class UnknownEvent {}
}
