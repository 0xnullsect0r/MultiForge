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

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Minimal Source-RCON client for driving a headless Minecraft/NeoForge
 * server from the bench harness.
 *
 * <p>Implements just enough of the wire protocol —
 * {@code SERVERDATA_AUTH} once, then {@code SERVERDATA_EXECCOMMAND} per
 * command, single packet in and out — to authenticate and round-trip the
 * handful of {@code /tick}, {@code /summon}, {@code /forceload}, and
 * {@code save-all}/{@code stop} commands the bench profiles issue. It is
 * deliberately not a general-purpose RCON library: every reply this
 * harness expects fits comfortably in one RCON packet (the longest,
 * {@code /tick query}, is well under 200 bytes), so there is no
 * multi-packet response reassembly.
 *
 * <p>Wire format — little-endian {@code int32} length prefix, then
 * {@code int32 id}, {@code int32 type}, the UTF-8 body, and two
 * terminating NUL bytes — mirrors the reference client at
 * {@code scratchpad/rcon.py} that was used to confirm the exact
 * NeoForge 1.21.1 {@code /tick query} reply text (see
 * {@link MetricsCollector}) during Phase 7.4a implementation.
 *
 * <p><b>Every packet is written in one {@code OutputStream.write(byte[])}
 * call.</b> The vendored server's own handler (see {@code RconClient.run()}
 * in {@code upstream/neoforge-1.21.1/projects/base/.../server/rcon/thread/RconClient.java})
 * reads a whole packet with exactly <em>one</em> {@code read(buf, 0, 1460)}
 * call — it does not loop to reassemble a packet split across multiple
 * reads. If this client's own write of one packet ever lands as more
 * than one TCP segment (which a naive implementation risks: writing a
 * length-prefixed int as four separate single-byte {@code write()} calls
 * with no buffering in between was an earlier, flaky version of this
 * class — confirmed live during Phase 7.4a implementation to
 * intermittently split across segments and get the connection reset),
 * the server's single {@code read()} sees a truncated frame, its
 * length-sanity-check fails, and it silently closes the connection. So
 * every packet here is assembled into one {@code byte[]} up front (the
 * same pattern the server's own {@code send()} method uses) and handed
 * to the socket in a single {@code write()} call.
 *
 * <p><b>One connection per command.</b> Live captures against a real
 * NeoForge 1.21.1 dev server (both the hand-written {@code rcon.py}
 * probe and the boot logs it produced — see {@code scratchpad/boot-*.log}
 * from Phase 7.4a implementation) show the server's RCON listener
 * accepting a client, handling exactly one auth+command round trip, then
 * logging {@code "Thread RCON Client ... shutting down"} and closing the
 * socket the moment the client itself disconnects. So unlike a typical
 * persistent-session RCON client, this one opens a fresh TCP connection
 * and re-authenticates for every single {@link #command(String)} call,
 * exactly as {@code rcon.py} does (it is invoked once per command from
 * the shell).
 *
 * <p>Thread-safe: {@link #command(String)} owns its socket for the
 * duration of the call and does not share state across calls.
 */
public final class RconClient {

    private static final int TYPE_AUTH = 3;
    private static final int TYPE_EXEC_COMMAND = 2;

    private final String host;
    private final int port;
    private final String password;
    private final Duration ioTimeout;

    public RconClient(String host, int port, String password, Duration ioTimeout) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.ioTimeout = ioTimeout;
    }

    /**
     * Opens a new connection, authenticates, sends one command, reads its
     * (single-packet) reply, and closes the connection — see the class
     * javadoc for why every call pays for its own connect+auth.
     */
    public String command(String command) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) ioTimeout.toMillis());
            socket.setSoTimeout((int) ioTimeout.toMillis());
            socket.setTcpNoDelay(true);

            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());

            Packet authResponse = sendAndRead(out, in, 1, TYPE_AUTH, password);
            // Per the Source RCON spec, an id of -1 on the auth-response
            // packet signals a rejected password.
            if (authResponse.id() == -1) {
                throw new IOException("RCON authentication failed (bad password?)");
            }

            Packet response = sendAndRead(out, in, 2, TYPE_EXEC_COMMAND, command);
            return response.body();
        }
    }

    private static Packet sendAndRead(OutputStream out, DataInputStream in, int id, int type, String body)
            throws IOException {
        out.write(encodePacket(id, type, body));
        out.flush();
        return readPacket(in);
    }

    /** Builds one whole RCON packet into a single byte array — see the class javadoc for why this matters. */
    private static byte[] encodePacket(int id, int type, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        int payloadLength = 4 + 4 + bodyBytes.length + 2;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(4 + payloadLength);
        writeIntLE(buffer, payloadLength);
        writeIntLE(buffer, id);
        writeIntLE(buffer, type);
        buffer.write(bodyBytes);
        buffer.write(0);
        buffer.write(0);
        return buffer.toByteArray();
    }

    private static Packet readPacket(DataInputStream in) throws IOException {
        int length = readIntLE(in);
        byte[] data = new byte[length];
        in.readFully(data);
        int id = readIntLE(data, 0);
        String body = new String(data, 8, data.length - 8 - 2, StandardCharsets.UTF_8);
        return new Packet(id, body);
    }

    private static void writeIntLE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static int readIntLE(DataInputStream in) throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int b3 = in.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private record Packet(int id, String body) {}
}
