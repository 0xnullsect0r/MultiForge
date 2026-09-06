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
package net.multiforge.runtime.diagnostics.emitters;

import net.multiforge.runtime.diagnostics.wire.DebugPayload;

/**
 * Per-viewer visibility gate for a {@code multiforge:debug/v1} payload,
 * consulted by the emitters in this package before a payload is handed
 * to the sink.
 *
 * <p>This pure-Java module has no notion of a NeoForge connection or a
 * real permission-node lookup (see {@code
 * docs/design/client-debug-protocol.md} §6, {@code
 * multiforge.debug.view}) — that check is fork-side, backed by
 * NeoForge's {@code Commands.LEVEL_GAMEMASTERS} default / a permission
 * plugin. {@link #ALWAYS_ALLOW} is the default used until the fork
 * wires in the real check; every emitter accepts a {@link
 * PermissionFilter} at install time so swapping it in is a one-line
 * change at the call site, not a code change in this module.
 */
@FunctionalInterface
public interface PermissionFilter {

    /**
     * @param payload the payload about to be produced/sent.
     * @param player the viewer being tested, or {@link PlayerRef#ANY}
     *     when an emitter is testing broadcast-level visibility rather
     *     than a specific connection (see {@link PlayerRef#ANY}).
     * @return {@code true} if {@code player} may see {@code payload}.
     */
    boolean canSee(DebugPayload payload, PlayerRef player);

    /** Default: never gate anything. Real enforcement is fork-side. */
    PermissionFilter ALWAYS_ALLOW = (payload, player) -> true;
}
