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
 * Resets per-connection state on the client side.
 *
 * <p>Two things reset here:
 *
 * <ul>
 *   <li>The subscription mask ({@link SubscriptionManager#reset()}).
 *       Pre-v1.3.16 nothing reset it, so switching between two
 *       MultiForge servers in one client session left the client
 *       thinking it had already opted in and never re-subscribing.
 *   <li>The whole {@link DebugHudState} (v1.4.0). Nothing had ever
 *       cleared it, so after leaving a MultiForge server the HUD went
 *       on drawing that server's build label, region count and TPS —
 *       and since the heatmap and pin renderers gate only on a world-id
 *       <em>string</em>, and {@code minecraft:overworld} matches
 *       everywhere, its heat tiles and pin boxes rendered on top of the
 *       player's own singleplayer world.
 * </ul>
 */
public final class DebugSessionHandler {

    private final DebugHudState state;
    private final SubscriptionManager subscriptions;

    public DebugSessionHandler(DebugHudState state, SubscriptionManager subscriptions) {
        this.state = Objects.requireNonNull(state, "state");
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
    }

    @SubscribeEvent
    public void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        subscriptions.reset();
        state.clear();
    }
}
