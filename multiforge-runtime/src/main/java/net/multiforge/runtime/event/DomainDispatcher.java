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
import java.util.Optional;
import net.multiforge.api.event.DispatchDomainKind;
import net.multiforge.api.event.OrderingContract;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.ownership.Domain;
import net.multiforge.runtime.ownership.OwnerToken;
import net.multiforge.runtime.region.RegionId;

/**
 * Pure routing logic implementing the dispatch decision tree from {@code
 * docs/design/m12-event-routing.md} §5-§6. No bus-specific types — {@link
 * RoutingListenerWrapper} is the only caller, and it hands us the caller's
 * ambient {@link OwnerToken} implicitly (read here via {@link
 * OwnerToken#current()}), the listener's declared domain/ordering, and a
 * {@link Runnable} that actually invokes the listener.
 *
 * <p>Never blocks the calling thread (CLAUDE.md rule 4): every branch is
 * either an inline {@link Runnable#run()} or a non-blocking hand-off via
 * {@link DispatchExecutor}.
 *
 * <p>Bumps one of five {@link ProbeRegistry} outcome buckets per dispatch —
 * {@code event.dispatch.inline}, {@code event.dispatch.region}, {@code
 * event.dispatch.global}, {@code event.dispatch.async}, {@code
 * event.dispatch.legacy} — so operators can see the traffic mix via
 * {@code /multiforge probe event.dispatch.*}. {@code inline} covers every
 * case that ran directly on the calling thread (same-region shortcut,
 * same-domain global shortcut, the {@code UNKNOWN}-caller bootstrap case,
 * and the "event has no derivable location" fallback when that fallback
 * itself resolves to an inline run); {@code region}/{@code global}/{@code
 * async} cover an actual hand-off through {@link DispatchExecutor}; {@code
 * legacy} covers every {@code LEGACY_SERIAL} firing (always inline, but
 * tracked separately so operators can see how much traffic is still
 * riding the unaudited default).
 */
public final class DomainDispatcher {

    private final DispatchExecutor executor;

    public DomainDispatcher(DispatchExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Routes one listener invocation. {@code event} is used only for
     * {@link DispatchExecutor#resolveEventLocation} and diagnostic
     * messages — {@code invocation} is what actually runs the listener.
     */
    public void dispatch(
            Object event, DispatchDomainKind listenerDomain, OrderingContract listenerOrdering, Runnable invocation) {
        Objects.requireNonNull(listenerDomain, "listenerDomain");
        Objects.requireNonNull(listenerOrdering, "listenerOrdering");
        Objects.requireNonNull(invocation, "invocation");

        OwnerToken caller = OwnerToken.current();

        // UNKNOWN (main thread, pre-boot, or any thread with no bound OwnerToken):
        // there is nowhere to defer to yet, regardless of the listener's declared
        // domain or ordering contract. Matches today's behavior for early
        // lifecycle stages (mod construction, FMLCommonSetupEvent, ...) and does
        // not regress anything — see design doc §5's UNKNOWN row.
        if (caller.domain() == Domain.UNKNOWN) {
            runInline(invocation);
            return;
        }

        // @Ordering(GLOBAL_TOTAL) forces every dispatch onto the global region
        // regardless of the listener's own @DispatchDomain — §6.
        if (listenerOrdering == OrderingContract.GLOBAL_TOTAL) {
            dispatchGlobal(caller, invocation);
            return;
        }

        switch (listenerDomain) {
            case ASYNC -> dispatchAsync(invocation);
            case LEGACY_SERIAL -> dispatchLegacySerial(event, invocation);
            case GLOBAL -> dispatchGlobal(caller, invocation);
            case REGION -> dispatchRegion(caller, event, invocation);
        }
    }

    private void dispatchRegion(OwnerToken caller, Object event, Runnable invocation) {
        Optional<RegionId> target = executor.resolveEventLocation(event);
        if (target.isEmpty()) {
            // Mod-author error (a REGION-domain listener on a non-spatial event) or
            // a genuinely non-spatial event annotated REGION by mistake. Never
            // throw (CLAUDE.md rule 5) — fall back to global-region routing and
            // warn once per rate-limit window. See design doc §5.1's closing note.
            ViolationLogger.warn(
                    "DomainDispatcher.noLocation",
                    "REGION-domain listener for " + describeEvent(event)
                            + " has no derivable location; routing to the global region");
            dispatchGlobal(caller, invocation);
            return;
        }
        RegionId destination = target.get();
        if (caller.domain() == Domain.REGION && caller.regionId() == destination.value()) {
            runInline(invocation);
        } else {
            executor.enqueueRegion(destination, invocation);
            ProbeRegistry.bump("event.dispatch.region");
        }
    }

    private void dispatchGlobal(OwnerToken caller, Runnable invocation) {
        if (caller.domain() == Domain.GLOBAL) {
            runInline(invocation);
        } else {
            executor.enqueueGlobal(invocation);
            ProbeRegistry.bump("event.dispatch.global");
        }
    }

    private void dispatchAsync(Runnable invocation) {
        executor.enqueueAsync(invocation);
        ProbeRegistry.bump("event.dispatch.async");
    }

    private void dispatchLegacySerial(Object event, Runnable invocation) {
        invocation.run();
        ViolationLogger.warn(
                "DomainDispatcher.legacySerial",
                "LEGACY_SERIAL handler for " + describeEvent(event)
                        + " ran inline on the calling thread; annotate with @DispatchDomain for proactive routing");
        ProbeRegistry.bump("event.dispatch.legacy");
    }

    private void runInline(Runnable invocation) {
        invocation.run();
        ProbeRegistry.bump("event.dispatch.inline");
    }

    private static String describeEvent(Object event) {
        return event == null ? "<null>" : event.getClass().getName();
    }
}
