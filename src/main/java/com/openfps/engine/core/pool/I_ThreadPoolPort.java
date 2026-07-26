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
 * The pool owns N = logicalProcessorCount / 2 hot worker threads. Each
 * worker loops: take event from bus → dispatch to subsystem → return to
 * pool. Threads are pre-started at {@link #start()} and stay alive
 * (blocked on the bus) until {@link #shutdown()}.
 *
 * Subsystem dispatch uses a {@link SubsystemRegistry}. The pool itself
 * is a state machine:
 *
 *   UNINITIALIZED ──init()──► READY ──start()──► RUNNING ──shutdown()──► SHUTDOWN
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

    /** Returns the configured number of workers. */
    int workerCount();

    /** Returns the number of workers currently active. */
    int activeWorkerCount();

    State state();

    enum State
    {
        UNINITIALIZED,
        READY,
        RUNNING,
        SHUTDOWN
    }
}
