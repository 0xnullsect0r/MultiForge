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

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import net.multiforge.runtime.ownership.Domain;
import net.multiforge.runtime.ownership.DomainAssertions;
import net.multiforge.runtime.ownership.OwnerToken;

/**
 * Per-region storage slot for a single value of type {@code T}. Every
 * region gets its own {@code T} instance; the current region worker
 * reaches its slot with {@link #get(Region)} or {@link #getOrCreate(Region)}.
 *
 * <p>M8 uses this to convert per-world mutable {@code ServerLevel}
 * fields that logically belong to a specific spatial region — the
 * scheduled block-tick queue, the block-event queue, the entity iterator
 * cache, etc. — into slots that live under the owning region rather
 * than the level, so region workers never share write access to one
 * mutable object.
 *
 * <p><b>Ownership check.</b> {@link #get(Region)} / {@link
 * #getOrCreate(Region)} require the caller to currently hold the
 * region's {@link OwnerToken}. Off-region access throws {@link
 * IllegalStateException} in unit tests and in strict-assertion builds
 * (behavior matches {@link net.multiforge.runtime.ownership.DomainAssertions}
 * — dev-only, off in production). This is intentional: patched call
 * sites hit slots via a facade that reroutes off-region access, they
 * do not call {@code get} directly under an alien owner.
 *
 * <p><b>Merge and split.</b> When two regions merge, this slot's
 * {@link Merger} callback folds the dying region's value into the
 * surviving region's value. When a region splits, {@link Splitter}
 * (default: fresh empty value from the {@link Supplier}) fills the
 * newly-peeled child region's slot. Both callbacks fire under
 * {@link ThreadedRegionizer}'s write lock alongside the section move,
 * so the region graph and every slot stay atomically consistent.
 */
public final class RegionizedData<T> implements RegionListener {

    /** Fold {@code source}'s value into {@code target}'s. Called under the regionizer write lock. */
    @FunctionalInterface
    public interface Merger<T> extends BiConsumer<T, T> {
        @Override
        void accept(T targetValue, T sourceValue);
    }

    /**
     * Produce the fresh value for a child region peeled off {@code source}.
     * Default is {@code (src, child) -> factory.get()} — full-copy or
     * spatial-split callers should supply their own.
     */
    @FunctionalInterface
    public interface Splitter<T> {
        T split(T sourceValue, Region child);
    }

    private final Supplier<T> factory;
    private final Merger<T> merger;
    private final Splitter<T> splitter;
    private final ConcurrentMap<RegionId, T> slots = new ConcurrentHashMap<>();

    /**
     * @param factory produces a fresh value for a region that has never had its slot touched
     *                (also the default value for newly-peeled child regions unless {@code splitter}
     *                overrides).
     * @param merger  called when a region is being merged into another; fold {@code source}'s
     *                value into {@code target}'s. If {@code null}, the source's value is discarded
     *                and the target's value is kept unchanged — safe only when the slot value is
     *                genuinely idempotent or accumulative-only.
     */
    public static <T> RegionizedData<T> of(Supplier<T> factory, Merger<T> merger) {
        return new RegionizedData<>(factory, merger, null);
    }

    /**
     * As {@link #of(Supplier, Merger)}, but also install a custom
     * {@link Splitter} to fill the freshly-peeled child region's slot.
     */
    public static <T> RegionizedData<T> of(Supplier<T> factory, Merger<T> merger, Splitter<T> splitter) {
        return new RegionizedData<>(factory, merger, splitter);
    }

    private RegionizedData(Supplier<T> factory, Merger<T> merger, Splitter<T> splitter) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.merger = merger == null ? (a, b) -> {} : merger;
        this.splitter = splitter == null ? (src, child) -> factory.get() : splitter;
    }

    /**
     * @return the slot for {@code region}, or {@code null} if it has never been touched.
     * @throws IllegalStateException if the current thread does not hold {@code region}'s
     *         {@link OwnerToken} (skipped in production per {@link DomainAssertions})
     */
    public T get(Region region) {
        assertOwnedOrIgnore(region);
        return slots.get(region.id());
    }

    /**
     * @return the slot for {@code region}, creating a fresh one via the {@link Supplier} on
     *         first touch. The value is stored under {@code region.id()} for the rest of the
     *         region's life.
     * @throws IllegalStateException if the current thread does not hold {@code region}'s
     *         {@link OwnerToken} (skipped in production per {@link DomainAssertions})
     */
    public T getOrCreate(Region region) {
        assertOwnedOrIgnore(region);
        return slots.computeIfAbsent(region.id(), k -> factory.get());
    }

    /**
     * Overwrite the slot for {@code region}. Use sparingly — the
     * intended pattern is {@link #getOrCreate(Region)} followed by
     * mutating {@code T} in place.
     */
    public void set(Region region, T value) {
        assertOwnedOrIgnore(region);
        if (value == null) slots.remove(region.id());
        else slots.put(region.id(), value);
    }

    /**
     * Non-checked accessor for cross-region observability (metrics,
     * shutdown coordination, dumps). Does not check ownership. Do not
     * mutate {@code T} through this — snapshot only.
     */
    public T peek(Region region) {
        return slots.get(region.id());
    }

    /** Count of live slots (regions that have ever had a value stored). */
    public int size() {
        return slots.size();
    }

    /** Apply {@code op} to every currently-live slot in an unspecified order. */
    public void forEachSlot(BiConsumer<RegionId, T> op) {
        slots.forEach(op::accept);
    }

    /**
     * Apply {@code op} to every currently-live slot, replacing its value
     * with {@code op}'s return. Intended for global-thread bookkeeping
     * (e.g. autosave, shutdown drain) where a single thread iterates
     * all slots.
     */
    public void mapInPlace(Function<T, T> op) {
        slots.replaceAll((id, v) -> op.apply(v));
    }

    // === RegionListener ===

    @Override
    public void onRegionsMerging(Region surviving, Region dying) {
        T sourceValue = slots.remove(dying.id());
        if (sourceValue == null) return;
        // Phase 1 task 1.1: ThreadedRegionizer.mergeInto transitions
        // {@code surviving} from READY to FOLDING before firing this
        // listener, and Region.tryMarkTicking() refuses the FOLDING
        // state. So while this callback runs, no worker can be mid-tick
        // on {@code surviving}, and the merger's mutation of the
        // surviving slot value is race-free. See
        // ThreadedRegionizer.mergeInto javadoc for the quiescence
        // contract. (The prior attempt to defer the fold via
        // Region.postTickActions had a TOCTOU + lost-fold-on-death
        // race and was reverted; quiesce-before-fire is the correct
        // Folia-parity shape.)
        T targetValue = slots.computeIfAbsent(surviving.id(), k -> factory.get());
        merger.accept(targetValue, sourceValue);
    }

    @Override
    public void onRegionSplit(Region source, Region child) {
        T sourceValue = slots.get(source.id());
        if (sourceValue == null) return; // source never had a value → child doesn't need one either
        slots.put(child.id(), splitter.split(sourceValue, child));
    }

    @Override
    public void onRegionDied(Region region) {
        slots.remove(region.id());
    }

    private static void assertOwnedOrIgnore(Region region) {
        OwnerToken tok = OwnerToken.current();
        if (tok.domain() == Domain.UNKNOWN) return; // no owner bound (bootstrap/test) → skip
        if (tok.domain() == Domain.GLOBAL) return; // global thread may reach any slot
        if (tok.domain() == Domain.REGION && tok.regionId() == region.id().value()) return;
        // /67 round-4 fix (finding B1): the javadoc on this class and on
        // get/getOrCreate promised "dev-only, off in production", but the
        // pre-fix code unconditionally threw — which directly violates
        // CLAUDE.md rule 5 ("Never throw from a mod's code path — reroute
        // and log a rate-limited warning"). Gate throwing on
        // DomainAssertions.enabled() (strict-mode / dev builds). In
        // production, degrade to a rate-limited warn + probe bump so
        // mishaps are visible in diagnostics without killing the caller.
        if (DomainAssertions.enabled()) {
            throw new IllegalStateException(
                    "RegionizedData.get(" + region.id() + ") on non-owner thread; token=" + tok);
        }
        ProbeRegistry.bump("RegionizedData.get:wrong-owner");
        ViolationLogger.warn(
                "RegionizedData.get",
                "region=" + region.id() + " token=" + tok + " — degraded to warn (assertions off)");
    }
}
