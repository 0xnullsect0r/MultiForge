/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
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
