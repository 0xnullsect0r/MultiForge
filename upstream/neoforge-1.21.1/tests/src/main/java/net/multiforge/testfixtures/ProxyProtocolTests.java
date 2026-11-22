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
package net.multiforge.testfixtures;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.players.IpBanList;
import net.minecraft.server.players.IpBanListEntry;
import net.multiforge.neoforge.network.ProxyProtocol;
import net.multiforge.runtime.config.NetworkConfig;
import net.neoforged.neoforge.eventtest.internal.TestsMod;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;

/**
 * GameTests for inbound PROXY protocol ({@code ProxyProtocol}, {@code
 * multiforge-patches/06-networking/}), on an embedded channel with the same
 * handler order as a real one. Live connections are covered by the bench.
 */
@ForEachTest(groups = "multiforge.network")
public class ProxyProtocolTests {
    private static final byte[] SIGNATURE = { 0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A };
    private static final NetworkConfig TRUST_PROXY = new NetworkConfig(true, List.of("10.83.6.250/32"));
    private static final byte[] PAYLOAD = { 1, 2, 3, 4 };

    /** An embedded channel with a real peer address, which the trust check reads. */
    private static final class PeerChannel extends EmbeddedChannel {
        private final SocketAddress peer;

        PeerChannel(SocketAddress peer, ChannelInitializer<Channel> handlers) throws Exception {
            super(false, true, handlers);
            this.peer = peer;
            register();
        }

        @Override
        protected SocketAddress remoteAddress0() {
            return peer;
        }
    }

    /** Collects the bytes that reach the Minecraft pipeline after the header. */
    private static final class Capture extends ChannelInboundHandlerAdapter {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            try {
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                bytes.writeBytes(data);
            } finally {
                buf.release();
            }
        }
    }

    private record Rig(PeerChannel channel, Connection connection, Capture capture) {
        void receive(byte[] data) {
            channel.writeInbound(Unpooled.wrappedBuffer(data));
            channel.runPendingTasks();
        }

        /** Closes without {@code finish}, which rethrows the close {@link Connection} reports on a mock channel. */
        void dispose() {
            channel.close();
            channel.runPendingTasks();
            channel.releaseInbound();
            channel.releaseOutbound();
        }
    }

    private static Rig rig(String peerIp) throws Exception {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        Capture capture = new Capture();
        PeerChannel channel = new PeerChannel(new InetSocketAddress(InetAddress.getByName(peerIp), 40000), new ChannelInitializer<>() {
            @Override
            protected void initChannel(Channel ch) {
                ch.pipeline().addLast(ProxyProtocol.gateName(), ProxyProtocol.gate(TRUST_PROXY));
                ch.pipeline().addLast("capture", capture);
                ch.pipeline().addLast("packet_handler", connection);
            }
        });
        channel.runPendingTasks();
        return new Rig(channel, connection, capture);
    }

    private static byte[] v2(int command, String client, int clientPort, String server) throws Exception {
        byte[] src = InetAddress.getByName(client).getAddress();
        byte[] dst = InetAddress.getByName(server).getAddress();
        ByteBuffer body = ByteBuffer.allocate(src.length + dst.length + 4);
        body.put(src).put(dst).putShort((short) clientPort).putShort((short) 25565);
        ByteBuffer out = ByteBuffer.allocate(SIGNATURE.length + 4 + body.capacity());
        out.put(SIGNATURE).put((byte) command).put((byte) (src.length == 4 ? 0x11 : 0x21));
        out.putShort((short) body.capacity()).put(body.array());
        return out.array();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static void varInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    /** A framed Minecraft handshake packet asking for {@code nextState}. */
    private static byte[] handshake(int protocol, int nextState) {
        byte[] host = "127.0.0.1".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        varInt(body, 0);
        varInt(body, protocol);
        varInt(body, host.length);
        body.writeBytes(host);
        body.write(25565 >> 8);
        body.write(25565 & 0xFF);
        varInt(body, nextState);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        varInt(frame, body.size());
        frame.writeBytes(body.toByteArray());
        return frame.toByteArray();
    }

    private static final byte[] STATUS_REQUEST = { 1, 0 };

    private static void assertClient(GameTestHelper helper, Rig rig, String ip, int port) throws Exception {
        InetSocketAddress expected = new InetSocketAddress(InetAddress.getByName(ip), port);
        helper.assertTrue(expected.equals(rig.connection().getRemoteAddress()),
                "remote address is " + rig.connection().getRemoteAddress() + ", expected " + expected);
    }

    private static void assertClosedAndDrainable(GameTestHelper helper, Rig rig) {
        helper.assertFalse(rig.channel().isOpen(), "the connection was left open");
        helper.assertFalse(rig.connection().isConnected(), "the Connection still reports connected");
        // ServerConnectionListener.tick only removes a closed connection that saw channelActive.
        helper.assertFalse(rig.connection().isConnecting(), "a rejected Connection would stay listed forever");
        helper.assertTrue(rig.capture().bytes.size() == 0, "bytes from a rejected peer reached the game");
    }

    private interface Body {
        void run(GameTestHelper helper) throws Exception;
    }

    private static void run(DynamicTest test, Body body) {
        test.onGameTest(helper -> {
            try {
                body.run(helper);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            helper.succeed();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = "A v2 header from a trusted proxy sets the client's address, and the bytes after it reach the game.")
    static void v2HeaderSetsTheClientAddress(final DynamicTest test) {
        run(test, helper -> {
            Rig rig = rig("10.83.6.250");
            rig.receive(concat(v2(0x21, "203.0.113.9", 4444, "10.83.6.251"), PAYLOAD));
            assertClient(helper, rig, "203.0.113.9", 4444);
            helper.assertTrue(java.util.Arrays.equals(rig.capture().bytes.toByteArray(), PAYLOAD), "payload was not forwarded");
            helper.assertTrue(rig.channel().pipeline().get(ProxyProtocol.gateName()) == null, "the gate stayed installed");
            rig.dispose();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = "A v1 text header from a trusted proxy sets the client's address.")
    static void v1HeaderSetsTheClientAddress(final DynamicTest test) {
        run(test, helper -> {
            Rig rig = rig("10.83.6.250");
            byte[] header = "PROXY TCP4 203.0.113.10 10.83.6.251 4445 25565\r\n".getBytes(StandardCharsets.US_ASCII);
            rig.receive(concat(header, PAYLOAD));
            assertClient(helper, rig, "203.0.113.10", 4445);
            helper.assertTrue(java.util.Arrays.equals(rig.capture().bytes.toByteArray(), PAYLOAD), "payload was not forwarded");
            rig.dispose();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = "An IPv6 client address is applied.")
    static void ipv6ClientAddress(final DynamicTest test) {
        run(test, helper -> {
            Rig rig = rig("10.83.6.250");
            rig.receive(v2(0x21, "2001:db8::77", 7778, "2001:db8::1"));
            assertClient(helper, rig, "2001:db8::77", 7778);
            rig.dispose();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = "A header split across reads is reassembled.")
    static void headerSplitAcrossReads(final DynamicTest test) {
        run(test, helper -> {
            Rig rig = rig("10.83.6.250");
            byte[] all = concat(v2(0x21, "203.0.113.11", 4446, "10.83.6.251"), PAYLOAD);
            for (byte b : all) rig.receive(new byte[] { b });
            assertClient(helper, rig, "203.0.113.11", 4446);
            helper.assertTrue(java.util.Arrays.equals(rig.capture().bytes.toByteArray(), PAYLOAD), "payload was not forwarded");
            rig.dispose();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = "A LOCAL command (a proxy health check) keeps the socket's address.")
    static void localCommandKeepsTheSocketAddress(final DynamicTest test) {
        run(test, helper -> {
            Rig rig = rig("10.83.6.250");
            ByteBuffer local = ByteBuffer.allocate(SIGNATURE.length + 4);
            local.put(SIGNATURE).put((byte) 0x20).put((byte) 0x00).putShort((short) 0);
            rig.receive(concat(local.array(), PAYLOAD));
            assertClient(helper, rig, "10.83.6.250", 40000);
            helper.assertTrue(rig.channel().isOpen(), "a LOCAL header closed the connection");
            rig.dispose();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = "An untrusted peer is closed before its header is read, and cannot leak a Connection.")
    static void untrustedPeerIsClosed(final DynamicTest test) {
        run(test, helper -> {
            Rig rig = rig("198.51.100.66");
            assertClosedAndDrainable(helper, rig);
            assertClient(helper, rig, "198.51.100.66", 40000);
            rig.dispose();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = "A local status ping needs no header and reaches the game byte for byte, even split across reads.")
    static void localStatusPingReachesTheGame(final DynamicTest test) {
        run(test, helper -> {
            for (int protocol : new int[] { 767, -1 }) {
                byte[] ping = concat(handshake(protocol, 1), STATUS_REQUEST);
                Rig whole = rig("127.0.0.1");
                whole.receive(ping);
                helper.assertTrue(whole.channel().isOpen(), "a local status ping was closed");
                helper.assertTrue(java.util.Arrays.equals(whole.capture().bytes.toByteArray(), ping), "ping bytes changed");
                assertClient(helper, whole, "127.0.0.1", 40000);
                whole.dispose();
                Rig split = rig("127.0.0.1");
                for (byte b : ping) split.receive(new byte[] { b });
                helper.assertTrue(split.channel().isOpen(), "a split local status ping was closed");
                helper.assertTrue(java.util.Arrays.equals(split.capture().bytes.toByteArray(), ping), "split ping bytes changed");
                split.dispose();
            }
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = "A local connection without a header cannot log in, transfer, or use the legacy ping.")
    static void localPeerCannotLogIn(final DynamicTest test) {
        run(test, helper -> {
            for (byte[] data : List.of(handshake(767, 2), handshake(767, 3), new byte[] { (byte) 0xFE, 1 })) {
                Rig rig = rig("127.0.0.1");
                rig.receive(data);
                assertClosedAndDrainable(helper, rig);
                rig.dispose();
            }
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = "A local connection that is not a trusted proxy cannot set a client address with a header.")
    static void localPeerCannotForgeAnAddress(final DynamicTest test) {
        run(test, helper -> {
            Rig rig = rig("127.0.0.1");
            rig.receive(v2(0x21, "203.0.113.12", 4447, "127.0.0.1"));
            assertClosedAndDrainable(helper, rig);
            assertClient(helper, rig, "127.0.0.1", 40000);
            rig.dispose();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = "A local connection claiming an oversized or malformed first packet is closed.")
    static void localMalformedHandshakeIsClosed(final DynamicTest test) {
        run(test, helper -> {
            byte[][] bad = {
                    { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 1 },
                    { 0 },
                    { 2, 9, 9 },
            };
            for (byte[] data : bad) {
                Rig rig = rig("127.0.0.1");
                rig.receive(data);
                assertClosedAndDrainable(helper, rig);
                rig.dispose();
            }
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = "A malformed header from a trusted peer closes the connection without a fault in Connection.")
    static void malformedHeaderIsClosed(final DynamicTest test) {
        run(test, helper -> {
            Rig rig = rig("10.83.6.250");
            ByteBuffer bad = ByteBuffer.allocate(SIGNATURE.length + 16);
            bad.put(SIGNATURE).put((byte) 0x71).put((byte) 0x11).putShort((short) 12).put(new byte[12]);
            try {
                rig.receive(bad.array());
            } catch (Exception e) {
                // An embedded channel tears its pipeline down during the close, so it can rethrow the decoder's error.
                helper.assertTrue("HAProxyProtocolException".equals(e.getClass().getSimpleName()), "unexpected failure: " + e);
            }
            assertClosedAndDrainable(helper, rig);
            rig.dispose();
        });
    }

    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = "An IP ban stored by /ban-ip matches an IPv6 client.")
    static void ipBanMatchesAnIpv6Client(final DynamicTest test) {
        run(test, helper -> {
            File file = Files.createTempFile("multiforge-ip-bans", ".json").toFile();
            try {
                IpBanList bans = new IpBanList(file);
                bans.add(new IpBanListEntry("2001:db8::77"));
                helper.assertTrue(bans.isBanned(new InetSocketAddress(InetAddress.getByName("2001:db8::77"), 7778)),
                        "the banned IPv6 client was let in");
                helper.assertFalse(bans.isBanned(new InetSocketAddress(InetAddress.getByName("2001:db8::78"), 7778)),
                        "a different IPv6 client was banned");
            } finally {
                Files.deleteIfExists(file.toPath());
            }
        });
    }
}
