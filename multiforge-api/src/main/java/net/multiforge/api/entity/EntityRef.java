/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
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
