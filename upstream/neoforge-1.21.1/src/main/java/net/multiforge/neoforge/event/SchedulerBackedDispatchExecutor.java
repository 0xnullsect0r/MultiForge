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
package net.multiforge.neoforge.event;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.multiforge.api.world.WorldRef;
import net.multiforge.neoforge.RegionizedTickCoordinator;
import net.multiforge.runtime.chunk.ChunkHolderManager;
import net.multiforge.runtime.chunk.NewChunkHolder;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.event.AsyncEventPool;
import net.multiforge.runtime.event.DispatchExecutor;
import net.multiforge.runtime.region.Region;
import net.multiforge.runtime.region.RegionId;
import net.multiforge.runtime.region.ThreadedRegionizer;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import net.neoforged.neoforge.event.entity.EntityEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * Production {@code DispatchExecutor} (M12.2, {@code
 * docs/design/m12-event-routing.md} §9) backed by a live {@link
 * MultiThreadedSchedulerHost}. Installed onto {@code NeoForge.EVENT_BUS}'s
 * {@link net.multiforge.runtime.event.LazyDispatchingEventBus} by {@link
 * EventBusBridge#attach} at {@code ServerAboutToStart}.
 *
 * <p>Every hand-off method here is non-blocking (CLAUDE.md rule 4): {@link
 * #enqueueRegion}/{@link #enqueueGlobal} hand off through {@code
 * RegionizedTaskQueue.queueChunkTask} (via {@link
 * MultiThreadedSchedulerHost#taskQueue()}), which enqueues onto the target
 * region's inbox and returns immediately; {@link #enqueueAsync} forwards to
 * a shared {@link AsyncEventPool}, whose own submission is likewise
 * non-blocking.
 */
public final class SchedulerBackedDispatchExecutor implements DispatchExecutor {
    /**
     * Matches the synthetic global world every other global-region call site uses (see
     * {@code MultiForgeGlobalSystemsInit.install}'s {@code CommandDispatchSystem} wiring).
     */
    private static final WorldRef GLOBAL_WORLD = WorldRef.of("multiforge:global");

    /** One shared pool per JVM — reused across every {@code DispatchingEventBus} instance. */
    private static final AsyncEventPool ASYNC_POOL = new AsyncEventPool();

    private final MultiThreadedSchedulerHost host;

    public SchedulerBackedDispatchExecutor(MultiThreadedSchedulerHost host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * Resolves {@code destination}'s owning world, picks any one of its
     * currently-owned chunks as an anchor (any owned chunk routes to the
     * same region — {@code RegionizedTaskQueue.queueChunkTask} resolves by
     * chunk position, not by region id directly), and enqueues {@code
     * task} there. If the region has died or owns no chunks by the time
     * this runs (a race against the caller's own snapshot of {@code
     * destination} — the region may have merged/split/died between the
     * routing decision and this call), the task is dropped with a
     * rate-limited warn rather than thrown (CLAUDE.md rule 5) — never
     * refuse, never crash a mod's event dispatch over a stale target.
     */
    @Override
    public void enqueueRegion(RegionId destination, Runnable task) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(task, "task");

        WorldRef world = host.worldForRegion(destination);
        if (world == null) {
            dropAndWarn(destination, "region has no owning world (dead or never materialised)");
            return;
        }
        ChunkHolderManager manager = host.chunkManagerForOrNull(world);
        if (manager == null) {
            dropAndWarn(destination, "no chunk manager for world " + world.dimensionId());
            return;
        }
        List<NewChunkHolder> holders = manager.holdersOwnedBy(destination);
        if (holders.isEmpty()) {
            dropAndWarn(destination, "region owns no chunks to anchor the dispatch on");
            return;
        }
        net.multiforge.api.world.ChunkPos anchor = holders.get(0).position();
        host.taskQueue().queueChunkTask(world, anchor, task);
    }

    /**
     * Mirrors {@code MultiThreadedSchedulerHost.enqueueOnGlobal} (line
     * ~1350): the global region is created eagerly and never moves, so
     * this is always a direct {@code queueChunkTask} at {@code (0, 0)}
     * against the synthetic global world — no lookup needed.
     */
    @Override
    public void enqueueGlobal(Runnable task) {
        Objects.requireNonNull(task, "task");
        host.taskQueue().queueChunkTask(GLOBAL_WORLD, 0, 0, task);
    }

    @Override
    public void enqueueAsync(Runnable task) {
        Objects.requireNonNull(task, "task");
        ASYNC_POOL.submit(task);
    }

    /**
     * Dispatch table over the highest-value NeoForge event shapes from
     * {@code docs/events.md:59-94} — grouped by base class rather than one
     * case per concrete event, since every concrete subtype in a group
     * resolves identically:
     *
     * <ul>
     * <li>{@link BlockEvent} — covers {@code BreakEvent}, {@code
     *       EntityPlaceEvent}, {@code PistonEvent.Pre}/{@code .Post} ({@code
     *       PistonEvent} extends {@code BlockEvent}), and every other
     * block-position-keyed {@code BlockEvent} subtype. Location:
     * {@code getPos()}.
     * <li>{@link ChunkWatchEvent} — covers {@code Watch}/{@code UnWatch}/
     * {@code Sent}. Location: {@code getPos()}, level already typed
     * {@code ServerLevel}.
     * <li>{@link ChunkEvent} — covers {@code ChunkEvent.Load}/{@code
     *       Unload} and {@code ChunkDataEvent.Load}/{@code Save} ({@code
     *       ChunkDataEvent} extends {@code ChunkEvent}). Location: {@code
     *       getChunk().getPos()}.
     * <li>{@link ExplosionEvent} — covers {@code Start}/{@code Detonate}.
     * Location: the explosion's origin, {@code
     *       getExplosion().center()}.
     * <li>{@link EntityEvent} — covers {@code EntityJoinLevelEvent},
     * {@code EntityLeaveLevelEvent}, {@code EntityMountEvent}, {@code
     *       EntityStruckByLightningEvent}, and (via {@code LivingEvent
     *       extends EntityEvent}) {@code LivingHurtEvent}/{@code
     *       LivingDeathEvent}. Location: {@code
     *       getEntity().chunkPosition()}.
     * </ul>
     *
     * Every other event shape (server-lifecycle, command, chat, tick
     * events already firing from inside a region context — see design doc
     * §5.1's closing rows) returns {@link Optional#empty()}; {@link
     * net.multiforge.runtime.event.DomainDispatcher} treats that as
     * "genuinely non-spatial" and falls back to global-region routing with
     * a rate-limited warn.
     */
    @Override
    public Optional<RegionId> resolveEventLocation(Object event) {
        return switch (event) {
            case BlockEvent e -> levelChunk(e.getLevel(), new net.minecraft.world.level.ChunkPos(e.getPos()));
            case ChunkWatchEvent e -> regionFor(e.getLevel(), e.getPos());
            case ChunkEvent e -> levelChunk(e.getLevel(), e.getChunk().getPos());
            case ExplosionEvent e -> explosionLocation(e);
            case EntityEvent e -> entityLocation(e);
            default -> Optional.empty();
        };
    }

    private Optional<RegionId> explosionLocation(ExplosionEvent event) {
        Level level = event.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return Optional.empty();
        }
        BlockPos origin = BlockPos.containing(event.getExplosion().center());
        return regionFor(serverLevel, new net.minecraft.world.level.ChunkPos(origin));
    }

    private Optional<RegionId> entityLocation(EntityEvent event) {
        Level level = event.getEntity().level();
        if (!(level instanceof ServerLevel serverLevel)) {
            return Optional.empty();
        }
        return regionFor(serverLevel, event.getEntity().chunkPosition());
    }

    /** {@code getLevel()} on most events returns {@code LevelAccessor} — only a {@code ServerLevel} has a region owner. */
    private Optional<RegionId> levelChunk(net.minecraft.world.level.LevelAccessor levelAccessor, net.minecraft.world.level.ChunkPos pos) {
        if (!(levelAccessor instanceof ServerLevel serverLevel)) {
            return Optional.empty();
        }
        return regionFor(serverLevel, pos);
    }

    private Optional<RegionId> regionFor(ServerLevel level, net.minecraft.world.level.ChunkPos pos) {
        WorldRef world = RegionizedTickCoordinator.asWorldRef(level);
        ThreadedRegionizer regionizer = host.regionizerForOrNull(world);
        if (regionizer == null) {
            return Optional.empty();
        }
        Region region = regionizer.regionAtChunk(pos.x, pos.z);
        return region == null ? Optional.empty() : Optional.of(region.id());
    }

    private static void dropAndWarn(RegionId destination, String reason) {
        ViolationLogger.warn(
                "SchedulerBackedDispatchExecutor.enqueueRegion",
                "dropped deferred event dispatch to " + destination + ": " + reason);
    }
}
