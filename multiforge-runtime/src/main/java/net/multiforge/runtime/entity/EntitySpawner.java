/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.multiforge.runtime.entity;

import java.util.function.Function;
import net.multiforge.api.world.BlockPos;
import net.multiforge.api.world.WorldRef;

/**
 * Narrow, framework-free-mockable view of {@link
 * EntityMigrationCoordinator#spawnInDestRegion} — the cross-region-safe
 * entity-<em>creation</em> surface global-region code (e.g. {@code
 * net.multiforge.runtime.globals.RaidsSystem}'s raider spawn,
 * docs/design/global-region.md §8.2 integration test 6) depends on.
 *
 * <p>Matches the same "depend on a narrow functional interface, not the
 * concrete coordinator/host class" shape already used throughout {@code
 * net.multiforge.runtime.globals} ({@link
 * net.multiforge.runtime.globals.CrossRegionEffects}, {@code
 * WeatherSystem.WeatherTarget}, {@code WorldBorderSystem.BorderTarget})
 * — {@code multiforge-runtime} has no mocking framework dependency
 * (CLAUDE.md's dependency gate), so every collaborator a {@code
 * GlobalSystem} needs is shaped as a single-method interface a test can
 * satisfy with a plain lambda.
 *
 * <p>{@link EntityMigrationCoordinator#spawnInDestRegion} matches this
 * interface's method signature exactly, so production wiring passes the
 * method reference directly:
 * {@code new RaidsSystem(effects, coordinator::spawnInDestRegion)}.
 */
@FunctionalInterface
public interface EntitySpawner {

    /**
     * Materialize a brand-new entity at {@code destPos} in {@code
     * destWorld}, never touching the destination region's entity list
     * from the calling thread — see {@link
     * EntityMigrationCoordinator#spawnInDestRegion} for the full
     * contract.
     */
    void spawnInDestRegion(
            WorldRef destWorld, BlockPos destPos, Function<BlockPos, EntityMigrationCoordinator.NewEntitySpec> factory);
}
