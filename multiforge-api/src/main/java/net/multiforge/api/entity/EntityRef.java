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
package net.multiforge.api.entity;

import java.util.UUID;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;

/**
 * Public handle for a live entity used by the MultiForge scheduler API.
 * The runtime is authoritative for {@link #world()} and {@link
 * #chunkPos()} — they reflect the entity's <em>current</em> region and
 * are re-resolved by the scheduler on every dispatch (so a task
 * scheduled on an entity that has since crossed a region border still
 * runs on the correct owner thread).
 *
 * <p>An entity that has been removed reports {@link #isRetired()}
 * {@code true}; scheduled callbacks associated with a retired entity
 * fire their {@code retired} runnable instead of the main task, matching
 * Folia's {@code EntityScheduler} semantics.
 */
public interface EntityRef {

    UUID uuid();

    WorldRef world();

    ChunkPos chunkPos();

    boolean isRetired();
}
