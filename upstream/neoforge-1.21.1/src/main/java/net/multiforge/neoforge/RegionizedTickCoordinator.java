/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.neoforge;

import net.minecraft.server.level.ServerLevel;
import net.multiforge.api.world.WorldRef;

/**
 * Fork-local façade patched vanilla call sites in
 * {@code MinecraftServer.tickChildren} route through, so the per-level
 * tick can be dispatched to region workers rather than run inline on
 * the server thread.
 *
 * <p>Mirrors the {@link OwnershipGuard} facade shape used by M7:
 * patched vanilla code references a stable fork-local API; the actual
 * runtime behind it is swapped out from behind without editing any
 * patch file.
 *
 * <p><b>M8 sub-step 5 state:</b> {@link #dispatchLevelTick} currently
 * runs {@code body} inline on the caller thread — no behavior change
 * versus vanilla. Sub-steps 6/7 will replace the body with real
 * per-region dispatch through {@link
 * net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime} once
 * {@link ServerLevel#tick} itself is decomposed into per-region phases.
 * Landing this pass-through facade first lets the vanilla patch stay
 * stable across those changes.
 */
public final class RegionizedTickCoordinator {
    private RegionizedTickCoordinator() {}

    /**
     * Dispatch {@code body} — vanilla's {@code serverlevel.tick(p)} call
     * for one {@link ServerLevel} — as one unit of per-level tick work.
     *
     * <p>Currently a pass-through: runs inline on the caller thread.
     * Documented for future sub-steps to swap without touching the
     * vanilla patch.
     *
     * @param level the level being ticked, kept as an argument now so
     *              future implementations can look up the level's
     *              regions and dispatch per-region instead of per-level.
     * @param body  the vanilla per-level tick call.
     */
    public static void dispatchLevelTick(ServerLevel level, Runnable body) {
        body.run();
    }

    /**
     * Convert a vanilla {@link ServerLevel} to a {@link WorldRef} — the
     * public API's dimension identifier. Kept here rather than in
     * {@link WorldRef} itself because {@code WorldRef} is in
     * multiforge-api and must not depend on Minecraft classes.
     */
    public static WorldRef asWorldRef(ServerLevel level) {
        return WorldRef.of(level.dimension().location().toString());
    }
}
