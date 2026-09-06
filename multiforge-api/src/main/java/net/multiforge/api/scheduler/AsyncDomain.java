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

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.multiforge.api.mod.ModIdentifier;

/**
 * Runs work on a shared async worker pool. <b>Never touches game
 * state.</b> Use for pure computation, IO, and CPU work that has no
 * dependency on region ownership.
 *
 * <p>A task that needs to hand results back into the tick loop should
 * finish with a {@code ServerDomains.region(...).execute(...)} or
 * {@code global().execute(...)} call.
 */
public interface AsyncDomain {

    ScheduledTask runNow(ModIdentifier mod, Consumer<ScheduledTask> task);

    ScheduledTask runDelayed(ModIdentifier mod, Consumer<ScheduledTask> task, long delay, TimeUnit unit);

    ScheduledTask runAtFixedRate(
            ModIdentifier mod, Consumer<ScheduledTask> task, long initial, long period, TimeUnit unit);

    /** Cancel every task owned by {@code mod}. Returns the number of tasks cancelled. */
    int cancelTasks(ModIdentifier mod);
}
