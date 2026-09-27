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

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import org.jetbrains.annotations.ApiStatus;

/**
 * Whether a listener's code depends on running on the server thread, read
 * from its bytecode by a {@link Scanner} the server binds (the runtime has no
 * bytecode library of its own).
 *
 * <p>A listener that asks which thread it is on ({@code isSameThread}, {@code
 * getRunningThread}) behaves differently on a region worker than on the
 * server thread; one that writes a static field shares state across regions.
 * {@link RoutingListenerWrapper} keeps such a listener on the serial lane
 * even where its event's default would run it on the region:
 * <ul>
 *   <li>asking for the thread pins any listener without an explicit
 *       {@code @DispatchDomain} (its effect would silently change);</li>
 *   <li>a static write pins a listener that runs on the region only because
 *       an audited entry lists it ({@link EventTypeDomainMap#lookup(Class,
 *       Class)}): the audit read a version that did not, so the mod changed.</li>
 * </ul>
 * Class bytes that cannot be read pin nothing (probe {@code
 * event.affinity.unscanned}).
 */
@ApiStatus.Internal
public final class ListenerAffinity {

    /**
     * What a listener's code does.
     *
     * @param readable whether its bytes could be read
     * @param asksThread whether it (or a method of its class it calls) asks which thread it runs on
     * @param writesStatic whether it (or a method of its class it calls) writes a static field
     */
    public record Scan(boolean readable, boolean asksThread, boolean writesStatic) {
        public static final Scan UNREADABLE = new Scan(false, false, false);
        public static final Scan CLEAN = new Scan(true, false, false);
    }

    /** Reads a listener's code. */
    @FunctionalInterface
    public interface Scanner {
        /**
         * @param host the listener's class (a lambda's host class for a lambda)
         * @param eventType the event type it listens to
         * @param methodName the listener method, or null when unknown (a lambda or
         *     method reference): every method of {@code host} taking {@code eventType}
         */
        Scan scan(Class<?> host, Class<?> eventType, String methodName);
    }

    private static volatile Scanner scanner = (host, eventType, methodName) -> Scan.UNREADABLE;

    private record Key(Class<?> host, Class<?> eventType, String methodName) {}

    private static final ConcurrentMap<Key, Scan> CACHE = new ConcurrentHashMap<>();

    private ListenerAffinity() {}

    /** Bind the bytecode reader (server start). */
    public static void bind(Scanner s) {
        scanner = Objects.requireNonNull(s, "scanner");
        CACHE.clear();
        RoutingEpoch.bump();
    }

    /** Test-only. */
    static void resetForTesting() {
        scanner = (host, eventType, methodName) -> Scan.UNREADABLE;
        CACHE.clear();
    }

    /**
     * Whether a listener that would run on the region must stay on the serial
     * lane; logs once per listener when it does.
     *
     * @param audited whether it runs on the region only because an audited entry lists it
     */
    static boolean pinned(Class<?> listenerClass, Class<?> eventType, String methodName, boolean audited) {
        if (listenerClass == null || eventType == null) return false;
        Class<?> host = hostOf(listenerClass);
        Scan scan = CACHE.computeIfAbsent(new Key(host, eventType, methodName), ListenerAffinity::read);
        if (!scan.readable()) {
            ProbeRegistry.bump("event.affinity.unscanned");
            return false;
        }
        boolean pin = scan.asksThread() || (audited && scan.writesStatic());
        if (pin) {
            ProbeRegistry.bump("event.affinity.pinned");
            ViolationLogger.warn(
                    "ListenerAffinity",
                    "listener " + host.getName() + (methodName == null ? "" : "#" + methodName) + " of "
                            + eventType.getName()
                            + (scan.asksThread() ? " asks which thread it runs on" : " writes a static field")
                            + "; it runs on the serial lane");
        }
        return pin;
    }

    private static Scan read(Key key) {
        try {
            Scan scan = scanner.scan(key.host(), key.eventType(), key.methodName());
            return scan == null ? Scan.UNREADABLE : scan;
        } catch (RuntimeException | LinkageError e) {
            return Scan.UNREADABLE;
        }
    }

    /** A lambda's or method reference's host class (its nest host), else the class itself. */
    static Class<?> hostOf(Class<?> listenerClass) {
        if (listenerClass.isHidden() || listenerClass.getName().contains("$$Lambda")) {
            try {
                return listenerClass.getNestHost();
            } catch (SecurityException e) {
                return listenerClass;
            }
        }
        return listenerClass;
    }
}
