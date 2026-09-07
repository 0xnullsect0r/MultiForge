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
package net.multiforge.neoforge.debug;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.MultiForge;
import net.multiforge.runtime.config.MultiForgeConfig;
import net.multiforge.runtime.diagnostics.emitters.HeartbeatEmitter;
import net.multiforge.runtime.diagnostics.emitters.PermissionFilter;
import net.multiforge.runtime.diagnostics.emitters.PinListEmitter;
import net.multiforge.runtime.diagnostics.emitters.PlayerRef;
import net.multiforge.runtime.diagnostics.emitters.RegionMapEmitter;
import net.multiforge.runtime.diagnostics.emitters.TpsHistogramEmitter;
import net.multiforge.runtime.diagnostics.emitters.ViolationEmitter;
import net.multiforge.runtime.diagnostics.wire.DebugPacketCodec;
import net.multiforge.runtime.diagnostics.wire.DebugPacketKind;
import net.multiforge.runtime.diagnostics.wire.DebugPayload;
import net.multiforge.runtime.region.pin.RegionPinManager;
import net.multiforge.runtime.scheduler.MultiForgeRegionizedRuntime;
import net.multiforge.runtime.scheduler.MultiThreadedSchedulerHost;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server side of the {@code multiforge:debug/v1} channel.
 *
 * <p>The client mod ({@code multiforge-client}) registers the same
 * channel client-side and populates the F3 HUD / chunk-border /
 * heatmap / pin renderers from frames the server pushes here. Prior
 * to v1.3.14 the server never registered the channel or instantiated
 * any emitter — every overlay stayed inert. This class closes that
 * gap:
 *
 * <ul>
 * <li>Mod-bus hook — registers the payload channel as OPTIONAL (so a
 * client without the mod still connects) and wires an inbound handler
 * for {@code SUBSCRIBE} frames.
 * <li>Game-bus hooks — instantiates the runtime's five emitters on
 * {@code ServerAboutToStart}, tears them down on {@code
 * ServerStopping}, and sends the unconditional {@code HELLO} frame on
 * {@code PlayerLoggedIn}.
 * <li>Sink — every emitter feeds a single {@link #broadcast(DebugPayload)}
 * method that fans out to all connected players whose subscription
 * mask enables the frame's stream.
 * </ul>
 *
 * <p>Idempotent per JVM. Everything is per-server state that's cleared
 * on {@code ServerStopping} so a reused {@code GameTestServer} JVM
 * cycles cleanly.
 */
public final class DebugChannelServer {
    private static final Logger LOGGER = LoggerFactory.getLogger("multiforge.debug");

    private static final AtomicBoolean MOD_BUS_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean GAME_BUS_INSTALLED = new AtomicBoolean(false);

    /** Player UUID → subscription mask (F_REGIONS | F_HEATMAP | F_PINS | F_VIOLATIONS). */
    private static final Map<UUID, Integer> SUBSCRIPTIONS = new ConcurrentHashMap<>();

    /** Player UUID → connection handle. */
    private static final Map<UUID, ServerPlayer> PLAYERS = new ConcurrentHashMap<>();

    /** Per-world heatmap emitter — one per active dimension. */
    private static final Map<String, AutoCloseable> HEATMAPS_BY_WORLD = new ConcurrentHashMap<>();

    /** All installed emitter handles for the current server instance. */
    private static final List<AutoCloseable> INSTALLED = new ArrayList<>();

    private static HeartbeatEmitter heartbeat;

    private DebugChannelServer() {}

    /** Called from NeoForgeMod's constructor with the NeoForge mod bus. */
    public static void installOnModBus(IEventBus modBus) {
        if (!MOD_BUS_INSTALLED.compareAndSet(false, true)) return;
        modBus.addListener(DebugChannelServer::onRegisterPayloadHandlers);
    }

    /** Called from ServerLifecycleHooks (game-bus side). Idempotent. */
    public static void installGameBusHooks() {
        if (!GAME_BUS_INSTALLED.compareAndSet(false, true)) return;
        NeoForge.EVENT_BUS.addListener(DebugChannelServer::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(DebugChannelServer::onServerStopping);
        NeoForge.EVENT_BUS.addListener(DebugChannelServer::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(DebugChannelServer::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(DebugChannelServer::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(DebugChannelServer::onLevelUnload);
    }

    // ------------------------------------------------------------------
    // Mod bus
    // ------------------------------------------------------------------

    private static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        // Optional matches the client (v1.3.13): a client without the
        // multiforge_debug mod can still connect to this server.
        event.registrar("1")
                .optional()
                .playBidirectional(
                        DebugFramePayload.TYPE,
                        DebugFramePayload.STREAM_CODEC,
                        (payload, context) -> handleClientFrame(context.player(), payload.data()));
        LOGGER.info("multiforge:debug/v1 server-side payload channel registered (optional)");
    }

    // ------------------------------------------------------------------
    // Server lifecycle
    // ------------------------------------------------------------------

    private static void onServerAboutToStart(ServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) {
            LOGGER.warn("multiforge:debug/v1 — no scheduler host on ServerAboutToStart; channel disabled this boot");
            return;
        }

        String buildLabel = MultiForge.VERSION;

        // Track a raw HeartbeatEmitter reference so we can call
        // helloForNewSubscriber(...) on player login. The install()
        // helper only returns the AutoCloseable, so build one via the
        // ctor and add its ScheduledFuture separately.
        heartbeat = new HeartbeatEmitter(DebugPacketCodec.PROTOCOL_VERSION, 20, buildLabel, DebugChannelServer::broadcast);
        var heartbeatFuture = host.scheduleGlobal(heartbeat::emit, HeartbeatEmitter.PERIOD_MILLIS);
        INSTALLED.add(() -> heartbeatFuture.cancel(false));

        INSTALLED.add(RegionMapEmitter.install(host, DebugChannelServer::broadcast, PermissionFilter.ALWAYS_ALLOW));
        INSTALLED.add(PinListEmitter.install(
                host, loadPins(server), DebugChannelServer::broadcast, PermissionFilter.ALWAYS_ALLOW));
        INSTALLED.add(ViolationEmitter.install(host, DebugChannelServer::broadcast, PermissionFilter.ALWAYS_ALLOW));

        LOGGER.info("multiforge:debug/v1 emitters installed (heartbeat + region-map + pin-list + violations)");
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        for (AutoCloseable c : INSTALLED) {
            try {
                c.close();
            } catch (Throwable t) {
                LOGGER.warn("multiforge:debug/v1 — emitter close threw: {}", t.toString());
            }
        }
        INSTALLED.clear();
        for (AutoCloseable c : HEATMAPS_BY_WORLD.values()) {
            try {
                c.close();
            } catch (Throwable t) {
                // ignore
            }
        }
        HEATMAPS_BY_WORLD.clear();
        PLAYERS.clear();
        SUBSCRIPTIONS.clear();
        heartbeat = null;
    }

    private static void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        MultiThreadedSchedulerHost host = MultiForgeRegionizedRuntime.current();
        if (host == null) return;
        String dimId = level.dimension().location().toString();
        AutoCloseable heatmap = TpsHistogramEmitter.install(
                host, WorldRef.of(dimId), DebugChannelServer::broadcast, PermissionFilter.ALWAYS_ALLOW);
        HEATMAPS_BY_WORLD.put(dimId, heatmap);
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        String dimId = level.dimension().location().toString();
        AutoCloseable prev = HEATMAPS_BY_WORLD.remove(dimId);
        if (prev != null) {
            try {
                prev.close();
            } catch (Throwable t) {
                // ignore
            }
        }
    }

    // ------------------------------------------------------------------
    // Player lifecycle
    // ------------------------------------------------------------------

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PLAYERS.put(player.getUUID(), player);
        SUBSCRIPTIONS.put(player.getUUID(), 0); // waits for the client to send SUBSCRIBE

        // Unconditional HELLO frame — protocol §3. Client uses it to
        // populate the "MultiForge build=… proto=… tickHz=…" HUD line.
        if (heartbeat != null) {
            PlayerRef ref = new PlayerRef(player.getUUID(), player.getName().getString());
            heartbeat.helloForNewSubscriber(ref);
            // helloForNewSubscriber() dispatches through the shared
            // sink (broadcast) — but at that point SUBSCRIPTIONS.put has
            // seeded a 0 mask for this player, and HELLO's flag=0xFF
            // matches any player who's in PLAYERS. Result: the just-joined
            // player receives HELLO. Other players ignore it (they've
            // already had their HELLO).
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PLAYERS.remove(player.getUUID());
        SUBSCRIPTIONS.remove(player.getUUID());
    }

    // ------------------------------------------------------------------
    // Sink + inbound frame handling
    // ------------------------------------------------------------------

    /**
     * Emitter sink. Fan out {@code payload} to every player whose
     * subscription mask enables its stream. HELLO is unconditional
     * (flag = 0xFF) — every connected player gets it.
     */
    private static void broadcast(DebugPayload payload) {
        int flag = flagFor(payload);
        if (flag == 0) return; // unknown payload type — drop
        byte[] frame;
        try {
            frame = encode(payload);
        } catch (Throwable t) {
            LOGGER.warn("multiforge:debug/v1 — encode failed for {}: {}", payload.getClass().getSimpleName(), t.toString());
            return;
        }
        DebugFramePayload wrapped = new DebugFramePayload(frame);
        for (Map.Entry<UUID, ServerPlayer> entry : PLAYERS.entrySet()) {
            int mask = SUBSCRIPTIONS.getOrDefault(entry.getKey(), 0);
            if ((mask & flag) == 0 && flag != 0xFF) continue;
            try {
                PacketDistributor.sendToPlayer(entry.getValue(), wrapped);
            } catch (Throwable t) {
                LOGGER.warn(
                        "multiforge:debug/v1 — send to {} failed: {}",
                        entry.getValue().getName().getString(),
                        t.toString());
            }
        }
    }

    private static int flagFor(DebugPayload payload) {
        if (payload instanceof DebugPayload.Hello) return 0xFF;
        if (payload instanceof DebugPayload.RegionSnapshot) return DebugPayload.Subscribe.F_REGIONS;
        if (payload instanceof DebugPayload.HeatmapUpdate) return DebugPayload.Subscribe.F_HEATMAP;
        if (payload instanceof DebugPayload.PinList) return DebugPayload.Subscribe.F_PINS;
        if (payload instanceof DebugPayload.ViolationEvent) return DebugPayload.Subscribe.F_VIOLATIONS;
        return 0;
    }

    private static byte[] encode(DebugPayload payload) {
        if (payload instanceof DebugPayload.Hello h) return DebugPacketCodec.encodeHello(h);
        if (payload instanceof DebugPayload.RegionSnapshot s) return DebugPacketCodec.encodeRegionSnapshot(s);
        if (payload instanceof DebugPayload.HeatmapUpdate u) return DebugPacketCodec.encodeHeatmap(u);
        if (payload instanceof DebugPayload.PinList l) return DebugPacketCodec.encodePinList(l);
        if (payload instanceof DebugPayload.ViolationEvent e) return DebugPacketCodec.encodeViolation(e);
        throw new IllegalArgumentException("unknown DebugPayload subtype: " + payload.getClass().getName());
    }

    /**
     * Handle an inbound frame from a client. Per protocol §1 the only
     * frame kind we ever expect here is SUBSCRIBE — a client's opt-in
     * mask.
     */
    private static void handleClientFrame(net.minecraft.world.entity.player.Player player, byte[] raw) {
        if (!(player instanceof ServerPlayer sp)) return;
        try {
            DebugPacketCodec.Frame frame = DebugPacketCodec.readFrame(raw);
            if (frame.kind() != DebugPacketKind.SUBSCRIBE) return; // ignore unexpected
            DebugPayload.Subscribe sub = DebugPacketCodec.decodeSubscribe(frame.body());
            int mask = sub.flags() & 0x0F; // strip unknown bits per §5
            SUBSCRIPTIONS.put(sp.getUUID(), mask);
            LOGGER.info(
                    "multiforge:debug/v1 — SUBSCRIBE from {} (mask=0x{})",
                    sp.getName().getString(),
                    Integer.toHexString(mask));
        } catch (IOException | RuntimeException t) {
            LOGGER.warn(
                    "multiforge:debug/v1 — dropped malformed inbound frame from {}: {}",
                    player.getName().getString(),
                    t.toString());
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static RegionPinManager loadPins(MinecraftServer server) {
        Path pinsFile = server.getServerDirectory()
                .toAbsolutePath()
                .resolve("config")
                .resolve("multiforge-region-pins.json");
        try {
            Files.createDirectories(pinsFile.getParent());
            return RegionPinManager.load(pinsFile);
        } catch (IOException e) {
            LOGGER.warn(
                    "multiforge:debug/v1 — failed to load {}, using empty pin manager: {}", pinsFile, e.getMessage());
            return new RegionPinManager(pinsFile);
        }
    }

    /**
     * Kept for future callers that want to instantiate a fresh
     * {@link MultiForgeConfig} for testing without wiring the full
     * pipeline. Currently unused — {@link #onServerAboutToStart}
     * pulls the live host directly.
     */
    @SuppressWarnings("unused")
    private static MultiForgeConfig defaults() {
        return MultiForgeConfig.defaults();
    }
}
