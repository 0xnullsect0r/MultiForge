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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.ApiStatus;

/**
 * The {@code [network]} knobs of {@code multiforge-server.toml}.
 *
 * <p>With {@link #proxyProtocol()} on, every connection must come from an
 * address in {@link #trustedProxies()} and start with a HAProxy PROXY protocol
 * header naming the real client. Any other peer is closed, so a client cannot
 * forge its address or bypass the proxy. An empty list therefore rejects every
 * connection. Entries are IP addresses or CIDR blocks, never host names.
 *
 * <p>Both keys can be overridden with {@code -Dmultiforge.network.<key>=...};
 * the list takes a comma-separated value. These settings apply in every mode,
 * including {@code off}: a server behind a proxy receives the header either way.
 *
 * @param proxyProtocol whether connections carry a PROXY protocol header
 * @param trustedProxies addresses or CIDR blocks allowed to send that header
 */
@ApiStatus.Internal
public record NetworkConfig(boolean proxyProtocol, List<String> trustedProxies) {

    public static final NetworkConfig DEFAULTS = new NetworkConfig(false, List.of());

    /**
     * @throws IllegalArgumentException if an entry of {@code trustedProxies} is
     *     not an IP address or CIDR block
     */
    public NetworkConfig {
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
        for (String entry : trustedProxies) IpRange.parse(entry);
    }

    /** Whether {@code address} is in {@link #trustedProxies()}. */
    public boolean isTrusted(InetAddress address) {
        for (IpRange range : ranges()) {
            if (range.contains(address)) return true;
        }
        return false;
    }

    /** {@link #trustedProxies()}, parsed. */
    public List<IpRange> ranges() {
        List<IpRange> out = new ArrayList<>(trustedProxies.size());
        for (String entry : trustedProxies) out.add(IpRange.parse(entry));
        return List.copyOf(out);
    }

    /** This config with every {@code -Dmultiforge.network.<key>} override applied. */
    public NetworkConfig effective() {
        return withOverrides(key -> System.getProperty("multiforge.network." + key));
    }

    /**
     * This config with the given per-key overrides applied (null = keep).
     *
     * @throws IllegalArgumentException if an override is invalid: ignoring it
     *     could leave a proxy's clients locked out or trust the wrong peer
     */
    public NetworkConfig withOverrides(UnaryOperator<String> lookup) {
        return new NetworkConfig(
                bool(lookup.apply("proxyProtocol"), proxyProtocol),
                list(lookup.apply("trustedProxies"), trustedProxies));
    }

    private static boolean bool(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.equals("true")) return true;
        if (s.equals("false")) return false;
        throw new IllegalArgumentException("multiforge.network.proxyProtocol must be true or false, got: " + raw);
    }

    private static List<String> list(String raw, List<String> fallback) {
        if (raw == null) return fallback;
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
