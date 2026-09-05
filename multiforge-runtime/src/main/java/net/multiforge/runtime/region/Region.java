/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.region;

import java.util.Collections;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
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
    /**
     * Actions to run on the region's worker thread after its current
     * tick completes and before the next one starts. Used by
     * {@link RegionizedData} to defer merge folds that would otherwise
     * race a tick body iterating a slot value; see also the /67
     * review's merge-during-tick race finding.
     */
    private final Queue<Runnable> postTickActions = new ConcurrentLinkedQueue<>();

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

    /**
     * Enqueue {@code action} to run after this region's current tick
     * completes. Safe to call from any thread. Actions execute on the
     * worker thread that just finished the tick, before it moves on
     * to schedule the next one.
     */
    public void addPostTickAction(Runnable action) {
        postTickActions.add(action);
    }

    /**
     * Drain and run every pending post-tick action. Called by
     * {@link TickRegionScheduler} after {@link #markNotTicking}. Uncaught
     * exceptions from an action are routed to the current thread's
     * uncaught handler and do not prevent later actions from running.
     */
    void runPostTickActions() {
        Runnable r;
        while ((r = postTickActions.poll()) != null) {
            try {
                r.run();
            } catch (Throwable t) {
                Thread.currentThread().getUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), t);
            }
        }
    }

    /** Test/observability only: post-tick action count without draining. */
    public int postTickActionCount() {
        return postTickActions.size();
    }

    static Set<SectionPos> emptySections() {
        return Collections.emptySet();
    }
}
