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
package net.multiforge.runtime.region;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Opaque unique id assigned to each {@link Region} at creation time. Ids
 * are monotonically increasing per JVM run; the value is stable for the
 * lifetime of the region and is reused for logs, metrics, and
 * {@code /multiforge region list}.
 */
public record RegionId(long value) {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    public static RegionId next() {
        return new RegionId(SEQUENCE.incrementAndGet());
    }

    @Override
    public String toString() {
        return "region#" + value;
    }
}
