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
