/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
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
