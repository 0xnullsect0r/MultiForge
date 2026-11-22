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
package net.multiforge.neoforge.network;

import com.google.common.net.InetAddresses;
import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.multiforge.runtime.chunk.InstanceRegistry;
import net.multiforge.runtime.config.ConfigCodec;
import net.multiforge.runtime.config.IpRange;
import net.multiforge.runtime.config.NetworkConfig;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.VisibleForTesting;
import org.slf4j.Logger;

/**
 * Inbound HAProxy PROXY protocol ({@code [network]} in {@code
 * multiforge-server.toml}): a server behind a TCP proxy sees each player's real
 * address in logins, IP bans and {@code /ban-ip}.
 *
 * <p>The peer is checked before a single byte is read. An untrusted peer is
 * closed, so it can neither forge an address nor bypass the proxy. The close
 * happens after {@code channelActive} has reached {@link Connection}: a
 * connection that never saw it has no channel, and {@code
 * ServerConnectionListener.tick} would keep it in its list forever.
 *
 * <p>The one exception is a loopback peer not listed as a proxy, such as a server
 * panel on the same machine checking the player count. It may complete a status
 * ping without a header. Anything else it sends, including a login, closes it.
 *
 * <p>Everything runs on the connection's Netty thread. The address is replaced
 * before the first Minecraft packet is decoded.
 */
@ApiStatus.Internal
public final class ProxyProtocol {
    static final String GATE = "multiforge_proxy_gate";
    static final String DECODER = "multiforge_proxy_decoder";
    static final String MESSAGE = "multiforge_proxy_message";
    static final String LOCAL_STATUS = "multiforge_proxy_local_status";
    private static final String PACKET_HANDLER = "packet_handler";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final InstanceRegistry<MinecraftServer, List<IpRange>> ACTIVE = InstanceRegistry.weak();

    private ProxyProtocol() {}

    /**
     * Server thread, before the port binds: read {@code [network]}. Failing here
     * stops the server, because starting without the setting would either accept
     * forged addresses or lock out every player behind the proxy.
     */
    public static void prepare(MinecraftServer server) {
        if (!server.isDedicatedServer()) return;
        Path file = server.getServerDirectory().toAbsolutePath().resolve("config").resolve("multiforge-server.toml");
        NetworkConfig network;
        try {
            network = ConfigCodec.load(file).effectiveNetwork();
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot read the [network] settings of " + file + ": " + e.getMessage(), e);
        }
        if (!network.proxyProtocol()) {
            ACTIVE.unregister(server);
            return;
        }
        if (network.trustedProxies().isEmpty()) {
            LOGGER.error("network.proxyProtocol is on but trustedProxies is empty: every connection will be closed");
        } else {
            LOGGER.info("PROXY protocol on; trusted proxies: {}", network.trustedProxies());
        }
        ACTIVE.register(server, network.ranges());
    }

    /** {@code initChannel}, straight after the read timeout: guard this channel if the protocol is on. */
    public static void install(ChannelPipeline pipeline, MinecraftServer server) {
        List<IpRange> trusted = ACTIVE.of(server).orElse(null);
        if (trusted != null) pipeline.addLast(GATE, new Gate(trusted));
    }

    /** The handler that admits or closes a peer. Tests install it on an embedded channel. */
    @VisibleForTesting
    public static ChannelInboundHandlerAdapter gate(NetworkConfig network) {
        return new Gate(network.ranges());
    }

    /** The name {@link #gate} must be added under. */
    @VisibleForTesting
    public static String gateName() {
        return GATE;
    }

    private static boolean isTrusted(List<IpRange> trusted, InetAddress address) {
        for (IpRange range : trusted) {
            if (range.contains(address)) return true;
        }
        return false;
    }

    private static void reject(ChannelHandlerContext ctx, String site, String detail) {
        ViolationLogger.warn(site, "closed connection from " + ctx.channel().remoteAddress() + ": " + detail);
        ctx.close();
    }

    /** First handler of a guarded channel: replaces itself with the decoder once the peer is trusted. */
    private static final class Gate extends ChannelInboundHandlerAdapter {
        private final List<IpRange> trusted;

        Gate(List<IpRange> trusted) {
            this.trusted = trusted;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            ctx.fireChannelActive();
            SocketAddress peer = ctx.channel().remoteAddress();
            ChannelPipeline pipeline = ctx.pipeline();
            if (!(peer instanceof InetSocketAddress socket) || !isTrusted(trusted, socket.getAddress())) {
                if (peer instanceof InetSocketAddress local && local.getAddress().isLoopbackAddress()) {
                    pipeline.addAfter(ctx.name(), LOCAL_STATUS, new LocalStatusOnly());
                    pipeline.remove(this);
                    return;
                }
                reject(ctx, "network.proxy.untrusted", "not in network.trustedProxies");
                return;
            }
            pipeline.addAfter(ctx.name(), DECODER, new HAProxyMessageDecoder());
            pipeline.addAfter(DECODER, MESSAGE, new MessageHandler());
            pipeline.remove(this);
        }
    }

    /**
     * Admits a loopback peer only if its first packet is a handshake asking for
     * status. The packet is left in the buffer and reaches the game unchanged. A
     * status connection cannot switch to login, so nothing else needs guarding.
     */
    private static final class LocalStatusOnly extends ByteToMessageDecoder {
        private static final int MAX_HANDSHAKE_BYTES = 1100;
        private static final int MAX_HOST_BYTES = 1020;
        private static final int LEGACY_PING = 0xFE;
        private static final int NEXT_STATE_STATUS = 1;
        private static final long INCOMPLETE = -1;
        private static final long MALFORMED = -2;

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (!in.isReadable()) return;
            if (in.getUnsignedByte(in.readerIndex()) == LEGACY_PING) {
                refuse(ctx, in);
                return;
            }
            ByteBuf view = in.slice();
            long length = readVarInt(view);
            if (length == INCOMPLETE) return;
            if (length <= 0 || length > MAX_HANDSHAKE_BYTES) {
                refuse(ctx, in);
                return;
            }
            if (view.readableBytes() < length) return;
            if (isStatusHandshake(view.readSlice((int) length))) {
                ctx.pipeline().remove(this);
            } else {
                refuse(ctx, in);
            }
        }

        private static void refuse(ChannelHandlerContext ctx, ByteBuf in) {
            in.skipBytes(in.readableBytes());
            reject(ctx, "network.proxy.local", "a local connection without a PROXY header may only read server status");
        }

        private static boolean isStatusHandshake(ByteBuf packet) {
            if (readVarInt(packet) != 0) return false;
            if (readVarInt(packet) < 0) return false; // protocol version; pingers may send -1
            long host = readVarInt(packet);
            if (host < 0 || host > MAX_HOST_BYTES || packet.readableBytes() < host + Short.BYTES) return false;
            packet.skipBytes((int) host + Short.BYTES);
            return readVarInt(packet) == NEXT_STATE_STATUS && !packet.isReadable();
        }

        /** The VarInt's 32 bits as an unsigned value, or {@link #INCOMPLETE} / {@link #MALFORMED}. */
        private static long readVarInt(ByteBuf buf) {
            long value = 0;
            for (int i = 0; i < 5; i++) {
                if (!buf.isReadable()) return INCOMPLETE;
                int b = buf.readUnsignedByte();
                value |= (long) (b & 0x7F) << (7 * i);
                if ((b & 0x80) == 0) return value & 0xFFFFFFFFL;
            }
            return MALFORMED;
        }
    }

    /** Applies the header, then leaves the pipeline. The decoder forwards any bytes that followed it. */
    private static final class MessageHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof HAProxyMessage header)) {
                ReferenceCountUtil.release(msg);
                reject(ctx, "network.proxy.malformed", "no PROXY protocol header");
                return;
            }
            try {
                if (!(ctx.pipeline().get(PACKET_HANDLER) instanceof Connection connection)) {
                    reject(ctx, "network.proxy.malformed", "no packet handler");
                    return;
                }
                if (carriesClientAddress(header)) {
                    InetAddress ip = InetAddresses.forString(header.sourceAddress());
                    connection.mfSetRemoteAddress(new InetSocketAddress(ip, header.sourcePort()));
                }
                ctx.pipeline().remove(this);
            } catch (IllegalArgumentException e) {
                reject(ctx, "network.proxy.malformed", "invalid client address in header");
            } finally {
                ReferenceCountUtil.release(header);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // Kept from Connection's fault handler: there is no protocol to answer in yet.
            reject(ctx, "network.proxy.malformed", "invalid PROXY protocol header");
        }

        private static boolean carriesClientAddress(HAProxyMessage header) {
            HAProxyProxiedProtocol protocol = header.proxiedProtocol();
            return header.command() == HAProxyCommand.PROXY
                    && (protocol == HAProxyProxiedProtocol.TCP4 || protocol == HAProxyProxiedProtocol.TCP6);
        }
    }
}
