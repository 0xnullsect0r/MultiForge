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
package net.multiforge.api.event;

/**
 * Where a {@link DispatchDomain}-annotated event handler expects to
 * run. Unannotated handlers default to {@link #LEGACY_SERIAL}, which
 * MultiForge routes through a per-mod serialised executor with
 * automatic reroute-and-warn for cross-region access. Opt in to a
 * stronger dispatch mode for handlers you have audited.
 */
public enum DispatchDomainKind {
    /** Runs on the region worker that currently owns the event's target. */
    REGION,

    /** Runs on the dedicated global-region thread. */
    GLOBAL,

    /** Runs on the shared async pool; must not touch game state. */
    ASYNC,

    /** Default: runs on the per-mod serialised legacy executor. */
    LEGACY_SERIAL,
}
