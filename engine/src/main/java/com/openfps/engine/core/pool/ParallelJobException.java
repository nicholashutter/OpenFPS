/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.pool;

/**
 * Thrown by {@link I_ThreadPoolPort#submitParallel(I_ParallelJob, int)} when
 * at least one job in the submitted range failed.
 *
 * The failure policy, stated once here because the alternatives are all
 * worse:
 *
 * <ul>
 *   <li>A throwing job does <b>not</b> abort the batch. Every index still
 *       runs. Cancelling in-flight work would leave the caller unable to say
 *       which tiles were drawn, and — worse — a half-run batch would still
 *       have to be joined, so it buys nothing.</li>
 *   <li>The <b>first</b> failure (in completion order) is kept and becomes
 *       this exception's {@linkplain #getCause() cause}. Later failures are
 *       logged at ERROR by the pool as they happen, so nothing is lost, and
 *       {@link #failureCount()} reports how many there were.</li>
 *   <li>The exception surfaces on the <b>submitting</b> thread, after the
 *       join. That is the only thread that has the context to decide what a
 *       failed frame means, and it keeps a job's failure from silently
 *       killing a pool worker.</li>
 *   <li>Pool state is untouched: the batch slot is released before this is
 *       thrown, the workers that ran the failing jobs stay hot, and the
 *       event bus is unaffected.</li>
 * </ul>
 *
 * {@code Throwable} is caught, not just {@code RuntimeException}. A job that
 * throws an {@code Error} must still be counted as complete, or the join
 * would hang forever waiting for a job that has already stopped running.
 */
public final class ParallelJobException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    private final int failureCount;
    private final int jobCount;

    /**
     * Creates the exception.
     *
     * @param failureCount number of jobs that threw
     * @param jobCount     total number of jobs in the submission
     * @param firstFailure the first failure, kept as the cause
     */
    public ParallelJobException(final int failureCount, final int jobCount,
                                final Throwable firstFailure)
    {
        super(failureCount + " of " + jobCount + " parallel jobs failed", firstFailure);

        this.failureCount = failureCount;

        this.jobCount = jobCount;
    }

    /** Returns how many jobs in the submission threw. Always 1 or more. */
    public int failureCount()
    {
        return failureCount;
    }

    /** Returns the total number of jobs in the submission. */
    public int jobCount()
    {
        return jobCount;
    }
}
