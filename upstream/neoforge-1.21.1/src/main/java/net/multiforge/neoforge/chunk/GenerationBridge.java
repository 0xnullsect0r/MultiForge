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
package net.multiforge.neoforge.chunk;

import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.diagnostics.ViolationLogger;
import org.jetbrains.annotations.ApiStatus;

/**
 * M9 Phase 4 task 4.7 — observability bridge for Vanilla
 * {@link ChunkGenerationTask} and {@link GenerationChunkHolder} step
 * transitions. Every method here is a thin probe-bumping observer:
 * count only, no control-flow effect, every entry point wrapped in
 * a defensive {@code catch (Throwable)} that funnels to
 * {@link ViolationLogger#warn} so a bug in the observation path
 * cannot break Vanilla chunk generation.
 *
 * <p><b>Non-goals (deferred to Phase 5+6):</b> per-region worker
 * routing of the step continuation, cross-region neighbour
 * cache-warming via {@code RegionizedTaskQueue.queueChunkTask}, and
 * replacement of the {@code AtomicReferenceArray<CompletableFuture>}
 * future chain with {@code NewChunkHolder}'s state gates. This class
 * only counts events; the counters seed the strict-mode assertions
 * that Phase 5's routing-correctness tests will read via
 * {@link ProbeRegistry#snapshot()}.
 *
 * <p><b>Threading:</b> may be called from any thread — a region
 * worker, the main server thread, or a worldgen off-tick executor —
 * because {@link ProbeRegistry} uses a {@link java.util.concurrent.atomic.LongAdder}
 * and {@link ViolationLogger} is intrinsically thread-safe. No
 * locks, no allocation on the fast path.
 *
 * <p><b>Naming convention:</b> probe names follow the pattern
 * {@code mfgen.observe.<verb>[.<qualifier>]} for grep-ability from
 * the {@code /multiforge probes} operator command (installed in
 * Phase 8) and from JFR dumps.
 *
 * @see net.multiforge.runtime.diagnostics.ProbeRegistry
 * @see net.multiforge.runtime.diagnostics.ViolationLogger
 */
@ApiStatus.Internal
public final class GenerationBridge {
    /** Probe name — {@link GenerationChunkHolder#applyStep} entry. */
    private static final String PROBE_STEP_START = "mfgen.observe.step.start";

    /** Probe name — {@link GenerationChunkHolder#applyStep} completion (success or failure). */
    private static final String PROBE_STEP_COMPLETE = "mfgen.observe.step.complete";

    /** Probe name — {@link ChunkGenerationTask#create} exit. */
    private static final String PROBE_TASK_CREATED = "mfgen.observe.task.created";

    /** Probe name — {@link ChunkGenerationTask#runUntilWait} terminal completion. */
    private static final String PROBE_TASK_COMPLETE = "mfgen.observe.task.complete";

    /** Site key used when funnelling an observer exception into {@link ViolationLogger}. */
    private static final String SITE = "mfgen.observe";

    private GenerationBridge() {}

    /**
     * Called at the entry of {@link GenerationChunkHolder#applyStep} —
     * every attempt to advance a chunk to {@code step.targetStatus}
     * bumps this counter, regardless of the disallowed-status or
     * acquire-status-bump short-circuits below. The Phase 5 test suite
     * compares this against {@link #PROBE_STEP_COMPLETE} to detect
     * dropped completions.
     */
    public static void observeStepStart(GenerationChunkHolder holder, ChunkStep step) {
        try {
            ProbeRegistry.bump(PROBE_STEP_START);
        } catch (Throwable t) {
            // Never break Vanilla: an observer bug becomes a rate-limited warn.
            ViolationLogger.warn(SITE, "observeStepStart failed: " + t);
        }
    }

    /**
     * Called from the {@code .handle(...)} lambda in {@link
     * GenerationChunkHolder#applyStep}. {@code success == true} when
     * the applyStep future completed without a throwable; {@code
     * false} when Vanilla routed to {@code MinecraftServer.setFatalException}.
     * The two paths are counted under one probe on purpose — Phase 5
     * only cares about the delta from {@link #PROBE_STEP_START}. Per-
     * outcome breakdown is left to the strict-mode diagnostic sink.
     */
    public static void observeStepComplete(GenerationChunkHolder holder, ChunkStep step, boolean success) {
        try {
            ProbeRegistry.bump(PROBE_STEP_COMPLETE);
        } catch (Throwable t) {
            ViolationLogger.warn(SITE, "observeStepComplete failed: " + t);
        }
    }

    /**
     * Called at the tail of {@link ChunkGenerationTask#create} once
     * the task instance has been constructed and its cache is
     * populated. Fires before the task is handed to whichever caller
     * scheduled it, so the counter monotonically leads
     * {@link #PROBE_TASK_COMPLETE}.
     */
    public static void observeTaskCreated(ChunkGenerationTask task, ChunkPos pos, ChunkStep targetStep) {
        try {
            ProbeRegistry.bump(PROBE_TASK_CREATED);
        } catch (Throwable t) {
            ViolationLogger.warn(SITE, "observeTaskCreated failed: " + t);
        }
    }

    /**
     * Called from {@link ChunkGenerationTask#runUntilWait} at the
     * terminal branch — either the task reached
     * {@code scheduledStatus == targetStatus} or it was marked for
     * cancellation and released its claim. Both terminate the task's
     * lifecycle, and both count here.
     */
    public static void observeTaskComplete(ChunkGenerationTask task, ChunkPos pos) {
        try {
            ProbeRegistry.bump(PROBE_TASK_COMPLETE);
        } catch (Throwable t) {
            ViolationLogger.warn(SITE, "observeTaskComplete failed: " + t);
        }
    }
}
