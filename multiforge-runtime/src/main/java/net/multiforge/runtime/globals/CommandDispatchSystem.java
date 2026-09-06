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
package net.multiforge.runtime.globals;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.ChunkPos;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.entity.EntityMigrationCoordinator;
import net.multiforge.runtime.entity.MigratingEntityRef;

/**
 * B2.8 — migrates {@code Commands.performPrefixedCommand} and {@code
 * ServerFunctionManager.execute} off the Vanilla-inline "run wherever the
 * caller happened to be" model and onto region-scoped or global-region
 * dispatch (docs/design/global-region.md §6.3, plan Track B2.8).
 *
 * <h2>What migrates and what doesn't</h2>
 *
 * <p>Only the <em>dispatch entry point</em> migrates — exactly like {@link
 * WeatherSystem}/{@link RaidsSystem}/{@link DragonFightSystem} before it,
 * this class holds no Brigadier or {@code CommandSourceStack} reference and
 * parses no command grammar of its own. Fork glue (the {@code
 * 08-globals/Commands.java.patch} / {@code
 * 08-globals/ServerFunctionManager.java.patch} hunks, plus their supporting
 * bridge classes) converts one Vanilla command/function invocation into a
 * {@link CommandRequest} — an MC-free description of where the command
 * originated and what it might touch — and a {@code Runnable} that runs the
 * original, unmodified Vanilla dispatch body (extracted verbatim by the
 * patch, same {@code mfXxxBody()} convention every other {@code 08-globals}
 * patch uses). This class decides <em>which thread</em> that body runs on,
 * never <em>what</em> it does.
 *
 * <h2>Scope decision — the three destinations</h2>
 *
 * <ul>
 *   <li>{@link CommandScope#SINGLE_REGION} — the command only ever touches
 *       the caller's own chunk (the common case: {@code /setblock}, {@code
 *       /give}, a same-region {@code /tp}). Routed directly onto the
 *       caller's chunk via {@link ChunkTaskRouter#queueChunkTask}, which
 *       resolves the owning region at drain time — no different from any
 *       other cross-region enqueue in this codebase.
 *   <li>{@link CommandScope#MULTI_REGION} — the command may touch more than
 *       one world/region ({@code /execute at @a run ...}, {@code /kill
 *       @e[type=...,dx=100,dy=100,dz=100]}, a datapack function whose
 *       contents cannot be cheaply proven single-region). Escalated onto the
 *       synthetic global region's own inbox (chunk {@code (0, 0)} of the
 *       {@code multiforge:global} world, the same target {@link
 *       net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost
 *       GlobalDomain} tasks already use, docs/design/global-region.md §1.4).
 *       Any per-region side effect the body performs from there is expected
 *       to go through {@link #crossRegionEffect} — that discipline is fork
 *       glue's responsibility inside {@code body}, this class only picks the
 *       thread.
 *   <li>{@link CommandScope#CROSS_REGION_TELEPORT} — a coordinate-form
 *       {@code /tp}/{@code /teleport} whose source entity and destination
 *       are both cheaply resolvable ahead of dispatch (fork glue populates
 *       {@link CommandRequest#teleportTarget()}), <em>and</em> the
 *       destination chunk differs from the caller's own. Escalated to the
 *       global region exactly like {@link CommandScope#MULTI_REGION}, but
 *       before {@code body} runs this class calls {@link
 *       EntityMigrationCoordinator#beginMigration} directly — the same A3
 *       networking-aware hop {@code Entity.teleportTo} itself would
 *       eventually trigger via {@code EntityMigrationBridge.onTeleport}, just
 *       invoked explicitly rather than relying on the command body to
 *       re-derive it. {@code body} in this scope is expected to be
 *       feedback-only (success message, advancement trigger) — fork glue
 *       must not hand this scope a {@code body} that itself re-runs a full
 *       Vanilla teleport, or the entity would be migrated twice.
 * </ul>
 *
 * <p>Selector-based scope analysis (the "based on {@code
 * ParseResults.getContext().getSelectors()} if reachable" heuristic the
 * landing plan describes) is <b>not reachable</b> against the vendored
 * Brigadier version this fork builds against (confirmed:
 * {@code com.mojang:brigadier:1.3.10}'s {@code CommandContextBuilder} has no
 * {@code getSelectors()} accessor) — fork glue's analyzer degrades to the
 * "safe default" branch the plan explicitly allows, and {@link
 * #defaultAnalyze} classifies purely from the MC-free {@link
 * CommandRequest} fields fork glue can populate without it: an empty or
 * caller-only {@code touchedWorlds} set is {@link CommandScope#SINGLE_REGION};
 * anything else is {@link CommandScope#MULTI_REGION} — "all commands are
 * global" for anything the cheap heuristic cannot positively prove is
 * confined to one chunk. The analyzer is pluggable via {@link
 * #setScopeAnalyzer} so a future patch can wire a richer classifier (or a
 * fork-side heuristic keyed off the raw command's first token, e.g. a static
 * allowlist of known single-target command literals) without an interface
 * break.
 *
 * <h2>Failure isolation</h2>
 *
 * <p>Every public entry point here is defensive on top of {@link
 * GlobalSystems#tickAll}'s own per-subsystem catch (docs/design/global-region.md
 * §7): a throwing scope analyzer, a throwing routing call, or a throwing
 * command/function body is caught, probed, and warned — never propagated to
 * the patched Vanilla call site, and never allowed to prevent a
 * later-dispatched command from running (CLAUDE.md rule 5). Command result
 * semantics are unaffected by this isolation: as of this NeoForge/Vanilla
 * version, both migrated entry points ({@code Commands.performPrefixedCommand}
 * and {@code ServerFunctionManager.execute}) already return {@code void} —
 * results are delivered via {@code CommandSourceStack.sendSuccess}/{@code
 * sendFailure} inside the body itself (Vanilla's 1.20.5+ deferred {@code
 * ExecutionContext} execution model), which this class preserves unchanged
 * since it never rewrites {@code body}, only relocates which thread runs it.
 */
public final class CommandDispatchSystem extends AbstractGlobalSystem {

    /** Where a single command/function dispatch is routed to run. */
    public enum CommandScope {
        SINGLE_REGION,
        MULTI_REGION,
        CROSS_REGION_TELEPORT
    }

    /**
     * The same shape {@link net.multiforge.runtime.region.RegionizedTaskQueue#queueChunkTask(WorldRef,
     * int, int, Runnable)} already exposes — kept as its own functional
     * interface (rather than a direct dependency on {@code
     * net.multiforge.runtime.region.RegionizedTaskQueue}) so this class only
     * declares the one method it actually calls, matching {@link
     * CrossRegionEffects}'s own minimal-surface convention.
     */
    @FunctionalInterface
    public interface ChunkTaskRouter {
        void queueChunkTask(WorldRef world, int chunkX, int chunkZ, Runnable task);
    }

    /**
     * Present only when fork glue has cheaply and confidently resolved a
     * coordinate-form teleport's source entity and destination ahead of
     * dispatch (see this class's javadoc). {@code null} in every other
     * {@link CommandRequest} — including entity-to-entity teleport forms
     * fork glue chooses not to pre-resolve, which fall through to the
     * ordinary {@link CommandScope#SINGLE_REGION}/{@link
     * CommandScope#MULTI_REGION} classification and rely on {@code
     * Entity.teleportTo}'s own A2 migration hook instead.
     */
    public record TeleportTarget(MigratingEntityRef entity, WorldRef destWorld, BlockPos destPos) {
        public TeleportTarget {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(destWorld, "destWorld");
            Objects.requireNonNull(destPos, "destPos");
        }
    }

    /**
     * MC-free description of one command/function dispatch, built by fork
     * glue from a {@code CommandSourceStack} + the raw command/function
     * text. Never carries a Brigadier or Minecraft type.
     *
     * @param callerWorld world the command/function was invoked from.
     * @param callerChunkX chunk-x of the invoking {@code
     *     CommandSourceStack}'s position.
     * @param callerChunkZ chunk-z of the invoking {@code
     *     CommandSourceStack}'s position.
     * @param commandLine the raw command/function text — probe/log
     *     identification only, never re-parsed by this class.
     * @param isFunctionCall {@code true} for a {@code
     *     ServerFunctionManager.execute} dispatch, {@code false} for a
     *     {@code Commands.performPrefixedCommand} dispatch — carried through
     *     only for probe/log bucketing (see {@code
     *     global.system.command_dispatch.function-*} vs. {@code
     *     .command-*} probe keys).
     * @param touchedWorlds every world fork glue believes this dispatch may
     *     touch, beyond {@code callerWorld} itself. Advisory and
     *     best-effort — see this class's javadoc on why a richer,
     *     selector-aware computation isn't available against the vendored
     *     Brigadier version. An empty set means "nothing beyond the caller's
     *     own position, as far as the cheap heuristic can tell."
     * @param teleportTarget see {@link TeleportTarget}; {@code null} unless
     *     fork glue pre-resolved a coordinate-form teleport.
     */
    public record CommandRequest(
            WorldRef callerWorld,
            int callerChunkX,
            int callerChunkZ,
            String commandLine,
            boolean isFunctionCall,
            Set<WorldRef> touchedWorlds,
            TeleportTarget teleportTarget) {
        public CommandRequest {
            Objects.requireNonNull(callerWorld, "callerWorld");
            Objects.requireNonNull(commandLine, "commandLine");
            touchedWorlds = touchedWorlds == null ? Set.of() : Set.copyOf(touchedWorlds);
        }

        /** Convenience factory for the common case: a plain command with no known cross-world reach. */
        public static CommandRequest ofCommand(WorldRef callerWorld, int chunkX, int chunkZ, String commandLine) {
            return new CommandRequest(callerWorld, chunkX, chunkZ, commandLine, false, Set.of(), null);
        }

        /** Convenience factory for a function dispatch with no known cross-world reach. */
        public static CommandRequest ofFunction(WorldRef callerWorld, int chunkX, int chunkZ, String functionId) {
            return new CommandRequest(callerWorld, chunkX, chunkZ, functionId, true, Set.of(), null);
        }

        /** Convenience factory carrying an explicit set of worlds the command may touch. */
        public static CommandRequest multiWorld(
                WorldRef callerWorld, int chunkX, int chunkZ, String commandLine, Set<WorldRef> touchedWorlds) {
            return new CommandRequest(callerWorld, chunkX, chunkZ, commandLine, false, touchedWorlds, null);
        }

        /** Convenience factory for a pre-resolved coordinate-form teleport. */
        public static CommandRequest teleport(
                WorldRef callerWorld, int chunkX, int chunkZ, String commandLine, TeleportTarget target) {
            return new CommandRequest(callerWorld, chunkX, chunkZ, commandLine, false, Set.of(), target);
        }
    }

    /**
     * Pluggable scope-classification strategy. See this class's javadoc for
     * why the default implementation ({@link #defaultAnalyze}) cannot
     * consult Brigadier selector information directly.
     */
    @FunctionalInterface
    public interface ScopeAnalyzer {
        CommandScope analyze(CommandRequest request);
    }

    private final ChunkTaskRouter router;
    private final WorldRef globalWorld;

    /**
     * May be {@code null} — a host that hasn't wired entity migration yet
     * (or a test) simply degrades every {@link CommandScope#CROSS_REGION_TELEPORT}
     * dispatch to {@link CommandScope#MULTI_REGION} (see {@link
     * #routeCrossRegionTeleport}), never throwing.
     */
    private final EntityMigrationCoordinator migrationCoordinator;

    private volatile ScopeAnalyzer analyzer = CommandDispatchSystem::defaultAnalyze;

    private final AtomicLong dispatchCount = new AtomicLong();
    private final AtomicLong teleportCount = new AtomicLong();

    public CommandDispatchSystem(CrossRegionEffects effects, ChunkTaskRouter router, WorldRef globalWorld) {
        this(effects, router, globalWorld, null);
    }

    public CommandDispatchSystem(
            CrossRegionEffects effects,
            ChunkTaskRouter router,
            WorldRef globalWorld,
            EntityMigrationCoordinator migrationCoordinator) {
        super(effects);
        this.router = Objects.requireNonNull(router, "router");
        this.globalWorld = Objects.requireNonNull(globalWorld, "globalWorld");
        this.migrationCoordinator = migrationCoordinator;
    }

    /**
     * Replaces the scope-classification strategy. {@code null} restores
     * {@link #defaultAnalyze}. Exposed so fork glue (or a test) can install
     * a richer or a deliberately-simplified analyzer without subclassing.
     */
    public void setScopeAnalyzer(ScopeAnalyzer newAnalyzer) {
        this.analyzer = newAnalyzer == null ? CommandDispatchSystem::defaultAnalyze : newAnalyzer;
    }

    @Override
    public String name() {
        return "command_dispatch";
    }

    /**
     * No periodic work — unlike every other B2.x subsystem, command
     * dispatch is driven entirely by {@link #dispatch}, called on demand
     * from the patched {@code Commands.performPrefixedCommand}/{@code
     * ServerFunctionManager.execute} call sites, not from a per-tick poll.
     * Registered as a {@link GlobalSystem} regardless (rather than skipping
     * registration entirely) so it appears in the M6 debug HUD's subsystem
     * list and shares {@link GlobalSystems#tickAll}'s isolation contract
     * like every other {@code command_dispatch}-named collaborator.
     */
    @Override
    public void tick(GlobalTickContext ctx) {
        // Intentionally empty — see javadoc above.
    }

    /**
     * Dispatch one command or function invocation. {@code body} is the
     * original, unmodified Vanilla dispatch body (the {@code
     * mfPerformPrefixedCommandBody}/{@code mfExecuteBody} the corresponding
     * patch extracted) — this method decides which thread it runs on and
     * never invokes it inline on the calling thread itself, so the caller
     * (the patched Vanilla method) always returns immediately regardless of
     * scope.
     *
     * <p>Never throws — a failure at any stage (scope analysis, routing, or
     * the body itself once it runs) is caught, probed, and warned per this
     * class's javadoc.
     */
    public void dispatch(CommandRequest request, Runnable body) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(body, "body");
        dispatchCount.incrementAndGet();

        CommandScope scope;
        try {
            scope = analyzer.analyze(request);
        } catch (Throwable t) {
            ProbeRegistry.bump("global.system.command_dispatch.analyze-failure");
            ViolationLogger.warn(
                    "global.system.command_dispatch",
                    "scope analyzer threw for '" + request.commandLine() + "': " + describe(t)
                            + " — falling back to MULTI_REGION");
            scope = CommandScope.MULTI_REGION;
        }

        switch (scope) {
            case SINGLE_REGION -> routeSingleRegion(request, body);
            case CROSS_REGION_TELEPORT -> routeCrossRegionTeleport(request, body);
            case MULTI_REGION -> routeMultiRegion(request, body);
        }
    }

    private void routeSingleRegion(CommandRequest request, Runnable body) {
        try {
            router.queueChunkTask(
                    request.callerWorld(), request.callerChunkX(), request.callerChunkZ(), wrap(request, body));
            ProbeRegistry.bump(bucket(request, "single-region-routed"));
        } catch (Throwable t) {
            handleRoutingFailure(request, t);
        }
    }

    private void routeMultiRegion(CommandRequest request, Runnable body) {
        try {
            router.queueChunkTask(globalWorld, 0, 0, wrap(request, body));
            ProbeRegistry.bump(bucket(request, "multi-region-escalated"));
        } catch (Throwable t) {
            handleRoutingFailure(request, t);
        }
    }

    /**
     * Escalates to the global region exactly like {@link #routeMultiRegion},
     * but the enqueued task first performs the entity migration hop
     * directly via {@link EntityMigrationCoordinator#beginMigration} — the
     * A3 networking-aware cross-region hop — before running {@code body}
     * (expected to be feedback-only for this scope; see this class's
     * javadoc). Degrades to {@link #routeMultiRegion} (never throws, never
     * skips the command) if either {@link CommandRequest#teleportTarget()}
     * or the injected {@link EntityMigrationCoordinator} is unavailable —
     * the command still runs, just without the direct migration
     * short-circuit, relying on {@code Entity.teleportTo}'s own hook
     * instead (CLAUDE.md rule 5: auto-reroute, never refuse).
     */
    private void routeCrossRegionTeleport(CommandRequest request, Runnable body) {
        TeleportTarget target = request.teleportTarget();
        if (target == null || migrationCoordinator == null) {
            ProbeRegistry.bump(bucket(request, "teleport-degraded-to-multi-region"));
            routeMultiRegion(request, body);
            return;
        }
        try {
            router.queueChunkTask(globalWorld, 0, 0, () -> {
                teleportCount.incrementAndGet();
                boolean started;
                try {
                    started =
                            migrationCoordinator.beginMigration(target.entity(), target.destWorld(), target.destPos());
                } catch (Throwable t) {
                    ProbeRegistry.bump(bucket(request, "teleport-migration-failure"));
                    ViolationLogger.warn(
                            "global.system.command_dispatch",
                            "beginMigration threw for '" + request.commandLine() + "': " + describe(t));
                    started = false;
                }
                ProbeRegistry.bump(bucket(request, started ? "teleport-routed" : "teleport-declined"));
                runBodySafely(request, body);
            });
        } catch (Throwable t) {
            handleRoutingFailure(request, t);
        }
    }

    private Runnable wrap(CommandRequest request, Runnable body) {
        return () -> runBodySafely(request, body);
    }

    private void runBodySafely(CommandRequest request, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            // Defensive: one bad command/function must never break other regions or other
            // pending commands (CLAUDE.md rule 5) — isolated per-dispatch on top of whatever
            // generic mailbox-drain catch the destination region's inbox already has.
            ProbeRegistry.bump(bucket(request, "command-failure"));
            ViolationLogger.warn(
                    "global.system.command_dispatch", "'" + request.commandLine() + "' threw: " + describe(t));
        }
    }

    private void handleRoutingFailure(CommandRequest request, Throwable t) {
        ProbeRegistry.bump(bucket(request, "routing-failure"));
        ViolationLogger.warn(
                "global.system.command_dispatch", "failed to route '" + request.commandLine() + "': " + describe(t));
    }

    private static String bucket(CommandRequest request, String suffix) {
        return "global.system.command_dispatch." + (request.isFunctionCall() ? "function-" : "command-") + suffix;
    }

    private static String describe(Throwable t) {
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    /** Total {@link #dispatch} calls observed so far — test/diagnostics accessor. */
    public long dispatchCount() {
        return dispatchCount.get();
    }

    /** Total direct {@link EntityMigrationCoordinator#beginMigration} attempts made so far. */
    public long teleportCount() {
        return teleportCount.get();
    }

    /**
     * The pluggable default heuristic (see this class's javadoc). Pure-Java,
     * operates only on {@link CommandRequest}'s precomputed fields:
     *
     * <ol>
     *   <li>A resolved {@link TeleportTarget} whose destination world
     *       differs from the caller's, or whose destination chunk differs
     *       from the caller's chunk in the same world, classifies as {@link
     *       CommandScope#CROSS_REGION_TELEPORT}.
     *   <li>A resolved {@link TeleportTarget} landing in the caller's own
     *       chunk classifies as {@link CommandScope#SINGLE_REGION} — no
     *       migration needed, ordinary same-region dispatch suffices.
     *   <li>An empty {@link CommandRequest#touchedWorlds()} — or one whose
     *       only entry is the caller's own world — classifies as {@link
     *       CommandScope#SINGLE_REGION}.
     *   <li>Anything else (multiple worlds, or a world other than the
     *       caller's) classifies as {@link CommandScope#MULTI_REGION} — the
     *       "all commands are global" safe default the landing plan
     *       explicitly sanctions when a more precise classification isn't
     *       available.
     * </ol>
     */
    static CommandScope defaultAnalyze(CommandRequest request) {
        TeleportTarget target = request.teleportTarget();
        if (target != null) {
            if (!target.destWorld().dimensionId().equals(request.callerWorld().dimensionId())) {
                return CommandScope.CROSS_REGION_TELEPORT;
            }
            ChunkPos destChunk = target.destPos().toChunkPos();
            if (destChunk.x() != request.callerChunkX() || destChunk.z() != request.callerChunkZ()) {
                return CommandScope.CROSS_REGION_TELEPORT;
            }
            return CommandScope.SINGLE_REGION;
        }

        Set<WorldRef> touched = request.touchedWorlds();
        if (touched.isEmpty()) {
            return CommandScope.SINGLE_REGION;
        }
        if (touched.size() == 1 && touched.contains(request.callerWorld())) {
            return CommandScope.SINGLE_REGION;
        }
        return CommandScope.MULTI_REGION;
    }
}
