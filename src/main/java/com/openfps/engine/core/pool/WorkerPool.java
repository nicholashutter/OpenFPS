/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.pool;

import com.openfps.engine.core.event.I_EngineEvent;
import com.openfps.engine.core.eventbus.I_EventBusPort;
import com.openfps.engine.core.subsystem.SubsystemRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Worker thread pool implementation.
 *
 * Workers are pre-started (hot) and loop on take() from the bus. Each
 * worker:
 *   1. Takes an event from the bus (blocks if empty)
 *   2. Dispatches it to the target subsystem
 *   3. Returns to the top of the loop (thread implicitly released)
 *
 * On shutdown the bus enters DRAINING mode; workers continue to process
 * remaining events, then take() returns null and they exit.
 */
public final class WorkerPool implements I_ThreadPoolPort
{
    private static final Logger LOG = LoggerFactory.getLogger(WorkerPool.class);

    private final I_EventBusPort bus;
    private final SubsystemRegistry registry;
    private int workerCount;
    private final List<WorkerThread> workers = new ArrayList<>();
    private final CountDownLatch allExited;
    private final AtomicInteger activeWorkers = new AtomicInteger(0);
    private volatile State state;

    public WorkerPool(final I_EventBusPort bus, final SubsystemRegistry registry)
    {
        if (bus == null)
        {
            throw new IllegalArgumentException("bus must not be null");
        }
        if (registry == null)
        {
            throw new IllegalArgumentException("registry must not be null");
        }
        this.bus = bus;
        this.registry = registry;
        this.state = State.UNINITIALIZED;
        this.allExited = new CountDownLatch(0);  // re-set in init
    }

    @Override
    public void init(final int workerCount)
    {
        if (state != State.UNINITIALIZED)
        {
            throw new IllegalStateException("init() called from state " + state
                + " — only valid from UNINITIALIZED");
        }
        if (workerCount < 1)
        {
            throw new IllegalArgumentException("workerCount must be >= 1, got " + workerCount);
        }
        this.workerCount = workerCount;
        // Replace the latch (cannot reset an existing one)
        this.state = State.READY;
        LOG.info("WorkerPool initialized: {} workers", workerCount);
    }

    @Override
    public void start()
    {
        if (state != State.READY)
        {
            throw new IllegalStateException("start() called from state " + state
                + " — only valid from READY");
        }

        final CountDownLatch exitLatch = new CountDownLatch(workerCount);

        for (int i = 0; i < workerCount; i++)
        {
            final WorkerThread w = new WorkerThread(i, bus, registry, exitLatch);
            workers.add(w);
            activeWorkers.incrementAndGet();
            final Thread t = new Thread(w, "openfps-worker-" + i);
            t.setDaemon(true);
            w.setThread(t);
            t.start();
        }
        state = State.RUNNING;
        LOG.info("WorkerPool started: {} hot workers", workerCount);
    }

    @Override
    public void shutdown()
    {
        if (state == State.SHUTDOWN)
        {
            throw new IllegalStateException("shutdown() called from state SHUTDOWN — already terminal");
        }
        // Tell the bus to drain; workers will see null from take() once empty
        try
        {
            bus.drain();
        }
        catch (final RuntimeException e)
        {
            LOG.warn("bus.drain() threw during shutdown", e);
        }
        state = State.SHUTDOWN;
        // Stop all workers — request stop AND interrupt so blocked take() calls wake up
        for (final WorkerThread w : workers)
        {
            w.requestStop();
            w.interruptThread();
        }
        LOG.info("WorkerPool shutdown signaled");
    }

    @Override
    public boolean awaitTermination(final long timeoutMillis) throws InterruptedException
    {
        if (state != State.SHUTDOWN)
        {
            throw new IllegalStateException("awaitTermination() called from state " + state
                + " — only valid from SHUTDOWN");
        }
        final long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (activeWorkers.get() > 0 && System.nanoTime() < deadline)
        {
            Thread.sleep(10);
        }
        return activeWorkers.get() == 0;
    }

    @Override
    public int workerCount()
    {
        return workerCount;
    }

    @Override
    public int activeWorkerCount()
    {
        return activeWorkers.get();
    }

    @Override
    public State state()
    {
        return state;
    }

    /**
     * The actual worker loop. One instance runs on a single dedicated
     * thread until stop is requested or the bus is drained and empty.
     */
    private final class WorkerThread implements Runnable
    {
        private final int id;
        private final I_EventBusPort busRef;
        private final SubsystemRegistry registryRef;
        private final CountDownLatch exitLatch;
        private volatile Thread thread;
        private volatile boolean stopRequested;

        WorkerThread(final int id, final I_EventBusPort busRef,
                     final SubsystemRegistry registryRef, final CountDownLatch exitLatch)
        {
            this.id = id;
            this.busRef = busRef;
            this.registryRef = registryRef;
            this.exitLatch = exitLatch;
        }

        void setThread(final Thread t)
        {
            this.thread = t;
        }

        void requestStop()
        {
            stopRequested = true;
        }

        void interruptThread()
        {
            final Thread t = this.thread;
            if (t != null)
            {
                t.interrupt();
            }
        }

        @Override
        public void run()
        {
            LOG.debug("Worker {} started", id);
            try
            {
                while (!stopRequested)
                {
                    final I_EngineEvent event;
                    try
                    {
                        event = busRef.take();
                    }
                    catch (final InterruptedException e)
                    {
                        LOG.debug("Worker {} interrupted — exiting", id);
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (event == null)
                    {
                        LOG.debug("Worker {} exiting (drained)", id);
                        break;
                    }
                    try
                    {
                        registryRef.dispatch(event);
                    }
                    catch (final RuntimeException e)
                    {
                        LOG.error("Worker {} dispatch threw on event {}", id, event, e);
                    }
                }
            }
            finally
            {
                activeWorkers.decrementAndGet();
                exitLatch.countDown();
                LOG.debug("Worker {} exited (active={})", id, activeWorkers.get());
            }
        }
    }
}
