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

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
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
 *       ({@link DebugPayloadRegistration}) on the mod event bus,
 *   <li>registers the per-overlay client config ({@link
 *       MultiForgeDebugConfig}) and the auto-generated screen that
 *       edits it from the Mods list,
 *   <li>registers the overlay keybind ({@link MultiForgeKeyMappings}),
 *       and
 *   <li>registers the HUD panel renderer plus the three world
 *       renderers ({@link ChunkBorderRenderer}, {@link
 *       HeatmapRenderer}, {@link PinRenderer}) on the main NeoForge
 *       event bus, all sharing one {@link DebugHudState} instance fed
 *       by {@link DebugChannelClient}.
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

    public MultiForgeDebugMod(IEventBus modEventBus, ModContainer modContainer) {
        DebugHudState state = new DebugHudState();
        DebugChannelClient channelClient = new DebugChannelClient(state);
        SubscriptionManager subscriptions = new SubscriptionManager(state, MultiForgeDebugConfig::desiredMask);

        // v1.4.0: per-overlay client config + the config screen NeoForge
        // generates from the spec. Registering the extension point is
        // what puts the "Config" button on this mod's row in the Mods
        // list; ConfigurationScreen's (ModContainer, Screen) constructor
        // is exactly IConfigScreenFactory#createScreen's shape.
        modContainer.registerConfig(ModConfig.Type.CLIENT, MultiForgeDebugConfig.SPEC);
        // Bound to a local first: registerExtensionPoint is overloaded on
        // (Class<T>, T) and (Class<T>, Supplier<T>), and a bare method
        // reference is ambiguous between them.
        IConfigScreenFactory configScreen = ConfigurationScreen::new;
        modContainer.registerExtensionPoint(IConfigScreenFactory.class, configScreen);

        modEventBus.addListener((RegisterPayloadHandlersEvent event) ->
                DebugPayloadRegistration.register(event, channelClient, state, subscriptions));

        // v1.3.15: user-configurable keybind (default F6) that toggles
        // every overlay + HUD line via DebugHudState.overlaysEnabled.
        // Keymapping registration is a mod-bus event.
        modEventBus.addListener((RegisterKeyMappingsEvent event) -> MultiForgeKeyMappings.register(event));

        NeoForge.EVENT_BUS.register(new DebugHudRenderer(state));
        NeoForge.EVENT_BUS.register(new ChunkBorderRenderer(state));
        NeoForge.EVENT_BUS.register(new HeatmapRenderer(state));
        NeoForge.EVENT_BUS.register(new PinRenderer(state));
        NeoForge.EVENT_BUS.register(new KeyInputHandler(state, enabled -> resync(state, subscriptions)));
        // v1.3.16: reset the per-connection subscription on disconnect so
        // hopping between MultiForge servers in one session works.
        // v1.4.0: also wipe the HUD model, so a disconnect stops the old
        // server's data bleeding into the next world.
        NeoForge.EVENT_BUS.register(new DebugSessionHandler(state, subscriptions));

        LOGGER.info("MultiForge debug client mod initialized (channel {})", DebugChannelClient.CHANNEL_ID);
    }

    /**
     * Push the current desired subscription mask to the server, if we
     * are on a MultiForge server at all.
     *
     * <p>The {@code hello() != null} guard matters: on a vanilla or
     * non-MultiForge server the channel was never negotiated, and
     * pressing F6 there must not attempt a send.
     */
    private static void resync(DebugHudState state, SubscriptionManager subscriptions) {
        if (state.hello() == null || state.protocolUnsupported()) {
            return;
        }
        subscriptions.syncIfChanged(bytes -> PacketDistributor.sendToServer(new DebugFramePayload(bytes)));
    }
}
