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
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Consumes queued key events on each client tick. On each queued press
 * of {@link MultiForgeKeyMappings#TOGGLE_OVERLAYS}, flips the
 * {@link DebugHudState#overlaysEnabled()} flag and shows a brief chat
 * ack so the user has feedback the keybind actually landed.
 */
public final class KeyInputHandler {

    private final DebugHudState state;

    public KeyInputHandler(DebugHudState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    @SubscribeEvent
    public void onClientTickPost(ClientTickEvent.Post event) {
        // consumeClick() drains any queued press since the last check;
        // it's idempotent-per-tick, so this handles the case where the
        // user mashes the key several times in one frame.
        while (MultiForgeKeyMappings.TOGGLE_OVERLAYS.consumeClick()) {
            boolean nowEnabled = state.toggleOverlays();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("MultiForge overlays: " + (nowEnabled ? "on" : "off")), true);
            }
        }
    }
}
