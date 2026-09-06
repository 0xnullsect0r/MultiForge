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
package net.multiforge.neoforge.chunk;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.multiforge.runtime.chunk.NewChunkHolder;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Facade adapter that projects a {@link NewChunkHolder} shadow onto the
 * Vanilla {@link ChunkHolder} shape so callers reading a legacy
 * {@code ChunkHolder} continue to work unchanged during the M9 transition.
 *
 * <p>Every state-carrying getter that Vanilla exposes on
 * {@link ChunkHolder} — {@code getTickingChunk}, {@code getFullChunkFuture},
 * {@code getTickingChunkFuture}, {@code getEntityTickingChunkFuture},
 * {@code getSendSyncFuture}, {@code getSaveSyncFuture},
 * {@code getTicketLevel} — is overridden here to read from the attached
 * {@link NewChunkHolder} shadow. The Vanilla backing fields are left at
 * their post-super-ctor defaults and are consulted only if the shadow
 * returns {@code null} — the same "shadow-preferred" fallback contract
 * that {@link ChunkHolder#attachMfShadow} sets up for the patched Vanilla
 * {@code ChunkHolder}.
 *
 * <p><b>Threading:</b> the shim itself is stateless beyond the final
 * {@code shadow} reference; every read hits the shadow's own thread-safe
 * volatile fields. Safe to construct and discard on every
 * {@link MultiForgeChunkMap#getVisibleChunkIfPresent} call — the outer
 * caller keeps the reference only for the duration of the local
 * computation.
 *
 * <p><b>Lifecycle:</b> a new shim is minted per {@code
 * getVisibleChunkIfPresent} call. Callers that must retain the shim
 * across ticks (e.g. Vanilla generation code) hold it only briefly
 * — the shadow is the durable identity. Shims for the same shadow
 * compare equal by shadow-position under {@link #equals} so cache-based
 * callers behave correctly.
 */
@ApiStatus.Internal
final class ChunkHolderShim extends ChunkHolder {
    private final NewChunkHolder shadow;

    private ChunkHolderShim(
            NewChunkHolder shadow,
            ServerLevel level,
            ThreadedLevelLightEngine lightEngine,
            ChunkHolder.PlayerProvider players) {
        super(
                new ChunkPos(shadow.position().x(), shadow.position().z()),
                shadow.level().distance(),
                level,
                lightEngine,
                NO_OP_LEVEL_CHANGE,
                players);
        this.shadow = shadow;
        // Vanilla ChunkHolder.attachMfShadow is package-private in
        // net.minecraft.server.level, so it cannot be called from this
        // package. The shim keeps its own reference to the shadow and
        // every overridden getter reads from it — the Vanilla-side
        // fields set by super() are never consulted through the shim.
        // Phase 4.4a's patched Vanilla ChunkHolder attaches its own
        // shadow via the package-private setter directly.
    }

    /**
     * Factory — builds a shim for {@code shadow}. If {@code shadow} is
     * {@code null} returns {@code null} so callers can chain the shim
     * lookup with the shadow lookup and short-circuit on absence.
     */
    @Nullable
    static ChunkHolder forShadow(
            @Nullable NewChunkHolder shadow,
            ServerLevel level,
            @Nullable ThreadedLevelLightEngine lightEngine,
            ChunkHolder.PlayerProvider players) {
        return shadow == null ? null : new ChunkHolderShim(shadow, level, lightEngine, players);
    }

    /**
     * Extract the {@link NewChunkHolder} from a shim, or {@code null} if
     * the {@link ChunkHolder} isn't a shim (Vanilla {@code ChunkHolder}
     * during the Phase 4.1c transition, or a mod-authored subclass).
     * Used by {@link MultiForgeChunkMap#prepareTickingChunk} etc. to
     * read the shadow's future gates directly.
     */
    @Nullable
    static NewChunkHolder shadowOrNull(@Nullable ChunkHolder holder) {
        return holder instanceof ChunkHolderShim s ? s.shadow : null;
    }

    // === delegated state getters ===

    @Override
    @Nullable
    public LevelChunk getTickingChunk() {
        Object current = shadow.getCurrentChunk();
        return current instanceof LevelChunk lc ? lc : null;
    }

    @Override
    @Nullable
    public LevelChunk getChunkToSend() {
        // Preserve Vanilla's sendSync gate — only return the ticking chunk
        // once the send-dependency chain is resolved.
        return shadow.getSendSyncFuture().isDone() ? getTickingChunk() : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<ChunkResult<LevelChunk>> getTickingChunkFuture() {
        return (CompletableFuture<ChunkResult<LevelChunk>>) (CompletableFuture<?>) shadow.getTickingChunkFuture();
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<ChunkResult<LevelChunk>> getEntityTickingChunkFuture() {
        return (CompletableFuture<ChunkResult<LevelChunk>>) (CompletableFuture<?>) shadow.getEntityTickingChunkFuture();
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<ChunkResult<LevelChunk>> getFullChunkFuture() {
        return (CompletableFuture<ChunkResult<LevelChunk>>) (CompletableFuture<?>) shadow.getFullChunkFuture();
    }

    @Override
    public CompletableFuture<?> getSendSyncFuture() {
        return shadow.getSendSyncFuture();
    }

    @Override
    public CompletableFuture<?> getSaveSyncFuture() {
        return shadow.getSaveSyncFuture();
    }

    @Override
    public int getTicketLevel() {
        return shadow.level().distance();
    }

    @Override
    public int getQueueLevel() {
        return shadow.level().distance();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ChunkHolderShim s && s.shadow.position().equals(this.shadow.position());
    }

    @Override
    public int hashCode() {
        return shadow.position().hashCode();
    }

    @Override
    public String toString() {
        return "ChunkHolderShim[" + shadow + "]";
    }

    /**
     * No-op {@link ChunkHolder.LevelChangeListener} — the shadow's own
     * {@link NewChunkHolder#setLevelChangeListener} carries the real
     * bridge (installed by {@link MultiForgeChunkMap} at Phase 5.1
     * wire-in time), so the Vanilla-ctor listener is redundant.
     */
    private static final ChunkHolder.LevelChangeListener NO_OP_LEVEL_CHANGE = (pos, oldLevel, newLevel, setter) -> {};
}
