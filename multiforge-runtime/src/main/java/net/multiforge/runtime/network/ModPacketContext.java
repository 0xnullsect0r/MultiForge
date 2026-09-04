/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.network;

import java.util.Objects;
import net.multiforge.api.entity.EntityRef;

/**
 * Runtime-side implementation of NeoForge's {@code
 * IPayloadContext#enqueueWork} mirror. Mods calling
 * {@code context.enqueueWork(() -> ...)} land on the sender player's
 * current region (auto-rerouted if the handler then reaches into a
 * different region).
 *
 * <p>The M5 patch replaces NeoForge's {@code PayloadContext.enqueueWork}
 * body with a call to
 * {@code ModPacketContext.of(router, senderRef).enqueueWork(...)}.
 */
public final class ModPacketContext {

    private final NetworkPacketRouter router;
    private final EntityRef sender;

    private ModPacketContext(NetworkPacketRouter router, EntityRef sender) {
        this.router = router;
        this.sender = sender;
    }

    public static ModPacketContext of(NetworkPacketRouter router, EntityRef sender) {
        return new ModPacketContext(Objects.requireNonNull(router, "router"), Objects.requireNonNull(sender, "sender"));
    }

    /** Deliver {@code work} on the sender player's owning region worker. */
    public void enqueueWork(Runnable work) {
        router.routeToPlayer(sender, work);
    }

    public EntityRef sender() {
        return sender;
    }
}
