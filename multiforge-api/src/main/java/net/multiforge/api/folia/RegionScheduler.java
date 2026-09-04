/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.api.folia;

import java.util.function.Consumer;
import net.multiforge.api.mod.ModIdentifier;
import net.multiforge.api.scheduler.ScheduledTask;
import net.multiforge.api.scheduler.ServerDomains;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;

/**
 * Folia-shaped mirror of the region scheduler. Signatures match
 * {@code io.papermc.paper.threadedregions.scheduler.RegionScheduler} so
 * porting Folia plugins to MultiForge is mostly a package rename.
 * Delegates to {@link ServerDomains}.
 */
public final class RegionScheduler {

    private RegionScheduler() {}

    public static ScheduledTask execute(ModIdentifier mod, WorldRef world, int chunkX, int chunkZ, Runnable task) {
        return ServerDomains.region(world, new ChunkPos(chunkX, chunkZ)).execute(mod, task);
    }

    public static ScheduledTask run(
            ModIdentifier mod, WorldRef world, int chunkX, int chunkZ, Consumer<ScheduledTask> task) {
        return ServerDomains.region(world, new ChunkPos(chunkX, chunkZ)).run(mod, task);
    }

    public static ScheduledTask runDelayed(
            ModIdentifier mod, WorldRef world, int chunkX, int chunkZ, Consumer<ScheduledTask> task, long delayTicks) {
        return ServerDomains.region(world, new ChunkPos(chunkX, chunkZ)).runDelayed(mod, task, delayTicks);
    }

    public static ScheduledTask runAtFixedRate(
            ModIdentifier mod,
            WorldRef world,
            int chunkX,
            int chunkZ,
            Consumer<ScheduledTask> task,
            long initialTicks,
            long periodTicks) {
        return ServerDomains.region(world, new ChunkPos(chunkX, chunkZ))
                .runAtFixedRate(mod, task, initialTicks, periodTicks);
    }
}
