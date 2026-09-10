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
    private final Runnable onToggled;

    /**
     * @param onToggled run after each toggle. v1.4.0 uses it to re-send
     *     SUBSCRIBE, which both stops server traffic while the overlays
     *     are hidden and gives protocol §6's permission re-check
     *     something to fire on.
     */
    public KeyInputHandler(DebugHudState state, Runnable onToggled) {
        this.state = Objects.requireNonNull(state, "state");
        this.onToggled = Objects.requireNonNull(onToggled, "onToggled");
    }

    @SubscribeEvent
    public void onClientTickPost(ClientTickEvent.Post event) {
        // consumeClick() drains any queued press since the last check;
        // it's idempotent-per-tick, so this handles the case where the
        // user mashes the key several times in one frame.
        while (MultiForgeKeyMappings.TOGGLE_OVERLAYS.consumeClick()) {
            boolean nowEnabled = state.toggleOverlays();
            onToggled.run();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.translatable(
                                nowEnabled ? "multiforge_debug.overlays.on" : "multiforge_debug.overlays.off"),
                        true);
            }
        }
    }
}
