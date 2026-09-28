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

import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class ActivationConfigTest {

    @Test
    void defaultsAreOnWithThePlannedRanges() {
        ActivationConfig d = MultiForgeConfig.defaults().activation();
        assertThat(d).isEqualTo(ActivationConfig.DEFAULTS);
        assertThat(d.activation()).isTrue();
        assertThat(List.of(d.monsterRange(), d.animalRange(), d.villagerRange(), d.flyingRange()))
                .containsOnly(32);
        assertThat(d.raiderRange()).isEqualTo(48);
        assertThat(List.of(d.waterRange(), d.ambientRange())).containsOnly(16);
        assertThat(d.wakeInterval()).isEqualTo(20);
        assertThat(d.maxEntityCollisions()).isEqualTo(8);
        assertThat(d.maxRange()).isEqualTo(48);
    }

    @Test
    void roundTripsThroughToml() {
        ActivationConfig a =
                new ActivationConfig(false, 1, 2, 3, 4, 5, 6, 7, 40, 0, List.of("minecraft:squid", "#c:bosses"));
        MultiForgeConfig c = MultiForgeConfig.defaults().withActivation(a);
        MultiForgeConfig parsed = ConfigCodec.parse(ConfigCodec.render(c));
        assertThat(parsed.activation()).isEqualTo(a);
        assertThat(parsed).isEqualTo(c);
    }

    @Test
    void anOldFileGetsTheDefaults() {
        assertThat(ConfigCodec.parse("[entities]\ndeferVisibility = false\n").activation())
                .isEqualTo(ActivationConfig.DEFAULTS);
        MultiForgeConfig partial = ConfigCodec.parse("[entities]\nwakeInterval = 10\nactivationExempt = [\"a:b\"]\n");
        assertThat(partial.activation().wakeInterval()).isEqualTo(10);
        assertThat(partial.activation().exempt()).containsExactly("a:b");
        assertThat(partial.activation().monsterRange()).isEqualTo(32);
    }

    @Test
    void overridesApplyPerKeyAndIgnoreGarbage() {
        Map<String, String> props = Map.of(
                "activation", "false",
                "maxEntityCollisions", "0",
                "waterRange", "not-a-number",
                "activationExempt", " a:b , #c:d ,");
        ActivationConfig a = ActivationConfig.DEFAULTS.withOverrides(props::get);
        assertThat(a.activation()).isFalse();
        assertThat(a.maxEntityCollisions()).isZero();
        assertThat(a.waterRange()).isEqualTo(16);
        assertThat(a.exempt()).containsExactly("a:b", "#c:d");
    }

    @Test
    void systemPropertiesOverrideTheFile() {
        String prev = System.getProperty("multiforge.entities.activation");
        try {
            System.setProperty("multiforge.entities.activation", "false");
            assertThat(MultiForgeConfig.defaults().effectiveActivation().activation())
                    .isFalse();
        } finally {
            if (prev == null) System.clearProperty("multiforge.entities.activation");
            else System.setProperty("multiforge.entities.activation", prev);
        }
    }

    @Test
    void modeOffForcesActivationAndThePushCapOff() {
        ActivationConfig off =
                MultiForgeConfig.defaults().withMode(MultiForgeConfig.Mode.OFF).effectiveActivation();
        assertThat(off.activation()).isFalse();
        assertThat(off.maxEntityCollisions()).isZero();
        ActivationConfig hybrid = MultiForgeConfig.defaults().effectiveActivation();
        assertThat(hybrid.activation()).isTrue();
        assertThat(hybrid.maxEntityCollisions()).isEqualTo(8);
    }

    @Test
    void valuesAreClamped() {
        ActivationConfig a = new ActivationConfig(true, -1, -1, -1, -1, -1, -1, -1, 0, -5, null);
        assertThat(a.maxRange()).isZero();
        assertThat(a.wakeInterval()).isEqualTo(1);
        assertThat(a.maxEntityCollisions()).isZero();
        assertThat(a.exempt()).isEmpty();
    }

    @Test
    void loadSheddingDoublesUpToEighty() {
        long ms = 1_000_000L;
        assertThat(ActivationConfig.shedInterval(20, 0)).isEqualTo(20);
        assertThat(ActivationConfig.shedInterval(20, 40 * ms)).isEqualTo(20);
        assertThat(ActivationConfig.shedInterval(20, 41 * ms)).isEqualTo(40);
        assertThat(ActivationConfig.shedInterval(20, 81 * ms)).isEqualTo(80);
        assertThat(ActivationConfig.shedInterval(20, 10_000 * ms)).isEqualTo(80);
        assertThat(ActivationConfig.shedInterval(50, 10_000 * ms)).isEqualTo(50);
        assertThat(ActivationConfig.shedInterval(100, 10_000 * ms)).isEqualTo(100);
    }

    @Property
    void shedIntervalStaysWithinBounds(
            @ForAll @IntRange(min = 1, max = 200) int base,
            @ForAll @LongRange(min = 0, max = 10_000_000_000L) long nanos) {
        int interval = ActivationConfig.shedInterval(base, nanos);
        assertThat(interval).isGreaterThanOrEqualTo(base);
        assertThat(interval).isLessThanOrEqualTo(Math.max(base, ActivationConfig.MAX_SHED_INTERVAL));
    }

    @Property
    void everyEntityWakesExactlyOncePerInterval(
            @ForAll @IntRange(min = 1, max = 80) int interval,
            @ForAll @IntRange(min = 0, max = Integer.MAX_VALUE) int id,
            @ForAll @LongRange(min = 0, max = 1L << 40) long start) {
        int wakes = 0;
        for (long t = start; t < start + interval; t++) {
            if (ActivationConfig.isWakeTick(t, id, interval)) wakes++;
        }
        assertThat(wakes).isEqualTo(1);
    }

    @Test
    void wakeTicksAreStaggeredById() {
        long t = 1000;
        int awake = 0;
        for (int id = 0; id < 200; id++) if (ActivationConfig.isWakeTick(t, id, 20)) awake++;
        assertThat(awake).isEqualTo(10);
    }
}
