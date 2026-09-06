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
package net.multiforge.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge entry point for the MultiForge client debug mod.
 *
 * <p>Loads on any vanilla NeoForge 1.21.x client ({@code side =
 * "CLIENT"} in {@code META-INF/neoforge.mods.toml}, so this class never
 * loads on a dedicated server). It:
 *
 * <ul>
 *   <li>registers the {@code multiforge:debug/v1} payload channel
 *       ({@link DebugPayloadRegistration}) on the mod event bus, and
 *   <li>registers the four overlay renderers ({@link DebugHudRenderer},
 *       {@link ChunkBorderRenderer}, {@link HeatmapRenderer}, {@link
 *       PinRenderer}) on the main NeoForge event bus, all sharing one
 *       {@link DebugHudState} instance fed by {@link DebugChannelClient}.
 * </ul>
 *
 * <p>The panel stays inert (no lines drawn, no channel traffic) against
 * a vanilla or non-MultiForge NeoForge server, which never opens the
 * {@code multiforge:debug/v1} channel -- see protocol doc §1.
 */
@Mod(MultiForgeDebugMod.MOD_ID)
public final class MultiForgeDebugMod {

    public static final String MOD_ID = "multiforge_debug";

    private static final Logger LOGGER = LoggerFactory.getLogger(MultiForgeDebugMod.class);

    public MultiForgeDebugMod(IEventBus modEventBus) {
        DebugHudState state = new DebugHudState();
        DebugChannelClient channelClient = new DebugChannelClient(state);

        modEventBus.addListener(
                (RegisterPayloadHandlersEvent event) -> DebugPayloadRegistration.register(event, channelClient));

        NeoForge.EVENT_BUS.register(new DebugHudRenderer(state));
        NeoForge.EVENT_BUS.register(new ChunkBorderRenderer(state));
        NeoForge.EVENT_BUS.register(new HeatmapRenderer(state));
        NeoForge.EVENT_BUS.register(new PinRenderer(state));

        LOGGER.info("MultiForge debug client mod initialized (channel {})", DebugChannelClient.CHANNEL_ID);
    }
}
