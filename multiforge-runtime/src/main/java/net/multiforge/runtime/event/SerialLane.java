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
package net.multiforge.runtime.event;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import net.multiforge.runtime.diagnostics.ProbeRegistry;
import net.multiforge.runtime.ownership.Domain;
import net.multiforge.runtime.ownership.OwnerToken;

/**
 * The serial lane: one thread (the server thread) on which work that must
 * not run concurrently with itself is executed, one job at a time.
 *
 * <p>In the barrier tick model the server thread waits at the tick barrier
 * while regions run, pumping tasks (see {@code TickRegionScheduler.driveTick}).
 * A region worker that calls {@link #run} hands its job to that pump and
 * waits for it; the job runs on the server thread under the worker's {@link
 * OwnerToken}, so ownership checks treat its world writes exactly as the
 * worker's own. Because the worker waits, the job's effects on an event
 * (cancellation, results) are visible to the worker when {@link #run}
 * returns.
 *
 * <p>Any other caller — the lane thread itself, a thread with no region
 * token, or any caller while no lane thread is bound — runs the job inline.
 */
public final class SerialLane {

    private static final class Job {
        final Runnable task;
        final OwnerToken token;
        final Thread waiter;
        volatile boolean done;
        volatile Throwable failure;
        /** Nanoseconds the job itself ran; written before {@link #done}. */
        long runNanos;

        Job(Runnable task, OwnerToken token, Thread waiter) {
            this.task = task;
            this.token = token;
            this.waiter = waiter;
        }
    }

    private static volatile Thread laneThread;

    /** Nesting of {@link #run} jobs on the lane thread (touched by the lane thread only). */
    private static int laneDepth;

    private static final ConcurrentLinkedQueue<Job> QUEUE = new ConcurrentLinkedQueue<>();

    private SerialLane() {}

    /** Bind the lane to {@code thread} (the server thread). */
    public static void bind(Thread thread) {
        laneThread = Objects.requireNonNull(thread, "thread");
    }

    /** Unbind (server stop); pending jobs run inline on the calling thread. */
    public static void unbind() {
        laneThread = null;
        drain();
    }

    /** Whether the calling thread would hand {@link #run} jobs to the lane. */
    public static boolean handsOff() {
        Thread lane = laneThread;
        return lane != null
                && Thread.currentThread() != lane
                && OwnerToken.current().domain() == Domain.REGION;
    }

    /**
     * Run {@code task} on the lane and return once it finished; an exception
     * it throws is rethrown here.
     */
    public static void run(Runnable task) {
        Objects.requireNonNull(task, "task");
        Thread lane = laneThread;
        if (lane == null || OwnerToken.current().domain() != Domain.REGION) {
            task.run();
            return;
        }
        if (Thread.currentThread() == lane) {
            // Already on the lane: a region ticking on the server thread, or a
            // listener of an event the lane is already posting. Only the
            // outermost job is a post a worker would have handed off.
            if (laneDepth == 0) {
                ProbeRegistry.bump("serial-lane.inline");
                net.multiforge.runtime.region.RegionTickWatchdog.countSerialPost();
            }
            laneDepth++;
            try {
                task.run();
            } finally {
                laneDepth--;
            }
            return;
        }
        Job job = new Job(task, OwnerToken.current(), Thread.currentThread());
        QUEUE.add(job);
        ProbeRegistry.bump("serial-lane.handoff");
        net.multiforge.runtime.region.RegionTickWatchdog.countSerialPost();
        LockSupport.unpark(lane);
        net.multiforge.runtime.region.RegionTickWatchdog.beginWait();
        try {
            while (!job.done) {
                LockSupport.park(job);
                if (laneThread == null && !job.done) drain(); // lane went away while we waited
            }
        } finally {
            // The listeners the lane ran are the region's work; only the hand-off is a wait.
            net.multiforge.runtime.region.RegionTickWatchdog.endWait(
                    net.multiforge.runtime.region.RegionTickWatchdog.SERIAL_LANE, job.done ? job.runNanos : 0L);
        }
        Throwable failure = job.failure;
        if (failure instanceof RuntimeException re) throw re;
        if (failure instanceof Error err) throw err;
        if (failure != null) throw new IllegalStateException("serial lane job failed", failure);
    }

    /**
     * Run every queued job on the calling thread (the lane thread's pump).
     *
     * @return whether any job ran
     */
    public static boolean drain() {
        boolean ran = false;
        Job job;
        while ((job = QUEUE.poll()) != null) {
            ran = true;
            Job current = job;
            boolean onLane = Thread.currentThread() == laneThread;
            if (onLane) laneDepth++;
            long start = System.nanoTime();
            try {
                OwnerToken.runAs(current.token, current.task);
            } catch (Throwable t) {
                current.failure = t;
            } finally {
                if (onLane) laneDepth--;
                current.runNanos = System.nanoTime() - start;
                current.done = true;
                LockSupport.unpark(current.waiter);
            }
        }
        return ran;
    }
}
