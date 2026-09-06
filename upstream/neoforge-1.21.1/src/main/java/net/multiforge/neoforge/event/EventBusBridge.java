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
package net.multiforge.neoforge.event;

import java.util.Objects;
import net.multiforge.runtime.event.DispatchingEventBus;
import net.multiforge.runtime.event.LazyDispatchingEventBus;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import net.neoforged.bus.api.IEventBus;

/**
 * Fork-side factory for wiring M12 event routing onto a NeoForge {@link
 * IEventBus}. See {@code docs/design/m12-event-routing.md} §2.1/§12.
 *
 * <p>Two entry points, for two different moments in the boot sequence:
 *
 * <ul>
 * <li>{@link #wrap(IEventBus, MultiThreadedSchedulerHost)} — eagerly
 * constructs a {@link DispatchingEventBus} around any bus, given a
 * host that already exists. Not used by the static {@code
 *       NeoForge.EVENT_BUS} initializer (a {@code
 *       MultiThreadedSchedulerHost} does not exist yet at class-load
 * time), but available for tests and any other bus instance that
 * does have a host in hand up front.
 * <li>{@link #attach(IEventBus, MultiThreadedSchedulerHost)} — the
 * production path. {@code NeoForge.EVENT_BUS} is initialized (by
 * the {@code 09-events/NeoForge.java.patch} hunk) as a {@link
 * LazyDispatchingEventBus} directly, with no executor attached yet.
 * {@code MultiForgeGlobalSystemsInit.install(...)} calls {@code
 *       attach} once a real host exists (at {@code ServerAboutToStart}),
 * swapping in the real {@link SchedulerBackedDispatchExecutor}.
 * </ul>
 *
 * <p>Both respect the {@code -Dmultiforge.event-dispatch=off} safety
 * valve (CLAUDE.md rule 5, design doc §8) and are idempotent — safe to
 * call more than once (a {@code GameTestServer} restart on a reused JVM,
 * for instance).
 */
public final class EventBusBridge {
    private EventBusBridge() {}

    /**
     * Returns {@code original} unchanged when the safety valve is set or
     * {@code host} is {@code null}; when {@code original} is already a
     * {@link DispatchingEventBus} (including a {@link
     * LazyDispatchingEventBus}), also returns it unchanged rather than
     * double-wrapping. Otherwise constructs a fresh {@link
     * DispatchingEventBus} backed by a new {@link
     * SchedulerBackedDispatchExecutor}.
     */
    public static IEventBus wrap(IEventBus original, MultiThreadedSchedulerHost host) {
        Objects.requireNonNull(original, "original");
        if (isDisabled() || host == null || original instanceof DispatchingEventBus) {
            return original;
        }
        return new DispatchingEventBus(original, new SchedulerBackedDispatchExecutor(host));
    }

    /**
     * Attaches a fresh {@link SchedulerBackedDispatchExecutor} onto {@code
     * bus} if (and only if) it is a {@link LazyDispatchingEventBus} with
     * no executor attached yet. Never throws (CLAUDE.md rule 5):
     *
     * <ul>
     * <li>Safety valve set — no-op, returns {@code true} (this is the
     * intended, operator-requested state, not a failure).
     * <li>{@code bus} is not a {@link LazyDispatchingEventBus} (the
     * 09-events patch was not applied, or something else replaced
     * {@code NeoForge.EVENT_BUS}) — returns {@code false} so the
     * caller can warn; every listener still dispatches inline via
     * the real bus's own semantics, so nothing crashes.
     * <li>{@code host} is {@code null} — same as above.
     * <li>Otherwise — attaches (idempotently; a second call after a
     * successful first attach is a harmless no-op) and returns
     * {@code true}.
     * </ul>
     */
    public static boolean attach(IEventBus bus, MultiThreadedSchedulerHost host) {
        if (isDisabled()) {
            return true;
        }
        if (!(bus instanceof LazyDispatchingEventBus lazy) || host == null) {
            return false;
        }
        lazy.attachExecutor(new SchedulerBackedDispatchExecutor(host));
        return true;
    }

    private static boolean isDisabled() {
        return "off".equalsIgnoreCase(System.getProperty(DispatchingEventBus.DISABLE_PROPERTY));
    }
}
