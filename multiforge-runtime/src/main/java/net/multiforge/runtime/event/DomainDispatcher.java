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

import java.util.Objects;
import net.multiforge.api.event.DispatchDomainKind;
import net.multiforge.api.event.OrderingContract;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.ownership.Domain;
import net.multiforge.runtime.ownership.OwnerToken;

/**
 * Decides where one listener invocation runs, given the posting thread and
 * the listener's effective domain (see docs/events.md).
 *
 * <p>Posted from a <b>region worker</b>:
 * <ul>
 *   <li>{@code REGION}: inline on the worker.</li>
 *   <li>{@code GLOBAL}, {@code LEGACY_SERIAL}, or {@code @Ordering(GLOBAL_TOTAL)}:
 *       on the {@link SerialLane} (the server thread, one listener at a
 *       time, under the worker's owner token), while the worker waits — so a
 *       cancellation or result the listener sets is seen by the poster.</li>
 *   <li>{@code ASYNC}: handed to the async pool; the poster does not wait,
 *       so such a listener cannot cancel the event or set its result.</li>
 * </ul>
 * Posted from any other thread (the server thread, world generation,
 * network, a mod's own thread): inline, as in NeoForge, except that an
 * {@code ASYNC} listener posted from a MultiForge async task goes to the
 * async pool and a {@code GLOBAL} one to the global region.
 *
 * <p>Outcomes are counted under {@code event.dispatch.*} probes.
 */
public final class DomainDispatcher {

    private final DispatchExecutor executor;

    public DomainDispatcher(DispatchExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** Route one listener invocation; {@code invocation} runs the listener. */
    public void dispatch(
            Object event, DispatchDomainKind listenerDomain, OrderingContract listenerOrdering, Runnable invocation) {
        Objects.requireNonNull(listenerDomain, "listenerDomain");
        Objects.requireNonNull(listenerOrdering, "listenerOrdering");
        Objects.requireNonNull(invocation, "invocation");

        Domain caller = OwnerToken.current().domain();
        boolean serial = listenerOrdering == OrderingContract.GLOBAL_TOTAL
                || listenerDomain == DispatchDomainKind.GLOBAL
                || listenerDomain == DispatchDomainKind.LEGACY_SERIAL;
        switch (caller) {
            case REGION -> {
                if (listenerDomain == DispatchDomainKind.ASYNC && listenerOrdering != OrderingContract.GLOBAL_TOTAL) {
                    executor.enqueueAsync(invocation);
                    ProbeRegistry.bump("event.dispatch.async");
                } else if (serial) {
                    executor.runSerial(invocation);
                    ProbeRegistry.bump("event.dispatch.serial");
                } else {
                    runInline(invocation);
                }
            }
            case ASYNC -> {
                if (listenerDomain == DispatchDomainKind.ASYNC) {
                    runInline(invocation);
                } else if (serial) {
                    // An async task cannot wait for the server thread, which only
                    // pumps during a tick barrier; run it at the next global tick.
                    executor.enqueueGlobal(invocation);
                    ProbeRegistry.bump("event.dispatch.global");
                } else {
                    runInline(invocation);
                }
            }
            default -> runInline(invocation);
        }
    }

    private void runInline(Runnable invocation) {
        invocation.run();
        ProbeRegistry.bump("event.dispatch.inline");
    }
}
