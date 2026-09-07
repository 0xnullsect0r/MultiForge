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
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.multiforge.runtime.commands.MultiForgeCommandDispatcher;
import net.multiforge.runtime.config.MultiForgeConfigStore;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.region.pin.RegionPinManager;
import net.neoforged.bus.api.SubscribeEvent;
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
 * <p>Uses {@code NeoForge.EVENT_BUS.register(this)} with a
 * {@link SubscribeEvent}-annotated instance method — same pattern as
 * NeoForge's own {@code /neoforge} command via {@code
 * NeoForgeEventHandler}, which is the empirically-working shape on the
 * MultiForge-wrapped bus. The 1-arg / 2-arg {@code addListener} paths
 * did not observe listener firings during v1.3.7/v1.3.8 live testing;
 * v1.3.9 switches to the {@code register(Object)} path.
 *
 * <p>Called from {@link net.neoforged.neoforge.server.ServerLifecycleHooks#handleServerAboutToStart}
 * with the server passed through so the config-store + pin-manager can
 * be resolved against the concrete server directory (Vanilla test
 * harnesses use ephemeral tmpdirs).
 */
public final class MultiForgeCommandBinder {
    private static final Logger LOGGER = LoggerFactory.getLogger("multiforge.commands");

    /** Singleton — one instance per JVM. NeoForge.EVENT_BUS.register(this) once. */
    private static final AtomicReference<MultiForgeCommandBinder> INSTANCE = new AtomicReference<>();

    private final MultiForgeCommandDispatcher dispatcher;

    private MultiForgeCommandBinder(MultiForgeCommandDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * Load the config store + pin manager from the server directory and
     * register a {@link RegisterCommandsEvent}-subscribing instance on
     * {@link NeoForge#EVENT_BUS}. Idempotent per JVM.
     */
    public static void register(MinecraftServer server) {
        if (INSTANCE.get() != null) {
            LOGGER.info("MultiForge: /multiforge binder already registered — skipping");
            return;
        }
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

        MultiForgeCommandBinder binder = new MultiForgeCommandBinder(new MultiForgeCommandDispatcher(configStore, pins));
        if (INSTANCE.compareAndSet(null, binder)) {
            NeoForge.EVENT_BUS.register(binder);
            LOGGER.info("MultiForge: /multiforge binder registered via NeoForge.EVENT_BUS.register(Object) with @SubscribeEvent");
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
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
                                    String raw = StringArgumentType.getString(ctx, "args").trim();
                                    String[] tokens = raw.isEmpty() ? new String[0] : raw.split("\\s+");
                                    dispatcher.dispatch(
                                            tokens,
                                            msg -> ctx.getSource().sendSuccess(() -> Component.literal(msg), false));
                                    return 1;
                                })));
    }
}
