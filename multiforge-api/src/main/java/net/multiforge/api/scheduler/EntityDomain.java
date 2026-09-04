/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.api.scheduler;

import java.util.function.Consumer;
import net.multiforge.api.mod.ModIdentifier;

/**
 * Runs work on the region worker that currently owns an entity — even
 * as the entity crosses region borders.
 *
 * <p>If the entity is retired (removed from the world) before the task
 * can run, the {@code retired} runnable fires instead. Repeating tasks
 * stop firing after retirement.
 */
public interface EntityDomain {

    ScheduledTask execute(ModIdentifier mod, Runnable task, Runnable retired);

    ScheduledTask run(ModIdentifier mod, Consumer<ScheduledTask> task, Runnable retired);

    ScheduledTask runDelayed(ModIdentifier mod, Consumer<ScheduledTask> task, Runnable retired, long delayTicks);

    ScheduledTask runAtFixedRate(
            ModIdentifier mod, Consumer<ScheduledTask> task, Runnable retired, long initialTicks, long periodTicks);
}
