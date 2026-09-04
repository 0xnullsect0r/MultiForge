/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.entity;

/**
 * Ownership state of an entity in the migration lifecycle. Guards
 * against two workers migrating the same entity concurrently and lets
 * a mid-flight retirement short-circuit the transfer.
 */
public enum MigrationState {
    /** Owned by exactly one region worker; safe to mutate on that worker. */
    RESIDENT,

    /**
     * Snapshot has been captured and handed off to the destination
     * region's inbox. No worker may mutate; a mid-flight retirement
     * is deferred until the destination discovers it.
     */
    MIGRATING,

    /**
     * Entity has been removed (killed, unloaded, cross-dimension exit).
     * Terminal — retirement callbacks fire and further mutation is
     * disallowed.
     */
    RETIRED,
}
