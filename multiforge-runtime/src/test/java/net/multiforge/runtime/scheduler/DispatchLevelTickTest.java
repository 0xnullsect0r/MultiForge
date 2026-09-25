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
package net.multiforge.runtime.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import org.junit.jupiter.api.Test;

/**
 * {@link LevelTickDispatchProbes}: a level that ticks inline because it has no
 * regionizer (no loaded chunk) is counted, overall and per world, and is not
 * reported as a violation.
 */
class DispatchLevelTickTest {

    @Test
    void everyInlineTickIsCountedPerWorldWithoutAViolation() throws Exception {
        String probe = LevelTickDispatchProbes.NO_REGIONIZER_INLINE_PROBE;
        long total = ProbeRegistry.get(probe);
        long end = ProbeRegistry.get(probe + ".minecraft:the_end");
        long nether = ProbeRegistry.get(probe + ".minecraft:the_nether");
        List<ViolationLogger.ViolationEvent> received = new ArrayList<>();
        AutoCloseable subscription = ViolationLogger.subscribe(received::add);
        try {
            for (int i = 0; i < 5; i++) LevelTickDispatchProbes.noRegionizerInline("minecraft:the_end");
            LevelTickDispatchProbes.noRegionizerInline("minecraft:the_nether");
        } finally {
            subscription.close();
        }

        assertThat(ProbeRegistry.get(probe) - total).isEqualTo(6L);
        assertThat(ProbeRegistry.get(probe + ".minecraft:the_end") - end).isEqualTo(5L);
        assertThat(ProbeRegistry.get(probe + ".minecraft:the_nether") - nether).isEqualTo(1L);
        assertThat(received).isEmpty();
    }
}
