/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.api.folia;

import java.util.function.Consumer;
import net.multiforge.api.mod.ModIdentifier;
import net.multiforge.api.scheduler.ScheduledTask;
import net.multiforge.api.scheduler.ServerDomains;

/**
 * Folia-shaped mirror of the global-region scheduler.
 */
public final class GlobalRegionScheduler {

    private GlobalRegionScheduler() {}

    public static ScheduledTask execute(ModIdentifier mod, Runnable task) {
        return ServerDomains.global().execute(mod, task);
    }

    public static ScheduledTask run(ModIdentifier mod, Consumer<ScheduledTask> task) {
        return ServerDomains.global().run(mod, task);
    }

    public static ScheduledTask runDelayed(ModIdentifier mod, Consumer<ScheduledTask> task, long delayTicks) {
        return ServerDomains.global().runDelayed(mod, task, delayTicks);
    }

    public static ScheduledTask runAtFixedRate(
            ModIdentifier mod, Consumer<ScheduledTask> task, long initialTicks, long periodTicks) {
        return ServerDomains.global().runAtFixedRate(mod, task, initialTicks, periodTicks);
    }
}
