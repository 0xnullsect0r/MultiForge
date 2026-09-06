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
package net.multiforge.neoforge.globals;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.server.ServerFunctionManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.neoforge.RegionizedTickCoordinator;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.entity.EntityRegistry;
import net.multiforge.runtime.entity.MigratingEntityRef;
import net.multiforge.runtime.globals.BossEventSystem;
import net.multiforge.runtime.globals.CommandDispatchSystem;
import net.multiforge.runtime.globals.CommandDispatchSystem.CommandRequest;
import net.multiforge.runtime.globals.CommandDispatchSystem.TeleportTarget;
import net.multiforge.runtime.globals.DragonFightSystem;
import net.multiforge.runtime.globals.RaidsSystem;
import net.multiforge.runtime.globals.ScoreboardSystem;
import net.multiforge.runtime.globals.TimeSystem;
import net.multiforge.runtime.globals.WeatherSystem;
import net.multiforge.runtime.globals.WorldBorderSystem;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;

/**
 * Fork-local façade the {@code multiforge-patches/08-globals/} Vanilla
 * patches call into — mirrors the {@link RegionizedTickCoordinator} /
 * {@code OwnershipGuard} facade shape (patched vanilla code references a
 * stable fork-local API; the actual runtime behind it is swapped out
 * from behind without editing any patch file).
 *
 * <p>Bound once by {@link MultiForgeGlobalSystemsInit#install} on a
 * fresh runtime install. Before binding (or after a runtime shutdown),
 * every method here degrades to "not handled" / "not routed" so a
 * patched Vanilla call site always has a safe fallback — never a
 * {@code NullPointerException} (CLAUDE.md rule 5).
 */
public final class GlobalSystemsBridge {
    private static volatile WeatherSystem weather;
    private static volatile TimeSystem time;
    private static volatile WorldBorderSystem worldBorder;
    private static volatile ScoreboardSystem scoreboard;
    private static volatile BossEventSystem bossEvents;
    private static volatile DragonFightSystem dragonFight;
    private static volatile RaidsSystem raids;
    private static volatile CommandDispatchSystem commandDispatch;

    /**
     * Best-effort coordinate-form teleport matcher for the {@link
     * CommandDispatchSystem.CommandScope#CROSS_REGION_TELEPORT} fast path
     * (see {@link CommandDispatchSystem}'s javadoc) — matches {@code tp}/
     * {@code teleport} invocations whose target is the command's own
     * source entity ({@code @s} or the source player's own literal name,
     * covering the common self-teleport case) and whose destination is
     * three literal coordinates, with an optional trailing rotation this
     * bridge does not need to parse. Anything else (entity-to-entity
     * teleports, relative {@code ~}/{@code ^} coordinates, {@code @p}/
     * {@code @a}/{@code @e} targets) is intentionally left unmatched —
     * {@link CommandDispatchSystem#defaultAnalyze} then falls through to
     * its ordinary {@code touchedWorlds}-based classification, and the
     * actual cross-region hop still happens correctly via {@code
     * Entity.teleportTo}'s own A2 migration hook once the command body
     * runs on whichever region worker {@link #dispatchCommand} routed it
     * to.
     */
    private static final Pattern SELF_TELEPORT_COORDS = Pattern.compile(
            "^(?:tp|teleport)\\s+(\\S+)\\s+"
                    + "(-?\\d+(?:\\.\\d+)?)\\s+(-?\\d+(?:\\.\\d+)?)\\s+(-?\\d+(?:\\.\\d+)?)"
                    + "(?:\\s+.*)?$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Commands whose Vanilla behavior is well-known to touch, at most, the
     * caller's own position — the "safe" half of the {@code touchedWorlds}
     * heuristic {@link #buildCommandRequest} falls back to when real
     * selector introspection isn't reachable (see {@link
     * CommandDispatchSystem}'s javadoc: {@code
     * CommandContextBuilder.getSelectors()} does not exist against the
     * vendored {@code com.mojang:brigadier:1.3.10}). Anything not in this
     * set — including every command this bridge cannot positively vouch
     * for — is classified {@code MULTI_REGION} by {@link
     * CommandDispatchSystem#defaultAnalyze}, per the landing plan's "all
     * commands are global" safe-default instruction.
     */
    private static final Set<String> SINGLE_REGION_SAFE_COMMANDS = Set.of(
            "setblock",
            "fill",
            "give",
            "clear",
            "item",
            "gamemode",
            "effect",
            "enchant",
            "xp",
            "experience",
            "title",
            "tellraw",
            "tell",
            "msg",
            "say",
            "playsound",
            "particle",
            "data",
            "attribute",
            "advancement",
            "recipe",
            "spawnpoint");

    /** Sentinel marking "the heuristic could not rule out additional worlds" — see {@link #buildCommandRequest}. */
    private static final WorldRef UNKNOWN_SCOPE = WorldRef.of("multiforge:unknown-scope");

    /**
     * Identity set of {@link WorldBorder} instances that currently have
     * a registered {@link WorldBorderSystem} target. {@code WorldBorder}
     * does not carry its owning {@link ServerLevel}/{@link WorldRef}
     * back-reference, so — unlike weather/time, which key off {@link
     * WorldRef} directly — the border-handled check needs its own
     * identity-keyed side table. {@code WorldBorder} does not override
     * {@code equals}/{@code hashCode}, so a plain {@link ConcurrentHashMap}-
     * backed set already gives identity semantics.
     */
    private static final Set<WorldBorder> HANDLED_BORDERS = ConcurrentHashMap.newKeySet();

    private GlobalSystemsBridge() {}

    /** Called once by {@link MultiForgeGlobalSystemsInit#install} on a fresh runtime install. */
    static void bind(WeatherSystem w, TimeSystem t, WorldBorderSystem b, ScoreboardSystem s, BossEventSystem be) {
        weather = w;
        time = t;
        worldBorder = b;
        scoreboard = s;
        bossEvents = be;
    }

    /**
     * Called once by {@code MultiForgeGlobalSystemsInit} (B2.7) when the
     * {@link DragonFightSystem} is registered — kept as a separate bind
     * call rather than widening {@link #bind} so B2low's already-landed
     * call site never needs to change.
     */
    static void bindDragonFight(DragonFightSystem d) {
        dragonFight = d;
    }

    /**
     * Called once by {@link MultiForgeGlobalSystemsInit} (B2.6) when the
     * {@link RaidsSystem} is registered on a fresh runtime install.
     * Separate from {@link #bind} (rather than an added parameter on
     * that method) so this addition doesn't collide with other B2.x
     * landings extending the same {@code bind} call independently.
     */
    static void bindRaids(RaidsSystem r) {
        raids = r;
    }

    /**
     * Called once by {@link MultiForgeGlobalSystemsInit} (B2.8) when the
     * {@link CommandDispatchSystem} is registered on a fresh runtime
     * install. Separate from {@link #bind} for the same reason {@link
     * #bindRaids}/{@link #bindDragonFight} are.
     */
    static void bindCommandDispatch(CommandDispatchSystem c) {
        commandDispatch = c;
    }

    /** Called by {@link MultiForgeGlobalSystemsInit} when a level's border target registers/deregisters. */
    static void markBorderHandled(WorldBorder border, boolean handled) {
        if (handled) {
            HANDLED_BORDERS.add(border);
        } else {
            HANDLED_BORDERS.remove(border);
        }
    }

    /**
     * Clears every binding and the border-handled side table. Called
     * from {@code ServerLifecycleHooks.handleServerStopped} so a
     * subsequent fresh install (next {@code GameTestServer} instance,
     * same JVM) starts from a known-clean state; also usable directly
     * from tests.
     */
    public static void unbind() {
        weather = null;
        time = null;
        worldBorder = null;
        scoreboard = null;
        bossEvents = null;
        raids = null;
        dragonFight = null;
        commandDispatch = null;
        HANDLED_BORDERS.clear();
    }

    /**
     * @return {@code true} if {@link net.multiforge.runtime.globals.WeatherSystem}
     *         is bound and has a registered target for {@code level}'s
     *         world — the {@code ServerLevel.advanceWeatherCycle} patch's
     *         no-op guard.
     */
    public static boolean weatherHandled(ServerLevel level) {
        WeatherSystem w = weather;
        return w != null && w.isHandling(worldRefOf(level));
    }

    /**
     * @return {@code true} if {@link net.multiforge.runtime.globals.TimeSystem}
     *         is bound and has a registered target for {@code level}'s
     *         world — consulted by both the {@code ServerLevel.tickTime}
     *         and {@code MinecraftServer.synchronizeTime} patches.
     */
    public static boolean timeHandled(ServerLevel level) {
        TimeSystem t = time;
        return t != null && t.isHandling(worldRefOf(level));
    }

    /**
     * @return {@code true} if {@code border} currently has a registered
     *         {@link WorldBorderSystem} target — the {@code
     *         WorldBorder.tick} patch's no-op guard.
     */
    public static boolean worldBorderHandled(WorldBorder border) {
        return worldBorder != null && HANDLED_BORDERS.contains(border);
    }

    /**
     * Attempts to route a {@code ServerScoreboard} mutation-hook body
     * through {@link ScoreboardSystem}.
     *
     * @return {@code true} if the mutation was accepted for routing (the
     *         patched call site must return without running {@code
     *         mutation} itself); {@code false} if not bound/ready yet —
     *         the patched call site must run {@code mutation} inline.
     */
    public static boolean routeScoreboardMutation(Runnable mutation) {
        ScoreboardSystem s = scoreboard;
        return s != null && s.tryRoute(mutation);
    }

    /**
     * Attempts to route a {@code CustomBossEvents} mutation-hook body
     * through {@link BossEventSystem}. Same contract as {@link
     * #routeScoreboardMutation}.
     */
    public static boolean routeBossEventMutation(Runnable mutation) {
        BossEventSystem be = bossEvents;
        return be != null && be.tryRoute(mutation);
    }

    /**
     * @return {@code true} iff MultiForge is installed <em>and</em>
     *         {@link RaidsSystem} has been registered — the {@code
     *         Raids.tick} patch's delegate guard
     *         (docs/design/global-region.md §4.2/§8.2 integration test
     *         6). Deliberately server-wide rather than per-{@code
     *         ServerLevel} (contrast {@link #weatherHandled}/{@link
     *         #timeHandled}) — {@code Raids} has no per-instance
     *         identity side table the way {@code WorldBorder} does, and
     *         a coarser check keeps the patch hunk itself a single
     *         no-arg call, per docs/design/global-region.md's "thin
     *         hunk" guidance for this patch.
     */
    public static boolean raidsReady() {
        return raids != null;
    }

    /** @return the bound {@link RaidsSystem}, or {@code null} if not yet installed. */
    public static RaidsSystem raids() {
        return raids;
    }

    /**
     * @return {@code true} iff MultiForge is installed <em>and</em>
     *         {@link DragonFightSystem} has been registered — the {@code
     *         EndDragonFight.tick()} patch's delegate guard
     *         (docs/design/global-region.md §6.3/§8.2 integration test
     *         3). Deliberately server-wide rather than per-{@code
     *         ServerLevel}, matching {@link #raidsReady()}'s rationale:
     *         {@code EndDragonFight} has no natural identity side table
     *         to key a per-instance check on without adding one just for
     *         this guard, and per-world granularity already lives in
     *         {@link DragonFightSystem#isHandling}, which the thin patch
     *         hunk itself has no need to call directly.
     */
    public static boolean dragonFightReady() {
        return dragonFight != null;
    }

    /** @return the bound {@link DragonFightSystem}, or {@code null} if not yet installed. */
    public static DragonFightSystem dragonFight() {
        return dragonFight;
    }

    /**
     * @return {@code true} iff MultiForge is installed <em>and</em>
     *         {@link CommandDispatchSystem} has been registered — the
     *         {@code Commands.performPrefixedCommand}/{@code
     *         ServerFunctionManager.execute} patches' delegate guard
     *         (docs/design/global-region.md §4.2, plan Track B2.8).
     */
    public static boolean commandDispatchReady() {
        return commandDispatch != null;
    }

    /** @return the bound {@link CommandDispatchSystem}, or {@code null} if not yet installed. */
    public static CommandDispatchSystem commandDispatch() {
        return commandDispatch;
    }

    /**
     * Called by the thin {@code Commands.java.patch} hunk in place of
     * running {@code commands.mfPerformPrefixedCommandBody} inline. Builds
     * an MC-free {@link CommandRequest} from {@code source}/{@code
     * rawCommand} and hands it to {@link CommandDispatchSystem#dispatch}
     * together with a closure that runs the original, unmodified Vanilla
     * body. Never throws — a failure anywhere in request construction
     * degrades to running {@code mfPerformPrefixedCommandBody} inline on
     * the calling thread, the same fallback the patch itself uses when
     * {@link #commandDispatchReady()} is {@code false} (CLAUDE.md rule 5).
     * Only called once {@link #commandDispatchReady()} is already known
     * {@code true} — see the patch hunk.
     */
    public static void dispatchCommand(Commands commands, CommandSourceStack source, String rawCommand) {
        CommandDispatchSystem sys = commandDispatch;
        Runnable body = () -> commands.mfPerformPrefixedCommandBody(source, rawCommand);
        if (sys == null) {
            body.run();
            return;
        }
        try {
            CommandRequest request = buildCommandRequest(source, rawCommand);
            sys.dispatch(request, body);
        } catch (Throwable t) {
            ViolationLogger.warn(
                    "global.system.command_dispatch",
                    "failed to build CommandRequest for '" + rawCommand + "' — running inline: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            body.run();
        }
    }

    /**
     * Called by the thin {@code ServerFunctionManager.java.patch} hunk in
     * place of running {@code manager.mfExecuteBody} inline. Same contract
     * as {@link #dispatchCommand}, for {@code /function} and
     * tick/load-tag function invocations.
     */
    public static void dispatchFunction(
            ServerFunctionManager manager, CommandFunction<CommandSourceStack> function, CommandSourceStack source) {
        CommandDispatchSystem sys = commandDispatch;
        Runnable body = () -> manager.mfExecuteBody(function, source);
        if (sys == null) {
            body.run();
            return;
        }
        try {
            WorldRef world = worldRefOf(source.getLevel());
            int[] chunk = chunkOf(source);
            CommandRequest request = CommandRequest.ofFunction(world, chunk[0], chunk[1], function.id().toString());
            sys.dispatch(request, body);
        } catch (Throwable t) {
            ViolationLogger.warn(
                    "global.system.command_dispatch",
                    "failed to build CommandRequest for function '" + function.id() + "' — running inline: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            body.run();
        }
    }

    /**
     * Builds a {@link CommandRequest} from one command dispatch. See
     * {@link CommandDispatchSystem}'s javadoc for why the {@code
     * touchedWorlds}/{@code teleportTarget} heuristics here are
     * deliberately conservative rather than a real Brigadier selector
     * walk.
     */
    private static CommandRequest buildCommandRequest(CommandSourceStack source, String rawCommand) {
        ServerLevel level = source.getLevel();
        WorldRef world = worldRefOf(level);
        int[] chunk = chunkOf(source);

        TeleportTarget teleportTarget = resolveSelfTeleportTarget(world, source, rawCommand);
        if (teleportTarget != null) {
            return CommandRequest.teleport(world, chunk[0], chunk[1], rawCommand, teleportTarget);
        }

        String firstToken = firstToken(rawCommand);
        if (SINGLE_REGION_SAFE_COMMANDS.contains(firstToken)) {
            return CommandRequest.ofCommand(world, chunk[0], chunk[1], rawCommand);
        }
        // Real selector introspection is not reachable against the vendored Brigadier version
        // (see CommandDispatchSystem's javadoc) — fall back to the safe default: anything not
        // positively vouched for above is treated as possibly touching more than one world.
        return CommandRequest.multiWorld(world, chunk[0], chunk[1], rawCommand, Set.of(world, UNKNOWN_SCOPE));
    }

    /**
     * Best-effort resolution of {@link #SELF_TELEPORT_COORDS} against
     * {@code rawCommand}. Returns {@code null} (never throws) whenever the
     * pattern doesn't match, the target token isn't resolvable, the
     * resolved entity isn't tracked by {@link
     * net.multiforge.runtime.entity.EntityRegistry} yet (e.g. a
     * bootstrap-window command before {@code onEntityRegistered} has run),
     * or the resolved entity is not {@code source}'s own — every one of
     * those is "can't fast-path this one," not an error; {@link
     * CommandDispatchSystem#defaultAnalyze} still classifies the request
     * correctly from {@code touchedWorlds} alone, and the actual hop still
     * happens correctly via {@code Entity.teleportTo}'s own A2 migration
     * hook once the (non-fast-pathed) command body runs.
     */
    private static TeleportTarget resolveSelfTeleportTarget(WorldRef callerWorld, CommandSourceStack source, String rawCommand) {
        Matcher m = SELF_TELEPORT_COORDS.matcher(rawCommand.trim());
        if (!m.matches()) return null;

        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return null;

        UUID uuid = resolveTargetUuid(source, m.group(1));
        if (uuid == null) return null;

        EntityRegistry registry = host.entityRegistry();
        MigratingEntityRef ref = registry.lookup(uuid);
        if (ref == null) return null;

        double x = Double.parseDouble(m.group(2));
        double y = Double.parseDouble(m.group(3));
        double z = Double.parseDouble(m.group(4));
        BlockPos destPos = new BlockPos(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        return new TeleportTarget(ref, callerWorld, destPos);
    }

    /**
     * Resolves the teleport target token to a UUID — {@code @s} (or {@code
     * @p} in the very common "self-executed by a player" case, i.e. the
     * source stack's own {@code getEntity()}) resolves to {@code
     * source.getEntity()}'s UUID directly; a literal UUID string resolves
     * to itself (the programmatic/command-block form); anything else
     * (a player name, {@code @a}/{@code @e}/{@code @r}) is intentionally
     * left unresolved here — those need either a live {@code
     * MinecraftServer} player lookup or genuine selector evaluation this
     * bridge does not reach for, and a missed fast-path degrades to the
     * always-correct {@code Entity.teleportTo} hook, never to incorrect
     * behavior.
     */
    private static UUID resolveTargetUuid(CommandSourceStack source, String literalToken) {
        if (literalToken.equals("@s") || literalToken.equals("@p")) {
            Entity entity = source.getEntity();
            return entity == null ? null : entity.getUUID();
        }
        try {
            return UUID.fromString(literalToken);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int[] chunkOf(CommandSourceStack source) {
        Vec3 pos = source.getPosition();
        return new int[] { Mth.floor(pos.x) >> 4, Mth.floor(pos.z) >> 4 };
    }

    private static String firstToken(String rawCommand) {
        String trimmed = rawCommand.trim();
        int sp = trimmed.indexOf(' ');
        String token = sp < 0 ? trimmed : trimmed.substring(0, sp);
        return token.toLowerCase(Locale.ROOT);
    }

    private static WorldRef worldRefOf(ServerLevel level) {
        return RegionizedTickCoordinator.asWorldRef(level);
    }
}
