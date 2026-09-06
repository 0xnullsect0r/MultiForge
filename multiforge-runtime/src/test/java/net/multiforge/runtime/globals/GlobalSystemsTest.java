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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GlobalSystemsTest {

    @Test
    void tickerFiresPerTick() {
        GlobalSystems gs = new GlobalSystems();
        AtomicInteger n = new AtomicInteger();
        gs.register("counter", tick -> n.incrementAndGet());
        gs.tickAll();
        gs.tickAll();
        gs.tickAll();
        assertThat(n.get()).isEqualTo(3);
        assertThat(gs.currentTick()).isEqualTo(3);
    }

    @Test
    void tickersFireInRegistrationOrder() {
        GlobalSystems gs = new GlobalSystems();
        List<String> order = new ArrayList<>();
        gs.register("first", t -> order.add("first"));
        gs.register("second", t -> order.add("second"));
        gs.register("third", t -> order.add("third"));
        gs.tickAll();
        assertThat(order).containsExactly("first", "second", "third");
    }

    @Test
    void oneTickerThrowingDoesNotStopOthers() {
        GlobalSystems gs = new GlobalSystems();
        AtomicInteger okCount = new AtomicInteger();
        gs.register("bad", t -> {
            throw new RuntimeException("mod threw");
        });
        gs.register("good", t -> okCount.incrementAndGet());
        gs.tickAll();
        assertThat(okCount.get()).isEqualTo(1);
    }
}
