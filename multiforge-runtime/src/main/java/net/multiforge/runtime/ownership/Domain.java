/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.ownership;

/**
 * The execution ownership context an operation is authorised to run in.
 * See {@code docs/blueprint.md} §Terminology.
 */
public enum Domain {
    /** A region worker thread. Owns some subset of the world's chunks and entities. */
    REGION,

    /**
     * A per-entity affine executor (typically an alias for the region that
     * currently owns the entity).
     */
    ENTITY,

    /**
     * The dedicated global-region thread. Owns weather, time, world border,
     * gamerules, ender dragon, wither, raids, scoreboards.
     */
    GLOBAL,

    /** A worker on the shared async pool. May touch pure/immutable data only. */
    ASYNC,

    /** Fallback single-threaded executor for legacy/unaudited mod callbacks. */
    LEGACY_SERIAL,

    /** Netty IO thread; never mutates game state directly. */
    NETWORK,

    /** Anything else (main-thread bootstrap, shutdown, unknown). */
    UNKNOWN,
}
