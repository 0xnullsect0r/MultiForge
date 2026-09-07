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

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side {@link KeyMapping} registrations for the MultiForge debug
 * mod. As of v1.3.15, one binding — {@link #TOGGLE_OVERLAYS} — flips
 * the master on/off flag consulted by every renderer + the F3-style
 * HUD.
 *
 * <p>Default key: {@code F6} (unbound in Vanilla). The user can
 * rebind it under Options → Controls → "MultiForge Debug" or clear the
 * assignment entirely.
 */
public final class MultiForgeKeyMappings {

    /** Vanilla-style category translation key. Rendered in the Controls menu. */
    public static final String CATEGORY = "key.categories.multiforge_debug";

    public static final KeyMapping TOGGLE_OVERLAYS = new KeyMapping(
            "key.multiforge_debug.toggle_overlays",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F6,
            CATEGORY);

    private MultiForgeKeyMappings() {}

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_OVERLAYS);
    }
}
