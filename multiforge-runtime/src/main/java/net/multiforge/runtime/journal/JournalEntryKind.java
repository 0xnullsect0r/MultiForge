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
package net.multiforge.runtime.journal;

/**
 * The kinds of writes a {@link RegionJournal} records. Bindings the
 * M6 patch adds:
 *
 * <ul>
 *   <li>{@link #CHUNK_SAVE} — an autosave snapshot for one chunk.</li>
 *   <li>{@link #ENTITY_MIGRATION} — an entity crossing between
 *       regions/dimensions. Both source and destination journal an
 *       entry so recovery can tell whether the transfer completed.</li>
 *   <li>{@link #REGION_MERGE} / {@link #REGION_SPLIT} — topology
 *       changes so recovery reconstitutes the same regionizer state.</li>
 *   <li>{@link #TICK_MARK} — periodic checkpoint entry so recovery
 *       knows how far the region got.</li>
 * </ul>
 */
public enum JournalEntryKind {
    CHUNK_SAVE,
    ENTITY_MIGRATION,
    REGION_MERGE,
    REGION_SPLIT,
    TICK_MARK,
}
