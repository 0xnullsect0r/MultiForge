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

import java.util.Objects;

/**
 * A ticket kind — matches Vanilla {@code TicketType}. Every {@link
 * Ticket} carries a type; the type's default distance is the
 * granularity at which chunks it keeps loaded reach on the ladder.
 *
 * <p>The stock Vanilla types are enumerated here for parity; mods may
 * register their own via {@link #of(String, int)}.
 *
 * <p><b>Timeout:</b> {@code timeoutTicks} matches Vanilla's per-type
 * timeout. A value of {@code 0} means "never expires" (the default for
 * everything except {@code POST_TELEPORT} and {@code ENDER_PEARL} in
 * Vanilla). {@link TicketExpiryTicker} sweeps expired tickets every N
 * ticks on the global region.
 */
public record TicketType(String name, int defaultDistance, int timeoutTicks) {

    public TicketType {
        Objects.requireNonNull(name, "name");
        if (defaultDistance < 0 || defaultDistance > 64) {
            throw new IllegalArgumentException("defaultDistance out of range: " + defaultDistance);
        }
        if (timeoutTicks < 0) {
            throw new IllegalArgumentException("timeoutTicks must be >= 0: " + timeoutTicks);
        }
    }

    /** Player-view radius; produces ENTITY_TICKING at the closest chunk. Permanent while player online. */
    public static final TicketType PLAYER = new TicketType("player", ChunkLoadLevel.ENTITY_TICKING.distance(), 0);

    /** Level.setChunkForced. Keeps chunks BORDER-loaded. Permanent until removed. */
    public static final TicketType FORCED = new TicketType("forced", ChunkLoadLevel.BORDER.distance(), 0);

    /** Spawn chunks around world spawn. TICKING. Permanent. */
    public static final TicketType START = new TicketType("start", ChunkLoadLevel.TICKING.distance(), 0);

    /** Mod/plugin-issued ticket. BORDER by default. Permanent unless mod removes. */
    public static final TicketType PLUGIN = new TicketType("plugin", ChunkLoadLevel.BORDER.distance(), 0);

    /** Ticket held for a few seconds after a player teleport. Vanilla: 5 seconds = 100 ticks. */
    public static final TicketType POST_TELEPORT =
            new TicketType("post_teleport", ChunkLoadLevel.ENTITY_TICKING.distance(), 5);

    /**
     * Special ticket kind for ender pearls — Folia keys these by
     * entityId to prevent global ticket-count exhaustion when many
     * pearls fly at once. Vanilla timeout: 40 ticks.
     */
    public static final TicketType ENDER_PEARL = new TicketType("ender_pearl", ChunkLoadLevel.TICKING.distance(), 40);

    /**
     * End-dragon-fight arena pin: keeps the end-podium and dragon
     * phase-relevant chunks loaded for the duration of an active fight,
     * independent of player presence. Matches vanilla's own
     * {@code net.minecraft.server.level.TicketType<Unit> DRAGON}
     * ({@code TicketType.create("dragon", (a, b) -> 0)}, timeout 0),
     * translated into this module's {@code (name, distance, timeoutTicks)}
     * shape. {@link ChunkLoadLevel#TICKING}'s distance (32) is used
     * rather than {@link ChunkLoadLevel#ENTITY_TICKING}'s (31) — block
     * and fluid ticks fire on the arena unconditionally, while a nearby
     * {@code PLAYER} ticket is what promotes chunks the rest of the way
     * to entity-ticking, mirroring vanilla's own layering. See
     * docs/design/global-region.md §5.1.
     */
    public static final TicketType DRAGON = new TicketType("dragon", ChunkLoadLevel.TICKING.distance(), 0);

    /** Named ticket type with permanent (never-expires) semantics. */
    public static TicketType of(String name, int defaultDistance) {
        return new TicketType(name, defaultDistance, 0);
    }

    /** Named ticket type with an explicit expiry timeout in game ticks. */
    public static TicketType of(String name, int defaultDistance, int timeoutTicks) {
        return new TicketType(name, defaultDistance, timeoutTicks);
    }
}
