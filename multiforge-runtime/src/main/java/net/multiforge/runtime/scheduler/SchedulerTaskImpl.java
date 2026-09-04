/*
 * MultiForge — Proprietary. Copyright (c) 2026 MultiForge authors.
 * All rights reserved. See LICENSE at the repository root.
 */
package net.multiforge.runtime.scheduler;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import net.multiforge.api.mod.ModIdentifier;
import net.multiforge.api.scheduler.ScheduledTask;
import net.multiforge.api.scheduler.TaskState;

/**
 * Concrete {@link ScheduledTask} implementation shared across all
 * domains in the reference runtime.
 */
final class SchedulerTaskImpl implements ScheduledTask {

    private final ModIdentifier owner;
    private final boolean repeating;
    private final AtomicReference<TaskState> state = new AtomicReference<>(TaskState.IDLE);
    private volatile Future<?> future;

    SchedulerTaskImpl(ModIdentifier owner, boolean repeating) {
        this.owner = owner;
        this.repeating = repeating;
    }

    void bindFuture(Future<?> future) {
        this.future = future;
    }

    /** Attempt to transition IDLE → EXECUTING. Returns true if we entered EXECUTING. */
    boolean enterExecuting() {
        return state.compareAndSet(TaskState.IDLE, TaskState.EXECUTING);
    }

    /** For non-repeating tasks: EXECUTING → FINISHED. */
    void markFinished() {
        state.compareAndSet(TaskState.EXECUTING, TaskState.FINISHED);
    }

    /** Between iterations of a repeating task: return to IDLE if we haven't been cancelled. */
    boolean prepareNextIteration() {
        return state.compareAndSet(TaskState.EXECUTING, TaskState.IDLE);
    }

    boolean isTerminal() {
        TaskState s = state.get();
        return s == TaskState.FINISHED || s == TaskState.CANCELLED || s == TaskState.CANCELLED_RUNNING;
    }

    @Override
    public TaskState state() {
        return state.get();
    }

    @Override
    public ModIdentifier owner() {
        return owner;
    }

    @Override
    public boolean isRepeating() {
        return repeating;
    }

    @Override
    public boolean cancel() {
        while (true) {
            TaskState cur = state.get();
            switch (cur) {
                case IDLE -> {
                    if (state.compareAndSet(TaskState.IDLE, TaskState.CANCELLED)) {
                        Future<?> f = future;
                        if (f != null) f.cancel(false);
                        return true;
                    }
                }
                case EXECUTING -> {
                    if (state.compareAndSet(TaskState.EXECUTING, TaskState.CANCELLED_RUNNING)) {
                        Future<?> f = future;
                        if (f != null) f.cancel(false);
                        return true;
                    }
                }
                default -> {
                    return false;
                }
            }
        }
    }
}
