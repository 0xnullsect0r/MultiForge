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
package net.multiforge.client;

import java.util.Objects;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * Resets per-connection state on the client side. Currently only the
 * {@link DebugChannelClient#hasSubscribed()} latch — pre-v1.3.16 that
 * latch never reset, so switching between two MultiForge servers in
 * one client session left the client thinking it had already sent
 * SUBSCRIBE and never re-opted in on the new server.
 */
public final class DebugSessionHandler {

    private final DebugChannelClient channelClient;

    public DebugSessionHandler(DebugChannelClient channelClient) {
        this.channelClient = Objects.requireNonNull(channelClient, "channelClient");
    }

    @SubscribeEvent
    public void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        channelClient.resetSubscription();
    }
}
