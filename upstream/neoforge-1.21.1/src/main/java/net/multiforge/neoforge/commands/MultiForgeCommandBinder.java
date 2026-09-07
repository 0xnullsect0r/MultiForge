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

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.multiforge.neoforge.MultiForgeServerState;
import net.multiforge.runtime.commands.MultiForgeCommandDispatcher;
import net.multiforge.runtime.config.MultiForgeConfigStore;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.region.pin.RegionPinManager;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the {@code /multiforge} Brigadier tree and registers it
 * directly on the server's command dispatcher from {@code
 * handleServerStarting}. Every terminal node routes through the same
 * {@link MultiForgeCommandDispatcher} so subcommand behavior stays
 * centralized in the runtime module — the tree here only exists for
 * tab-completion and argument-typing.
 *
 * <p>Op-only ({@code source.hasPermission(2)}), per {@code README.md
 * § In-game commands}.
 */
public final class MultiForgeCommandBinder {
    private static final Logger LOGGER = LoggerFactory.getLogger("multiforge.commands");

    private MultiForgeCommandBinder() {}

    public static void register(MinecraftServer server) {
        Path serverDir = server.getServerDirectory().toAbsolutePath();
        Path configFile = serverDir.resolve("config").resolve("multiforge-server.toml");

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

        // v1.3.16: share ONE RegionPinManager instance with
        // DebugChannelServer via MultiForgeServerState so /multiforge
        // region pin mutations are visible to the client's pin
        // renderer within one 4 Hz PinListEmitter tick.
        RegionPinManager pins = MultiForgeServerState.pinManagerFor(server);

        // v1.3.16: wire the 3-arg dispatcher constructor so
        // /multiforge chunks <world> resolves against the M9
        // ChunkHolderManager instead of returning "bridge not
        // installed". chunkManagerForOrNull is a non-creating
        // lookup — legitimate for a diagnostic subcommand.
        java.util.function.Function<net.multiforge.api.world.WorldRef, net.multiforge.runtime.chunk.ChunkHolderManager>
                chunkManagers = world -> {
                    MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
                    return host == null ? null : host.chunkManagerForOrNull(world);
                };
        MultiForgeCommandDispatcher dispatcher = new MultiForgeCommandDispatcher(configStore, pins, chunkManagers);

        try {
            server.getCommands().getDispatcher().register(buildTree(dispatcher));
            LOGGER.info("MultiForge: /multiforge Brigadier tree registered with tab-completion");
        } catch (Throwable t) {
            ViolationLogger.warn(
                    "MultiForgeCommandBinder.register",
                    "failed to register /multiforge on the server's dispatcher: " + t.getMessage());
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildTree(MultiForgeCommandDispatcher dispatcher) {
        return Commands.literal("multiforge")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> run(dispatcher, ctx)) // bare `/multiforge` → help
                .then(Commands.literal("help").executes(ctx -> run(dispatcher, ctx, "help")))
                .then(configSubtree(dispatcher))
                .then(regionSubtree(dispatcher))
                .then(probesSubtree(dispatcher))
                .then(chunksSubtree(dispatcher))
                .then(warnSubtree(dispatcher))
                .then(certifySubtree(dispatcher));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> configSubtree(MultiForgeCommandDispatcher dispatcher) {
        return Commands.literal("config")
                .then(Commands.literal("cores")
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 128))
                                .executes(ctx -> run(dispatcher, ctx, "config", "cores", intArg(ctx, "n")))))
                .then(Commands.literal("threads")
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 8))
                                .executes(ctx -> run(dispatcher, ctx, "config", "threads", intArg(ctx, "n")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> regionSubtree(MultiForgeCommandDispatcher dispatcher) {
        return Commands.literal("region")
                .then(Commands.literal("list").executes(ctx -> run(dispatcher, ctx, "region", "list")))
                .then(Commands.literal("size")
                        .then(Commands.argument("chunks", IntegerArgumentType.integer(1, 256))
                                .executes(ctx -> run(dispatcher, ctx, "region", "size", intArg(ctx, "chunks")))))
                .then(Commands.literal("mode")
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                        Stream.of("player-only", "full-world"), b))
                                .executes(ctx -> run(dispatcher, ctx, "region", "mode", strArg(ctx, "mode")))))
                .then(pinSubtree(dispatcher))
                .then(Commands.literal("unpin")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> run(dispatcher, ctx, "region", "unpin", strArg(ctx, "id")))));
    }

    /**
     * `/multiforge region pin <id> <world> <fromCX> <fromCZ> <toCX> <toCZ>` —
     * broken into a helper so the deeply-nested Brigadier tree doesn't
     * exceed Google-Java-Format's parser depth.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> pinSubtree(MultiForgeCommandDispatcher dispatcher) {
        // Build inside-out so we avoid Java 21 parser limits and keep it readable.
        var toCZ = Commands.argument("toCZ", IntegerArgumentType.integer())
                .executes(ctx -> run(
                        dispatcher,
                        ctx,
                        "region",
                        "pin",
                        strArg(ctx, "id"),
                        strArg(ctx, "world"),
                        intArg(ctx, "fromCX"),
                        intArg(ctx, "fromCZ"),
                        intArg(ctx, "toCX"),
                        intArg(ctx, "toCZ")));
        var toCX = Commands.argument("toCX", IntegerArgumentType.integer()).then(toCZ);
        var fromCZ = Commands.argument("fromCZ", IntegerArgumentType.integer()).then(toCX);
        var fromCX = Commands.argument("fromCX", IntegerArgumentType.integer()).then(fromCZ);
        var world = Commands.argument("world", StringArgumentType.string())
                .suggests((ctx, b) -> SharedSuggestionProvider.suggestResource(
                        ctx.getSource().levels().stream().map(level -> level.location()), b))
                .then(fromCX);
        var id = Commands.argument("id", StringArgumentType.word()).then(world);
        return Commands.literal("pin").then(id);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> probesSubtree(MultiForgeCommandDispatcher dispatcher) {
        return Commands.literal("probes")
                .executes(ctx -> run(dispatcher, ctx, "probes"))
                .then(Commands.argument("prefix", StringArgumentType.greedyString())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                Stream.of("region-tick", "chunk-system", "event-dispatch", "ownership"), b))
                        .executes(ctx -> run(dispatcher, ctx, "probes", strArg(ctx, "prefix"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> chunksSubtree(MultiForgeCommandDispatcher dispatcher) {
        return Commands.literal("chunks")
                .then(Commands.argument("world", StringArgumentType.string())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggestResource(
                                ctx.getSource().levels().stream().map(level -> level.location()), b))
                        .executes(ctx -> run(dispatcher, ctx, "chunks", strArg(ctx, "world"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> warnSubtree(MultiForgeCommandDispatcher dispatcher) {
        return Commands.literal("warn")
                .then(Commands.literal("list").executes(ctx -> run(dispatcher, ctx, "warn", "list")))
                .then(Commands.literal("clear").executes(ctx -> run(dispatcher, ctx, "warn", "clear")));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> certifySubtree(MultiForgeCommandDispatcher dispatcher) {
        return Commands.literal("certify")
                .then(Commands.literal("all").executes(ctx -> run(dispatcher, ctx, "certify", "all")))
                .then(Commands.argument("modId", StringArgumentType.word())
                        .executes(ctx -> run(dispatcher, ctx, "certify", strArg(ctx, "modId"))));
    }

    private static int run(MultiForgeCommandDispatcher dispatcher, CommandContext<CommandSourceStack> ctx, String... args) {
        dispatcher.dispatch(args, msg -> ctx.getSource().sendSuccess(() -> Component.literal(msg), false));
        return 1;
    }

    private static String strArg(CommandContext<CommandSourceStack> ctx, String name) {
        return StringArgumentType.getString(ctx, name);
    }

    private static String intArg(CommandContext<CommandSourceStack> ctx, String name) {
        return String.valueOf(IntegerArgumentType.getInteger(ctx, name));
    }
}
