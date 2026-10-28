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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.multiforge.neoforge.MultiForgeServerState;
import net.multiforge.neoforge.world.EntityAudit;
import net.multiforge.runtime.commands.MultiForgeCommandDispatcher;
import net.multiforge.runtime.config.MultiForgeConfigStore;
import net.multiforge.runtime.diagnostics.EntityCensus;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.region.RegionPhase;
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
        MultiForgeConfigStore configStore = MultiForgeServerState.configStoreFor(server);

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
        java.util.function.Function<net.multiforge.api.world.WorldRef, net.multiforge.runtime.chunk.ChunkHolderManager> chunkManagers = world -> {
            MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
            return host == null ? null : host.chunkManagerForOrNull(world);
        };
        MultiForgeCommandDispatcher dispatcher = new MultiForgeCommandDispatcher(configStore, pins, chunkManagers)
                .withEntityCensus((level, heal) -> entityCensus(server, level, heal));

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
                .then(Commands.literal("tickstats")
                        .executes(ctx -> run(dispatcher, ctx, "tickstats"))
                        .then(Commands.literal("reset")
                                .requires(MultiForgeCommandBinder::isAdmin)
                                .executes(ctx -> run(dispatcher, ctx, "tickstats", "reset"))))
                .then(chunkCostSubtree(dispatcher))
                .then(entitiesSubtree(dispatcher))
                .then(certifySubtree(dispatcher));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> configSubtree(MultiForgeCommandDispatcher dispatcher) {
        return Commands.literal("config")
                .then(Commands.literal("show").executes(ctx -> run(dispatcher, ctx, "config", "show")))
                .then(Commands.literal("reload")
                        .requires(MultiForgeCommandBinder::isAdmin)
                        .executes(ctx -> runTopology(dispatcher, ctx, "config", "reload")))
                .then(Commands.literal("cores")
                        .requires(MultiForgeCommandBinder::isAdmin)
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 4096))
                                .executes(ctx -> runTopology(dispatcher, ctx, "config", "cores", intArg(ctx, "n")))))
                .then(Commands.literal("threads")
                        .requires(MultiForgeCommandBinder::isAdmin)
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> runTopology(dispatcher, ctx, "config", "threads", intArg(ctx, "n")))))
                .then(Commands.literal("mode")
                        .requires(MultiForgeCommandBinder::isAdmin)
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(Stream.of("hybrid", "strict", "off"), b))
                                .executes(ctx -> run(dispatcher, ctx, "config", "mode", strArg(ctx, "mode")))))
                .then(Commands.literal("policy")
                        .requires(MultiForgeCommandBinder::isAdmin)
                        .then(Commands.argument("policy", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(Stream.of("warn", "reroute-only", "fail"), b))
                                .executes(ctx -> run(dispatcher, ctx, "config", "policy", strArg(ctx, "policy")))))
                .then(Commands.literal("warnPerMin")
                        .requires(MultiForgeCommandBinder::isAdmin)
                        .then(Commands.argument("n", IntegerArgumentType.integer(0, 100000))
                                .executes(ctx -> run(dispatcher, ctx, "config", "warnPerMin", intArg(ctx, "n")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> regionSubtree(MultiForgeCommandDispatcher dispatcher) {
        return Commands.literal("region")
                .then(Commands.literal("list").executes(ctx -> run(dispatcher, ctx, "region", "list")))
                .then(Commands.literal("size")
                        .executes(ctx -> run(dispatcher, ctx, "region", "size"))
                        .then(Commands.argument(
                                "chunks",
                                IntegerArgumentType.integer(
                                        net.multiforge.runtime.config.MultiForgeConfig.MIN_REGION_SIZE_CHUNKS, 256))
                                .requires(MultiForgeCommandBinder::isAdmin)
                                .executes(ctx -> runTopology(dispatcher, ctx, "region", "size", intArg(ctx, "chunks")))))
                .then(pinSubtree(dispatcher).requires(MultiForgeCommandBinder::isAdmin))
                .then(Commands.literal("unpin")
                        .requires(MultiForgeCommandBinder::isAdmin)
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> runTopology(dispatcher, ctx, "region", "unpin", strArg(ctx, "id")))));
    }

    /**
     * `/multiforge region pin <id> <world> <fromCX> <fromCZ> <toCX> <toCZ>` —
     * broken into a helper so the deeply-nested Brigadier tree doesn't
     * exceed Google-Java-Format's parser depth.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> chunkCostSubtree(MultiForgeCommandDispatcher dispatcher) {
        var r = Commands.argument("r", IntegerArgumentType.integer(0, 1024))
                .executes(ctx -> run(
                        dispatcher,
                        ctx,
                        "chunkcost",
                        "report",
                        worldArg(ctx),
                        intArg(ctx, "cx"),
                        intArg(ctx, "cz"),
                        intArg(ctx, "r")));
        var cz = Commands.argument("cz", IntegerArgumentType.integer()).then(r);
        var cx = Commands.argument("cx", IntegerArgumentType.integer()).then(cz);
        var world = Commands.argument("world", ResourceLocationArgument.id())
                .suggests((ctx, b) -> SharedSuggestionProvider.suggestResource(
                        ctx.getSource().levels().stream().map(level -> level.location()), b))
                .then(cx);
        return Commands.literal("chunkcost")
                .then(Commands.literal("on")
                        .requires(MultiForgeCommandBinder::isAdmin)
                        .executes(ctx -> run(dispatcher, ctx, "chunkcost", "on")))
                .then(Commands.literal("off")
                        .requires(MultiForgeCommandBinder::isAdmin)
                        .executes(ctx -> run(dispatcher, ctx, "chunkcost", "off")))
                .then(Commands.literal("report").then(world));
    }

    /**
     * {@code /multiforge entities [level] [top N | region <id> | audit [heal]]}:
     * the views hang both off {@code entities} (every level) and off its {@code
     * level} argument.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> entitiesSubtree(MultiForgeCommandDispatcher dispatcher) {
        var level = Commands.argument("level", ResourceLocationArgument.id())
                .suggests((ctx, b) -> SharedSuggestionProvider.suggestResource(
                        ctx.getSource().levels().stream().map(l -> l.location()), b))
                .executes(ctx -> run(dispatcher, ctx, "entities", levelArg(ctx)));
        addEntityViews(dispatcher, level, true);
        var root = Commands.literal("entities").executes(ctx -> run(dispatcher, ctx, "entities"));
        addEntityViews(dispatcher, root, false);
        return root.then(level);
    }

    private static void addEntityViews(
            MultiForgeCommandDispatcher dispatcher,
            com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> parent,
            boolean withLevel) {
        parent.then(Commands.literal("top")
                .executes(ctx -> run(dispatcher, ctx, entityArgs(ctx, withLevel, "top")))
                .then(Commands.argument("n", IntegerArgumentType.integer(1, 1000))
                        .executes(ctx -> run(dispatcher, ctx, entityArgs(ctx, withLevel, "top", intArg(ctx, "n"))))))
                .then(Commands.literal("region")
                        .then(Commands.argument("id", com.mojang.brigadier.arguments.LongArgumentType.longArg(-1))
                                .executes(ctx -> run(
                                        dispatcher,
                                        ctx,
                                        entityArgs(
                                                ctx,
                                                withLevel,
                                                "region",
                                                String.valueOf(com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "id")))))))
                .then(Commands.literal("audit")
                        .executes(ctx -> run(dispatcher, ctx, entityArgs(ctx, withLevel, "audit")))
                        .then(Commands.literal("heal")
                                .requires(MultiForgeCommandBinder::isAdmin)
                                .executes(ctx -> run(dispatcher, ctx, entityArgs(ctx, withLevel, "audit", "heal")))));
    }

    private static String[] entityArgs(CommandContext<CommandSourceStack> ctx, boolean withLevel, String... view) {
        List<String> args = new ArrayList<>();
        args.add("entities");
        if (withLevel) args.add(levelArg(ctx));
        args.addAll(List.of(view));
        return args.toArray(String[]::new);
    }

    private static String levelArg(CommandContext<CommandSourceStack> ctx) {
        return ResourceLocationArgument.getId(ctx, "level").toString();
    }

    /**
     * {@code /multiforge entities}: each matching level's census (see {@link
     * EntityAudit}), taken here on the server thread; a level matches by its
     * full id or its path ({@code overworld}).
     */
    private static List<EntityCensus> entityCensus(MinecraftServer server, String level, boolean heal) {
        if (!server.isSameThread() || RegionPhase.workersInFlight()) {
            throw new IllegalStateException("The entity census runs on the server thread between ticks; try again.");
        }
        List<EntityCensus> out = new ArrayList<>();
        for (ServerLevel l : server.getAllLevels()) {
            ResourceLocation id = l.dimension().location();
            if (level == null || id.toString().equals(level) || id.getPath().equals(level)) {
                out.add(EntityAudit.take(l, heal));
            }
        }
        return out;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> pinSubtree(MultiForgeCommandDispatcher dispatcher) {
        // Build inside-out so we avoid Java 21 parser limits and keep it readable.
        var toCZ = Commands.argument("toCZ", IntegerArgumentType.integer())
                .executes(ctx -> runTopology(
                        dispatcher,
                        ctx,
                        "region",
                        "pin",
                        strArg(ctx, "id"),
                        worldArg(ctx),
                        intArg(ctx, "fromCX"),
                        intArg(ctx, "fromCZ"),
                        intArg(ctx, "toCX"),
                        intArg(ctx, "toCZ")));
        var toCX = Commands.argument("toCX", IntegerArgumentType.integer()).then(toCZ);
        var fromCZ = Commands.argument("fromCZ", IntegerArgumentType.integer()).then(toCX);
        var fromCX = Commands.argument("fromCX", IntegerArgumentType.integer()).then(fromCZ);
        var world = Commands.argument("world", ResourceLocationArgument.id())
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
                                Stream.of("top event.dispatch.serial", "event.dispatch", "serial-lane", "region-tick", "ownership"), b))
                        .executes(ctx -> run(dispatcher, ctx, "probes", strArg(ctx, "prefix"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> chunksSubtree(MultiForgeCommandDispatcher dispatcher) {
        return Commands.literal("chunks")
                .then(Commands.argument("world", ResourceLocationArgument.id())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggestResource(
                                ctx.getSource().levels().stream().map(level -> level.location()), b))
                        .executes(ctx -> run(dispatcher, ctx, "chunks", worldArg(ctx))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> warnSubtree(MultiForgeCommandDispatcher dispatcher) {
        return Commands.literal("warn")
                .then(Commands.literal("list").executes(ctx -> run(dispatcher, ctx, "warn", "list")))
                .then(Commands.literal("clear")
                        .requires(MultiForgeCommandBinder::isAdmin)
                        .executes(ctx -> run(dispatcher, ctx, "warn", "clear")));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> certifySubtree(MultiForgeCommandDispatcher dispatcher) {
        return Commands.literal("certify")
                .requires(MultiForgeCommandBinder::isAdmin)
                .then(Commands.literal("all").executes(ctx -> runOffThread(dispatcher, ctx, "certify", "all")))
                .then(Commands.argument("modId", StringArgumentType.word())
                        .executes(ctx -> runOffThread(dispatcher, ctx, "certify", strArg(ctx, "modId"))));
    }

    /**
     * Permission level for subcommands that change state (config, region topology,
     * heals, certify, clearing counters). Level 2 — what command blocks and datapack
     * functions run at — may only read; changing a running server's threads or
     * regions is a server-management action, Vanilla's level 3.
     */
    static final int ADMIN_PERMISSION = 3;

    private static boolean isAdmin(CommandSourceStack src) {
        return src.hasPermission(ADMIN_PERMISSION);
    }

    /**
     * Run a subcommand that changes region topology or the worker pool. Refused
     * from inside a region's tick (a command block or a mod running a command on a
     * region the server thread ticks itself): it would split, merge or rebuild the
     * very region that is ticking.
     */
    private static int runTopology(
            MultiForgeCommandDispatcher dispatcher, CommandContext<CommandSourceStack> ctx, String... args) {
        if (net.multiforge.runtime.ownership.OwnerToken.current().domain() == net.multiforge.runtime.ownership.Domain.REGION) {
            ctx.getSource()
                    .sendFailure(Component.literal(
                            "/multiforge " + String.join(" ", args)
                                    + " cannot run from inside a region's tick (command block or mod code);"
                                    + " run it from the console or chat."));
            return 0;
        }
        return run(dispatcher, ctx, args);
    }

    private static int run(MultiForgeCommandDispatcher dispatcher, CommandContext<CommandSourceStack> ctx, String... args) {
        dispatcher.dispatch(args, msg -> ctx.getSource().sendSuccess(() -> Component.literal(msg), false));
        return 1;
    }

    /**
     * For slow subcommands ({@code certify} runs the scanner as a child
     * process, seconds per jar): dispatch on a background thread so the
     * server keeps ticking, and deliver each output line on the server thread.
     */
    private static int runOffThread(
            MultiForgeCommandDispatcher dispatcher, CommandContext<CommandSourceStack> ctx, String... args) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        // One background run at a time: each certify starts a scanner JVM per mod jar.
        if (!BACKGROUND_RUNNING.compareAndSet(false, true)) {
            source.sendFailure(Component.literal("/multiforge " + args[0] + " is already running; wait for it to finish."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Running in the background..."), false);
        Thread worker = new Thread(
                () -> {
                    try {
                        dispatcher.dispatch(
                                args, msg -> server.execute(() -> source.sendSuccess(() -> Component.literal(msg), false)));
                    } finally {
                        BACKGROUND_RUNNING.set(false);
                    }
                },
                "multiforge-" + args[0]);
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean BACKGROUND_RUNNING = new java.util.concurrent.atomic.AtomicBoolean();

    private static String strArg(CommandContext<CommandSourceStack> ctx, String name) {
        return StringArgumentType.getString(ctx, name);
    }

    /** The {@code world} argument: a dimension id such as {@code minecraft:overworld}. */
    private static String worldArg(CommandContext<CommandSourceStack> ctx) {
        return ResourceLocationArgument.getId(ctx, "world").toString();
    }

    private static String intArg(CommandContext<CommandSourceStack> ctx, String name) {
        return String.valueOf(IntegerArgumentType.getInteger(ctx, name));
    }
}
