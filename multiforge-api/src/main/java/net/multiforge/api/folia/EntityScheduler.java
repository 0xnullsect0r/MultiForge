/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
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
