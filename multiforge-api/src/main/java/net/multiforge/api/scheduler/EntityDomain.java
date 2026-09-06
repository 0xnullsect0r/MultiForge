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
