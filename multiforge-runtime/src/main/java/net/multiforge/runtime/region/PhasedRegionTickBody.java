/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.region;

import java.util.EnumMap;
import java.util.Map;

/**
 * A {@link RegionTickBody} composed of the six ordered phases from
 * {@code docs/blueprint.md} §"Region local phase ordering":
 * <ol>
 *   <li>{@link Phase#INBOUND_MAILBOX} — apply queued inbound tasks (in
 *       production the {@link TickRegionScheduler} already drains the
 *       mailbox itself before and after {@code tickOnce}; this phase
 *       exists for callers driving a body directly, e.g. tests).</li>
 *   <li>{@link Phase#BLOCK_FLUID_TICKS} — scheduled block/fluid updates
 *       drained from the per-region tick lists.</li>
 *   <li>{@link Phase#ENTITY_AI} — entity iteration, AI, physics.</li>
 *   <li>{@link Phase#BLOCK_ENTITIES} — block-entity iteration.</li>
 *   <li>{@link Phase#REGION_EVENTS} — per-region NeoForge events + tasks.</li>
 *   <li>{@link Phase#FLUSH_OUTBOUND} — deliver outbound cross-region
 *       messages that phases 2–5 produced.</li>
 * </ol>
 *
 * <p>Each phase is an independent {@link RegionTickBody} slot. Empty
 * slots are silent no-ops. The M8 patches wire the block/fluid,
 * entity, block-entity, and event slots to real {@code ServerLevel}
 * subsystem calls; the mailbox and outbound slots are usually left
 * empty in production because {@link TickRegionScheduler}'s own
 * before/after drain covers them.
 */
public final class PhasedRegionTickBody implements RegionTickBody {

    public enum Phase {
        INBOUND_MAILBOX,
        BLOCK_FLUID_TICKS,
        ENTITY_AI,
        BLOCK_ENTITIES,
        REGION_EVENTS,
        FLUSH_OUTBOUND,
    }

    private static final RegionTickBody NOOP = region -> {};

    private final EnumMap<Phase, RegionTickBody> phases;

    private PhasedRegionTickBody(EnumMap<Phase, RegionTickBody> phases) {
        this.phases = new EnumMap<>(phases);
    }

    @Override
    public void tickOnce(Region region) {
        // Phase.values() is defined in blueprint order — iterating over it
        // is the canonical way to run phases in sequence.
        //
        // /67 round-4 fix (B7): each phase runs inside its own try/catch so
        // one throwing phase (say REGION_EVENTS from a mod handler) doesn't
        // strand the FLUSH_OUTBOUND phase and lose cross-region messages.
        // Exceptions route to the uncaught handler — same shape as
        // RegionizedTaskQueue.drain — so the tick pipeline stays alive.
        for (Phase p : Phase.values()) {
            try {
                phases.getOrDefault(p, NOOP).tickOnce(region);
            } catch (Throwable t) {
                Thread.currentThread().getUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), t);
            }
        }
    }

    /** Retrieve the body wired to {@code phase}; {@link #NOOP} if none. */
    public RegionTickBody phase(Phase phase) {
        return phases.getOrDefault(phase, NOOP);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final EnumMap<Phase, RegionTickBody> phases = new EnumMap<>(Phase.class);

        private Builder() {}

        /**
         * Set the body for {@code phase}. Overwrites any previously-set
         * body. Passing {@code null} clears the phase back to no-op.
         */
        public Builder set(Phase phase, RegionTickBody body) {
            if (body == null) phases.remove(phase);
            else phases.put(phase, body);
            return this;
        }

        /** Convenience aliases for {@link #set(Phase, RegionTickBody)}. */
        public Builder inboundMailbox(RegionTickBody body) {
            return set(Phase.INBOUND_MAILBOX, body);
        }

        public Builder blockFluidTicks(RegionTickBody body) {
            return set(Phase.BLOCK_FLUID_TICKS, body);
        }

        public Builder entityAi(RegionTickBody body) {
            return set(Phase.ENTITY_AI, body);
        }

        public Builder blockEntities(RegionTickBody body) {
            return set(Phase.BLOCK_ENTITIES, body);
        }

        public Builder regionEvents(RegionTickBody body) {
            return set(Phase.REGION_EVENTS, body);
        }

        public Builder flushOutbound(RegionTickBody body) {
            return set(Phase.FLUSH_OUTBOUND, body);
        }

        public PhasedRegionTickBody build() {
            return new PhasedRegionTickBody(phases);
        }
    }

    /** For metrics/debug: unmodifiable snapshot of the wired phases. */
    public Map<Phase, RegionTickBody> phasesSnapshot() {
        return Map.copyOf(phases);
    }
}
