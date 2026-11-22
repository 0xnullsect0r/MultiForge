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

import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.redstone.Orientation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link CollectingNeighborUpdater} with one delegate per thread.
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
@ApiStatus.Internal
public final class PerThreadNeighborUpdater extends CollectingNeighborUpdater {
    private final Thread owner;
    private final ThreadLocal<CollectingNeighborUpdater> others;

    /** A subclass because {@code Level.neighborUpdater} is declared with Vanilla's concrete type. */
    public PerThreadNeighborUpdater(Level level, int maxChainedNeighborUpdates) {
        super(level, maxChainedNeighborUpdates);
        this.owner = Thread.currentThread();
        this.others = ThreadLocal.withInitial(() -> new CollectingNeighborUpdater(level, maxChainedNeighborUpdates));
    }

    private boolean onOwner() {
        return Thread.currentThread() == this.owner;
    }

    /** Vanilla's debug overlay: set by {@code ServerLevel.tick}, on the owner's chain only. */
    @Override
    public void setDebugListener(@Nullable Consumer<BlockPos> debugListener) {
        super.setDebugListener(debugListener);
    }

    @Override
    public void shapeUpdate(Direction direction, BlockState neighborState, BlockPos pos, BlockPos neighborPos, int updateFlags, int updateLimit) {
        if (this.onOwner()) super.shapeUpdate(direction, neighborState, pos, neighborPos, updateFlags, updateLimit);
        else this.others.get().shapeUpdate(direction, neighborState, pos, neighborPos, updateFlags, updateLimit);
    }

    @Override
    public void neighborChanged(BlockPos pos, Block block, @Nullable Orientation orientation) {
        if (this.onOwner()) super.neighborChanged(pos, block, orientation);
        else this.others.get().neighborChanged(pos, block, orientation);
    }

    @Override
    public void neighborChanged(BlockState state, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston) {
        if (this.onOwner()) super.neighborChanged(state, pos, block, orientation, movedByPiston);
        else this.others.get().neighborChanged(state, pos, block, orientation, movedByPiston);
    }

    @Override
    public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block block, @Nullable Direction skipDirection, @Nullable Orientation orientation) {
        if (this.onOwner()) super.updateNeighborsAtExceptFromFacing(pos, block, skipDirection, orientation);
        else this.others.get().updateNeighborsAtExceptFromFacing(pos, block, skipDirection, orientation);
    }
}
