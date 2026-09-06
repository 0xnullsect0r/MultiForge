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
