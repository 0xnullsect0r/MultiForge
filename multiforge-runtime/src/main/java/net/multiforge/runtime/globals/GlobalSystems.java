/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.globals;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registry of {@link GlobalTicker} subsystems. The MultiThreaded
 * scheduler's global region ticks all registered tickers once per
 * epoch in registration order.
 *
 * <p>Anticipated bindings (added by M5 patches):
 * <ul>
 *   <li>WorldTimeTicker — advances day/night cycle per world.</li>
 *   <li>WeatherTicker — rain, thunder, downfall duration per world.</li>
 *   <li>WorldBorderTicker — border resize animation.</li>
 *   <li>DragonFightTicker — ender dragon boss state.</li>
 *   <li>WitherFightTicker — active wither battles.</li>
 *   <li>RaidManagerTicker — raid wave scheduling.</li>
 *   <li>ScoreboardTicker — objective display cycling.</li>
 *   <li>CommandDispatchTicker — pending commands from console/players.</li>
 * </ul>
 */
public final class GlobalSystems {

    private final CopyOnWriteArrayList<Registered> tickers = new CopyOnWriteArrayList<>();
    private final AtomicLong tickCounter = new AtomicLong();

    public Registered register(String name, GlobalTicker ticker) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(ticker, "ticker");
        Registered r = new Registered(name, ticker);
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
     * Fire all registered tickers in registration order. Called once
     * per global tick by the scheduler.
     */
    public long tickAll() {
        long tick = tickCounter.incrementAndGet();
        for (Registered r : tickers) {
            try {
                r.ticker.tick(tick);
            } catch (Throwable t) {
                Thread.currentThread().getUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), t);
            }
        }
        return tick;
    }

    public long currentTick() {
        return tickCounter.get();
    }

    public record Registered(String name, GlobalTicker ticker) {}
}
