/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.api.folia;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.multiforge.api.mod.ModIdentifier;
import net.multiforge.api.scheduler.ScheduledTask;
import net.multiforge.api.scheduler.ServerDomains;

/**
 * Folia-shaped mirror of the async scheduler.
 */
public final class AsyncScheduler {

    private AsyncScheduler() {}

    public static ScheduledTask runNow(ModIdentifier mod, Consumer<ScheduledTask> task) {
        return ServerDomains.async().runNow(mod, task);
    }

    public static ScheduledTask runDelayed(ModIdentifier mod, Consumer<ScheduledTask> task, long delay, TimeUnit unit) {
        return ServerDomains.async().runDelayed(mod, task, delay, unit);
    }

    public static ScheduledTask runAtFixedRate(
            ModIdentifier mod, Consumer<ScheduledTask> task, long initial, long period, TimeUnit unit) {
        return ServerDomains.async().runAtFixedRate(mod, task, initial, period, unit);
    }

    public static int cancelTasks(ModIdentifier mod) {
        return ServerDomains.async().cancelTasks(mod);
    }
}
