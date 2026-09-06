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
package net.multiforge.runtime.chunk;

import net.multiforge.api.world.BlockPos;

/**
 * MC-free abstraction over a single block entity's per-tick ticker —
 * the moral equivalent of Vanilla's {@code TickingBlockEntity}
 * (usually a {@code BlockEntity} implementing {@code
 * net.minecraft.world.level.block.entity.TickingBlockEntity}).
 *
 * <p>{@code multiforge-runtime} deliberately carries no {@code
 * net.minecraft.*} dependency (see the module-level note on {@link
 * NewChunkHolder}), so this interface exposes only the primitives
 * {@link HolderManagerRegionData}'s per-region block-entity slice
 * needs: whether the ticker is still live, whether it wants to run
 * this tick, the tick call itself, and the block position it ticks at
 * (used to resolve which region owns it on split/merge — see {@link
 * HolderManagerRegionData#split}). The fork bridge under {@code
 * net.multiforge.neoforge.chunk} (a later B3.4 task) supplies the
 * adapter wrapping Vanilla's real {@code TickingBlockEntity}.
 *
 * <p>Implementations are expected to be cheap and side-effect-free for
 * {@link #shouldTick()}/{@link #isRemoved()}/{@link #pos()}. {@link
 * #tick()} is called only from the owning region's worker thread —
 * same single-writer contract as the rest of {@link
 * HolderManagerRegionData}.
 */
public interface TickingBlockEntityRef {

    /** Whether this ticker currently wants to run — Vanilla's per-entity tick gate. */
    boolean shouldTick();

    /** Run the block entity's tick logic. Owning region worker only. */
    void tick();

    /** Whether the underlying block entity has been removed (ticker should be dropped). */
    boolean isRemoved();

    /**
     * The block position this ticker ticks at. Stable for the life of
     * the ticker — used to resolve which region a split/merge should
     * re-home this ticker under (see {@link HolderManagerRegionData#split}).
     */
    BlockPos pos();
}
