/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
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
