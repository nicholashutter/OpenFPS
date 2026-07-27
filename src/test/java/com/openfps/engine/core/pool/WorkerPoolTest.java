/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.pool;

import com.openfps.engine.core.event.EventFactory;
import com.openfps.engine.core.event.I_EngineEvent;
import com.openfps.engine.core.event.TickEvent;
import com.openfps.engine.core.eventbus.EventBusFactory;
import com.openfps.engine.core.eventbus.I_EventBusPort;
import com.openfps.engine.core.subsystem.SubsystemId;
import com.openfps.engine.core.subsystem.SubsystemRegistry;
import com.openfps.engine.hal.adapter.nulladapter.NullTimePort;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link WorkerPool} — verifies hot threads, parallel
 * dispatch, and clean shutdown.
 */
class WorkerPoolTest
{
    private I_EventBusPort bus;
    private SubsystemRegistry registry;
    private I_ThreadPoolPort pool;
    private final EventFactory factory = new EventFactory(new NullTimePort());

    @BeforeEach
    void setUp()
    {
        bus = EventBusFactory.createShared();
        bus.init(64);
        registry = new SubsystemRegistry();
    }

    @Test
    @DisplayName("init/start spawns the requested number of hot threads")
    void shouldStartHotThreads() throws Exception
    {
        pool = ThreadPoolFactory.createFixed(bus, registry);
        pool.init(4);
        assertThat(pool.state()).isEqualTo(I_ThreadPoolPort.State.READY);
        assertThat(pool.workerCount()).isEqualTo(4);

        pool.start();
        assertThat(pool.state()).isEqualTo(I_ThreadPoolPort.State.RUNNING);
        Thread.sleep(50);  // give the JVM time to spin up the threads
        assertThat(pool.activeWorkerCount()).isEqualTo(4);

        pool.shutdown();
        assertThat(pool.awaitTermination(2000)).isTrue();
    }

    @Test
    @DisplayName("workers consume events from the bus and dispatch them")
    void shouldConsumeAndDispatch() throws Exception
    {
        // Counter subsystem
        final AtomicInteger processed = new AtomicInteger(0);
        registry.register(new com.openfps.engine.core.subsystem.Subsystem(SubsystemId.P_)
        {
            @Override
            protected void onEvent(final I_EngineEvent event)
            {
                processed.incrementAndGet();
            }
        });
        registry.initAll();

        // Use a larger bus so publishing 100 events doesn't block the test thread.
        // We can't re-init a SHUTDOWN bus, so create a fresh one.
        bus.shutdown();
        bus = EventBusFactory.createShared();
        bus.init(256);

        pool = ThreadPoolFactory.createFixed(bus, registry);
        pool.init(2);
        pool.start();

        // Publish 100 events
        for (int i = 0; i < 100; i++)
        {
            bus.publish(factory.newTick(i, 0));
        }

        // Wait for all events to be processed
        for (int i = 0; i < 100; i++)
        {
            while (processed.get() < i + 1)
            {
                Thread.sleep(5);
                if (Thread.currentThread().isInterrupted())
                {
                    fail("interrupted");
                }
            }
        }
        assertThat(processed.get()).isEqualTo(100);

        pool.shutdown();
        assertThat(pool.awaitTermination(2000)).isTrue();
    }

    @Test
    @DisplayName("multiple workers process events in parallel")
    void shouldParallelizeDispatch() throws Exception
    {
        final int workers = 4;
        final int events = 40;
        final ConcurrentLinkedQueue<String> log = new ConcurrentLinkedQueue<>();
        final CountDownLatch allDone = new CountDownLatch(events);

        registry.register(new com.openfps.engine.core.subsystem.Subsystem(SubsystemId.P_)
        {
            @Override
            protected void onEvent(final I_EngineEvent event)
            {
                try
                {
                    Thread.sleep(5);
                }
                catch (final InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                log.add(Thread.currentThread().getName() + " processed " + event);
                allDone.countDown();
            }
        });
        registry.initAll();

        pool = ThreadPoolFactory.createFixed(bus, registry);
        pool.init(workers);
        pool.start();

        for (int i = 0; i < events; i++)
        {
            bus.publish(factory.newTick(i, 0));
        }

        assertThat(allDone.await(5, TimeUnit.SECONDS))
            .as("all events should be processed within 5s")
            .isTrue();
        // Confirm multiple worker thread names were used
        final long distinctWorkerNames = log.stream()
            .map(s -> s.split(" ")[0])
            .distinct()
            .count();
        assertThat(distinctWorkerNames)
            .as("should use multiple worker threads")
            .isGreaterThan(1);

        pool.shutdown();
        assertThat(pool.awaitTermination(2000)).isTrue();
    }

    @Test
    @DisplayName("init() with bad worker count throws")
    void shouldThrowOnBadInit()
    {
        pool = ThreadPoolFactory.createFixed(bus, registry);
        assertThatThrownBy(() -> pool.init(0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pool.init(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("start() before init() throws")
    void shouldThrowOnStartBeforeInit()
    {
        pool = ThreadPoolFactory.createFixed(bus, registry);
        assertThatThrownBy(pool::start)
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("double-shutdown is rejected")
    void shouldThrowOnDoubleShutdown() throws Exception
    {
        pool = ThreadPoolFactory.createFixed(bus, registry);
        pool.init(2);
        pool.start();
        pool.shutdown();
        assertThatThrownBy(pool::shutdown)
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("worker dispatch error doesn't kill the worker")
    void shouldSurviveHandlerException() throws Exception
    {
        final AtomicInteger processed = new AtomicInteger(0);
        registry.register(new com.openfps.engine.core.subsystem.Subsystem(SubsystemId.P_)
        {
            @Override
            protected void onEvent(final I_EngineEvent event)
            {
                final int n = processed.incrementAndGet();
                if (n == 1)
                {
                    throw new RuntimeException("intentional");
                }
            }
        });
        registry.initAll();

        pool = ThreadPoolFactory.createFixed(bus, registry);
        pool.init(1);
        pool.start();

        for (int i = 0; i < 3; i++)
        {
            bus.publish(factory.newTick(i, 0));
        }

        // Give the worker time to process all three events (including the one that throws)
        for (int i = 0; i < 50 && processed.get() < 3; i++)
        {
            Thread.sleep(50);
        }
        assertThat(processed.get()).isEqualTo(3);

        pool.shutdown();
        assertThat(pool.awaitTermination(2000)).isTrue();
    }
}
