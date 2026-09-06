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
package net.multiforge.runtime.globals;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.region.RegionId;

/**
 * Registry of {@link GlobalTicker} / {@link GlobalSystem} subsystems. The
 * MultiThreaded scheduler's global region ticks all registered entries
 * once per global tick, in registration order.
 *
 * <p>Two registration shapes are supported side by side:
 * <ul>
 *   <li>{@link #register(String, GlobalTicker)} — the original bare-
 *       {@code long}-tick shape, kept for back-compat with any interim
 *       {@link GlobalTicker} consumer and this class's existing unit
 *       tests.</li>
 *   <li>{@link #register(GlobalSystem)} — the richer {@link GlobalSystem}
 *       contract (name, context-aware tick, advisory read/write sets, a
 *       cross-region escape hatch) that Track B (M5) subsystems use. See
 *       {@code docs/design/global-region.md} §3–§4.</li>
 * </ul>
 *
 * <p>Anticipated {@link GlobalSystem} bindings (added by M5 patches):
 * {@code weather}, {@code time}, {@code world_border}, {@code
 * scoreboard}, {@code boss_events}, {@code raids}, {@code dragon_fight},
 * {@code command_dispatch}.
 */
public final class GlobalSystems {

    private final CopyOnWriteArrayList<Registered> tickers = new CopyOnWriteArrayList<>();
    private final AtomicLong tickCounter = new AtomicLong();

    // Cross-region effects handle every GlobalSystem#crossRegionEffect
    // call ultimately delegates to (via AbstractGlobalSystem or a direct
    // implementation). Defaults to null (unbound) so GlobalSystems stays
    // constructible standalone (`new GlobalSystems()`, unit tests with no
    // host); MultiThreadedSchedulerHost binds a real implementation via
    // bindCrossRegionEffects() as part of its own construction.
    private volatile CrossRegionEffects crossRegionEffects;

    // Stable indirection handed out by effects() regardless of bind order —
    // a subsystem constructor can capture this before bindCrossRegionEffects
    // runs; a call made before binding logs + drops rather than throwing
    // (CLAUDE.md rule 5) instead of NPE-ing on a null field.
    private final CrossRegionEffects effectsIndirection = (dest, task) -> {
        CrossRegionEffects impl = crossRegionEffects;
        if (impl == null) {
            ProbeRegistry.bump("global.system.effects.unbound");
            ViolationLogger.warn(
                    "global.system.effects",
                    "crossRegionEffect(" + dest + ") dropped — no CrossRegionEffects bound yet");
            return;
        }
        impl.enqueue(dest, task);
    };

    public Registered register(String name, GlobalTicker ticker) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(ticker, "ticker");
        Registered r = new Registered(name, ticker, null);
        tickers.add(r);
        return r;
    }

    /**
     * Register a {@link GlobalSystem}. Track B (M5) subsystems (weather,
     * time, world border, ...) use this overload; the returned handle is
     * accepted by {@link #unregister(Registered)} exactly like {@link
     * #register(String, GlobalTicker)}'s. Does not reject duplicate
     * {@link GlobalSystem#name()}s — two subsystems under the same name
     * both tick (docs/design/global-region.md §4.3).
     */
    public Registered register(GlobalSystem sys) {
        Objects.requireNonNull(sys, "sys");
        Registered r = new Registered(sys.name(), null, sys);
        tickers.add(r);
        return r;
    }

    public boolean unregister(Registered r) {
        return tickers.remove(r);
    }

    public Collection<Registered> registered() {
        return List.copyOf(tickers);
    }

    /**
     * Fire all registered entries in registration order using a
     * self-incrementing tick counter (no caller-supplied {@link
     * GlobalTickContext}). Kept for back-compat with existing {@link
     * GlobalTicker}-only callers/tests; a {@link GlobalSystem} entry
     * receives a context built from this call's tick number and a
     * {@code null} region id (no host to source one from in this
     * overload).
     */
    public long tickAll() {
        long tick = tickCounter.incrementAndGet();
        GlobalTickContext ctx = new GlobalTickContext(tick, null);
        for (Registered r : tickers) {
            invoke(r, ctx);
        }
        return tick;
    }

    /**
     * Fire all registered entries in registration order against an
     * explicit {@link GlobalTickContext}. This is the entry point {@link
     * net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost}'s
     * phase-4 ({@code BLOCK_ENTITIES}) wiring calls once per global-region
     * tick (docs/design/global-region.md §2.3). Publishes {@code
     * ctx.globalTick()} as the new {@link #currentTick()} value.
     *
     * <p>Failure semantics (docs/design/global-region.md §7.1): a {@link
     * GlobalSystem}/{@link GlobalTicker} throwing from its tick body
     * never propagates past this method, never stops later-registered
     * entries in the same pass from running, and never crashes the
     * global region's tick — each throw bumps a per-subsystem probe
     * counter and emits a rate-limited violation warning, then the loop
     * continues.
     */
    public void tickAll(GlobalTickContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        tickCounter.set(ctx.globalTick());
        for (Registered r : tickers) {
            invoke(r, ctx);
        }
    }

    private void invoke(Registered r, GlobalTickContext ctx) {
        try {
            if (r.system() != null) {
                r.system().tick(ctx);
            } else {
                r.ticker().tick(ctx.globalTick());
            }
        } catch (Throwable t) {
            ProbeRegistry.bump("global.system.failure." + r.name());
            ViolationLogger.warn(
                    "global.system." + r.name(),
                    "tick() threw: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public long currentTick() {
        return tickCounter.get();
    }

    /**
     * Handle every {@link GlobalSystem#crossRegionEffect(RegionId,
     * Runnable)} call ultimately delegates to (directly, or via {@link
     * AbstractGlobalSystem}). Safe to call before {@link
     * #bindCrossRegionEffects} runs — such calls log + drop rather than
     * throwing (CLAUDE.md rule 5).
     */
    public CrossRegionEffects effects() {
        return effectsIndirection;
    }

    /**
     * Bind the real {@link CrossRegionEffects} implementation. Called
     * once by {@link
     * net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost} as
     * part of its own construction. Safe to call more than once
     * (last-writer-wins) — no production call site does, but tests may
     * rebind a stub.
     */
    public void bindCrossRegionEffects(CrossRegionEffects impl) {
        this.crossRegionEffects = Objects.requireNonNull(impl, "impl");
    }

    /**
     * One registered entry. Exactly one of {@code ticker} / {@code
     * system} is non-null — {@code ticker} for {@link #register(String,
     * GlobalTicker)} registrations, {@code system} for {@link
     * #register(GlobalSystem)} registrations.
     */
    public record Registered(String name, GlobalTicker ticker, GlobalSystem system) {}
}
