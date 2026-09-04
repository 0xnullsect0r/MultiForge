/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.api.scheduler;

import java.util.function.Consumer;
import net.multiforge.api.mod.ModIdentifier;

/**
 * Runs work on the dedicated global-region thread — weather, time,
 * world border, gamerules, ender-dragon/wither/raid managers,
 * scoreboards, command dispatch. Use this for state that has no natural
 * spatial owner.
 */
public interface GlobalDomain {

    ScheduledTask execute(ModIdentifier mod, Runnable task);

    ScheduledTask run(ModIdentifier mod, Consumer<ScheduledTask> task);

    ScheduledTask runDelayed(ModIdentifier mod, Consumer<ScheduledTask> task, long delayTicks);

    ScheduledTask runAtFixedRate(ModIdentifier mod, Consumer<ScheduledTask> task, long initialTicks, long periodTicks);
}
