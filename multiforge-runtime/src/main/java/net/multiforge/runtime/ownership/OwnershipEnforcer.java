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
package net.multiforge.runtime.ownership;

import java.util.Locale;
import java.util.Objects;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;

/**
 * Default-on behavioural guard for real {@code net.minecraft.*} mutation
 * sites patched by {@code multiforge-patches/01-ownership/}. Unlike {@link
 * DomainAssertions} (a passive, opt-in dev/CI probe that never changes
 * control flow), this class is the thing patched call sites actually call
 * to decide whether to run inline or hand off elsewhere — see CLAUDE.md
 * rule 5 ("auto-reroute + warn is the default").
 *
 * <p>Two checks exist:
 *
 * <ul>
 * <li>{@link #canMutate} — a thread-level check: is the caller a region
 *     worker, the global region, or the server tick thread at all? Used by
 *     sites that have no position (entity removal by identity).</li>
 * <li>{@link #canMutateAt} — the positional check: is the chunk being
 *     written owned by the region whose worker is calling? A region worker
 *     writing into a chunk another region owns is a cross-region mutation
 *     and is handed to the owner's mailbox via {@link #rerouteAt}, which
 *     runs it on the owner's worker during that region's next task drain.
 *     Mutations with no owning region (chunk not regionized) go to the
 *     server-thread {@link RerouteTarget}.</li>
 * </ul>
 *
 * <p>The positional lookups are supplied by the running host through
 * {@link #bindPositionRouter}; with none bound, {@link #canMutateAt}
 * degrades to {@link #canMutate}.
 */
public final class OwnershipEnforcer {

    /** Enforcement mode, selected via {@code -Dmultiforge.ownership.mode} (default {@code reroute}). */
    public enum Mode {
        /** Skip the check entirely; every call runs inline. Escape hatch for audited modpacks. */
        OFF,
        /** Default. Off-owner-thread mutations are warned about and handed to the reroute target. */
        REROUTE,
        /** Off-owner-thread mutations throw {@link OwnershipViolationException}. Regression-testing only. */
        STRICT,
    }

    /** Hands a deferred mutation to whatever context is actually allowed to run it. */
    @FunctionalInterface
    public interface RerouteTarget {
        void reroute(Runnable mutation);
    }

    /**
     * Resolves chunk ownership and delivers rerouted mutations to the owning
     * region. Implemented by {@code MultiThreadedSchedulerHost}.
     */
    public interface PositionRouter {
        /** Sentinel for "no region owns this chunk". */
        long UNOWNED = -1L;

        /**
         * @return the owning region's id value, or {@link #UNOWNED}. Must be
         *     non-blocking and safe to call from any thread.
         */
        long ownerOf(WorldRef world, int chunkX, int chunkZ);

        /**
         * Queue {@code mutation} on the region owning the chunk.
         *
         * @return {@code false} if no region owns it (the caller falls back
         *     to the server-thread target)
         */
        boolean queueOnOwner(WorldRef world, int chunkX, int chunkZ, Runnable mutation);

        /**
         * The id value of the synthetic global region, or {@link #UNOWNED}. The
         * global region ticks on its own before any level's regions, so like the
         * server thread it may write anywhere.
         */
        default long globalRegionId() {
            return UNOWNED;
        }
    }

    private static final String MODE_PROP = "multiforge.ownership.mode";
    private static volatile PositionRouter positionRouter;

    private static volatile Mode mode = parseMode(System.getProperty(MODE_PROP, "reroute"));
    private static volatile Thread tickThread;
    private static volatile RerouteTarget rerouteTarget = OwnershipEnforcer::runInlineUnconfigured;

    private OwnershipEnforcer() {}

    public static Mode mode() {
        return mode;
    }

    /**
     * Select the enforcement mode (the server applies its configured
     * {@code mode = "strict"} through this). {@code -Dmultiforge.ownership.mode}
     * sets the initial value.
     */
    public static void setMode(Mode m) {
        mode = Objects.requireNonNull(m, "m");
    }

    /** Test-only: overrides the mode without going through the system property. */
    public static void setModeForTesting(Mode m) {
        mode = Objects.requireNonNull(m, "m");
    }

    public static Mode parseMode(String raw) {
        try {
            return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Mode.REROUTE;
        }
    }

    /**
     * Records the thread that is currently the legitimate single-threaded
     * tick executor, so {@link #canMutate} doesn't flag it as a violation.
     * Called once during bootstrap from an existing NeoForge lifecycle
     * hook that already runs on that thread — never from a vanilla patch
     * site, and never from {@code MinecraftServer.runServer} itself.
     */
    public static void bindTickThread(Thread thread) {
        tickThread = Objects.requireNonNull(thread, "thread");
    }

    /** Test-only: clears the bound tick thread. */
    public static void clearTickThreadForTesting() {
        tickThread = null;
    }

    /**
     * Binds the target that deferred (rerouted) mutations are handed to.
     * Must be called during bootstrap before any guarded call site can be
     * safely rerouted; if never called, a misconfigured build still runs
     * the mutation inline rather than crashing a mod's code path (see
     * {@link #runInlineUnconfigured}), but logs loudly that this happened.
     */
    public static void bindRerouteTarget(RerouteTarget target) {
        rerouteTarget = Objects.requireNonNull(target, "target");
    }

    /** Test-only: resets the reroute target to the unconfigured fallback. */
    public static void resetRerouteTargetForTesting() {
        rerouteTarget = OwnershipEnforcer::runInlineUnconfigured;
    }

    /**
     * Called from {@link net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime#shutdown}
     * so a reroute target bound to a specific server executor (via
     * {@code server::execute} in {@code ServerLifecycleHooks}) does not
     * outlive the server it captured — otherwise the next off-thread
     * mutation between server-stop and next-server-start submits into a
     * dead {@code MinecraftServer.execute} and throws
     * {@code RejectedExecutionException}, or pins the dead server in
     * memory across GameTestServer JVM restarts.
     */
    public static void unbindTickThreadAndRerouteTarget() {
        tickThread = null;
        rerouteTarget = OwnershipEnforcer::runInlineUnconfigured;
    }

    /**
     * @param site short symbolic id of the call site, e.g. {@code "Level.setBlock"}.
     * @return {@code true} if the caller may run {@code site}'s mutation body inline right now.
     *         {@code false} means the caller must instead hand its mutation to {@link #reroute}.
     * @throws OwnershipViolationException only in {@link Mode#STRICT}.
     */
    public static boolean canMutate(String site) {
        Objects.requireNonNull(site, "site");
        if (mode == Mode.OFF) return true;

        OwnerToken tok = OwnerToken.current();
        Domain domain = tok.domain();
        if (domain == Domain.REGION || domain == Domain.GLOBAL) return true;

        Thread expected = tickThread;
        Thread current = Thread.currentThread();
        if (expected != null && current == expected) return true;

        // Genuine violation past this point.
        ProbeRegistry.bump(site + ":off-thread");
        ViolationLogger.warn(
                site, "off-thread mutation attempt from '" + current.getName() + "' (domain=" + domain + ")");

        if (mode == Mode.STRICT) {
            throw new OwnershipViolationException(site, current, domain);
        }
        return false;
    }

    /**
     * Hands {@code mutation} to the bound {@link RerouteTarget}. Callers
     * invoke this only after {@link #canMutate} has returned {@code
     * false} for the same site.
     */
    public static void reroute(String site, Runnable mutation) {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(mutation, "mutation");
        rerouteTarget.reroute(mutation);
    }

    /** Bind the host's ownership lookups (see {@link PositionRouter}). */
    public static void bindPositionRouter(PositionRouter router) {
        positionRouter = Objects.requireNonNull(router, "router");
    }

    /** Unbind the position router — the host is shutting down. */
    public static void unbindPositionRouter() {
        positionRouter = null;
    }

    /**
     * Positional ownership check for a mutation of chunk ({@code chunkX},
     * {@code chunkZ}) in {@code world}.
     *
     * <ul>
     * <li>Mode {@link Mode#OFF}, the global region, and the server tick
     *     thread: allowed. In the barrier tick model these never run
     *     concurrently with region workers.</li>
     * <li>A region worker: allowed iff its region owns the chunk. Otherwise
     *     the probe {@code <site>:cross-region} is bumped, a rate-limited
     *     warning logged ({@link Mode#STRICT} throws instead), and
     *     {@code false} returned so the call site reroutes via
     *     {@link #rerouteAt}.</li>
     * <li>Any other thread: as {@link #canMutate}.</li>
     * </ul>
     */
    public static boolean canMutateAt(String site, WorldRef world, int chunkX, int chunkZ) {
        Objects.requireNonNull(site, "site");
        if (mode == Mode.OFF) return true;
        OwnerToken tok = OwnerToken.current();
        if (tok.domain() != Domain.REGION) return canMutate(site);
        PositionRouter router = positionRouter;
        if (router == null || world == null) return true;
        if (tok.regionId() == router.globalRegionId()) return true; // global region: allowed
        long owner = router.ownerOf(world, chunkX, chunkZ);
        if (owner == tok.regionId()) return true;
        ProbeRegistry.bump(site + ":cross-region");
        String msg = "cross-region mutation of chunk [" + chunkX + ", " + chunkZ + "] in " + world.dimensionId()
                + " from region " + tok.regionId() + " (owner="
                + (owner == PositionRouter.UNOWNED ? "none" : Long.toString(owner)) + ") — rerouted to owner";
        ViolationLogger.warn(site, msg);
        if (mode == Mode.STRICT) {
            throw new OwnershipViolationException(site, Thread.currentThread(), tok.domain());
        }
        return false;
    }

    /**
     * @return {@code true} iff the caller is a region worker and chunk
     *     ({@code chunkX}, {@code chunkZ}) of {@code world} is owned by a
     *     different region (or by none). Unlike {@link #canMutateAt} this is a
     *     plain query — no probe, no warning — for call sites that treat a
     *     cross-region target as a normal event to be deferred (an entity
     *     teleporting into another region), not a violation.
     */
    public static boolean isCrossRegionFromWorker(WorldRef world, int chunkX, int chunkZ) {
        if (mode == Mode.OFF) return false;
        OwnerToken tok = OwnerToken.current();
        if (tok.domain() != Domain.REGION) return false;
        PositionRouter router = positionRouter;
        if (router == null || world == null) return false;
        return router.ownerOf(world, chunkX, chunkZ) != tok.regionId();
    }

    /**
     * Hand a mutation refused by {@link #canMutateAt} to the owner of the
     * chunk, or to the server-thread {@link RerouteTarget} when the chunk
     * has no owning region.
     */
    public static void rerouteAt(String site, WorldRef world, int chunkX, int chunkZ, Runnable mutation) {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(mutation, "mutation");
        PositionRouter router = positionRouter;
        if (router != null && world != null && router.queueOnOwner(world, chunkX, chunkZ, mutation)) {
            return;
        }
        rerouteTarget.reroute(mutation);
    }

    private static void runInlineUnconfigured(Runnable mutation) {
        ViolationLogger.warn(
                "OwnershipEnforcer",
                "reroute target not configured; running a deferred mutation inline as an unsafe fallback");
        mutation.run();
    }
}
