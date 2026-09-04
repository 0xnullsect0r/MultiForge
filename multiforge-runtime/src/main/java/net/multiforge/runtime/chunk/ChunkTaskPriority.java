/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.chunk;

/**
 * Priority ladder matching Folia's {@code ChunkTaskScheduler.Priority}
 * used by the chunk task queue. Lower ordinal = higher priority.
 */
public enum ChunkTaskPriority {
    BLOCKING,
    HIGHEST,
    HIGH,
    NORMAL,
    LOW,
    LOWEST,
    IDLE,
}
