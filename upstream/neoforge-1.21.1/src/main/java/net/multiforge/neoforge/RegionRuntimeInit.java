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

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.multiforge.neoforge.tick.BlockEntityTickerBridge;
import net.multiforge.neoforge.tick.EntityTickRunnerBridge;
import net.multiforge.neoforge.tick.ScheduledTickRunnerBridge;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Binds the Vanilla-backed per-region runners to a freshly installed
 * {@link MultiThreadedSchedulerHost}: scheduled block/fluid ticks and block
 * events ({@link ScheduledTickRunnerBridge}), entity ticking ({@link
 * EntityTickRunnerBridge}), block-entity ticking ({@link
 * BlockEntityTickerBridge}), and M12 event-bus domain routing ({@link
 * net.multiforge.neoforge.event.EventBusBridge}). Also materialises a
 * regionizer for every level up front so no level falls back to inline
 * ticking for lack of one.
 *
 * <p>Called once per fresh runtime install from {@code
 * ServerLifecycleHooks.handleServerAboutToStart}. World-wide Vanilla
 * systems — weather, time, world border, raids, the dragon fight, boss
 * bars — need no binding: they run in {@code ServerLevel.tick} on the
 * server thread, which never overlaps region work (see {@link
 * RegionizedTickCoordinator}).
 */
public final class RegionRuntimeInit {
    private static final java.util.concurrent.atomic.AtomicBoolean LEVEL_LISTENERS_INSTALLED = new java.util.concurrent.atomic.AtomicBoolean();

    private RegionRuntimeInit() {}

    public static void install(MultiThreadedSchedulerHost host, MinecraftServer server) {
        RegionizerEagerInit.materialiseAll(server, host);

        ScheduledTickRunnerBridge.installOnEventBus();
        host.setBlockFluidRunner(new ScheduledTickRunnerBridge());
        host.setEntityTickRunner(new EntityTickRunnerBridge(host, server));
        installBlockEntityTickerBridgeListeners();

        boolean eventBusAttached = net.multiforge.neoforge.event.EventBusBridge.attach(NeoForge.EVENT_BUS, host);
        if (!eventBusAttached) {
            ViolationLogger.warn(
                    "RegionRuntimeInit",
                    "failed to attach SchedulerBackedDispatchExecutor onto NeoForge.EVENT_BUS "
                            + "(not a LazyDispatchingEventBus); events will dispatch without M12 domain routing");
        }
    }

    private static void installBlockEntityTickerBridgeListeners() {
        // Once per JVM: a GameTestServer JVM installs a fresh runtime per server.
        if (!LEVEL_LISTENERS_INSTALLED.compareAndSet(false, true)) return;
        NeoForge.EVENT_BUS.addListener((LevelEvent.Load event) -> {
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            BlockEntityTickerBridge.installOnLevel(level);
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            BlockEntityTickerBridge.uninstallLevel(level);
        });
    }
}
