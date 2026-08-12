/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RingBufferLogBus}: the ring buffer and live
 * subscriber list together form the bus's contract.
 *
 * <p>The contract, restated:</p>
 * <ul>
 *   <li>{@link RingBufferLogBus#publish} never blocks and never
 *       throws on the calling thread when a handler throws;</li>
 *   <li>the ring keeps at most {@code capacity} entries and
 *       overflow increments {@link RingBufferLogBus#droppedCount()};</li>
 *   <li>{@link RingBufferLogBus#recent} returns events newest-first
 *       up to its {@code max} argument;</li>
 *   <li>subscribers see only events published after their
 *       subscription;</li>
 *   <li>a {@link LogSubscription#close} removes the handler from
 *       the dispatch list.</li>
 * </ul>
 */
@DisplayName("RingBufferLogBus")
class RingBufferLogBusTest
{
    @Test
    @DisplayName("constructor rejects non-positive capacity")
    void shouldRejectNonPositiveCapacity()
    {
        assertThatThrownBy(() -> new RingBufferLogBus(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("capacity");

        assertThatThrownBy(() -> new RingBufferLogBus(-1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("capacity");
    }

    @Test
    @DisplayName("publish rejects null")
    void shouldRejectNullEvent()
    {
        final RingBufferLogBus bus = new RingBufferLogBus(8);

        assertThatThrownBy(() -> bus.publish(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("publish lands on the ring and is visible to recent()")
    void shouldPublishAndReadBack()
    {
        final RingBufferLogBus bus = new RingBufferLogBus(8);

        bus.publish(infoEvent("a"));

        bus.publish(infoEvent("b"));

        final List<LogEvent> recent = bus.recent(8);

        // Newest-first: "b" then "a".
        assertThat(recent).hasSize(2);

        assertThat(recent.get(0).message()).isEqualTo("b");

        assertThat(recent.get(1).message()).isEqualTo("a");
    }

    @Test
    @DisplayName("ring buffer overflow drops the oldest and increments droppedCount")
    void shouldDropOldestOnOverflow()
    {
        final RingBufferLogBus bus = new RingBufferLogBus(3);

        bus.publish(infoEvent("a"));

        bus.publish(infoEvent("b"));

        bus.publish(infoEvent("c"));

        bus.publish(infoEvent("d"));

        assertThat(bus.droppedCount()).isEqualTo(1L);

        // The three most recent are b, c, d — a was overwritten.
        final List<LogEvent> recent = bus.recent(10);

        assertThat(recent).hasSize(3);

        assertThat(recent.get(0).message()).isEqualTo("d");

        assertThat(recent.get(2).message()).isEqualTo("b");
    }

    @Test
    @DisplayName("recent(max) returns at most max entries even if more are in the ring")
    void shouldRespectRecentMax()
    {
        final RingBufferLogBus bus = new RingBufferLogBus(16);

        for (int i = 0; i < 10; i++)
        {
            bus.publish(infoEvent("e-" + i));
        }

        final List<LogEvent> recent = bus.recent(3);

        assertThat(recent).hasSize(3);

        // Newest first: e-9, e-8, e-7.
        assertThat(recent.get(0).message()).isEqualTo("e-9");

        assertThat(recent.get(2).message()).isEqualTo("e-7");
    }

    @Test
    @DisplayName("recent rejects non-positive max")
    void shouldRejectNonPositiveMax()
    {
        final RingBufferLogBus bus = new RingBufferLogBus(4);

        assertThatThrownBy(() -> bus.recent(0))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> bus.recent(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a subscriber sees events published after subscribe()")
    void shouldDeliverToLiveSubscriber()
    {
        final RingBufferLogBus bus = new RingBufferLogBus(8);

        final List<LogEvent> received = new ArrayList<>();

        bus.subscribe(received::add);

        bus.publish(infoEvent("a"));

        bus.publish(infoEvent("b"));

        assertThat(received).hasSize(2);

        assertThat(received.get(0).message()).isEqualTo("a");

        assertThat(received.get(1).message()).isEqualTo("b");
    }

    @Test
    @DisplayName("a subscriber does NOT see events published before subscribe()")
    void shouldNotDeliverPreSubscriptionEvents()
    {
        final RingBufferLogBus bus = new RingBufferLogBus(8);

        bus.publish(infoEvent("pre"));

        final List<LogEvent> received = new ArrayList<>();

        bus.subscribe(received::add);

        bus.publish(infoEvent("post"));

        assertThat(received).hasSize(1);

        assertThat(received.get(0).message()).isEqualTo("post");
    }

    @Test
    @DisplayName("a throwing handler does not stop the bus from dispatching to siblings")
    void shouldIsolateHandlerExceptions()
    {
        final RingBufferLogBus bus = new RingBufferLogBus(8);

        final AtomicInteger received = new AtomicInteger();

        bus.subscribe(e -> {
            throw new RuntimeException("boom");
        });

        bus.subscribe(e -> received.incrementAndGet());

        bus.publish(infoEvent("a"));

        // The throwing handler must not block the second handler.
        assertThat(received.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("close() makes publish a no-op and clears subscribers")
    void shouldCloseAndStopDelivering()
    {
        final RingBufferLogBus bus = new RingBufferLogBus(8);

        final AtomicInteger received = new AtomicInteger();

        bus.subscribe(e -> received.incrementAndGet());

        bus.publish(infoEvent("pre-close"));

        bus.close();

        bus.publish(infoEvent("post-close"));

        assertThat(received.get())
            .as("subscriber should not see events published after close()")
            .isEqualTo(1);

        // recent() still works on the closed bus (it reads the
        // ring buffer, not the subscriber list).
        assertThat(bus.recent(8)).hasSize(1);
    }

    @Test
    @DisplayName("a LogSubscription.close() removes the handler from dispatch")
    void shouldRemoveSubscriptionOnClose()
    {
        final RingBufferLogBus bus = new RingBufferLogBus(8);

        final AtomicInteger received = new AtomicInteger();

        final LogSubscription sub = bus.subscribe(e -> received.incrementAndGet());

        bus.publish(infoEvent("a"));

        sub.close();

        bus.publish(infoEvent("b"));

        assertThat(received.get())
            .as("handler should not be invoked after subscription closed")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("drain() returns and removes every buffered event, oldest first")
    void shouldDrainAllEvents()
    {
        final RingBufferLogBus bus = new RingBufferLogBus(8);

        bus.publish(new LogEvent(1L, "engine.core", "L", LogLevel.INFO, "a", null));
        bus.publish(new LogEvent(2L, "engine.core", "L", LogLevel.INFO, "b", null));
        bus.publish(new LogEvent(3L, "engine.core", "L", LogLevel.INFO, "c", null));

        final List<LogEvent> drained = bus.drain();

        assertThat(drained).hasSize(3);

        assertThat(drained.get(0).message()).isEqualTo("a");
        assertThat(drained.get(1).message()).isEqualTo("b");
        assertThat(drained.get(2).message()).isEqualTo("c");

        // The ring is empty afterwards.
        assertThat(bus.recent(10)).isEmpty();

        // A second drain returns an empty list, not a duplicate.
        assertThat(bus.drain()).isEmpty();
    }

    @Test
    @DisplayName("drain() on an empty bus returns an empty list")
    void shouldDrainEmptyBusCleanly()
    {
        final RingBufferLogBus bus = new RingBufferLogBus(8);

        assertThat(bus.drain()).isEmpty();
    }

    @Test
    @DisplayName("drain() after overflow preserves the last N events in arrival order")
    void shouldDrainAfterOverflow()
    {
        final RingBufferLogBus bus = new RingBufferLogBus(3);

        // Five events into a 3-deep ring; the first two are dropped
        // by overflow, the last three are still in the ring.
        for (int i = 1; i <= 5; i++)
        {
            bus.publish(new LogEvent(i, "engine.core", "L", LogLevel.INFO,
                "msg-" + i, null));
        }

        final List<LogEvent> drained = bus.drain();

        assertThat(drained).hasSize(3);

        assertThat(drained.get(0).message()).isEqualTo("msg-3");
        assertThat(drained.get(1).message()).isEqualTo("msg-4");
        assertThat(drained.get(2).message()).isEqualTo("msg-5");

        assertThat(bus.droppedCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("drain() does not duplicate events that live subscribers have already seen")
    void shouldNotDoubleDeliverViaDrain()
    {
        final RingBufferLogBus bus = new RingBufferLogBus(8);

        final List<String> seen = new ArrayList<>();

        bus.subscribe(e -> seen.add(e.message()));

        bus.publish(new LogEvent(1L, "engine.core", "L", LogLevel.INFO, "x", null));
        bus.publish(new LogEvent(2L, "engine.core", "L", LogLevel.INFO, "y", null));

        // Live subscribers have already seen both events.
        assertThat(seen).containsExactly("x", "y");

        // drain() must NOT replay them.
        final List<LogEvent> drained = bus.drain();

        assertThat(drained).extracting(LogEvent::message).containsExactly("x", "y");

        // Live subscriber count is unchanged.
        assertThat(seen).containsExactly("x", "y");
    }

    @Test
    @DisplayName("multiple subscribers each see every event")
    void shouldDeliverToAllSubscribers()
    {
        final RingBufferLogBus bus = new RingBufferLogBus(8);

        final AtomicInteger a = new AtomicInteger();

        final AtomicInteger b = new AtomicInteger();

        bus.subscribe(e -> a.incrementAndGet());

        bus.subscribe(e -> b.incrementAndGet());

        for (int i = 0; i < 5; i++)
        {
            bus.publish(infoEvent("e-" + i));
        }

        assertThat(a.get()).isEqualTo(5);

        assertThat(b.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("subscribe is safe to call from a handler (CopyOnWrite iteration)")
    void shouldAllowSubscribeFromInsideHandler() throws Exception
    {
        final RingBufferLogBus bus = new RingBufferLogBus(8);

        final AtomicInteger inner = new AtomicInteger();

        final CountDownLatch done = new CountDownLatch(1);

        bus.subscribe(e -> {
            // The CopyOnWrite list keeps this from corrupting
            // the in-progress iteration; the inner handler is
            // added to the list and is called for the next
            // publish, not the current one.
            bus.subscribe(e2 -> inner.incrementAndGet());

            done.countDown();
        });

        bus.publish(infoEvent("first"));

        assertThat(done.await(1L, TimeUnit.SECONDS)).isTrue();

        bus.publish(infoEvent("second"));

        // The inner subscriber should have been invoked on the
        // second publish only.
        assertThat(inner.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("subscribe rejects null handler")
    void shouldRejectNullHandler()
    {
        final RingBufferLogBus bus = new RingBufferLogBus(8);

        assertThatThrownBy(() -> bus.subscribe(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static LogEvent infoEvent(final String message)
    {
        return new LogEvent(System.currentTimeMillis(), "engine.core",
            "com.openfps.engine.test", LogLevel.INFO, message, null);
    }
}
