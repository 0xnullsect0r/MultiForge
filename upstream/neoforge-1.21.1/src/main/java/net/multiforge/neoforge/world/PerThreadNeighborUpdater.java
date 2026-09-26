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
package net.multiforge.neoforge.world;

import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.NeighborUpdater;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link NeighborUpdater} with one delegate per thread.
 *
 * <p>Vanilla gives each {@code Level} a single {@code
 * CollectingNeighborUpdater}, whose stack and "added this layer" list are
 * plain fields. Region workers update neighbours of their own blocks
 * concurrently, so a shared instance would interleave two workers' update
 * chains. The thread that built the level keeps the original instance (so
 * single-threaded behaviour is exactly Vanilla's); every other thread gets
 * its own, lazily. Neighbour updates never cross into another region's
 * chunks (regions are separated by unloaded sections), so per-thread chains
 * are complete.
 */
public final class PerThreadNeighborUpdater implements NeighborUpdater {
    private final Thread owner;
    private final NeighborUpdater primary;
    private final ThreadLocal<NeighborUpdater> others;

    public PerThreadNeighborUpdater(Supplier<NeighborUpdater> factory) {
        this.owner = Thread.currentThread();
        this.primary = factory.get();
        this.others = ThreadLocal.withInitial(factory);
    }

    private NeighborUpdater current() {
        return Thread.currentThread() == this.owner ? this.primary : this.others.get();
    }

    @Override
    public void shapeUpdate(Direction direction, BlockState state, BlockPos pos, BlockPos neighborPos, int flags, int recursionLeft) {
        this.current().shapeUpdate(direction, state, pos, neighborPos, flags, recursionLeft);
    }

    @Override
    public void neighborChanged(BlockPos pos, Block block, BlockPos neighborPos) {
        this.current().neighborChanged(pos, block, neighborPos);
    }

    @Override
    public void neighborChanged(BlockState state, BlockPos pos, Block block, BlockPos neighborPos, boolean movedByPiston) {
        this.current().neighborChanged(state, pos, block, neighborPos, movedByPiston);
    }

    @Override
    public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block block, @Nullable Direction skip) {
        this.current().updateNeighborsAtExceptFromFacing(pos, block, skip);
    }
}
