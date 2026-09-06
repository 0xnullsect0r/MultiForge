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
