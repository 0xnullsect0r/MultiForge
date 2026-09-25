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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link LevelTickDispatchProbes}: the operator-visible counter and
 * warn-once behaviour for a level that ticks inline because it has no
 * regionizer yet.
 */
class DispatchLevelTickTest {

    @BeforeEach
    @AfterEach
    void resetPerWorldWarnOnceState() {
        LevelTickDispatchProbes.resetForTesting();
        ViolationLogger.resetForTesting();
        ViolationLogger.clearSubscribersForTesting();
    }

    @Test
    void everyInlineTickIsCountedButWarnedOncePerWorld() throws Exception {
        long before = ProbeRegistry.get(LevelTickDispatchProbes.NO_REGIONIZER_INLINE_PROBE);
        List<ViolationLogger.ViolationEvent> received = new ArrayList<>();
        AutoCloseable subscription = ViolationLogger.subscribe(received::add);
        try {
            for (int i = 0; i < 5; i++) {
                LevelTickDispatchProbes.noRegionizerInline("minecraft:the_end");
            }
        } finally {
            subscription.close();
        }

        assertThat(ProbeRegistry.get(LevelTickDispatchProbes.NO_REGIONIZER_INLINE_PROBE) - before)
                .isEqualTo(5L);
        assertThat(received)
                .extracting(ViolationLogger.ViolationEvent::site)
                .containsExactly(LevelTickDispatchProbes.NO_REGIONIZER_INLINE_PROBE + "::minecraft:the_end");
    }

    @Test
    void distinctWorldsGetDistinctWarnings() throws Exception {
        List<ViolationLogger.ViolationEvent> received = new ArrayList<>();
        AutoCloseable subscription = ViolationLogger.subscribe(received::add);
        try {
            LevelTickDispatchProbes.noRegionizerInline("minecraft:the_nether");
            LevelTickDispatchProbes.noRegionizerInline("minecraft:the_end");
            LevelTickDispatchProbes.noRegionizerInline("minecraft:custom_dim");
        } finally {
            subscription.close();
        }

        assertThat(received)
                .extracting(ViolationLogger.ViolationEvent::site)
                .containsExactlyInAnyOrder(
                        LevelTickDispatchProbes.NO_REGIONIZER_INLINE_PROBE + "::minecraft:the_nether",
                        LevelTickDispatchProbes.NO_REGIONIZER_INLINE_PROBE + "::minecraft:the_end",
                        LevelTickDispatchProbes.NO_REGIONIZER_INLINE_PROBE + "::minecraft:custom_dim");
    }
}
