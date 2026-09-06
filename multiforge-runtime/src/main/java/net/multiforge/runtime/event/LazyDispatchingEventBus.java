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
import java.util.concurrent.atomic.AtomicReference;
import net.multiforge.runtime.region.RegionId;
import net.neoforged.bus.api.IEventBus;

/**
 * {@link DispatchingEventBus} that can be constructed before a {@link
 * DispatchExecutor} exists to back it.
 *
 * <p>M12.2's patch (see {@code
 * multiforge-patches/09-events/net/neoforged/neoforge/common/NeoForge.java.patch})
 * replaces {@code NeoForge.EVENT_BUS}'s initializer with one of these,
 * constructed at class-load time — well before {@code
 * MultiThreadedSchedulerHost} exists, since that only comes into being once
 * a {@code MinecraftServer} boots. Every mod's {@code @Mod} constructor and
 * {@code FMLCommonSetupEvent} handler that calls {@code
 * NeoForge.EVENT_BUS.register(...)} therefore runs against a {@code
 * LazyDispatchingEventBus} whose routing is a safe no-op: {@link
 * #attachExecutor} has not been called yet, so every dispatch — regardless
 * of the listener's declared {@code @DispatchDomain} — runs inline on the
 * calling thread, matching pre-M12 Vanilla behavior exactly (see the inline
 * {@link InlineDispatchExecutor} below).
 *
 * <p>Once the real {@code MultiThreadedSchedulerHost} exists — in practice,
 * from {@code MultiForgeGlobalSystemsInit.install(...)}, called at {@code
 * ServerAboutToStart} — the fork bridge ({@code
 * net.multiforge.neoforge.event.EventBusBridge#attach}) calls {@link
 * #attachExecutor(DispatchExecutor)} exactly once, swapping in the real
 * {@code SchedulerBackedDispatchExecutor}. Every listener registered before
 * that point (i.e. every mod's own registration, which by construction
 * always happens before {@code ServerAboutToStart}) is already wrapped in a
 * {@link RoutingListenerWrapper} that consults this bus's {@link
 * DomainDispatcher} live at dispatch time — so attaching the executor after
 * the fact retroactively "activates" proactive routing for every
 * already-registered listener with no re-registration needed.
 */
public final class LazyDispatchingEventBus extends DispatchingEventBus {

    private static final DispatchExecutor INLINE = new InlineDispatchExecutor();

    private final AtomicReference<DispatchExecutor> executorRef;

    public LazyDispatchingEventBus(IEventBus inner) {
        this(inner, new AtomicReference<>(INLINE));
    }

    private LazyDispatchingEventBus(IEventBus inner, AtomicReference<DispatchExecutor> executorRef) {
        super(inner, new SwappableDispatchExecutor(executorRef));
        this.executorRef = executorRef;
    }

    /**
     * Swaps in the real {@link DispatchExecutor}. Idempotent and
     * thread-safe: only the first call (a CAS from the {@link #INLINE}
     * sentinel) actually takes effect — a second call (e.g. a {@code
     * GameTestServer} restart re-running install on a reused JVM) is a
     * silent no-op, never a double-attach.
     *
     * @return {@code true} if this call performed the attach, {@code
     *         false} if an executor was already attached (including by a
     *         concurrent caller).
     */
    public boolean attachExecutor(DispatchExecutor executor) {
        Objects.requireNonNull(executor, "executor");
        return executorRef.compareAndSet(INLINE, executor);
    }

    /** Whether {@link #attachExecutor} has taken effect yet. */
    public boolean isAttached() {
        return executorRef.get() != INLINE;
    }

    /**
     * Delegates to whatever {@link DispatchExecutor} is currently attached
     * (initially {@link #INLINE}, later the real one) — lets {@link
     * #attachExecutor} swap the backing executor without needing to touch
     * or reconstruct the {@link DomainDispatcher}/{@link
     * RoutingListenerWrapper} instances already installed on every
     * pre-attach listener registration.
     */
    private static final class SwappableDispatchExecutor implements DispatchExecutor {
        private final AtomicReference<DispatchExecutor> executorRef;

        SwappableDispatchExecutor(AtomicReference<DispatchExecutor> executorRef) {
            this.executorRef = executorRef;
        }

        @Override
        public void enqueueRegion(RegionId destination, Runnable task) {
            executorRef.get().enqueueRegion(destination, task);
        }

        @Override
        public void enqueueGlobal(Runnable task) {
            executorRef.get().enqueueGlobal(task);
        }

        @Override
        public void enqueueAsync(Runnable task) {
            executorRef.get().enqueueAsync(task);
        }

        @Override
        public Optional<RegionId> resolveEventLocation(Object event) {
            return executorRef.get().resolveEventLocation(event);
        }
    }

    /**
     * Pre-attach fallback: runs every hand-off inline on the calling
     * thread, exactly as an un-wrapped {@code net.neoforged.bus.EventBus}
     * would. {@link DomainDispatcher} already short-circuits to inline for
     * an {@code UNKNOWN}-domain caller (the common case this early), but
     * this is the belt-and-suspenders fallback for the rarer case of a
     * caller with a bound {@link net.multiforge.runtime.ownership.OwnerToken}
     * firing an event before {@link #attachExecutor} has run.
     */
    private static final class InlineDispatchExecutor implements DispatchExecutor {
        @Override
        public void enqueueRegion(RegionId destination, Runnable task) {
            task.run();
        }

        @Override
        public void enqueueGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void enqueueAsync(Runnable task) {
            task.run();
        }

        @Override
        public Optional<RegionId> resolveEventLocation(Object event) {
            return Optional.empty();
        }
    }
}
