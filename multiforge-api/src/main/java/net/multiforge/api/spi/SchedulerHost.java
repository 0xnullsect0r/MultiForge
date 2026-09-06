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
