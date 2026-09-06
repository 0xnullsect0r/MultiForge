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

import java.lang.reflect.Method;
import net.multiforge.api.event.DispatchDomain;
import net.multiforge.api.event.DispatchDomainKind;
import net.multiforge.api.event.Ordering;
import net.multiforge.api.event.OrderingContract;
import net.multiforge.runtime.event.AnnotationScanner.MetadataEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnnotationScannerTest {

    @BeforeEach
    void resetCache() {
        AnnotationScanner.resetForTesting();
        EventTypeDomainMap.resetForTesting();
    }

    @AfterEach
    void resetEventTypeMap() {
        EventTypeDomainMap.resetForTesting();
    }

    @Test
    void methodLevelDomainAnnotationWins() throws NoSuchMethodException {
        Method method = MethodLevelOnly.class.getDeclaredMethod("handle", Object.class);
        MetadataEntry entry = AnnotationScanner.scan(method);
        assertThat(entry.domain()).isEqualTo(DispatchDomainKind.REGION);
    }

    @Test
    void classLevelDomainFallsBackWhenMethodUnannotated() throws NoSuchMethodException {
        Method method = ClassLevelOnly.class.getDeclaredMethod("handle", Object.class);
        MetadataEntry entry = AnnotationScanner.scan(method);
        assertThat(entry.domain()).isEqualTo(DispatchDomainKind.GLOBAL);
    }

    @Test
    void methodLevelDomainOverridesClassLevel() throws NoSuchMethodException {
        Method method = MethodOverridesClass.class.getDeclaredMethod("handle", Object.class);
        MetadataEntry entry = AnnotationScanner.scan(method);
        assertThat(entry.domain()).isEqualTo(DispatchDomainKind.REGION);
    }

    @Test
    void unannotatedMethodDefaultsToLegacySerialAndPerRegion() throws NoSuchMethodException {
        Method method = NoAnnotations.class.getDeclaredMethod("handle", Object.class);
        MetadataEntry entry = AnnotationScanner.scan(method);
        assertThat(entry.domain()).isEqualTo(DispatchDomainKind.LEGACY_SERIAL);
        assertThat(entry.ordering()).isEqualTo(OrderingContract.PER_REGION);
    }

    @Test
    void orderingReadsMethodLevelThenClassLevel() throws NoSuchMethodException {
        Method methodLevel = MethodLevelOrdering.class.getDeclaredMethod("handle", Object.class);
        assertThat(AnnotationScanner.scan(methodLevel).ordering()).isEqualTo(OrderingContract.BEST_EFFORT);

        Method classLevel = ClassLevelOrdering.class.getDeclaredMethod("handle", Object.class);
        assertThat(AnnotationScanner.scan(classLevel).ordering()).isEqualTo(OrderingContract.GLOBAL_TOTAL);
    }

    @Test
    void scanIsCachedPerMethod() throws NoSuchMethodException {
        Method method = MethodLevelOnly.class.getDeclaredMethod("handle", Object.class);
        MetadataEntry first = AnnotationScanner.scan(method);
        MetadataEntry second = AnnotationScanner.scan(method);
        assertThat(second).isSameAs(first);
    }

    @Test
    void unannotatedMethodWithKnownEventTypeUsesEventTypeDomainMapTier() throws NoSuchMethodException {
        EventTypeDomainMap.register(FakeKnownEvent.class.getName(), DispatchDomainKind.GLOBAL);

        Method method = NoAnnotationsKnownEvent.class.getDeclaredMethod("handle", FakeKnownEvent.class);
        MetadataEntry entry = AnnotationScanner.scan(method);

        assertThat(entry.domain()).isEqualTo(DispatchDomainKind.GLOBAL);
    }

    @Test
    void unannotatedMethodWithUnknownEventTypeFallsThroughToLegacySerial() throws NoSuchMethodException {
        Method method = NoAnnotationsUnknownEvent.class.getDeclaredMethod("handle", FakeUnknownEvent.class);
        MetadataEntry entry = AnnotationScanner.scan(method);

        assertThat(entry.domain()).isEqualTo(DispatchDomainKind.LEGACY_SERIAL);
    }

    @Test
    void explicitMethodAnnotationOverridesEventTypeDomainMap() throws NoSuchMethodException {
        EventTypeDomainMap.register(FakeKnownEvent.class.getName(), DispatchDomainKind.GLOBAL);

        Method method = MethodAnnotationOverridesEventTypeMap.class.getDeclaredMethod("handle", FakeKnownEvent.class);
        MetadataEntry entry = AnnotationScanner.scan(method);

        assertThat(entry.domain()).isEqualTo(DispatchDomainKind.ASYNC);
    }

    @DispatchDomain(DispatchDomainKind.GLOBAL)
    private static class ClassLevelOnly {
        @SuppressWarnings("unused")
        static void handle(Object event) {}
    }

    private static class MethodLevelOnly {
        @DispatchDomain(DispatchDomainKind.REGION)
        @SuppressWarnings("unused")
        static void handle(Object event) {}
    }

    @DispatchDomain(DispatchDomainKind.GLOBAL)
    private static class MethodOverridesClass {
        @DispatchDomain(DispatchDomainKind.REGION)
        @SuppressWarnings("unused")
        static void handle(Object event) {}
    }

    private static class NoAnnotations {
        @SuppressWarnings("unused")
        static void handle(Object event) {}
    }

    @Ordering(OrderingContract.GLOBAL_TOTAL)
    private static class ClassLevelOrdering {
        @SuppressWarnings("unused")
        static void handle(Object event) {}
    }

    private static class MethodLevelOrdering {
        @Ordering(OrderingContract.BEST_EFFORT)
        @SuppressWarnings("unused")
        static void handle(Object event) {}
    }

    private static class FakeKnownEvent {}

    private static class FakeUnknownEvent {}

    private static class NoAnnotationsKnownEvent {
        @SuppressWarnings("unused")
        static void handle(FakeKnownEvent event) {}
    }

    private static class NoAnnotationsUnknownEvent {
        @SuppressWarnings("unused")
        static void handle(FakeUnknownEvent event) {}
    }

    private static class MethodAnnotationOverridesEventTypeMap {
        @DispatchDomain(DispatchDomainKind.ASYNC)
        @SuppressWarnings("unused")
        static void handle(FakeKnownEvent event) {}
    }
}
