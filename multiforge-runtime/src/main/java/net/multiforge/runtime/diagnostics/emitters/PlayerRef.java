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

import java.util.Objects;
import java.util.UUID;

/**
 * MC-free stand-in for a connected viewer of the {@code
 * multiforge:debug/v1} channel. {@code multiforge-runtime} has no
 * dependency on Minecraft types (CLAUDE.md repository layout), so
 * emitters and {@link PermissionFilter} implementations exchange this
 * minimal identity record instead of a NeoForge {@code ServerPlayer}.
 * The fork's network glue is expected to construct one per connection
 * (backed by the player's UUID + name) when it wires a real {@link
 * PermissionFilter} and per-viewer {@code PayloadDistributor}.
 *
 * @param id UUID of the connected player.
 * @param name the player's display/login name, for logging.
 */
public record PlayerRef(UUID id, String name) {

    /**
     * Sentinel used by an emitter when it tests broadcast-level
     * visibility (i.e. "should this payload be produced/sent at all,
     * independent of any single viewer's per-connection state") rather
     * than a specific connected player. See {@link
     * PermissionFilter#ALWAYS_ALLOW} and the emitters in this package
     * for how it is used.
     */
    public static final PlayerRef ANY = new PlayerRef(new UUID(0L, 0L), "*");

    public PlayerRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }
}
