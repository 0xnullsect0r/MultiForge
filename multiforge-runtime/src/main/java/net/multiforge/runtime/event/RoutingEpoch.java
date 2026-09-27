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

/**
 * A counter bumped whenever an input to a listener's routing decision
 * changes after registration: the mod classification is bound ({@link
 * ModClassifier#bind}) or an event type's default domain is registered
 * ({@link EventTypeDomainMap#register}). A {@link RoutingListenerWrapper}
 * caches its resolved domain and resolves it again only when the epoch has
 * moved, so the per-call cost is one volatile read.
 */
public final class RoutingEpoch {

    private static volatile int epoch;

    private RoutingEpoch() {}

    public static int current() {
        return epoch;
    }

    static synchronized void bump() {
        epoch++;
    }
}
