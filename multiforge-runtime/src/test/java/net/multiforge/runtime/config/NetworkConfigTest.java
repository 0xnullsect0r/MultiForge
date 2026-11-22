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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NetworkConfigTest {

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    @Test
    void offByDefaultAndAbsentFromAnOldFile() {
        assertThat(NetworkConfig.DEFAULTS.proxyProtocol()).isFalse();
        assertThat(ConfigCodec.parse("[mtserver]\ncores = 2\n").network()).isEqualTo(NetworkConfig.DEFAULTS);
    }

    @Test
    void roundTripsThroughTheFileSoAConfigCommandCannotDropTheAllowlist() {
        NetworkConfig network = new NetworkConfig(true, List.of("10.83.6.250/32", "2001:db8::/32"));
        MultiForgeConfig config = MultiForgeConfig.defaults().withNetwork(network);

        MultiForgeConfig reread = ConfigCodec.parse(ConfigCodec.render(config));
        assertThat(reread.network()).isEqualTo(network);

        MultiForgeConfig changed = reread.withWarnPerMin(9).withMode(MultiForgeConfig.Mode.STRICT);
        assertThat(ConfigCodec.parse(ConfigCodec.render(changed)).network()).isEqualTo(network);
    }

    @Test
    void anInvalidEntryStopsParsingInsteadOfFailingOpen() {
        assertThatThrownBy(
                        () -> ConfigCodec.parse("[network]\nproxyProtocol = true\ntrustedProxies = [\"proxy.lan\"]\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("network.trustedProxies");
    }

    @Test
    void modeOffKeepsTheNetworkSettings() {
        NetworkConfig network = new NetworkConfig(true, List.of("10.0.0.1"));
        MultiForgeConfig config =
                MultiForgeConfig.defaults().withNetwork(network).withMode(MultiForgeConfig.Mode.OFF);
        assertThat(config.effectiveNetwork().proxyProtocol()).isTrue();
    }

    @Test
    void overridesApplyAndInvalidOnesThrow() {
        NetworkConfig on = NetworkConfig.DEFAULTS.withOverrides(
                Map.of("proxyProtocol", "true", "trustedProxies", "10.0.0.1, 10.0.1.0/24")::get);
        assertThat(on.proxyProtocol()).isTrue();
        assertThat(on.trustedProxies()).containsExactly("10.0.0.1", "10.0.1.0/24");
        assertThatThrownBy(() -> NetworkConfig.DEFAULTS.withOverrides(Map.of("proxyProtocol", "maybe")::get))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchesAddressesAndBlocks() throws Exception {
        NetworkConfig config = new NetworkConfig(true, List.of("10.83.6.250/32", "192.168.4.0/22", "2001:db8::/48"));
        assertThat(config.isTrusted(ip("10.83.6.250"))).isTrue();
        assertThat(config.isTrusted(ip("10.83.6.251"))).isFalse();
        assertThat(config.isTrusted(ip("192.168.7.255"))).isTrue();
        assertThat(config.isTrusted(ip("192.168.8.0"))).isFalse();
        assertThat(config.isTrusted(ip("2001:db8:0:ffff::1"))).isTrue();
        assertThat(config.isTrusted(ip("2001:db8:1::1"))).isFalse();
        assertThat(config.isTrusted(ip("::ffff:10.83.6.250"))).isTrue();
    }

    @Test
    void anEmptyAllowlistTrustsNobody() throws Exception {
        assertThat(new NetworkConfig(true, List.of()).isTrusted(ip("127.0.0.1")))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "localhost",
                "proxy.lan",
                "10.0.0",
                "10.0.0.256",
                "010.0.0.1",
                "10.0.0.1/33",
                "10.0.0.1/-1",
                "10.0.0.1/",
                "10.0.0.1/a",
                "2001:db8::/129",
                "[2001:db8::1]",
                "fe80::1%eth0",
                "gggg::1"
            })
    void rejectsAnythingButAnIpLiteralWithAValidPrefix(String bad) {
        assertThatThrownBy(() -> IpRange.parse(bad)).isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    void aBlockContainsExactlyTheAddressesSharingItsPrefix(
            @ForAll @Size(4) byte[] network, @ForAll @Size(4) byte[] candidate, @ForAll @IntRange(max = 32) int prefix)
            throws Exception {
        InetAddress base = InetAddress.getByAddress(network);
        IpRange range = IpRange.parse(base.getHostAddress() + "/" + prefix);
        int a = java.nio.ByteBuffer.wrap(network).getInt();
        int b = java.nio.ByteBuffer.wrap(candidate).getInt();
        int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
        assertThat(range.contains(InetAddress.getByAddress(candidate))).isEqualTo((a & mask) == (b & mask));
    }
}
