/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common;

import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.IModBusEvent;

public class NeoForge {
    /**
     * The NeoForge event bus, used for most events.
     * Also known as the "game" bus.
     */
    // MultiForge (multiforge-patches/09-events/, M12.2, docs/design/m12-event-routing.md
    // §2.1/§12): EVENT_BUS is constructed as a LazyDispatchingEventBus rather than the raw
    // bus. Every listener registered before ServerAboutToStart (i.e. every mod's own
    // registration) is wrapped and routed through DomainDispatcher from the moment it is
    // registered — but with no DispatchExecutor attached yet, so every dispatch runs inline
    // (pre-M12 Vanilla-equivalent behavior) until net.multiforge.neoforge.event.EventBusBridge
    // .attach(...) swaps in the real SchedulerBackedDispatchExecutor from
    // MultiForgeGlobalSystemsInit.install(...) at ServerAboutToStart. Respects
    // -Dmultiforge.event-dispatch=off (LazyDispatchingEventBus/DispatchingEventBus's own
    // DISABLE_PROPERTY check) as the safety valve (CLAUDE.md rule 5).
    public static final IEventBus EVENT_BUS = new net.multiforge.runtime.event.LazyDispatchingEventBus(BusBuilder.builder().startShutdown().classChecker(eventType -> {
        if (IModBusEvent.class.isAssignableFrom(eventType)) {
            throw new IllegalArgumentException("IModBusEvent events are not allowed on the common NeoForge bus! Use a mod bus instead.");
        }
    }).build());
}
