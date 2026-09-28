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
    void theModpackHotEventsRunOnTheRegionButHealingStaysSerial() {
        assertThat(EventTypeDomainMap.entryFor("net.neoforged.neoforge.event.entity.EntityInvulnerabilityCheckEvent"))
                .contains(DispatchDomainKind.REGION);
        assertThat(EventTypeDomainMap.entryFor("net.neoforged.neoforge.event.level.LevelEvent$PotentialSpawns"))
                .contains(DispatchDomainKind.REGION);
        assertThat(EventTypeDomainMap.entryFor("net.neoforged.neoforge.event.enchanting.GetEnchantmentLevelEvent"))
                .contains(DispatchDomainKind.REGION);
        // Relics gates its heal listeners on MinecraftServer.isSameThread().
        assertThat(EventTypeDomainMap.entryFor("net.neoforged.neoforge.event.entity.living.LivingHealEvent"))
                .isEmpty();
    }

    @Test
    void theModPerEntityEventsAreAuditedListenerByListenerAndXycraftIsDeferred() {
        assertThat(EventTypeDomainMap.auditedListeners(
                        "it.hurts.sskirillss.relics.api.events.utility.FluidCollisionEvent"))
                .contains(java.util.Set.of(
                        "it.hurts.sskirillss.relics.items.relics.feet.CutGlassBootItem$CommonEvents",
                        "it.hurts.shatterbyte.reliquified_artifacts.items.feet.AquaDashersItem$CommonEvents",
                        "it.hurts.shatterbyte.reliquified_artifacts.items.feet.StriderShoesItem$CommonEvents"));
        assertThat(EventTypeDomainMap.auditedListeners(
                        "it.hurts.sskirillss.relics.api.events.utility.LivingSlippingEvent"))
                .isPresent();
        assertThat(EventTypeDomainMap.auditedListeners(
                        "it.hurts.sskirillss.relics.api.events.utility.EntityBlockSpeedFactorEvent"))
                .isPresent();
        assertThat(EventTypeDomainMap.auditedListeners("be.florens.expandability.api.forge.LivingFluidCollisionEvent"))
                .contains(java.util.Set.of("artifacts.neoforge.event.ArtifactHooksNeoForge"));
        assertThat(EventTypeDomainMap.auditedListeners("com.github.L_Ender.lionfishapi.server.event.StandOnFluidEvent"))
                .contains(java.util.Set.of("com.github.L_Ender.cataclysm.event.ServerEventHandler"));
        // Not an unconditional default: another mod's listener keeps the lane.
        assertThat(EventTypeDomainMap.entryFor("it.hurts.sskirillss.relics.api.events.utility.FluidCollisionEvent"))
                .isEmpty();
        assertThat(EventTypeDomainMap.deferredEntry("tv.soaryn.xycraft.core.event.ItemEntityTickEvent"))
                .isTrue();
    }

    @Test
    void anAuditedEntryAppliesOnlyToTheListenersItNames() {
        EventTypeDomainMap.registerAudited(
                FakeEvent.class.getName(), DispatchDomainKind.REGION, AuditedListener.class.getName());

        EventTypeDomainMap.Resolution audited = EventTypeDomainMap.lookup(FakeEvent.class, AuditedListener.class);
        assertThat(audited.kind()).contains(DispatchDomainKind.REGION);
        assertThat(audited.audited()).isTrue();

        EventTypeDomainMap.Resolution other = EventTypeDomainMap.lookup(FakeEvent.class, OtherListener.class);
        assertThat(other.kind()).isEmpty();
        assertThat(other.audited()).isFalse();
        // The one-argument lookup knows no listener: the serial default.
        assertThat(EventTypeDomainMap.lookup(FakeEvent.class)).isEmpty();
    }

    @Test
    void aLambdaIsJudgedByItsHostClass() {
        java.util.function.Consumer<Object> lambda = o -> {};
        EventTypeDomainMap.registerAudited(
                FakeEvent.class.getName(), DispatchDomainKind.REGION, EventTypeDomainMapTest.class.getName());

        assertThat(EventTypeDomainMap.lookup(FakeEvent.class, lambda.getClass()).kind())
                .contains(DispatchDomainKind.REGION);
    }

    @Test
    void anOperatorEntryBeatsAnAuditedOneAndReplacesDeferral() {
        EventTypeDomainMap.registerAudited(
                FakeEvent.class.getName(), DispatchDomainKind.REGION, AuditedListener.class.getName());
        EventTypeDomainMap.register(FakeEvent.class.getName(), DispatchDomainKind.LEGACY_SERIAL);
        assertThat(EventTypeDomainMap.lookup(FakeEvent.class, AuditedListener.class)
                        .kind())
                .contains(DispatchDomainKind.LEGACY_SERIAL);

        EventTypeDomainMap.registerDeferred(FakeSubEvent.class.getName());
        assertThat(EventTypeDomainMap.isDeferred(FakeSubEvent.class)).isTrue();
        EventTypeDomainMap.register(FakeSubEvent.class.getName(), DispatchDomainKind.REGION);
        assertThat(EventTypeDomainMap.isDeferred(FakeSubEvent.class)).isFalse();
    }

    @Test
    void theCachedDeferralAnswerFollowsEveryRegistrationAndReset() {
        // Asked (and cached) before any change.
        assertThat(EventTypeDomainMap.isDeferred(FakeSubEvent.class)).isFalse();
        assertThat(EventTypeDomainMap.isDeferred(FakeSubEvent.class)).isFalse();
        // Deferring the base class defers the subclass: the cached "no" is dropped.
        EventTypeDomainMap.registerDeferred(FakeBaseEvent.class.getName());
        assertThat(EventTypeDomainMap.isDeferred(FakeSubEvent.class)).isTrue();
        assertThat(EventTypeDomainMap.isDeferred(FakeSubEvent.class)).isTrue();
        EventTypeDomainMap.register(FakeBaseEvent.class.getName(), DispatchDomainKind.REGION);
        assertThat(EventTypeDomainMap.isDeferred(FakeSubEvent.class)).isFalse();
        EventTypeDomainMap.registerDeferred(FakeSubEvent.class.getName());
        assertThat(EventTypeDomainMap.isDeferred(FakeSubEvent.class)).isTrue();
        EventTypeDomainMap.resetForTesting();
        assertThat(EventTypeDomainMap.isDeferred(FakeSubEvent.class)).isFalse();
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

    private static class AuditedListener {}

    private static class OtherListener {}
}
