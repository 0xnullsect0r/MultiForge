/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.neoforge;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * M8 sub-step 6a: NeoForge event listeners that keep the regionizer in
 * sync with Vanilla's chunk load/unload state. Every time a chunk goes
 * FULL (Vanilla fires {@link ChunkEvent.Load}), a region is created or
 * grown for it; on {@link ChunkEvent.Unload}, the chunk is removed from
 * its region and the region auto-cascades through {@link
 * net.multiforge.runtime.region.RegionListener}s (see
 * {@link net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost#regionizerFor}
 * auto-wiring).
 *
 * <p>Deliberately does not add a keep-loaded ticket or create a
 * chunk holder — Vanilla's own ticket already keeps the chunk loaded,
 * and holder management stays out of scope until M9. The only effect
 * of this class is that region workers now have real regions to tick
 * (with the M8 sub-step 4 no-op body until sub-step 6b/c wires a real
 * per-region body).
 *
 * <p>Registration is idempotent per JVM: {@link #installOnEventBus}
 * only registers once even if called from repeated
 * {@code handleServerAboutToStart} invocations (dedi GameTestServer
 * reuses one JVM across servers).
 */
public final class RegionizedChunkLifecycle {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private RegionizedChunkLifecycle() {}

    /**
     * Register {@link ChunkEvent.Load} and {@link ChunkEvent.Unload}
     * handlers on {@link NeoForge#EVENT_BUS}. Idempotent — second and
     * later calls do nothing.
     */
    public static void installOnEventBus() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        NeoForge.EVENT_BUS.addListener(RegionizedChunkLifecycle::onChunkLoaded);
        NeoForge.EVENT_BUS.addListener(RegionizedChunkLifecycle::onChunkUnloaded);
    }

    private static void onChunkLoaded(final ChunkEvent.Load event) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return; // runtime not installed yet (bootstrap ordering)
        LevelAccessor level = event.getLevel();
        if (level.isClientSide()) return; // regionizer is server-side only
        WorldRef world = worldRefFor(level);
        if (world == null) return;
        ChunkPos pos = event.getChunk().getPos();
        host.registerChunk(world, pos.x, pos.z);
        // Drain the orphan queue: tasks queued for this chunk BEFORE its region
        // existed (typically from mods that scheduled work at
        // ServerAboutToStart) can now be delivered to the fresh region. Without
        // this call the orphan queue leaks for the JVM lifetime, since no other
        // production code calls reroute() (found by /67 round-2 finding #9; the
        // fix was accidentally reverted in b829f99 alongside a separate broken
        // mapInPlace change — /67 round-3 flagged the regression).
        //
        // Cost: amortized O(orphans) per chunk load. Orphan queue is normally
        // empty; worst case at server-start is O(chunks × orphans_initial) which
        // is O(N) total work per boot for N mod-queued orphans.
        host.taskQueue().reroute();
    }

    private static void onChunkUnloaded(final ChunkEvent.Unload event) {
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return;
        LevelAccessor level = event.getLevel();
        if (level.isClientSide()) return;
        WorldRef world = worldRefFor(level);
        if (world == null) return;
        ChunkPos pos = event.getChunk().getPos();
        host.unregisterChunk(world, pos.x, pos.z);
    }

    /**
     * Extract a {@link WorldRef} from a {@link LevelAccessor}. Only
     * ServerLevels have a well-defined dimension id we can address via
     * WorldRef; returns null for anything else so the caller can no-op.
     */
    private static WorldRef worldRefFor(LevelAccessor level) {
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            return RegionizedTickCoordinator.asWorldRef(serverLevel);
        }
        return null;
    }

    /** Test-only: reset the installed flag so a fresh test JVM can re-install. */
    static void resetForTesting() {
        INSTALLED.set(false);
    }
}
