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
}
