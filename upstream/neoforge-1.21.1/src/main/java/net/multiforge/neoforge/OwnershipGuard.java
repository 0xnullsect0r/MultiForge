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
package net.multiforge.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.multiforge.runtime.ownership.OwnershipEnforcer;

/**
 * Thin adapter that patched {@code net.minecraft.*} mutation sites
 * (multiforge-patches/01-ownership/) call into. All real decision logic
 * lives in {@link OwnershipEnforcer} (multiforge-runtime), which this
 * fork depends on via mavenLocal — see projects/neoforge/build.gradle.
 *
 * <p>Kept deliberately as a pass-through: this class exists so patched
 * vanilla call sites reference a stable, fork-local package rather than
 * multiforge-runtime directly, mirroring how NeoForge itself keeps its
 * own hand-written glue under {@code net.neoforged.neoforge} separate
 * from patched vanilla under {@code net.minecraft}.
 */
public final class OwnershipGuard {
    private OwnershipGuard() {}

    /**
     * @param site short symbolic id of the call site, e.g. {@code "Level.setBlock"}.
     * @return {@code true} if the caller may run {@code site}'s mutation body inline right now.
     *         {@code false} means the caller must instead hand its mutation to {@link #reroute}.
     */
    public static boolean canMutate(String site) {
        return OwnershipEnforcer.canMutate(site);
    }

    /**
     * Hands {@code mutation} to MultiForge's configured reroute target.
     * Callers invoke this only after {@link #canMutate} has returned
     * {@code false} for the same site.
     */
    public static void reroute(String site, Runnable mutation) {
        OwnershipEnforcer.reroute(site, mutation);
    }

    /**
     * Positional check: may the caller mutate chunk ({@code chunkX}, {@code
     * chunkZ}) of {@code level} right now? A region worker may only mutate
     * chunks its own region owns — see {@link OwnershipEnforcer#canMutateAt}.
     * Client levels are never checked.
     */
    public static boolean canMutateAt(String site, Level level, int chunkX, int chunkZ) {
        if (level.isClientSide()) return true;
        return OwnershipEnforcer.canMutateAt(site, level.mfWorldRef(), chunkX, chunkZ);
    }

    /** {@link #canMutateAt(String, Level, int, int)} for the chunk containing {@code pos}. */
    public static boolean canMutateAt(String site, Level level, BlockPos pos) {
        return canMutateAt(site, level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * Hands {@code mutation} to the region owning chunk ({@code chunkX},
     * {@code chunkZ}), or to the server thread when no region owns it.
     * Callers invoke this only after {@link #canMutateAt} returned {@code false}.
     */
    public static void rerouteAt(String site, Level level, int chunkX, int chunkZ, Runnable mutation) {
        OwnershipEnforcer.rerouteAt(site, level.mfWorldRef(), chunkX, chunkZ, mutation);
    }

    /** {@link #rerouteAt(String, Level, int, int, Runnable)} for the chunk containing {@code pos}. */
    public static void rerouteAt(String site, Level level, BlockPos pos, Runnable mutation) {
        rerouteAt(site, level, pos.getX() >> 4, pos.getZ() >> 4, mutation);
    }

    /**
     * Defer an entity move that would take {@code entity} into a chunk of
     * {@code target} owned by another region. Called at the top of the
     * teleport entry points. On a region worker, moving an entity into a
     * foreign region would hand it to a region that may be ticking right now;
     * instead the whole call ({@code redo}) is re-run on the server thread
     * after the tick barrier, where no region runs. Moves within the caller's
     * region, and calls from any non-region thread, proceed inline. A move to
     * another dimension proceeds inline too (only this level's regions are
     * ticking), except for players: removing a player from a level updates
     * every tracked entity's viewer set, including other regions' entities,
     * so a player's dimension change is always deferred.
     *
     * @return {@code true} if the move was deferred and the caller must return
     */
    public static boolean deferCrossRegionMove(
            String site, net.minecraft.world.entity.Entity entity, Level target, double x, double z, Runnable redo) {
        if (target.isClientSide()) return false;
        if (target != entity.level()) {
            if (!(entity instanceof net.minecraft.server.level.ServerPlayer)
                    || net.multiforge.runtime.ownership.OwnerToken.current().domain() != net.multiforge.runtime.ownership.Domain.REGION) {
                return false;
            }
            net.multiforge.runtime.diagnostics.ProbeRegistry.bump(site + ":deferred-player-dimension-change");
            OwnershipEnforcer.reroute(site, redo);
            return true;
        }
        int chunkX = net.minecraft.util.Mth.floor(x) >> 4;
        int chunkZ = net.minecraft.util.Mth.floor(z) >> 4;
        if (!OwnershipEnforcer.isCrossRegionFromWorker(target.mfWorldRef(), chunkX, chunkZ)) return false;
        net.multiforge.runtime.diagnostics.ProbeRegistry.bump(site + ":deferred-cross-region");
        OwnershipEnforcer.reroute(site, redo);
        return true;
    }

    /**
     * Defer {@code work} to the server thread when called from a region
     * worker. Used for operations whose effects are not confined to one
     * region — command execution (a command block's {@code /fill}, {@code
     * /tp @e}, {@code /kill}), function execution — so they run after the
     * tick barrier, while no region runs, exactly as a command typed by a
     * player does. From any other thread this returns {@code false} and the
     * caller runs inline.
     *
     * @return {@code true} if deferred and the caller must return
     */
    public static boolean deferToServerThread(String site, Runnable work) {
        if (net.multiforge.runtime.ownership.OwnerToken.current().domain() != net.multiforge.runtime.ownership.Domain.REGION) {
            return false;
        }
        net.multiforge.runtime.diagnostics.ProbeRegistry.bump(site + ":deferred-to-server-thread");
        OwnershipEnforcer.reroute(site, work);
        return true;
    }

    /**
     * @return whether the calling thread is ticking the world on MultiForge's
     *         behalf right now — a region worker or the global region. Vanilla
     *         code that only serves "the level's own thread" (e.g. {@code
     *     Level.getBlockEntity}) must serve these threads too.
     */
    public static boolean isTickDomain() {
        net.multiforge.runtime.ownership.Domain domain = net.multiforge.runtime.ownership.OwnerToken.current().domain();
        return domain == net.multiforge.runtime.ownership.Domain.REGION
                || domain == net.multiforge.runtime.ownership.Domain.GLOBAL;
    }
}
