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
package net.multiforge.runtime.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FML 11's {@code @EventBusSubscriber} registers each listener by passing its
 * {@link Method} to {@link IEventBus#register}. Treating that as a listener
 * object scans {@code java.lang.reflect.Method} and silently drops the listener.
 */
class DispatchingEventBusMethodRegistrationTest {
    static final class Ping extends Event {}

    static final AtomicInteger CALLS = new AtomicInteger();

    static final class Listeners {
        @SubscribeEvent
        static void onPing(Ping event) {
            CALLS.incrementAndGet();
        }

        @SubscribeEvent
        void instanceListener(Ping event) {}

        static void notAListener(Ping event) {}
    }

    private IEventBus bus;

    @BeforeEach
    void setUp() {
        CALLS.set(0);
        bus = new LazyDispatchingEventBus(BusBuilder.builder().build());
        bus.start();
    }

    private static Method method(String name) throws NoSuchMethodException {
        return Listeners.class.getDeclaredMethod(name, Ping.class);
    }

    @Test
    void aRegisteredStaticMethodReceivesEvents() throws Exception {
        bus.register(method("onPing"));
        bus.post(new Ping());
        assertThat(CALLS).hasValue(1);
    }

    @Test
    void anEqualMethodRegisteredTwiceFiresOnce() throws Exception {
        // getDeclaredMethod returns a new, equal Method object on every call.
        bus.register(method("onPing"));
        bus.register(method("onPing"));
        bus.post(new Ping());
        assertThat(CALLS).hasValue(1);
    }

    @Test
    void anEqualMethodUnregisters() throws Exception {
        bus.register(method("onPing"));
        bus.unregister(method("onPing"));
        bus.post(new Ping());
        assertThat(CALLS).hasValue(0);
    }

    @Test
    void aNonStaticMethodIsRejectedAsTheBusDoes() throws Exception {
        Method instance = method("instanceListener");
        assertThatThrownBy(() -> bus.register(instance)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnannotatedMethodIsRejectedAsTheBusDoes() throws Exception {
        Method plain = method("notAListener");
        assertThatThrownBy(() -> bus.register(plain)).isInstanceOf(IllegalArgumentException.class);
    }
}
