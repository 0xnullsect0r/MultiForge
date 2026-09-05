/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.region;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A spatial simulation unit that owns some set of {@link SectionPos
 * sections}. Every chunk in the world belongs to exactly one region;
 * cross-region access is illegal off the owner thread and must be
 * routed through {@link RegionizedTaskQueue}.
 *
 * <p>Regions are managed by {@link ThreadedRegionizer}. Callers should
 * not construct them directly.
 */
public final class Region {

    private final RegionId id;
    private final int sectionChunkShift;
    private final Set<SectionPos> sections = new HashSet<>(4);
    private final AtomicReference<RegionState> state = new AtomicReference<>(RegionState.TRANSIENT);
    private volatile long currentTick;

    Region(RegionId id, int sectionChunkShift) {
        this.id = id;
        this.sectionChunkShift = sectionChunkShift;
    }

    public RegionId id() {
        return id;
    }

    public RegionState state() {
        return state.get();
    }

    public long currentTick() {
        return currentTick;
    }

    /** Sections currently owned by this region (unmodifiable snapshot). */
    public Set<SectionPos> sections() {
        synchronized (sections) {
            return Set.copyOf(sections);
        }
    }

    public int sectionCount() {
        synchronized (sections) {
            return sections.size();
        }
    }

    boolean owns(SectionPos section) {
        synchronized (sections) {
            return sections.contains(section);
        }
    }

    void addSection(SectionPos section) {
        synchronized (sections) {
            sections.add(section);
        }
    }

    void removeSection(SectionPos section) {
        synchronized (sections) {
            sections.remove(section);
        }
    }

    Set<SectionPos> drainSections() {
        synchronized (sections) {
            Set<SectionPos> copy = new HashSet<>(sections);
            sections.clear();
            return copy;
        }
    }

    /** Attempt {@link RegionState#READY} → {@link RegionState#TICKING}. */
    boolean tryMarkTicking() {
        return state.compareAndSet(RegionState.READY, RegionState.TICKING);
    }

    /** {@link RegionState#TICKING} → {@link RegionState#READY} at the end of a tick. */
    boolean markNotTicking() {
        currentTick++;
        return state.compareAndSet(RegionState.TICKING, RegionState.READY);
    }

    /** {@link RegionState#TRANSIENT} → {@link RegionState#READY} after publishing. */
    boolean markReady() {
        return state.compareAndSet(RegionState.TRANSIENT, RegionState.READY);
    }

    /** Any non-DEAD → {@link RegionState#DEAD}. */
    void markDead() {
        state.set(RegionState.DEAD);
    }

    boolean canGrow() {
        RegionState s = state.get();
        return s == RegionState.TRANSIENT || s == RegionState.READY;
    }

    @Override
    public String toString() {
        return id + "[sections=" + sectionCount() + ",state=" + state.get() + "]";
    }

    /** For testing / diagnostics only. */
    public int sectionChunkShift() {
        return sectionChunkShift;
    }

    static Set<SectionPos> emptySections() {
        return Collections.emptySet();
    }
}
