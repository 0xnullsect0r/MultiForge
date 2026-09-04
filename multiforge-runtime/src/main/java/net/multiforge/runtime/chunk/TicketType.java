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
 * register their own via {@link #of(String, int)}. The M3 patch binds
 * MultiForge tickets to Vanilla ones one-to-one.
 */
public record TicketType(String name, int defaultDistance) {

    public TicketType {
        Objects.requireNonNull(name, "name");
        if (defaultDistance < 0 || defaultDistance > 64) {
            throw new IllegalArgumentException("defaultDistance out of range: " + defaultDistance);
        }
    }

    /** Player-view radius; produces ENTITY_TICKING at the closest chunk. */
    public static final TicketType PLAYER = new TicketType("player", ChunkLoadLevel.ENTITY_TICKING.distance());

    /** Level.setChunkForced. Keeps chunks BORDER-loaded. */
    public static final TicketType FORCED = new TicketType("forced", ChunkLoadLevel.BORDER.distance());

    /** Spawn chunks around world spawn. TICKING. */
    public static final TicketType START = new TicketType("start", ChunkLoadLevel.TICKING.distance());

    /** Mod/plugin-issued ticket. BORDER by default. */
    public static final TicketType PLUGIN = new TicketType("plugin", ChunkLoadLevel.BORDER.distance());

    /** Ticket held for a few seconds after a player teleport. */
    public static final TicketType POST_TELEPORT =
            new TicketType("post_teleport", ChunkLoadLevel.ENTITY_TICKING.distance());

    /**
     * Special ticket kind for ender pearls — Folia keys these by
     * entityId to prevent global ticket-count exhaustion when many
     * pearls fly at once.
     */
    public static final TicketType ENDER_PEARL = new TicketType("ender_pearl", ChunkLoadLevel.TICKING.distance());

    public static TicketType of(String name, int defaultDistance) {
        return new TicketType(name, defaultDistance);
    }
}
