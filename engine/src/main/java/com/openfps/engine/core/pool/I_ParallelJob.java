/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.pool;

/**
 * One indexed unit of parallel work, run by
 * {@link I_ThreadPoolPort#submitParallel(I_ParallelJob, int)}.
 *
 * The shape is deliberately narrow: a submission is "run indices
 * {@code 0 .. jobCount-1}", and the job object is a single long-lived
 * callback invoked once per index. That is exactly what the tiled
 * rasterizer needs — the index is the tile index — and it is what keeps the
 * submit path allocation-free. A general future-returning executor would
 * force a task object (and, with boxed indices, a {@code Integer}) per tile
 * per frame; at 320x200 with 64x64 tiles that is garbage in the frame path
 * for no benefit.
 *
 * Implementations are expected to be stored once and reused every frame:
 *
 * <pre>
 *   private final I_ParallelJob rasterizeTile = this::rasterizeTile;
 *   ...
 *   pool.submitParallel(rasterizeTile, tileCount);   // no allocation
 * </pre>
 *
 * Contract for implementors:
 *
 * <ul>
 *   <li>{@code runJob} is called concurrently on several threads with
 *       different indices. It must be safe for that — the renderer gets
 *       this for free because a tile is owned exclusively by whichever
 *       thread claimed it.</li>
 *   <li>Each index in the range is passed exactly once, but the order and
 *       the thread assignment are unspecified. Do not depend on either.</li>
 *   <li>A job must not block waiting on other engine work. It may call
 *       {@code submitParallel} again (see that method's reentrancy note),
 *       but it must not, for example, wait on an event that only the
 *       submitting thread could publish — the submitting thread is busy
 *       running jobs.</li>
 * </ul>
 */
@FunctionalInterface
public interface I_ParallelJob
{
    /**
     * Runs the work for one index.
     *
     * @param jobIndex index in {@code [0, jobCount)}; every value in the
     *                 range is passed exactly once per submission
     */
    void runJob(int jobIndex);
}
