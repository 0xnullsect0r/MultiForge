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
package net.multiforge.api.event;

/**
 * Where a {@link DispatchDomain}-annotated event handler runs when the
 * event is posted from a region worker. Posted from any other thread (the
 * server thread, world generation, network), every handler runs on the
 * posting thread, as in NeoForge.
 *
 * <p>An unannotated handler takes the event type's default (block-,
 * chunk- and entity-local events are {@link #REGION}) or else {@link
 * #LEGACY_SERIAL}; a mod's {@code multiforge_safety} classification can
 * widen or narrow that. See docs/events.md.
 */
public enum DispatchDomainKind {
    /** Runs on the posting region worker, in parallel with other regions. */
    REGION,

    /**
     * Runs on the server thread, one handler at a time, while the posting
     * worker waits; cancellation and results reach the poster.
     */
    GLOBAL,

    /** Runs on the shared async pool; the poster does not wait, so it cannot cancel. Must not touch game state. */
    ASYNC,

    /**
     * Like {@link #GLOBAL}: serialised on the server thread, so handler code
     * that keeps unsynchronised state never runs concurrently with itself.
     */
    LEGACY_SERIAL,
}
