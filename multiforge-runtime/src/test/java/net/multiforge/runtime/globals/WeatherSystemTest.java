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
package net.multiforge.runtime.globals;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import net.multiforge.api.world.WorldRef;
import net.multiforge.runtime.region.RegionId;
import org.junit.jupiter.api.Test;

class WeatherSystemTest {

    private static final WorldRef OVERWORLD = WorldRef.of("minecraft:overworld");
    private static final WorldRef NETHER = WorldRef.of("minecraft:the_nether");

    private static CrossRegionEffects noopEffects() {
        return (dest, task) -> {};
    }

    @Test
    void tickInvokesEveryRegisteredWorldOncePerCall() {
        WeatherSystem sys = new WeatherSystem(noopEffects());
        List<WorldRef> invoked = new ArrayList<>();
        sys.registerWorld(OVERWORLD, () -> invoked.add(OVERWORLD));
        sys.registerWorld(NETHER, () -> invoked.add(NETHER));

        sys.tick(new GlobalTickContext(1L, RegionId.next()));

        assertThat(invoked).containsExactlyInAnyOrder(OVERWORLD, NETHER);
    }

    @Test
    void unregisterWorldStopsFutureInvocations() {
        WeatherSystem sys = new WeatherSystem(noopEffects());
        List<WorldRef> invoked = new ArrayList<>();
        sys.registerWorld(OVERWORLD, () -> invoked.add(OVERWORLD));
        sys.unregisterWorld(OVERWORLD);

        sys.tick(new GlobalTickContext(1L, RegionId.next()));

        assertThat(invoked).isEmpty();
        assertThat(sys.isHandling(OVERWORLD)).isFalse();
    }

    @Test
    void isHandlingReflectsRegistrationState() {
        WeatherSystem sys = new WeatherSystem(noopEffects());
        assertThat(sys.isHandling(OVERWORLD)).isFalse();
        sys.registerWorld(OVERWORLD, () -> {});
        assertThat(sys.isHandling(OVERWORLD)).isTrue();
    }

    @Test
    void oneWorldThrowingDoesNotPreventOthersFromAdvancing() {
        WeatherSystem sys = new WeatherSystem(noopEffects());
        List<WorldRef> invoked = new ArrayList<>();
        sys.registerWorld(OVERWORLD, () -> {
            throw new RuntimeException("boom");
        });
        sys.registerWorld(NETHER, () -> invoked.add(NETHER));

        sys.tick(new GlobalTickContext(1L, RegionId.next()));

        assertThat(invoked).containsExactly(NETHER);
    }

    @Test
    void readAndWriteSetsReflectRegisteredWorlds() {
        WeatherSystem sys = new WeatherSystem(noopEffects());
        sys.registerWorld(OVERWORLD, () -> {});
        sys.registerWorld(NETHER, () -> {});

        assertThat(sys.readSet()).containsExactlyInAnyOrder(OVERWORLD, NETHER);
        assertThat(sys.writeSet()).isEqualTo(sys.readSet());
    }

    @Test
    void nameIsStable() {
        assertThat(new WeatherSystem(noopEffects()).name()).isEqualTo("weather");
    }
}
