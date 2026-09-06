/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
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

class WorldBorderSystemTest {

    private static final WorldRef OVERWORLD = WorldRef.of("minecraft:overworld");
    private static final WorldRef END = WorldRef.of("minecraft:the_end");

    @Test
    void tickAdvancesEveryRegisteredBorder() {
        WorldBorderSystem sys = new WorldBorderSystem((dest, task) -> {});
        List<WorldRef> advanced = new ArrayList<>();
        sys.registerWorld(OVERWORLD, () -> advanced.add(OVERWORLD));
        sys.registerWorld(END, () -> advanced.add(END));

        sys.tick(new GlobalTickContext(1L, RegionId.next()));

        assertThat(advanced).containsExactlyInAnyOrder(OVERWORLD, END);
    }

    @Test
    void oneBorderThrowingDoesNotBlockOthers() {
        WorldBorderSystem sys = new WorldBorderSystem((dest, task) -> {});
        List<WorldRef> advanced = new ArrayList<>();
        sys.registerWorld(OVERWORLD, () -> {
            throw new IllegalStateException("boom");
        });
        sys.registerWorld(END, () -> advanced.add(END));

        sys.tick(new GlobalTickContext(1L, RegionId.next()));

        assertThat(advanced).containsExactly(END);
    }

    @Test
    void unregisterStopsAdvancingAndClearsHandlingFlag() {
        WorldBorderSystem sys = new WorldBorderSystem((dest, task) -> {});
        List<WorldRef> advanced = new ArrayList<>();
        sys.registerWorld(OVERWORLD, () -> advanced.add(OVERWORLD));
        sys.unregisterWorld(OVERWORLD);

        sys.tick(new GlobalTickContext(1L, RegionId.next()));

        assertThat(advanced).isEmpty();
        assertThat(sys.isHandling(OVERWORLD)).isFalse();
    }

    @Test
    void nameIsStable() {
        assertThat(new WorldBorderSystem((dest, task) -> {}).name()).isEqualTo("world_border");
    }
}
