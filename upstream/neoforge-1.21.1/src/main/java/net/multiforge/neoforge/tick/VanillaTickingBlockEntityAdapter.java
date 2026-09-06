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
package net.multiforge.neoforge.tick;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;

/**
 * Adapts Vanilla's {@link TickingBlockEntity} — the element type of
 * {@code Level.blockEntityTickers}/{@code Level.pendingBlockEntityTickers}
 * — to MultiForge's MC-free {@link
 * net.multiforge.runtime.chunk.TickingBlockEntityRef}, the abstraction
 * {@link net.multiforge.runtime.region.BlockEntityTickRunner} and
 * {@link net.multiforge.runtime.chunk.HolderManagerRegionData} operate
 * over (docs/design/m13-b3-region-tick.md §5.3).
 *
 * <p>{@link #shouldTick()} reproduces the gate Vanilla's own {@code
 * tickBlockEntities()} loop applies at the {@code
 * blockEntityTickers}-iteration level — {@code
 * shouldTickBlocksAt(getPos())} — before this adapter's {@link
 * #tick()} runs the delegate. This is Vanilla parity, not a new gate:
 * a block entity ticks if its chunk is at {@code ChunkLoadLevel
 * .TICKING} or higher, the same bar Vanilla's inline loop already
 * enforces (see {@code Level.java:573} at freeze time — {@code flag &&
 * this.shouldTickBlocksAt(tickingblockentity.getPos())}). The {@code
 * flag} (tick-rate-manager "runs normally") half of that Vanilla
 * condition is deliberately not reproduced here: {@link
 * net.multiforge.runtime.region.BlockEntityTickRunner#standard} has no
 * hook into Vanilla's {@code TickRateManager}, and freezing/slowing the
 * game tick rate is an orthogonal, server-wide concern this adapter
 * does not need to duplicate — the per-region phase body simply runs
 * whenever the region worker's own tick runs, matching how every other
 * B3 phase body already behaves under a paused/slowed tick rate.
 *
 * <p>Instances are cheap, stateless wrappers — one per registered
 * ticker, created once at {@link BlockEntityTickerBridge#onTickerAdded}
 * time and held for the life of the ticker.
 */
public final class VanillaTickingBlockEntityAdapter implements net.multiforge.runtime.chunk.TickingBlockEntityRef {
    private final Level level;
    private final TickingBlockEntity delegate;
    private final net.multiforge.api.world.BlockPos pos;

    public VanillaTickingBlockEntityAdapter(Level level, TickingBlockEntity delegate) {
        this.level = level;
        this.delegate = delegate;
        net.minecraft.core.BlockPos p = delegate.getPos();
        this.pos = new net.multiforge.api.world.BlockPos(p.getX(), p.getY(), p.getZ());
    }

    @Override
    public boolean shouldTick() {
        return level.shouldTickBlocksAt(delegate.getPos());
    }

    @Override
    public void tick() {
        delegate.tick();
    }

    @Override
    public boolean isRemoved() {
        return delegate.isRemoved();
    }

    @Override
    public net.multiforge.api.world.BlockPos pos() {
        return pos;
    }

    /**
     * The wrapped Vanilla ticker — used by {@link BlockEntityTickerBridge}
     * for identity-based bookkeeping (e.g. avoiding a double-wrap of the
     * same delegate across a backfill pass and a live {@code
     * addBlockEntityTicker} call racing it).
     */
    public TickingBlockEntity delegate() {
        return delegate;
    }
}
