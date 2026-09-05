/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
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

    /** Named ticket type with permanent (never-expires) semantics. */
    public static TicketType of(String name, int defaultDistance) {
        return new TicketType(name, defaultDistance, 0);
    }

    /** Named ticket type with an explicit expiry timeout in game ticks. */
    public static TicketType of(String name, int defaultDistance, int timeoutTicks) {
        return new TicketType(name, defaultDistance, timeoutTicks);
    }
}
