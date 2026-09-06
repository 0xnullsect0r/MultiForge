/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
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

    /**
     * Attempt {@link RegionState#READY} → {@link RegionState#TICKING}.
     * CAS from READY only — {@link RegionState#FOLDING} refuses so a merge
     * in progress on the caller thread cannot race a tick body (Phase 1 task 1.1).
     */
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

    /**
     * {@link RegionState#READY} → {@link RegionState#FOLDING}. Called from
     * {@link ThreadedRegionizer#mergeInto} before firing merge listeners so
     * that {@link #tryMarkTicking()} refuses this region while the merger
     * folds side state into its slots (Phase 1 task 1.1). Returns {@code
     * false} if the region is currently TICKING (caller should spin-wait
     * with {@link Thread#onSpinWait()}), TRANSIENT (not yet published — no
     * quiesce needed; caller no-ops), FOLDING (should not happen: caller
     * already holds the write lock which serialises all mergers), or DEAD.
     */
    boolean tryMarkFolding() {
        return state.compareAndSet(RegionState.READY, RegionState.FOLDING);
    }

    /**
     * {@link RegionState#FOLDING} → {@link RegionState#READY}. Called from
     * {@link ThreadedRegionizer#mergeInto} after all merge listeners have
     * completed and the surviving region is safe to tick again (Phase 1
     * task 1.1). Returns {@code false} only if the region has since been
     * marked DEAD (which would be a bug: merger completed on a region that
     * was concurrently killed).
     */
    boolean markReadyFromFolding() {
        return state.compareAndSet(RegionState.FOLDING, RegionState.READY);
    }

    /** Any non-DEAD → {@link RegionState#DEAD}. Terminal. */
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
