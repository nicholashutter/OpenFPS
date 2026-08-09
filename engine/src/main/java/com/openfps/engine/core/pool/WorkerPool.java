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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Worker thread pool implementation.
 *
 * Workers are pre-started (hot) and serve two kinds of work:
 *
 * <ol>
 *   <li><b>Event draining.</b> Take an event from the bus, dispatch it to
 *       the target subsystem, repeat. This is the original behaviour and it
 *       is unchanged in every externally observable way.</li>
 *   <li><b>Parallel batches.</b> Run indices from an in-flight
 *       {@link #submitParallel(I_ParallelJob, int)} submission.</li>
 * </ol>
 *
 * On shutdown the bus enters DRAINING mode; workers continue to process
 * remaining events, then take() returns null and they exit.
 *
 * <h2>Leader / follower</h2>
 *
 * A worker blocked in {@code bus.take()} is unreachable — nothing short of
 * an interrupt can wake it, and interrupting a worker that might be inside
 * subsystem code is not something this pool is willing to do. So at most
 * <b>one</b> worker at a time is blocked in {@code take()}: the leader.
 * Every other idle worker waits on {@link #idleCondition}, which the pool
 * controls and can therefore signal when a parallel batch arrives.
 *
 * When the leader gets an event it releases leadership and signals, so
 * another idle worker takes over the bus, and only then dispatches. Event
 * throughput is unaffected — {@code LinkedBlockingQueue.take()} serialises
 * on its own lock anyway, and up to N dispatches still run concurrently.
 *
 * <h2>Caller participation</h2>
 *
 * {@code submitParallel} runs jobs on the submitting thread until the range
 * is exhausted, then joins. The submitting thread is normally a pool worker
 * that is mid-dispatch (and therefore, by construction, not the leader), so
 * blocking it without letting it work would deadlock the pool at
 * {@code workerCount == 1}. See {@code I_ThreadPoolPort.submitParallel}.
 */
public final class WorkerPool implements I_ThreadPoolPort
{
    private static final Logger LOG = LoggerFactory.getLogger(WorkerPool.class);

    /**
     * Number of parallel batches that may be in flight at once. Slots are
     * pre-allocated so a submission allocates nothing; the count bounds
     * concurrent plus nested submissions. Eight is far more fan-out than the
     * engine has subsystems, and exceeding it is not an error — the extra
     * submission runs inline on its caller.
     */
    private static final int MAX_CONCURRENT_BATCHES = 8;

    /** Spin iterations a joining thread burns before it starts yielding. */
    private static final int JOIN_SPIN_LIMIT = 256;

    /**
     * Yields a joining thread takes before it resorts to parking.
     *
     * <b>This is what makes a fine-grained parallel batch viable on Windows,
     * and it was measured, not guessed.</b> Nothing unparks a joining thread —
     * {@link ParallelBatch#awaitCompletion} is a polling loop — so its park is
     * a <i>timed</i> park, and a timed park is rounded up to the system timer
     * period. That period is 15.6 ms by default on Windows, so a join that
     * needed 50 microseconds slept for fifteen milliseconds. It showed up as
     * frame times quantised to exact multiples of 15.6 ms: the software
     * rasterizer's demo scene posted a best frame of 4 ms and a median of 31 ms
     * on eight workers, because a typical frame's eight joins caught two ticks
     * between them.
     *
     * <p>{@link Thread#yield()} has no such floor — it is a reschedule, not a
     * sleep — so the wait now costs what the work costs. The park below is kept
     * as a backstop for a batch that is genuinely stuck behind a descheduled
     * worker, where burning a core is the worse option; at that point the extra
     * millisecond does not matter.</p>
     */
    private static final int JOIN_YIELD_LIMIT = 4096;

    /** Park interval used by a joining thread once even its yield budget is gone. */
    private static final long JOIN_PARK_NANOS = 50_000L;

    /**
     * Upper bound on an idle worker's park. The condition re-check makes a
     * lost signal impossible, so this is purely a backstop: it turns any
     * future mistake in the signalling protocol into a latency blip rather
     * than a hang.
     */
    private static final long IDLE_PARK_MILLIS = 50L;

    private final I_EventBusPort bus;
    private final SubsystemRegistry registry;
    /** Set once at {@link #init(int)}, read by {@link #workerCount()}. MUTABLE. */
    private int workerCount;
    private final List<WorkerThread> workers = new ArrayList<>();
    private final AtomicInteger activeWorkers = new AtomicInteger(0);
    /** Lifecycle state. MUTABLE: read by the workers, written by {@link #init()}, {@link #start()}, {@link #shutdown()}. */
    private volatile State state;

    /** Pre-allocated parallel batch slots. Contents are reused, never replaced. */
    private final ParallelBatch[] batches = new ParallelBatch[MAX_CONCURRENT_BATCHES];

    /** True while exactly one worker is blocked in {@code bus.take()}. */
    private final AtomicBoolean leaderPresent = new AtomicBoolean(false);

    /**
     * Bumped once per submission. An idle worker records this before it looks
     * for work and re-checks it under {@link #idleLock} before parking, which
     * is what makes a batch published in that window impossible to miss.
     */
    private final AtomicLong batchEpoch = new AtomicLong(0L);

    private final ReentrantLock idleLock = new ReentrantLock();
    private final Condition idleCondition = idleLock.newCondition();

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

        for (int i = 0; i < batches.length; i++)
        {
            batches[i] = new ParallelBatch();
        }

        this.state = State.UNINITIALIZED;
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

        for (int i = 0; i < workerCount; i++)
        {
            final WorkerThread w = new WorkerThread(i, bus, registry);

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

        if (state == State.DRAINING)
        {
            throw new IllegalStateException(
                "shutdown() called from state DRAINING — shutdown is already in progress");
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

        // DRAINING, not SHUTDOWN. The workers below are still alive and still
        // dispatching whatever the bus had queued, and a subsystem handling one
        // of those events may fan out through submitParallel. Declaring the
        // pool terminal here is what made every clean exit throw.
        state = State.DRAINING;

        // Stop all workers — request stop AND interrupt so blocked take() calls wake up
        for (final WorkerThread w : workers)
        {
            w.requestStop();

            w.interruptThread();
        }

        // Followers park on our own condition, so they are woken without
        // relying on the interrupt above.
        signalAllIdleWorkers();

        // Deliberately NOT advancing to SHUTDOWN here even when every worker
        // has already exited. An earlier revision did, and it made the state
        // immediately after shutdown() depend on whether the workers happened
        // to have noticed the interrupt yet — a race that showed up as a test
        // failing roughly one run in three. shutdown() now always lands in
        // DRAINING and awaitTermination() is the single place the terminal
        // state is reached, which is both deterministic and easier to state.
        LOG.info("WorkerPool shutdown signaled");
    }

    @Override
    public boolean awaitTermination(final long timeoutMillis) throws InterruptedException
    {
        if (state != State.DRAINING && state != State.SHUTDOWN)
        {
            throw new IllegalStateException("awaitTermination() called from state " + state
                + " — only valid from DRAINING or SHUTDOWN");
        }

        final long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;

        while (activeWorkers.get() > 0 && System.nanoTime() < deadline)
        {
            Thread.sleep(10);
        }

        final boolean allExited = activeWorkers.get() == 0;

        if (allExited)
        {
            // The drain window is over: no worker can dispatch another event,
            // so no further submitParallel can legitimately arrive.
            state = State.SHUTDOWN;
        }

        return allExited;
    }

    @Override
    public void submitParallel(final I_ParallelJob job, final int jobCount)
    {
        if (job == null)
        {
            throw new IllegalArgumentException("job must not be null");
        }

        if (jobCount < 0)
        {
            throw new IllegalArgumentException("jobCount must be >= 0, got " + jobCount);
        }

        // DRAINING is permitted deliberately: the bus drain still delivers
        // queued events, and a subsystem handling one may fan out. The workers
        // are alive and the batch machinery is intact, so the work is safe to
        // run — and caller participation means it completes even if every
        // worker has already exited.
        if (state != State.RUNNING && state != State.DRAINING)
        {
            throw new IllegalStateException("submitParallel() called from state " + state
                + " — only valid from RUNNING or DRAINING");
        }

        if (jobCount == 0)
        {
            return;
        }

        final ParallelBatch batch = claimBatch();

        if (batch == null)
        {
            // Every slot is in flight. Running the whole range on this thread
            // is the degenerate case of caller participation: no parallelism,
            // but the same guarantee, and the submission never fails for want
            // of a slot.
            LOG.debug("All {} parallel batch slots in flight — running {} jobs inline",
                MAX_CONCURRENT_BATCHES, jobCount);

            runInline(job, jobCount);

            return;
        }

        batch.open(job, jobCount);

        batchEpoch.incrementAndGet();

        signalAllIdleWorkers();

        // Caller participation. This thread drains the claim counter itself,
        // so the batch completes even if no worker ever helps.
        batch.runAvailable();

        batch.awaitCompletion(jobCount);

        final Throwable firstFailure = batch.firstFailure();

        final int failures = batch.failureCount();

        batch.close();

        if (firstFailure != null)
        {
            throw new ParallelJobException(failures, jobCount, firstFailure);
        }
    }

    @Override
    public int workerCount()
    {
        return workerCount;
    }

    @Override
    public State state()
    {
        return state;
    }

    // Takes the first free batch slot, or null when all are in flight.
    private ParallelBatch claimBatch()
    {
        for (final ParallelBatch candidate : batches)
        {
            if (candidate.tryClaimSlot())
            {
                return candidate;
            }
        }

        return null;
    }

    // Fallback path: run the whole range on the calling thread, with the same
    // failure policy a batch would have applied.
    private void runInline(final I_ParallelJob job, final int jobCount)
    {
        // MUTABLE: accumulated across the range
        Throwable firstFailure = null;

        int failures = 0;

        for (int i = 0; i < jobCount; i++)
        {
            try
            {
                job.runJob(i);
            }
            catch (final Throwable t)
            {
                failures++;

                if (firstFailure == null)
                {
                    firstFailure = t;
                }
                else
                {
                    LOG.error("Parallel job {} failed (inline, additional failure)", i, t);
                }
            }
        }

        if (firstFailure != null)
        {
            throw new ParallelJobException(failures, jobCount, firstFailure);
        }
    }

    // Runs whatever is currently claimable across every in-flight batch.
    // Cheap when nothing is in flight: a handful of volatile reads.
    private void helpWithParallelWork()
    {
        for (final ParallelBatch candidate : batches)
        {
            candidate.runAvailable();
        }
    }

    // Wakes every idle worker — used when a batch is published and on shutdown.
    private void signalAllIdleWorkers()
    {
        idleLock.lock();

        try
        {
            idleCondition.signalAll();
        }
        finally
        {
            idleLock.unlock();
        }
    }

    // Wakes one idle worker — used to hand the bus over to a new leader.
    private void signalOneIdleWorker()
    {
        idleLock.lock();

        try
        {
            idleCondition.signal();
        }
        finally
        {
            idleLock.unlock();
        }
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
        private volatile Thread thread;
        private volatile boolean stopRequested;

        WorkerThread(final int id, final I_EventBusPort busRef,
                     final SubsystemRegistry registryRef)
        {
            this.id = id;

            this.busRef = busRef;

            this.registryRef = registryRef;
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
                    // Read the epoch BEFORE looking for work, so a batch
                    // published while we are looking is detected below.
                    final long seenEpoch = batchEpoch.get();

                    helpWithParallelWork();

                    if (stopRequested)
                    {
                        break;
                    }

                    if (!leaderPresent.compareAndSet(false, true))
                    {
                        awaitWork(seenEpoch);

                        continue;
                    }

                    final I_EngineEvent event = takeAsLeader();

                    if (event == null)
                    {
                        LOG.debug("Worker {} exiting (drained or interrupted)", id);

                        break;
                    }

                    dispatch(event);
                }
            }
            finally
            {
                activeWorkers.decrementAndGet();

                LOG.debug("Worker {} exited (active={})", id, activeWorkers.get());
            }
        }

        // Blocks on the bus as the sole leader. Leadership is always released,
        // and a successor always signalled, even if the bus throws.
        private I_EngineEvent takeAsLeader()
        {
            try
            {
                return busRef.take();
            }
            catch (final InterruptedException e)
            {
                LOG.debug("Worker {} interrupted — exiting", id);

                Thread.currentThread().interrupt();

                return null;
            }
            finally
            {
                leaderPresent.set(false);

                signalOneIdleWorker();
            }
        }

        // Dispatch never propagates: one bad handler must not cost a worker.
        private void dispatch(final I_EngineEvent event)
        {
            try
            {
                registryRef.dispatch(event);
            }
            catch (final RuntimeException e)
            {
                LOG.error("Worker {} dispatch threw on event {}", id, event, e);
            }
        }

        // Parks until there is something to do. The three conditions are
        // re-checked under the lock, so a signal issued between our last look
        // and the park cannot be missed.
        private void awaitWork(final long seenEpoch)
        {
            idleLock.lock();

            try
            {
                if (stopRequested || !leaderPresent.get() || batchEpoch.get() != seenEpoch)
                {
                    return;
                }

                idleCondition.await(IDLE_PARK_MILLIS, TimeUnit.MILLISECONDS);
            }
            catch (final InterruptedException e)
            {
                LOG.debug("Worker {} interrupted while idle", id);

                Thread.currentThread().interrupt();
            }
            finally
            {
                idleLock.unlock();
            }
        }
    }

    /**
     * One reusable parallel batch slot.
     *
     * <h2>Why the generation counter</h2>
     *
     * Slots are recycled, which opens an ABA window: a helper could read the
     * job of batch N and then claim an index from batch N+1 after the slot was
     * reused, running the wrong callback with an out-of-range index.
     * {@link #claimState} closes it by packing a generation in the high 32
     * bits and the next unclaimed index in the low 32. {@link #open} bumps the
     * generation before it publishes the new job, so any claim CAS carrying a
     * stale word fails outright. The index cannot carry into the generation
     * because it never exceeds {@code jobCount}, which is an {@code int}.
     */
    private static final class ParallelBatch
    {
        private final AtomicBoolean inUse = new AtomicBoolean(false);
        private final AtomicLong claimState = new AtomicLong(0L);
        private final AtomicInteger completed = new AtomicInteger(0);
        private final AtomicInteger failures = new AtomicInteger(0);
        private final AtomicReference<Throwable> firstFailure = new AtomicReference<>(null);

        /** The work. MUTABLE: rebound by open(), cleared by close(). */
        private volatile I_ParallelJob job;

        /** Range size. MUTABLE: rebound by open(); zero while the slot is idle. */
        private volatile int jobCount;

        // Takes ownership of this slot for one submission.
        private boolean tryClaimSlot()
        {
            return inUse.compareAndSet(false, true);
        }

        // Arms the slot. Called only by the thread that won tryClaimSlot().
        // Write order matters: counters, then generation, then job, then
        // jobCount. A helper reads them in the reverse order, so a visible
        // jobCount implies a visible matching job and generation.
        private void open(final I_ParallelJob newJob, final int newJobCount)
        {
            completed.set(0);

            failures.set(0);

            firstFailure.set(null);

            final long nextGeneration = (claimState.get() >>> 32) + 1L;

            claimState.set(nextGeneration << 32);

            this.job = newJob;

            this.jobCount = newJobCount;
        }

        // Claims and runs indices until the range is exhausted. Safe to call
        // on an idle or foreign slot: it simply finds nothing to do.
        private void runAvailable()
        {
            while (true)
            {
                final long snapshot = claimState.get();

                final int index = (int) (snapshot & 0xFFFFFFFFL);

                final int count = jobCount;

                final I_ParallelJob current = job;

                if (current == null || index >= count)
                {
                    return;
                }

                if (claimState.compareAndSet(snapshot, snapshot + 1L))
                {
                    runOne(current, index);
                }
            }
        }

        // The completed counter is bumped in a finally so that a job which
        // throws — including one that throws an Error — can never hang a join.
        private void runOne(final I_ParallelJob current, final int index)
        {
            try
            {
                current.runJob(index);
            }
            catch (final Throwable t)
            {
                failures.incrementAndGet();

                if (!firstFailure.compareAndSet(null, t))
                {
                    // The first failure is rethrown to the submitter; the rest
                    // would otherwise vanish, so they are logged here.
                    LOG.error("Parallel job {} failed (additional failure)", index, t);
                }
            }
            finally
            {
                completed.incrementAndGet();
            }
        }

        // Waits for indices claimed by other threads. The caller has already
        // exhausted the claim counter, so this only ever waits on work that is
        // genuinely in flight elsewhere.
        //
        // Three stages, cheapest first: spin, yield, park. The yield stage is
        // the load-bearing one and JOIN_YIELD_LIMIT explains why — a timed park
        // cannot resolve faster than the platform's timer period, which is
        // 15.6 ms on Windows, and that is an eternity next to a batch that
        // takes tens of microseconds.
        private void awaitCompletion(final int target)
        {
            // MUTABLE: backoff budget, and whether we swallowed an interrupt
            int waits = 0;

            boolean interrupted = false;

            while (completed.get() < target)
            {
                if (waits < JOIN_SPIN_LIMIT)
                {
                    Thread.onSpinWait();
                }
                else if (waits < JOIN_SPIN_LIMIT + JOIN_YIELD_LIMIT)
                {
                    Thread.yield();
                }
                else
                {
                    // parkNanos returns immediately while the interrupt flag is
                    // set, which would turn this into a hot spin. Take the flag
                    // and hand it back once the batch is joined.
                    if (Thread.interrupted())
                    {
                        interrupted = true;
                    }

                    LockSupport.parkNanos(JOIN_PARK_NANOS);
                }

                // Saturating, not wrapping: a join that parked two billion
                // times would otherwise wrap negative and fall back to spinning
                // on a core it has already decided not to burn.
                if (waits < JOIN_SPIN_LIMIT + JOIN_YIELD_LIMIT)
                {
                    waits++;
                }
            }

            if (interrupted)
            {
                Thread.currentThread().interrupt();
            }
        }

        private Throwable firstFailure()
        {
            return firstFailure.get();
        }

        private int failureCount()
        {
            return failures.get();
        }

        // Disarms and releases the slot. Called only after awaitCompletion(),
        // so no thread can still be inside a job of this batch.
        private void close()
        {
            this.job = null;

            this.jobCount = 0;

            firstFailure.set(null);

            failures.set(0);

            inUse.set(false);
        }
    }
}
