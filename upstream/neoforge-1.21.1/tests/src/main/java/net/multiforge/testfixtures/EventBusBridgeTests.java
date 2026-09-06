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
package net.multiforge.testfixtures;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.GameTest;
import net.multiforge.api.event.DispatchDomain;
import net.multiforge.api.event.DispatchDomainKind;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.event.DispatchingEventBus;
import net.multiforge.runtime.event.LazyDispatchingEventBus;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.eventtest.internal.TestsMod;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;

/**
 * GameTest fixtures for M12.2 (docs/design/m12-event-routing.md §2.1/§12):
 * prove the {@code 09-events/NeoForge.java.patch} hunk plus {@code
 * MultiForgeGlobalSystemsInit.install}'s {@code EventBusBridge.attach}
 * call actually wire proactive event routing onto the live {@code
 * NeoForge.EVENT_BUS} in a running server — not just in the
 * {@code multiforge-runtime} unit tests (see {@code
 * LazyDispatchingEventBusTest}), which exercise the same logic in
 * isolation against a fake bus/executor pair.
 */
@ForEachTest(groups = "multiforge.event-bus-bridge")
public class EventBusBridgeTests {
    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = {
            "After ServerAboutToStart, NeoForge.EVENT_BUS is a LazyDispatchingEventBus with an",
            "attached SchedulerBackedDispatchExecutor — proving the patch + install() wiring landed."
    })
    static void eventBusIsLazyDispatchingAndAttachedAfterBoot(final DynamicTest test) {
        test.onGameTest(helper -> {
            helper.assertTrue(
                    NeoForge.EVENT_BUS instanceof LazyDispatchingEventBus,
                    "expected NeoForge.EVENT_BUS to be a LazyDispatchingEventBus — is the "
                            + "09-events/NeoForge.java.patch hunk applied? actual type: "
                            + NeoForge.EVENT_BUS.getClass().getName());
            LazyDispatchingEventBus lazy = (LazyDispatchingEventBus) NeoForge.EVENT_BUS;
            helper.assertTrue(
                    lazy.isAttached(),
                    "expected MultiForgeGlobalSystemsInit.install's EventBusBridge.attach call to have "
                            + "swapped in a real SchedulerBackedDispatchExecutor by ServerAboutToStart");
            helper.succeed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = {
            "A @DispatchDomain(REGION)-annotated listener registered on the live NeoForge.EVENT_BUS",
            "actually fires when the event is posted from inside the GameTest's own (region-owning)",
            "worker context, and the dispatch is recorded via ProbeRegistry — proving the listener",
            "went through DomainDispatcher's real routing path, not a bypassed/raw bus."
    })
    static void regionAnnotatedListenerFiresAndIsProbeRecorded(final DynamicTest test) {
        test.onGameTest(helper -> {
            MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
            helper.assertTrue(host != null, "runtime must be installed");

            long inlineBefore = ProbeRegistry.get("event.dispatch.inline");
            long regionBefore = ProbeRegistry.get("event.dispatch.region");
            long globalBefore = ProbeRegistry.get("event.dispatch.global");

            RegionAnnotatedListener listener = new RegionAnnotatedListener();
            NeoForge.EVENT_BUS.register(listener);
            try {
                // GameTest ticks are driven from the level's owning region worker, so this
                // caller already carries a REGION OwnerToken. ProbeEvent has no derivable
                // location (resolveEventLocation returns empty for it), so DomainDispatcher
                // falls back to global-region routing (event.dispatch.global) rather than
                // resolving same-region inline — either outcome proves real M12 routing ran,
                // not a bypassed/raw bus, which is all this fixture needs to demonstrate.
                NeoForge.EVENT_BUS.post(new ProbeEvent());

                long inlineAfter = ProbeRegistry.get("event.dispatch.inline");
                long regionAfter = ProbeRegistry.get("event.dispatch.region");
                long globalAfter = ProbeRegistry.get("event.dispatch.global");
                boolean movedThroughDispatcher = inlineAfter > inlineBefore || regionAfter > regionBefore || globalAfter > globalBefore;
                helper.assertTrue(
                        movedThroughDispatcher,
                        "expected ProbeEvent's dispatch to bump one of event.dispatch.{inline,region,global} — "
                                + "none moved, so the listener likely bypassed DomainDispatcher entirely");
                helper.assertTrue(listener.calls.get() == 1, "listener must have fired exactly once");
            } finally {
                NeoForge.EVENT_BUS.unregister(listener);
            }
            helper.succeed();
        });
    }

    /**
     * Unit-shaped (no boot-time flag dependency) check of the {@code
     * -Dmultiforge.event-dispatch=off} safety valve, exercised directly
     * against {@link net.multiforge.neoforge.event.EventBusBridge#attach}
     * and a freshly-constructed {@link LazyDispatchingEventBus} rather
     * than the live, already-booted {@code NeoForge.EVENT_BUS} — that
     * bus's own flag read happened once at class-load time
     * (docs/design/m12-event-routing.md §8), long before this test could
     * set the property, so it cannot be toggled mid-GameTest for the live
     * bus. Still registered as a {@code @GameTest} (rather than a plain
     * JUnit {@code @Test}) so it runs under the same {@code
     * gameTestServer} harness as every other fixture in this module, and
     * still needs a live {@link MultiThreadedSchedulerHost} to hand
     * {@code attach} a real one to attach-or-not.
     */
    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = {
            "-Dmultiforge.event-dispatch=off makes EventBusBridge.attach a no-op: the",
            "LazyDispatchingEventBus stays unattached, so every dispatch runs inline —",
            "the same observable (no cross-thread hand-off) behavior as the raw bus."
    })
    static void disableFlagLeavesTheBusUnattachedAndDispatchInline(final DynamicTest test) {
        test.onGameTest(helper -> {
            String prior = System.getProperty(DispatchingEventBus.DISABLE_PROPERTY);
            try {
                System.setProperty(DispatchingEventBus.DISABLE_PROPERTY, "off");

                MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
                helper.assertTrue(host != null, "runtime must be installed");

                LazyDispatchingEventBus bus = new LazyDispatchingEventBus(BusBuilder.builder().build());
                boolean attachResult = net.multiforge.neoforge.event.EventBusBridge.attach(bus, host);
                helper.assertTrue(
                        attachResult, "attach() must report success (a deliberate no-op) when the flag is off");
                helper.assertTrue(!bus.isAttached(), "expected the safety valve to leave the bus unattached");

                RegionAnnotatedListener listener = new RegionAnnotatedListener();
                bus.register(listener);

                long regionBefore = ProbeRegistry.get("event.dispatch.region");
                long globalBefore = ProbeRegistry.get("event.dispatch.global");
                bus.post(new ProbeEvent());
                long regionAfter = ProbeRegistry.get("event.dispatch.region");
                long globalAfter = ProbeRegistry.get("event.dispatch.global");

                helper.assertTrue(listener.calls.get() == 1, "listener must still fire with the flag off");
                helper.assertTrue(
                        regionAfter == regionBefore && globalAfter == globalBefore,
                        "expected no region/global hand-off counters to move — matches raw-bus "
                                + "(fully inline, no cross-thread dispatch) behavior");
            } finally {
                if (prior == null) {
                    System.clearProperty(DispatchingEventBus.DISABLE_PROPERTY);
                } else {
                    System.setProperty(DispatchingEventBus.DISABLE_PROPERTY, prior);
                }
            }
            helper.succeed();
        });
    }

    private static final class ProbeEvent extends Event {}

    private static final class RegionAnnotatedListener {
        final AtomicInteger calls = new AtomicInteger();

        @SubscribeEvent
        @DispatchDomain(DispatchDomainKind.REGION)
        void onEvent(ProbeEvent event) {
            calls.incrementAndGet();
        }
    }
}
