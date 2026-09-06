/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.multiforge.runtime.shutdown;

/**
 * Phases the {@link RegionShutdownCoordinator} walks through in order
 * on {@code /stop}. Each phase is idempotent; each blocks until every
 * region has acknowledged before advancing.
 *
 * <ul>
 *   <li>{@link #ACCEPTING} — normal running state; new work admitted.</li>
 *   <li>{@link #DRAINING_INBOX} — refuse new admits, keep ticking so
 *       backlog and in-flight teleports settle.</li>
 *   <li>{@link #FLUSHING_JOURNAL} — force every {@code RegionJournal} to
 *       disk so a crash after this point still recovers the state
 *       written before.</li>
 *   <li>{@link #STOPPING_WORKERS} — signal the tick pool to exit and
 *       await pool termination.</li>
 *   <li>{@link #STOPPED} — terminal; safe to release the JVM.</li>
 * </ul>
 */
public enum ShutdownPhase {
    ACCEPTING,
    DRAINING_INBOX,
    FLUSHING_JOURNAL,
    STOPPING_WORKERS,
    STOPPED,
}
