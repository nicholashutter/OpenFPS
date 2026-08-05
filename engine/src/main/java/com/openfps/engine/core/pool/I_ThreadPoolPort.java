/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.pool;

import com.openfps.engine.core.eventbus.I_EventBusPort;
import com.openfps.engine.core.subsystem.SubsystemRegistry;

/**
 * Port interface for the worker thread pool.
 *
 * The pool owns N hot worker threads, where N is whatever
 * {@link ThreadPoolFactory#resolveWorkerCount(int)} decided — one per logical
 * processor bar one, unless it was pinned. Each worker loops: take event from
 * bus → dispatch to subsystem → return to pool. Threads are pre-started at
 * {@link #start()} and stay alive (blocked on the bus) until
 * {@link #shutdown()}.
 *
 * Subsystem dispatch uses a {@link SubsystemRegistry}. The pool itself
 * is a state machine:
 *
 *   UNINITIALIZED ──init()──► READY ──start()──► RUNNING
 *                                                  │
 *                                            shutdown()
 *                                                  ▼
 *                                              DRAINING ──(last worker exits)──► SHUTDOWN
 *
 * On top of that draining loop the pool offers one fan-out primitive,
 * {@link #submitParallel(I_ParallelJob, int)} — "run indices 0..N-1 across
 * the workers and return when they are all done". The renderer's tiled
 * raster pass is the motivating caller; see the render package README § 7.
 */
public interface I_ThreadPoolPort
{
    /**
     * Lifecycle: UNINITIALIZED → READY.
     * @param workerCount number of worker threads to create
     */
    void init(int workerCount);

    /** Lifecycle: READY → RUNNING. Pre-starts all worker threads. */
    void start();

    /** Lifecycle: RUNNING|READY → SHUTDOWN. Drains the bus and stops workers. */
    void shutdown();

    /**
     * Waits for all workers to finish their current event and exit.
     *
     * @param timeoutMillis max time to wait
     * @return true if all workers exited, false on timeout
     * @throws InterruptedException if interrupted while waiting
     */
    boolean awaitTermination(long timeoutMillis) throws InterruptedException;

    /**
     * Runs {@code job} once for every index in {@code [0, jobCount)} across
     * the pool, and returns only when every index has completed.
     *
     * <b>The calling thread participates.</b> It claims and runs jobs itself
     * until the range is exhausted, and only then waits on the jobs other
     * workers already claimed. This is not an optimisation, it is the
     * correctness property: the caller of this method is usually itself a
     * pool worker, because subsystems are dispatched from the bus. A
     * submit-and-block implementation would remove a worker from the very
     * pool that has to execute the batch — total deadlock at
     * {@code workerCount == 1}, and a latent one above it as soon as two
     * subsystems fan out in the same frame. Because the caller alone can
     * drive the whole range to completion, progress never depends on how
     * many workers exist.
     *
     * <b>Allocation.</b> Nothing is allocated per job or per submission.
     * Callers are expected to hold one long-lived {@link I_ParallelJob} and
     * pass it every frame.
     *
     * <b>Ordering.</b> Indices are claimed in ascending order but complete in
     * no particular order, and no index is tied to any particular thread.
     *
     * <b>Failure.</b> A job that throws does not abort the batch and does not
     * hang the join; every index still runs. The first failure is rethrown to
     * this caller wrapped in a {@link ParallelJobException} once the batch has
     * joined, and any further failures are logged. See that class for the full
     * policy.
     *
     * <b>Reentrancy.</b> Supported. A job may call {@code submitParallel}
     * again; the nested submission is independent and the thread running the
     * outer job participates in it exactly as any other caller would. Nesting
     * depth is bounded only by the number of free batch slots; when they are
     * all in flight the submission simply runs its whole range on the calling
     * thread, which is the same correctness guarantee with no parallelism.
     * Concurrent submissions from different threads are likewise supported.
     *
     * <b>Interruption.</b> This method does not abandon a batch on interrupt;
     * it cannot, because the jobs are already running. The interrupt flag is
     * preserved and restored before returning.
     *
     * @param job       the work to run; must not be null
     * @param jobCount  number of indices to run; 0 is a no-op, negative is an error
     * @throws IllegalArgumentException if job is null or jobCount is negative
     * @throws IllegalStateException    if the pool is not RUNNING
     * @throws ParallelJobException     if any job threw
     */
    void submitParallel(I_ParallelJob job, int jobCount);

    /** Returns the configured number of workers. */
    int workerCount();

    /** Returns the pool state. */
    State state();

    /** Pool lifecycle states. See the state diagram in the class Javadoc. */
    enum State
    {
        /** Default state at construction. Must call init() to advance. */
        UNINITIALIZED,
        /** Worker count configured; threads not yet started. */
        READY,
        /** Workers are hot and draining the bus. */
        RUNNING,
        /**
         * {@code shutdown()} has been called and workers are finishing the
         * events already queued, but have not all exited.
         *
         * <b>This state exists because dispatch legitimately continues after
         * shutdown begins.</b> Draining the bus means the remaining events are
         * still delivered to their subsystems, and a subsystem handling one may
         * fan out through {@link #submitParallel}. Collapsing this window into
         * {@code SHUTDOWN} made that a hard failure: the renderer threw twice on
         * every clean exit, because the trailing {@code RenderFrameEvent}s were
         * dispatched to a pool that had already declared itself terminal.
         *
         * Parallel submission is therefore still permitted here — the workers
         * are alive and the batch machinery is intact. What is not permitted is
         * a second {@code shutdown()}.
         */
        DRAINING,
        /** Terminal state. All workers have exited. */
        SHUTDOWN
    }
}
