/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.api.scheduler;

import java.util.function.Consumer;
import net.multiforge.api.mod.ModIdentifier;

/**
 * Runs work on the region worker thread that currently owns a chunk.
 * The region that owns a chunk can change over time (merge/split); the
 * scheduler resolves the owner at dispatch time, so tasks are always
 * delivered to the current owner even if it differs from the owner at
 * schedule time.
 */
public interface RegionDomain {

    /** Run {@code task} once, as soon as possible on the owning region. */
    ScheduledTask execute(ModIdentifier mod, Runnable task);

    /** Same as {@link #execute(ModIdentifier, Runnable)} but the task receives its own handle. */
    ScheduledTask run(ModIdentifier mod, Consumer<ScheduledTask> task);

    /** Run {@code task} once after {@code delayTicks} (each tick ≈ 50 ms). */
    ScheduledTask runDelayed(ModIdentifier mod, Consumer<ScheduledTask> task, long delayTicks);

    /**
     * Run {@code task} first after {@code initialTicks}, then every
     * {@code periodTicks} until cancelled.
     */
    ScheduledTask runAtFixedRate(ModIdentifier mod, Consumer<ScheduledTask> task, long initialTicks, long periodTicks);
}
