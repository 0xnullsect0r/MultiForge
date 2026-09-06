/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.ownership;

import java.util.Locale;
import java.util.Objects;
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
 * <p><b>M7 scope note:</b> until M2 wires {@link
 * net.multiforge.runtime.region.RegionizedTaskQueue} to real {@code
 * ServerLevel}s, the only real reroute target is the pre-existing
 * single-threaded main-server executor (bound via {@link
 * #bindRerouteTarget}). {@link #reroute} is written against the {@link
 * RerouteTarget} interface specifically so M2 can swap that binding for a
 * real per-region mailbox without touching any patched call site again.
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

    private static final String MODE_PROP = "multiforge.ownership.mode";

    private static volatile Mode mode = parseMode(System.getProperty(MODE_PROP, "reroute"));
    private static volatile Thread tickThread;
    private static volatile RerouteTarget rerouteTarget = OwnershipEnforcer::runInlineUnconfigured;

    private OwnershipEnforcer() {}

    public static Mode mode() {
        return mode;
    }

    /** Test-only: overrides the mode without going through the system property. */
    public static void setModeForTesting(Mode m) {
        mode = Objects.requireNonNull(m, "m");
    }

    static Mode parseMode(String raw) {
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

    private static void runInlineUnconfigured(Runnable mutation) {
        ViolationLogger.warn(
                "OwnershipEnforcer",
                "reroute target not configured; running a deferred mutation inline as an unsafe fallback");
        mutation.run();
    }
}
