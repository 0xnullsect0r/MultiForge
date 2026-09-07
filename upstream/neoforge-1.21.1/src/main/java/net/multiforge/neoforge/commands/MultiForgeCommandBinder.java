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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps {@link MultiForgeCommandDispatcher} in a Brigadier tree rooted
 * at {@code /multiforge} and registers it directly on the server's
 * command dispatcher. Op-only ({@code source.hasPermission(2)}), per
 * {@code README.md § In-game commands}.
 *
 * <p>Called from {@link net.neoforged.neoforge.server.ServerLifecycleHooks#handleServerStarting}
 * (not {@code AboutToStart}) so {@code server.getCommands()} is already
 * initialized — {@code loadLevel()} constructs {@code
 * ReloadableServerResources} which owns {@code Commands}, so by the
 * time {@code handleServerStarting} runs the dispatcher exists.
 *
 * <p>Direct registration bypasses {@code RegisterCommandsEvent}
 * entirely. v1.3.7 and v1.3.8 both proved (via log-line instrumentation)
 * that both {@code NeoForge.EVENT_BUS.addListener(Class, Consumer)} and
 * {@code register(Object)} with {@code @SubscribeEvent} DO successfully
 * register a listener on the MultiForge-wrapped bus, yet the listener
 * never fires when {@code RegisterCommandsEvent} is posted. Rather
 * than chase that mystery, this class registers on the concrete
 * dispatcher instead — same effect, no event-bus dependency.
 */
public final class MultiForgeCommandBinder {
    private static final Logger LOGGER = LoggerFactory.getLogger("multiforge.commands");

    private MultiForgeCommandBinder() {}

    /**
     * Load the config store + pin manager from the server directory and
     * add the {@code /multiforge} subtree to the server's command
     * dispatcher. Idempotent per server instance — a re-invocation for
     * a reused GameTestServer JVM overwrites the previous registration.
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

        MultiForgeCommandDispatcher dispatcher = new MultiForgeCommandDispatcher(configStore, pins);

        try {
            server.getCommands()
                    .getDispatcher()
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
            LOGGER.info("MultiForge: /multiforge command registered directly on server's Brigadier dispatcher");
        } catch (Throwable t) {
            ViolationLogger.warn(
                    "MultiForgeCommandBinder.register",
                    "failed to register /multiforge on the server's dispatcher: " + t.getMessage());
        }
    }
}
