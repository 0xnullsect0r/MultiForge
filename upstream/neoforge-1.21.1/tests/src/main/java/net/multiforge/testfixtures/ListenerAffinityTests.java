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
package net.multiforge.testfixtures;

import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.server.MinecraftServer;
import net.multiforge.neoforge.event.ThreadAffinityScanner;
import net.multiforge.runtime.event.ListenerAffinity;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.eventtest.internal.TestsMod;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;

/**
 * The bytecode reader behind {@link ListenerAffinity}, run on real class files
 * served by the game's class loader: a listener that asks which thread it runs
 * on, one that writes a static field through a helper of its class, a lambda
 * whose host class is read, and a clean one.
 */
@ForEachTest(groups = "multiforge.listener-affinity")
public class ListenerAffinityTests {
    @GameTest(template = TestsMod.TEMPLATE_3x3)
    @TestHolder(description = "ThreadAffinityScanner finds thread checks and static writes in listener bytecode")
    static void scannerReadsListenerBytecode(final DynamicTest test) {
        test.onGameTest(helper -> {
            ThreadAffinityScanner scanner = new ThreadAffinityScanner();

            ListenerAffinity.Scan asks = scanner.scan(Fixtures.class, ProbeEvent.class, "asksThread");
            helper.assertTrue(asks.readable() && asks.asksThread() && !asks.writesStatic(), "isSameThread: " + asks);

            ListenerAffinity.Scan writes = scanner.scan(Fixtures.class, ProbeEvent.class, "writesStatic");
            helper.assertTrue(writes.readable() && !writes.asksThread() && writes.writesStatic(), "static write via helper: " + writes);

            ListenerAffinity.Scan clean = scanner.scan(Fixtures.class, ProbeEvent.class, "clean");
            helper.assertTrue(clean.readable() && !clean.asksThread() && !clean.writesStatic(), "clean: " + clean);

            // No method name (a lambda): every method taking the event, lambdas included.
            ListenerAffinity.Scan lambdaHost = scanner.scan(LambdaHost.class, ProbeEvent.class, null);
            helper.assertTrue(lambdaHost.readable() && lambdaHost.asksThread(), "lambda host: " + lambdaHost);
            helper.succeed();
        });
    }

    public static final class ProbeEvent extends Event {}

    static final class Fixtures {
        static int counter;

        static void asksThread(ProbeEvent event) {
            MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null && server.isSameThread()) counter();
        }

        static void writesStatic(ProbeEvent event) {
            bump();
        }

        static void clean(ProbeEvent event) {
            event.hashCode();
        }

        private static void bump() {
            counter++;
        }

        private static int counter() {
            return counter;
        }
    }

    static final class LambdaHost {
        static final Consumer<ProbeEvent> LISTENER = event -> {
            MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) server.isSameThread();
        };
    }
}
