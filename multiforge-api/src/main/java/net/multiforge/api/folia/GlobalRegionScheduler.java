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
