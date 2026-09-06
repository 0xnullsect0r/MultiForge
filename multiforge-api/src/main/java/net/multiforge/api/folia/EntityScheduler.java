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
import net.multiforge.api.entity.EntityRef;
import net.multiforge.api.mod.ModIdentifier;
import net.multiforge.api.scheduler.ScheduledTask;
import net.multiforge.api.scheduler.ServerDomains;

/**
 * Folia-shaped mirror of the per-entity scheduler.
 */
public final class EntityScheduler {

    private EntityScheduler() {}

    public static ScheduledTask execute(ModIdentifier mod, EntityRef entity, Runnable task, Runnable retired) {
        return ServerDomains.entity(entity).execute(mod, task, retired);
    }

    public static ScheduledTask run(
            ModIdentifier mod, EntityRef entity, Consumer<ScheduledTask> task, Runnable retired) {
        return ServerDomains.entity(entity).run(mod, task, retired);
    }

    public static ScheduledTask runDelayed(
            ModIdentifier mod, EntityRef entity, Consumer<ScheduledTask> task, Runnable retired, long delayTicks) {
        return ServerDomains.entity(entity).runDelayed(mod, task, retired, delayTicks);
    }

    public static ScheduledTask runAtFixedRate(
            ModIdentifier mod,
            EntityRef entity,
            Consumer<ScheduledTask> task,
            Runnable retired,
            long initialTicks,
            long periodTicks) {
        return ServerDomains.entity(entity).runAtFixedRate(mod, task, retired, initialTicks, periodTicks);
    }
}
