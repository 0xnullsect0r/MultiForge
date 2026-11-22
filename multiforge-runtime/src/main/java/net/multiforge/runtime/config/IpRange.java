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
package net.multiforge.runtime.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;
import org.jetbrains.annotations.ApiStatus;

/**
 * An IPv4 or IPv6 address or CIDR block. Parsing accepts literals only and
 * never resolves a host name, so it is safe on a network thread. An
 * IPv4-mapped IPv6 address is treated as the IPv4 address it carries.
 */
@ApiStatus.Internal
public final class IpRange {
    private static final Pattern IPV4 =
            Pattern.compile("(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-fA-F:.]+");

    private final byte[] network;
    private final int prefix;

    private IpRange(byte[] network, int prefix) {
        this.network = network;
        this.prefix = prefix;
    }

    /**
     * Parse {@code address} or {@code address/prefix}.
     *
     * @throws IllegalArgumentException if it is not an IP literal with a valid prefix
     */
    public static IpRange parse(String text) {
        if (text == null) throw new IllegalArgumentException("IP range is null");
        String s = text.trim();
        int slash = s.indexOf('/');
        String host = slash < 0 ? s : s.substring(0, slash);
        byte[] bytes = literal(host);
        if (bytes == null) throw new IllegalArgumentException("Not an IP address or CIDR block: " + text);
        int bits = bytes.length * 8;
        int prefix = bits;
        if (slash >= 0) {
            String rawPrefix = s.substring(slash + 1);
            if (!rawPrefix.matches("\\d{1,3}")) {
                throw new IllegalArgumentException("Invalid CIDR prefix: " + text);
            }
            prefix = Integer.parseInt(rawPrefix);
            if (prefix > bits) throw new IllegalArgumentException("Invalid CIDR prefix: " + text);
        }
        return new IpRange(bytes, prefix);
    }

    /** Whether {@code address} is inside this range. Different address families never match. */
    public boolean contains(InetAddress address) {
        if (address == null) return false;
        byte[] candidate = address.getAddress();
        if (candidate.length != network.length) return false;
        int whole = prefix / 8;
        for (int i = 0; i < whole; i++) {
            if (candidate[i] != network[i]) return false;
        }
        int rest = prefix % 8;
        if (rest == 0) return true;
        int mask = 0xFF << (8 - rest) & 0xFF;
        return (candidate[whole] & mask) == (network[whole] & mask);
    }

    private static byte[] literal(String host) {
        boolean v4 = IPV4.matcher(host).matches();
        // A string containing ':' is only ever parsed as an IPv6 literal, never looked up.
        boolean v6 = host.indexOf(':') >= 0 && IPV6_CHARS.matcher(host).matches();
        if (!v4 && !v6) return null;
        try {
            return InetAddress.getByName(host).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
