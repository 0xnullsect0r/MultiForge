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
     * Reroute a {@code Level.setBlock} the caller may not run here, and return
     * what Vanilla's {@code setBlock} would have returned: whether the block
     * changes. Callers use that value (to consume an item, play a sound, count
     * a placement), so answering {@code false} for every rerouted write would
     * silently change their behaviour. The prediction reads the target chunk
     * without its owner's cooperation, so it can be stale; when the owner
     * applies the write, a differing result is counted under {@code
     * reroute.Level.setBlock.mismatch} and logged.
     */
    public static boolean rerouteSetBlock(
            Level level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state, java.util.function.BooleanSupplier apply) {
        boolean predicted = predictSetBlock(level, pos, state);
        rerouteChecked("Level.setBlock", level, pos, predicted, apply);
        return predicted;
    }

    static boolean predictSetBlock(Level level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        if (level.isOutsideBuildHeight(pos) || level.isDebug()) return false;
        net.minecraft.world.level.chunk.LevelChunk chunk = level instanceof net.minecraft.server.level.ServerLevel serverLevel
                ? serverLevel.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4)
                : null;
        // Not loaded: the owner loads it and writes, which changes the block
        // unless it already held this state; assume it did not.
        if (chunk == null) return true;
        return chunk.getBlockState(pos) != state;
    }

    /**
     * Reroute a {@code ServerLevel.addFreshEntity} the caller may not run here,
     * and return what Vanilla would have: {@code false} for an entity that is
     * already removed or whose UUID the level already holds, else {@code true}.
     * Mismatches with the owner's actual result are counted and logged, as for
     * {@link #rerouteSetBlock}.
     */
    public static boolean rerouteAddFreshEntity(
            net.minecraft.server.level.ServerLevel level, net.minecraft.world.entity.Entity entity, java.util.function.BooleanSupplier apply) {
        boolean predicted = !entity.isRemoved() && level.getEntity(entity.getUUID()) == null;
        rerouteChecked("ServerLevel.addFreshEntity", level, entity.blockPosition(), predicted, apply);
        return predicted;
    }

    private static void rerouteChecked(String site, Level level, BlockPos pos, boolean predicted, java.util.function.BooleanSupplier apply) {
        rerouteAt(site, level, pos, () -> {
            boolean actual = apply.getAsBoolean();
            if (actual != predicted) {
                net.multiforge.runtime.diagnostics.ProbeRegistry.bump("reroute." + site + ".mismatch");
                net.multiforge.runtime.diagnostics.ViolationLogger.warn(
                        site + ".mismatch",
                        "rerouted " + site + " at " + pos + " returned " + predicted + " to its caller but " + actual
                                + " when applied by the owner (the target changed in between)");
            }
        });
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
     * Defer a chunk-ticket change ({@code ServerChunkCache.addRegionTicket} /
     * {@code removeRegionTicket}) made on a region worker — or on the global
     * region while it ticks on a worker — to the server thread. The ticket
     * maps of {@code DistanceManager} are the server thread's; a change made
     * while the barrier pumps chunk tasks corrupts them, and feeds promotions
     * and demotions into the middle of the region phase. The ticket takes
     * effect after the barrier, one tick later than inline. A rate-limited
     * warning names the site.
     *
     * @return {@code true} if deferred and the caller must return
     */
    public static <T> boolean deferRegionTicket(
            String site,
            net.minecraft.server.level.ServerChunkCache cache,
            boolean add,
            net.minecraft.server.level.TicketType<T> type,
            net.minecraft.world.level.ChunkPos pos,
            int distance,
            T value,
            boolean forceTicks) {
        if (!onTickWorker()) return false;
        deferTicket(site, pos, add ? () -> cache.addRegionTicket(type, pos, distance, value, forceTicks)
                : () -> cache.removeRegionTicket(type, pos, distance, value, forceTicks));
        return true;
    }

    /**
     * {@code ServerLevel.setChunkForced} on a region worker: deferred like
     * {@link #deferRegionTicket} (it adds or removes a forced ticket and may
     * load the chunk). Returns what Vanilla would have — whether the forced
     * set changes — predicted from the set as it is now; {@code null} when the
     * call runs inline.
     */
    public static Boolean deferSetChunkForced(net.minecraft.server.level.ServerLevel level, int chunkX, int chunkZ, boolean add) {
        if (!onTickWorker()) return null;
        boolean forced = level.getForcedChunks().contains(net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ));
        deferTicket("ServerLevel.setChunkForced", new net.minecraft.world.level.ChunkPos(chunkX, chunkZ),
                () -> level.setChunkForced(chunkX, chunkZ, add));
        return forced != add;
    }

    private static boolean onTickWorker() {
        net.multiforge.runtime.ownership.Domain domain = net.multiforge.runtime.ownership.OwnerToken.current().domain();
        return domain == net.multiforge.runtime.ownership.Domain.REGION
                || (domain == net.multiforge.runtime.ownership.Domain.GLOBAL
                        && net.multiforge.runtime.region.RegionPhase.workersInFlight());
    }

    private static void deferTicket(String site, net.minecraft.world.level.ChunkPos pos, Runnable work) {
        net.multiforge.runtime.diagnostics.ProbeRegistry.bump(site + ":deferred-to-server-thread");
        net.multiforge.runtime.diagnostics.ViolationLogger.warn(
                site,
                "chunk ticket change at " + pos + " from " + Thread.currentThread().getName()
                        + " deferred to the server thread (applies after this tick's barrier)");
        OwnershipEnforcer.reroute(site, work);
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
