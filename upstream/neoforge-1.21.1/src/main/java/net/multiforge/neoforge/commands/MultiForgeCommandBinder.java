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
package net.multiforge.neoforge.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.multiforge.runtime.commands.MultiForgeCommandDispatcher;
import net.multiforge.runtime.config.MultiForgeConfigStore;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.region.pin.RegionPinManager;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps {@link MultiForgeCommandDispatcher} in a Brigadier tree rooted
 * at {@code /multiforge} and registers it on the server's command
 * dispatcher via {@link RegisterCommandsEvent}. Op-only
 * ({@code source.hasPermission(2)}), per {@code README.md § In-game
 * commands}.
 *
 * <p>Prior to v1.3.5 the dispatcher class shipped in the runtime jar
 * but was unreachable — no Brigadier registration existed anywhere in
 * the fork or runtime. Every {@code /multiforge …} the user typed hit
 * "Unknown or incomplete command". This class closes that gap.
 *
 * <p>Called from {@link net.neoforged.neoforge.server.ServerLifecycleHooks#handleServerAboutToStart}
 * with the server passed through so the config-store + pin-manager can
 * be resolved against the concrete server directory (Vanilla test
 * harnesses use ephemeral tmpdirs).
 */
public final class MultiForgeCommandBinder {
    private static final Logger LOGGER = LoggerFactory.getLogger("multiforge.commands");

    private MultiForgeCommandBinder() {}

    /**
     * Load the config store + pin manager from the server directory and
     * register a {@link RegisterCommandsEvent} listener that binds
     * {@code /multiforge} to a {@link MultiForgeCommandDispatcher}
     * built from them.
     *
     * <p>The event listener is added to {@link NeoForge#EVENT_BUS} —
     * same pattern as {@link net.multiforge.neoforge.RegionizedChunkLifecycle#register}.
     */
    public static void register(MinecraftServer server) {
        Path serverDir = server.getServerDirectory().toAbsolutePath();
        Path configFile = serverDir.resolve("config").resolve("multiforge-server.toml");
        Path pinsFile = serverDir.resolve("config").resolve("multiforge-region-pins.json");

        MultiForgeConfigStore configStore;
        try {
            Files.createDirectories(configFile.getParent());
            configStore = MultiForgeConfigStore.load(configFile);
        } catch (IOException e) {
            ViolationLogger.warn(
                    "MultiForgeCommandBinder.register",
                    "failed to load " + configFile + " — /multiforge config subcommands will not persist: " + e.getMessage());
            configStore = new MultiForgeConfigStore(
                    configFile, net.multiforge.runtime.config.MultiForgeConfig.defaults());
        }

        RegionPinManager pins;
        try {
            pins = RegionPinManager.load(pinsFile);
        } catch (IOException e) {
            ViolationLogger.warn(
                    "MultiForgeCommandBinder.register",
                    "failed to load " + pinsFile + " — /multiforge region pin state resets on restart: " + e.getMessage());
            pins = new RegionPinManager(pinsFile);
        }

        final MultiForgeCommandDispatcher dispatcher = new MultiForgeCommandDispatcher(configStore, pins);
        LOGGER.info("MultiForge: /multiforge command binder attaching RegisterCommandsEvent listener");

        // Explicit Class<T> form — bypasses NeoForge's ASM introspection
        // of the Consumer lambda's generic type, which has been unreliable
        // for MultiForge event-bus wrappers (see M12 fix history).
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event -> {
            LOGGER.info("MultiForge: RegisterCommandsEvent fired — registering /multiforge Brigadier tree");
            event.getDispatcher()
                    .register(Commands.literal("multiforge")
                            .requires(src -> src.hasPermission(2))
                            .executes(ctx -> {
                                dispatcher.dispatch(
                                        new String[0],
                                        msg -> ctx.getSource().sendSuccess(() -> Component.literal(msg), false));
                                return 1;
                            })
                            .then(Commands.argument("args", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        String raw = StringArgumentType.getString(ctx, "args")
                                                .trim();
                                        String[] tokens = raw.isEmpty() ? new String[0] : raw.split("\\s+");
                                        dispatcher.dispatch(
                                                tokens,
                                                msg -> ctx.getSource().sendSuccess(() -> Component.literal(msg), false));
                                        return 1;
                                    })));
        });
    }
}
