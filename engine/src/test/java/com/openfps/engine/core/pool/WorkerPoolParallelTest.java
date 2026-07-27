/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.pool;

import com.openfps.engine.core.event.EventFactory;
import com.openfps.engine.core.event.I_EngineEvent;
import com.openfps.engine.core.eventbus.EventBusFactory;
import com.openfps.engine.core.eventbus.I_EventBusPort;
import com.openfps.engine.core.subsystem.Subsystem;
import com.openfps.engine.core.subsystem.SubsystemId;
import com.openfps.engine.core.subsystem.SubsystemRegistry;
import com.openfps.engine.hal.adapter.nulladapter.NullTimePort;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@code WorkerPool.submitParallel} — the fan-out primitive the
 * tiled rasterizer needs.
 *
 * Every test in this class carries a hard timeout. The failure mode this
 * feature exists to prevent is a hang, and a hang that is not a test failure
 * is a hung CI job. {@code SEPARATE_THREAD} makes the timeout preemptive:
 * JUnit reports the failure at the deadline instead of waiting for a call
 * that is never coming back.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WorkerPoolParallelTest
{
    private static final String WORKER_NAME_PREFIX = "openfps-worker-";

    private I_EventBusPort bus;
    private SubsystemRegistry registry;
    private I_ThreadPoolPort pool;
    private final EventFactory factory = new EventFactory(new NullTimePort());

    @BeforeEach
    void setUp()
    {
        bus = EventBusFactory.createShared();
        bus.init(512);
        registry = new SubsystemRegistry();
    }

    @AfterEach
    void tearDown() throws Exception
    {
        if (pool != null && pool.state() != I_ThreadPoolPort.State.SHUTDOWN
            && pool.state() != I_ThreadPoolPort.State.UNINITIALIZED)
        {
            pool.shutdown();
            pool.awaitTermination(5000);
        }
        if (bus.state() != I_EventBusPort.State.SHUTDOWN)
        {
            bus.shutdown();
        }
    }

    // Brings the pool up with the given worker count.
    private void startPool(final int workers)
    {
        pool = ThreadPoolFactory.createFixed(bus, registry);
        pool.init(workers);
        pool.start();
    }

    // ------------------------------------------------------------------
    // The deadlock cases. These are the reason this feature exists.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a subsystem may fan out from inside its own event handler at workerCount == 1")
    void shouldNotDeadlockWhenTheOnlyWorkerSubmits() throws Exception
    {
        // THE headline test. A naive "queue the jobs, then block until done"
        // implementation hangs here forever: the sole worker is the thread
        // inside onEvent, so it is the thread that would be blocking, and
        // nothing is left to run what it queued. The latch timeout turns that
        // hang into a failed assertion.
        final AtomicInteger ran = new AtomicInteger(0);
        final Set<String> threadNames = ConcurrentHashMap.newKeySet();
        final CountDownLatch submitReturned = new CountDownLatch(1);
        final I_ParallelJob job = index -> recordRun(ran, threadNames);

        registry.register(new Subsystem(SubsystemId.P_)
        {
            @Override
            protected void onEvent(final I_EngineEvent event)
            {
                pool.submitParallel(job, 32);
                submitReturned.countDown();
            }
        });
        registry.initAll();

        startPool(1);
        bus.publish(factory.newTick(1, 0));

        assertThat(submitReturned.await(10, TimeUnit.SECONDS))
            .as("submitParallel() from the only worker must return, not deadlock")
            .isTrue();
        assertThat(ran.get()).isEqualTo(32);
        assertThat(threadNames)
            .as("with one worker, that worker is the caller and runs every job")
            .containsExactly(WORKER_NAME_PREFIX + "0");
    }

    @Test
    @DisplayName("the caller runs the jobs itself when every worker is occupied")
    void shouldRunEveryJobOnTheCallerWhenWorkersAreBusy() throws Exception
    {
        // Proves participation rather than mere blocking: the pool's one
        // worker is parked inside a handler and cannot possibly help, so if
        // the submitting thread were only waiting, nothing would ever run.
        final CountDownLatch handlerEntered = new CountDownLatch(1);
        final CountDownLatch releaseHandler = new CountDownLatch(1);
        registry.register(new Subsystem(SubsystemId.P_)
        {
            @Override
            protected void onEvent(final I_EngineEvent event)
            {
                handlerEntered.countDown();
                awaitQuietly(releaseHandler);
            }
        });
        registry.initAll();

        startPool(1);
        bus.publish(factory.newTick(1, 0));
        assertThat(handlerEntered.await(10, TimeUnit.SECONDS)).isTrue();

        final AtomicInteger ran = new AtomicInteger(0);
        final Set<String> threadNames = ConcurrentHashMap.newKeySet();
        final I_ParallelJob job = index -> recordRun(ran, threadNames);

        pool.submitParallel(job, 64);

        assertThat(ran.get()).isEqualTo(64);
        assertThat(threadNames).hasSize(1);
        assertThat(threadNames.iterator().next())
            .as("every job ran on the submitting thread, not on a pool worker")
            .doesNotStartWith(WORKER_NAME_PREFIX);

        releaseHandler.countDown();
    }

    // ------------------------------------------------------------------
    // Correctness of the work distribution
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every index runs exactly once under many jobs and few workers")
    void shouldRunEveryIndexExactlyOnce()
    {
        final int jobCount = 20_000;
        final AtomicIntegerArray runs = new AtomicIntegerArray(jobCount);
        final I_ParallelJob job = index -> runs.incrementAndGet(index);

        startPool(2);
        pool.submitParallel(job, jobCount);

        // MUTABLE: scanning for the first violation
        int wrong = -1;
        for (int i = 0; i < jobCount; i++)
        {
            if (runs.get(i) != 1)
            {
                wrong = i;
                break;
            }
        }
        assertThat(wrong)
            .as("index %d ran %d times instead of once", wrong, Math.max(0, wrong))
            .isEqualTo(-1);
    }

    @Test
    @DisplayName("idle workers help, so the batch is not run by the caller alone")
    void shouldSpreadJobsAcrossWorkers()
    {
        final Set<String> threadNames = ConcurrentHashMap.newKeySet();
        final AtomicInteger ran = new AtomicInteger(0);
        final I_ParallelJob job = index -> recordSlowRun(ran, threadNames);

        startPool(4);
        pool.submitParallel(job, 64);

        assertThat(ran.get()).isEqualTo(64);
        assertThat(threadNames)
            .as("idle workers should be woken to help: %s", threadNames)
            .hasSizeGreaterThan(1);
        assertThat(threadNames)
            .as("at least one pool worker took part")
            .anyMatch(WorkerPoolParallelTest::isWorkerThreadName);
    }

    @Test
    @DisplayName("a zero-length submission is a no-op")
    void shouldDoNothingForZeroJobs()
    {
        final AtomicInteger ran = new AtomicInteger(0);
        final I_ParallelJob job = index -> ran.incrementAndGet();

        startPool(2);
        pool.submitParallel(job, 0);

        assertThat(ran.get()).isZero();
    }

    // ------------------------------------------------------------------
    // Failure handling
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a throwing job propagates to the submitter without losing the batch")
    void shouldPropagateFirstFailureAndStillRunEveryJob()
    {
        final int jobCount = 200;
        final AtomicIntegerArray runs = new AtomicIntegerArray(jobCount);
        final I_ParallelJob job = index -> runAndFailAt(runs, index, 42);

        startPool(3);

        assertThatThrownBy(() -> pool.submitParallel(job, jobCount))
            .isInstanceOf(ParallelJobException.class)
            .hasMessageContaining("1 of 200")
            .hasRootCauseMessage("job 42 exploded");

        // MUTABLE: total across the range
        int total = 0;
        for (int i = 0; i < jobCount; i++)
        {
            total += runs.get(i);
        }
        assertThat(total)
            .as("a failure must not abort the rest of the batch")
            .isEqualTo(jobCount);
    }

    @Test
    @DisplayName("every failure is counted and the pool keeps working afterwards")
    void shouldCountEveryFailureAndStayUsable()
    {
        final int jobCount = 60;
        final AtomicIntegerArray runs = new AtomicIntegerArray(jobCount);
        final I_ParallelJob failing = index -> runAndFailBelow(runs, index, 5);

        startPool(3);

        final Throwable thrown = catchThrowable(() -> pool.submitParallel(failing, jobCount));
        assertThat(thrown).isInstanceOf(ParallelJobException.class);
        final ParallelJobException failure = (ParallelJobException) thrown;
        assertThat(failure.failureCount()).isEqualTo(5);
        assertThat(failure.jobCount()).isEqualTo(jobCount);
        assertThat(failure.getCause()).isInstanceOf(IllegalStateException.class);

        // Pool state must be intact: the very next submission has to work.
        final AtomicInteger ran = new AtomicInteger(0);
        final I_ParallelJob clean = index -> ran.incrementAndGet();
        pool.submitParallel(clean, 100);
        assertThat(ran.get()).isEqualTo(100);
    }

    @Test
    @DisplayName("a job throwing an Error still completes the join")
    void shouldNotHangWhenAJobThrowsAnError()
    {
        final AtomicInteger ran = new AtomicInteger(0);
        final I_ParallelJob job = index -> runOrThrowError(ran, index);

        startPool(2);

        assertThatThrownBy(() -> pool.submitParallel(job, 40))
            .isInstanceOf(ParallelJobException.class)
            .hasRootCauseInstanceOf(AssertionError.class);
        assertThat(ran.get()).isEqualTo(39);
    }

    // ------------------------------------------------------------------
    // Reentrancy and concurrency
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a job may submit its own batch, past the point where slots run out")
    void shouldSupportNestedAndConcurrentSubmissions()
    {
        // 24 concurrent outer jobs each fan out again. That is well past the
        // eight pre-allocated batch slots, so this also exercises the inline
        // fallback — which must still run every index exactly once.
        final int outerCount = 24;
        final int innerCount = 16;
        final AtomicIntegerArray outerRuns = new AtomicIntegerArray(outerCount);
        final AtomicInteger innerRuns = new AtomicInteger(0);
        final I_ParallelJob inner = index -> innerRuns.incrementAndGet();
        final I_ParallelJob outer = index -> runNested(outerRuns, index, inner, innerCount);

        startPool(4);
        pool.submitParallel(outer, outerCount);

        for (int i = 0; i < outerCount; i++)
        {
            assertThat(outerRuns.get(i)).as("outer index %d", i).isEqualTo(1);
        }
        assertThat(innerRuns.get()).isEqualTo(outerCount * innerCount);
    }

    // ------------------------------------------------------------------
    // Coexistence with the event bus — the pool's original job
    // ------------------------------------------------------------------

    @Test
    @DisplayName("events are still dispatched while and after a parallel section runs")
    void shouldKeepDispatchingEventsAroundAParallelSection() throws Exception
    {
        final int firstBurst = 40;
        final int secondBurst = 20;
        // Both latches count down on every event, so the first reaches zero
        // after the first burst and the second only after both.
        final CountDownLatch firstDispatched = new CountDownLatch(firstBurst);
        final CountDownLatch allDispatched = new CountDownLatch(firstBurst + secondBurst);
        final AtomicInteger dispatched = new AtomicInteger(0);

        registry.register(new Subsystem(SubsystemId.P_)
        {
            @Override
            protected void onEvent(final I_EngineEvent event)
            {
                dispatched.incrementAndGet();
                firstDispatched.countDown();
                allDispatched.countDown();
            }
        });
        registry.initAll();

        startPool(3);

        for (int i = 0; i < firstBurst; i++)
        {
            bus.publish(factory.newTick(i, 0));
        }

        // Fan out immediately, so the batch overlaps the burst still in flight.
        final AtomicInteger ran = new AtomicInteger(0);
        final I_ParallelJob job = index -> recordSlowRun(ran, null);
        pool.submitParallel(job, 120);
        assertThat(ran.get()).isEqualTo(120);

        assertThat(firstDispatched.await(15, TimeUnit.SECONDS))
            .as("events published before the parallel section must still be dispatched")
            .isTrue();

        for (int i = 0; i < secondBurst; i++)
        {
            bus.publish(factory.newTick(firstBurst + i, 0));
        }
        assertThat(allDispatched.await(15, TimeUnit.SECONDS))
            .as("the pool must keep draining the bus after a parallel section")
            .isTrue();
        assertThat(dispatched.get()).isEqualTo(firstBurst + secondBurst);
    }

    // ------------------------------------------------------------------
    // Argument and lifecycle validation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("null job and negative count are rejected")
    void shouldRejectBadArguments()
    {
        final I_ParallelJob job = index -> assertThat(index).isNotNegative();
        startPool(2);

        assertThatThrownBy(() -> pool.submitParallel(null, 4))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pool.submitParallel(job, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("submitting outside the RUNNING state throws")
    void shouldRejectSubmitOutsideRunningState() throws Exception
    {
        final I_ParallelJob job = index -> assertThat(index).isNotNegative();

        pool = ThreadPoolFactory.createFixed(bus, registry);
        assertThatThrownBy(() -> pool.submitParallel(job, 4))
            .as("UNINITIALIZED")
            .isInstanceOf(IllegalStateException.class);

        pool.init(2);
        assertThatThrownBy(() -> pool.submitParallel(job, 4))
            .as("READY — no workers exist yet")
            .isInstanceOf(IllegalStateException.class);

        pool.start();
        pool.shutdown();
        assertThat(pool.awaitTermination(5000)).isTrue();
        assertThatThrownBy(() -> pool.submitParallel(job, 4))
            .as("SHUTDOWN")
            .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------
    // Helpers. Extracted so no lambda in this file contains another lambda
    // and no lambda body grows past a single call (STYLE.md 6.2).
    // ------------------------------------------------------------------

    private static boolean isWorkerThreadName(final String name)
    {
        return name.startsWith(WORKER_NAME_PREFIX);
    }

    private static void recordRun(final AtomicInteger ran, final Set<String> threadNames)
    {
        ran.incrementAndGet();
        threadNames.add(Thread.currentThread().getName());
    }

    // Slow enough that a single thread cannot plausibly finish the range
    // before the others are woken.
    private static void recordSlowRun(final AtomicInteger ran, final Set<String> threadNames)
    {
        try
        {
            Thread.sleep(2);
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        ran.incrementAndGet();
        if (threadNames != null)
        {
            threadNames.add(Thread.currentThread().getName());
        }
    }

    private static void runAndFailAt(final AtomicIntegerArray runs, final int index,
                                     final int failIndex)
    {
        runs.incrementAndGet(index);
        if (index == failIndex)
        {
            throw new IllegalStateException("job " + index + " exploded");
        }
    }

    private static void runAndFailBelow(final AtomicIntegerArray runs, final int index,
                                        final int failBelow)
    {
        runs.incrementAndGet(index);
        if (index < failBelow)
        {
            throw new IllegalStateException("job " + index + " exploded");
        }
    }

    private static void runOrThrowError(final AtomicInteger ran, final int index)
    {
        if (index == 7)
        {
            throw new AssertionError("job 7 threw an Error");
        }
        ran.incrementAndGet();
    }

    private void runNested(final AtomicIntegerArray outerRuns, final int index,
                           final I_ParallelJob inner, final int innerCount)
    {
        outerRuns.incrementAndGet(index);
        pool.submitParallel(inner, innerCount);
    }

    private static void awaitQuietly(final CountDownLatch latch)
    {
        try
        {
            latch.await(20, TimeUnit.SECONDS);
        }
        catch (final InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
