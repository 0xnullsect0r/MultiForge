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
package net.multiforge.neoforge.world;

import java.util.function.Supplier;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.metrics.MetricCategory;
import org.jetbrains.annotations.ApiStatus;

/**
 * The server's profiler, usable only from the thread it was handed out on.
 *
 * <p>Vanilla's profiler (an {@code ActiveProfiler} under {@code /debug start} or
 * {@code /perf start}) keeps a path stack and is single-threaded. {@code
 * ServerLevel.tick} takes it on the server thread and its per-entity lambda
 * captures it; with regions, every region worker runs that same lambda (kept so
 * mixins targeting it still apply), so workers pushed and popped the server's
 * profiler concurrently and could crash the tick. Calls from any other thread
 * are dropped here, so a worker's push and its pop are both no-ops; the server
 * thread profiles as before.
 */
@ApiStatus.Internal
public final class ThreadConfinedProfiler implements ProfilerFiller {
    private final ProfilerFiller delegate;
    private final Thread owner;

    public ThreadConfinedProfiler(ProfilerFiller delegate, Thread owner) {
        this.delegate = delegate;
        this.owner = owner;
    }

    /** The profiler this wraps. */
    public ProfilerFiller delegate() {
        return delegate;
    }

    /** The thread whose calls are forwarded. */
    public Thread owner() {
        return owner;
    }

    private boolean onOwner() {
        return Thread.currentThread() == owner;
    }

    @Override
    public void startTick() {
        if (onOwner()) delegate.startTick();
    }

    @Override
    public void endTick() {
        if (onOwner()) delegate.endTick();
    }

    @Override
    public void push(String name) {
        if (onOwner()) delegate.push(name);
    }

    @Override
    public void push(Supplier<String> name) {
        if (onOwner()) delegate.push(name);
    }

    @Override
    public void pop() {
        if (onOwner()) delegate.pop();
    }

    @Override
    public void popPush(String name) {
        if (onOwner()) delegate.popPush(name);
    }

    @Override
    public void popPush(Supplier<String> name) {
        if (onOwner()) delegate.popPush(name);
    }

    @Override
    public void markForCharting(MetricCategory category) {
        if (onOwner()) delegate.markForCharting(category);
    }

    @Override
    public void incrementCounter(String name, int amount) {
        if (onOwner()) delegate.incrementCounter(name, amount);
    }

    @Override
    public void incrementCounter(Supplier<String> name, int amount) {
        if (onOwner()) delegate.incrementCounter(name, amount);
    }
}
