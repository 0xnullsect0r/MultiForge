/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.api.spi;

import net.multiforge.api.entity.EntityRef;
import net.multiforge.api.scheduler.AsyncDomain;
import net.multiforge.api.scheduler.EntityDomain;
import net.multiforge.api.scheduler.GlobalDomain;
import net.multiforge.api.scheduler.RegionDomain;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;

/**
 * Service provider interface that {@link
 * net.multiforge.api.scheduler.ServerDomains} delegates to. Bound by
 * the MultiForge runtime via {@link java.util.ServiceLoader} or an
 * explicit call to {@code ServerDomains.install(...)} during server
 * boot. Mods should not implement this.
 */
public interface SchedulerHost {

    RegionDomain region(WorldRef world, ChunkPos pos);

    EntityDomain entity(EntityRef entity);

    GlobalDomain global();

    AsyncDomain async();
}
