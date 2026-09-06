/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
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
