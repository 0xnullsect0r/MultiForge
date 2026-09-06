/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.globals;

import java.util.function.Supplier;

/**
 * Marks the calling thread as "currently executing the global region's
 * phase-4 global-systems tick" for the duration of a {@link
 * #runMarked(Supplier)} call. {@code tryRoute} implementations (e.g. {@link
 * BossEventSystem#tryRoute} and {@link ScoreboardSystem#tryRoute}) consult
 * {@link #isCurrent()} to detect same-thread reentry: a mutation triggered
 * by code already running on the global region worker (a command function,
 * an event handler firing off {@code CommandDispatchSystem}) must observe
 * Vanilla's return-after-mutate contract, not get deferred to the next
 * tick's mailbox drain.
 *
 * <p>Internal glue, not public API — deliberately kept out of {@code
 * net.multiforge.api.*} (see CLAUDE.md's package-naming rule). It is
 * {@code public} rather than package-private only because its single
 * writer, {@code MultiThreadedSchedulerHost.phaseGlobalSystemsTick},
 * lives in {@code net.multiforge.runtime.scheduler} — a different
 * package from its readers here in {@code net.multiforge.runtime.globals}
 * — so a package-private type cannot be shared between the two. No
 * other caller should use this class; treat it as {@code @ApiStatus.Internal}
 * the same as every other non-API type in this module.
 *
 * <p>Backed by a {@link ThreadLocal} rather than any form of shared/atomic
 * state: "is the *calling* thread the global-region worker, right now"
 * is inherently a per-thread question, and a {@code ThreadLocal} is the
 * only construct that answers it without a thread-identity comparison at
 * every {@code tryRoute} call site. The marker is set immediately before
 * the wrapped body and cleared immediately after in a {@code finally}
 * block (see {@link #runMarked(Supplier)}), so a mid-tick exception can
 * never leave the marker stuck at {@code true} for that thread.
 */
public final class GlobalRegionThreadMarker {

    private static final ThreadLocal<Boolean> ON_GLOBAL_WORKER = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private GlobalRegionThreadMarker() {}

    /**
     * @return {@code true} if the calling thread is currently inside a
     *         {@link #runMarked(Supplier)} call (i.e. is the global
     *         region's worker thread, mid-tick) — {@code false} for every
     *         other thread, and for the global worker thread itself
     *         outside of that call.
     */
    public static boolean isCurrent() {
        return ON_GLOBAL_WORKER.get();
    }

    /**
     * Run {@code body} with {@link #isCurrent()} reporting {@code true}
     * for the calling thread for the duration of the call, restoring the
     * prior value afterward (try/finally — a nested call, or a throw from
     * {@code body}, cannot leave the marker stuck).
     *
     * @param body the work to run marked; its return value is passed
     *             through unchanged
     * @return whatever {@code body} returned
     */
    public static <T> T runMarked(Supplier<T> body) {
        Boolean previous = ON_GLOBAL_WORKER.get();
        ON_GLOBAL_WORKER.set(Boolean.TRUE);
        try {
            return body.get();
        } finally {
            ON_GLOBAL_WORKER.set(previous);
        }
    }

    // Deliberately no void-returning Runnable overload alongside
    // runMarked(Supplier<T>): a lambda whose body is a single statement
    // expression (e.g. a method call) is simultaneously congruent with a
    // void-compatible and a value-compatible functional interface (JLS
    // §15.27.3), so overloading both here would make every call-site
    // lambda ambiguous. Callers with a void body pass `() -> { body.run();
    // return null; }` (see MultiThreadedSchedulerHost.phaseGlobalSystemsTick)
    // instead.
}
