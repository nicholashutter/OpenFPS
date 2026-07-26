/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.eventbus;

import com.openfps.engine.core.event.EventFactory;
import com.openfps.engine.core.event.I_EngineEvent;
import com.openfps.engine.core.event.TickEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link SharedEventBus}.
 */
class SharedEventBusTest
{
    private SharedEventBus bus;
    private EventFactory factory;

    @BeforeEach
    void setUp()
    {
        bus = new SharedEventBus();
        factory = new EventFactory(0L);
    }

    @Test
    @DisplayName("init() moves UNINITIALIZED → READY")
    void shouldInitToReady()
    {
        bus.init(16);
        assertThat(bus.state()).isEqualTo(I_EventBusPort.State.READY);
        assertThat(bus.capacity()).isEqualTo(16);
        assertThat(bus.pendingCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("publish then take returns the same event")
    void shouldRoundTrip()
    {
        bus.init(16);
        final TickEvent event = factory.newTick(0, 100);
        try
        {
            bus.publish(event);
        }
        catch (final InterruptedException e)
        {
            fail("publish was interrupted");
        }
        assertThat(bus.pendingCount()).isEqualTo(1);
        try
        {
            assertThat(bus.take()).isSameAs(event);
        }
        catch (final InterruptedException e)
        {
            fail("take was interrupted");
        }
        assertThat(bus.pendingCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("FIFO ordering across multiple events")
    void shouldFifo()
    {
        bus.init(16);
        for (int i = 0; i < 5; i++)
        {
            try
            {
                bus.publish(factory.newTick(i, 0));
            }
            catch (final InterruptedException e)
            {
                fail("publish was interrupted");
            }
        }
        for (int i = 0; i < 5; i++)
        {
            try
            {
                final I_EngineEvent e = bus.take();
                assertThat(e).isInstanceOf(TickEvent.class);
                assertThat(((TickEvent) e).ticNumber()).isEqualTo(i);
            }
            catch (final InterruptedException ex)
            {
                fail("take was interrupted");
            }
        }
    }

    @Test
    @DisplayName("publish() blocks when the queue is full (backpressure)")
    void shouldBlockWhenFull() throws Exception
    {
        bus.init(2);
        bus.publish(factory.newTick(0, 0));
        bus.publish(factory.newTick(1, 0));
        // Queue is now full
        final AtomicReference<Boolean> publishedAfterDelay = new AtomicReference<>(false);
        final CountDownLatch publisherStarted = new CountDownLatch(1);
        final Thread publisher = new Thread(() -> {
            publisherStarted.countDown();
            try
            {
                bus.publish(factory.newTick(2, 0));
                publishedAfterDelay.set(true);
            }
            catch (final InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        });
        publisher.start();
        assertThat(publisherStarted.await(100, TimeUnit.MILLISECONDS)).isTrue();
        // Give the publisher time to block
        Thread.sleep(100);
        assertThat(publishedAfterDelay.get())
            .as("publisher should still be blocked")
            .isFalse();

        // Drain one event — the publisher should now unblock
        bus.take();
        Thread.sleep(100);
        assertThat(publishedAfterDelay.get())
            .as("publisher should have unblocked")
            .isTrue();
    }

    @Test
    @DisplayName("take() blocks when the queue is empty")
    void shouldBlockWhenEmpty() throws Exception
    {
        bus.init(4);
        final AtomicReference<I_EngineEvent> taken = new AtomicReference<>();
        final Thread consumer = new Thread(() -> {
            try
            {
                taken.set(bus.take());
            }
            catch (final InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();
        Thread.sleep(100);
        assertThat(taken.get()).isNull();

        bus.publish(factory.newTick(42, 0));
        Thread.sleep(100);
        assertThat(taken.get()).isNotNull();
        assertThat(((TickEvent) taken.get()).ticNumber()).isEqualTo(42);
    }

    @Test
    @DisplayName("drain() flips state to DRAINING; take() returns null when empty")
    void shouldDrain() throws Exception
    {
        bus.init(8);
        bus.publish(factory.newTick(0, 0));
        bus.publish(factory.newTick(1, 0));
        bus.drain();
        assertThat(bus.state()).isEqualTo(I_EventBusPort.State.DRAINING);

        // Remaining events still come out
        assertThat(((TickEvent) bus.take()).ticNumber()).isEqualTo(0);
        assertThat(((TickEvent) bus.take()).ticNumber()).isEqualTo(1);

        // Now empty — take returns null instead of blocking
        assertThat(bus.take()).isNull();
    }

    @Test
    @DisplayName("shutdown() throws on subsequent operations")
    void shouldThrowAfterShutdown()
    {
        bus.init(4);
        bus.shutdown();
        assertThat(bus.state()).isEqualTo(I_EventBusPort.State.SHUTDOWN);

        assertThatThrownBy(() -> bus.publish(factory.newTick(0, 0)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bus.take())
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(bus::drain)
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("publish() to non-READY bus throws")
    void shouldThrowOnPublishToUninitialized()
    {
        assertThatThrownBy(() -> bus.publish(factory.newTick(0, 0)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("init() with bad capacity throws")
    void shouldThrowOnBadCapacity()
    {
        assertThatThrownBy(() -> bus.init(0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bus.init(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("publish(null) throws")
    void shouldThrowOnNullPublish()
    {
        bus.init(4);
        assertThatThrownBy(() -> bus.publish(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
