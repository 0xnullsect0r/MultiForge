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
package net.multiforge.bench.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.tcp.TcpClientSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility;
import org.geysermc.mcprotocollib.protocol.data.game.setting.SkinPart;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;

/**
 * Real Minecraft protocol clients for the swarm bench, built on MCProtocolLib
 * (MIT). Each bot is an offline-mode player: it logs in over the game port,
 * goes through the configuration phase, confirms the server's teleports, and
 * then, every client tick (50 ms), walks a random heading and every few
 * seconds places a block and breaks it again.
 *
 * <p>Walking is a straight-line random walk at player speed with no client
 * physics: the bench runs with {@code allow-flight=true}, so a bot that walks
 * off an edge hovers instead of being kicked, and one that walks into terrain
 * is corrected by the server with a teleport the bot accepts, as a real client
 * would; it then turns and steps up a block so it climbs rather than pushing
 * into the same wall. Block work needs creative mode (instant breaking and an
 * inexhaustible stack), which {@link SwarmBench} grants over RCON after the
 * bots join. Placing clicks the block two below the feet: when that is air
 * the dirt lands there, when it is ground the dirt lands on top of it; the
 * next action breaks both candidates, so the swarm keeps the world churning
 * without walling itself in.
 *
 * <p>Everything a bot receives is decoded by MCProtocolLib (chunks, entities,
 * light) on its netty event loop, so client-side cost scales with the bot
 * count; the {@code renderDistance} a bot reports keeps that bounded.
 */
public final class BotSwarm implements AutoCloseable {
    private static final double STEP = 0.2158; // blocks per tick: vanilla walking speed
    private static final int ACTION_PERIOD_TICKS = 60;

    private final String host;
    private final int port;
    private final int renderDistance;
    private final List<Bot> bots = new ArrayList<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "bench-bot-ticker");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger joined = new AtomicInteger();
    private final AtomicLong blocksPlaced = new AtomicLong();
    private final AtomicLong blocksBroken = new AtomicLong();
    private final AtomicLong teleportsAccepted = new AtomicLong();
    private final AtomicLong corrections = new AtomicLong();
    private final Map<String, String> disconnects = new ConcurrentHashMap<>();
    private volatile boolean closing;
    private volatile boolean walking = true;

    public BotSwarm(String host, int port, int renderDistance) {
        this.host = host;
        this.port = port;
        this.renderDistance = renderDistance;
    }

    /**
     * Connect {@code count} bots named {@code <prefix>0..}, {@code perSecond} at
     * a time, and start the shared 50 ms client tick. Returns once every
     * connection attempt has been made; use {@link #awaitJoined} to wait for
     * logins to finish.
     */
    public void connect(String prefix, int count, int perSecond) throws InterruptedException {
        long gapMs = Math.max(1, 1000 / Math.max(1, perSecond));
        for (int i = 0; i < count; i++) {
            Bot bot = new Bot(prefix + i, new Random(0x5eedL * 31 + i));
            bots.add(bot);
            bot.connect();
            Thread.sleep(gapMs);
        }
        ticker.scheduleAtFixedRate(this::tickAll, 50, 50, TimeUnit.MILLISECONDS);
    }

    /** Wait until {@code count} bots are in the world or {@code timeoutMs} passes; returns how many are. */
    public int awaitJoined(int count, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (joined.get() < count
                && System.currentTimeMillis() < deadline
                && disconnects.size() + joined.get() < count) {
            Thread.sleep(200);
        }
        return joined.get();
    }

    /** Whether bots walk each tick (default) or stand where the server put them. */
    public void setWalking(boolean walking) {
        this.walking = walking;
    }

    /** Let bots start placing and breaking blocks (after they were made creative). */
    public void enableBlockWork() {
        for (Bot bot : bots) bot.blockWork = true;
    }

    public List<String> names() {
        return bots.stream().map(b -> b.name).toList();
    }

    public int joinedCount() {
        return joined.get();
    }

    /** Bots whose session is still open. */
    public int connectedCount() {
        return (int) bots.stream().filter(b -> b.session.isConnected()).count();
    }

    public long blocksPlaced() {
        return blocksPlaced.get();
    }

    public long blocksBroken() {
        return blocksBroken.get();
    }

    public long teleportsAccepted() {
        return teleportsAccepted.get();
    }

    /** Server position corrections after joining (walked into terrain, or moved too far in one step). */
    public long corrections() {
        return corrections.get();
    }

    /** Bot name to disconnect reason, for every bot the server dropped (not ones {@link #close} closed). */
    public Map<String, String> disconnects() {
        return Map.copyOf(disconnects);
    }

    private void tickAll() {
        for (Bot bot : bots) {
            try {
                bot.tick();
            } catch (RuntimeException e) {
                // one bot's bad state must not stop the shared ticker
                System.err.println("bench: bot " + bot.name + " tick failed: " + e);
            }
        }
    }

    @Override
    public void close() {
        closing = true;
        ticker.shutdownNow();
        for (Bot bot : bots) {
            if (bot.session.isConnected()) bot.session.disconnect(Component.text("bench finished"));
        }
    }

    private final class Bot extends SessionAdapter {
        final String name;
        final Random rnd;
        final TcpClientSession session;
        volatile boolean inWorld;
        volatile boolean blockWork;
        // Position is written by the netty thread (teleports) and the ticker; guarded by `this`.
        double x, y, z;
        double heading;
        int ticks;
        int sequence;
        boolean placedLast;
        Vector3i lastTarget;

        Bot(String name, Random rnd) {
            this.name = name;
            this.rnd = rnd;
            this.session = new TcpClientSession(host, port, new MinecraftProtocol(name));
            this.session.addListener(this);
            this.heading = rnd.nextDouble() * Math.PI * 2;
            this.ticks = rnd.nextInt(ACTION_PERIOD_TICKS);
        }

        void connect() {
            session.connect(false);
        }

        @Override
        public void packetReceived(Session s, Packet packet) {
            if (packet instanceof ClientboundPingPacket ping) {
                // A vanilla client answers every ping; NeoForge opens configuration
                // with ping 0 and waits for the pong before it configures the client.
                s.send(new ServerboundPongPacket(ping.getId()));
            } else if (packet instanceof ClientboundLoginPacket) {
                s.send(new ServerboundClientInformationPacket(
                        "en_us",
                        renderDistance,
                        ChatVisibility.FULL,
                        true,
                        List.of(SkinPart.values()),
                        HandPreference.RIGHT_HAND,
                        false,
                        true));
            } else if (packet instanceof ClientboundPlayerPositionPacket pos) {
                synchronized (this) {
                    List<PositionElement> rel = pos.getRelative();
                    x = rel.contains(PositionElement.X) ? x + pos.getX() : pos.getX();
                    y = rel.contains(PositionElement.Y) ? y + pos.getY() : pos.getY();
                    z = rel.contains(PositionElement.Z) ? z + pos.getZ() : pos.getZ();
                    s.send(new ServerboundAcceptTeleportationPacket(pos.getTeleportId()));
                    s.send(new ServerboundMovePlayerPosRotPacket(false, x, y, z, pos.getYaw(), pos.getPitch()));
                    if (inWorld) {
                        // A correction: the server refused a step into terrain. Turn,
                        // and hop up so the bot climbs slopes instead of pushing
                        // into the same wall every tick.
                        heading = rnd.nextDouble() * Math.PI * 2;
                        y += 1.0;
                        corrections.incrementAndGet();
                    }
                }
                teleportsAccepted.incrementAndGet();
                if (!inWorld) {
                    inWorld = true;
                    joined.incrementAndGet();
                }
            }
        }

        @Override
        public void disconnected(DisconnectedEvent event) {
            if (inWorld) joined.decrementAndGet();
            inWorld = false;
            if (!closing) {
                String reason = event.getReason() == null
                        ? "(no reason)"
                        : GsonComponentSerializer.gson().serialize(event.getReason());
                if (event.getCause() != null) reason += " / " + event.getCause();
                disconnects.put(name, reason);
            }
        }

        synchronized void tick() {
            if (!inWorld || !session.isConnected()) return;
            ticks++;
            if (walking) {
                if (rnd.nextInt(40) == 0) heading += (rnd.nextDouble() - 0.5) * Math.PI;
                x += Math.cos(heading) * STEP;
                z += Math.sin(heading) * STEP;
            }
            session.send(new ServerboundMovePlayerPosPacket(false, x, y, z));

            if (blockWork && ticks % ACTION_PERIOD_TICKS == 0) {
                if (!placedLast) {
                    Vector3i target = Vector3i.from((int) Math.floor(x), (int) Math.floor(y) - 2, (int) Math.floor(z));
                    session.send(new ServerboundUseItemOnPacket(
                            target, Direction.UP, Hand.MAIN_HAND, 0.5f, 1.0f, 0.5f, false, ++sequence));
                    session.send(new ServerboundSwingPacket(Hand.MAIN_HAND));
                    lastTarget = target;
                    blocksPlaced.incrementAndGet();
                } else if (lastTarget != null) {
                    for (Vector3i p : List.of(lastTarget.add(0, 1, 0), lastTarget)) {
                        session.send(new ServerboundPlayerActionPacket(
                                PlayerAction.START_DIGGING, p, Direction.UP, ++sequence));
                    }
                    session.send(new ServerboundSwingPacket(Hand.MAIN_HAND));
                    blocksBroken.incrementAndGet();
                }
                placedLast = !placedLast;
            }
        }
    }
}
