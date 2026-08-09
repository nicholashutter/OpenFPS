/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.openfps.engine.core.event.RenderFrameEvent;
import com.openfps.engine.core.event.TickEvent;
import com.openfps.engine.render.port.I_RenderPort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@link RenderSubsystem} renders the newest frame asked for and
 * drops the rest.
 *
 * <p><b>Why this matters more than it looks.</b> {@code GameLoop} publishes a
 * {@link RenderFrameEvent} every tic and cannot know how long a frame takes. If
 * the consumer queues them, a rasterizer slower than the tic rate spends every
 * cycle on frames whose camera pose is already stale, falls further behind on
 * every tic, and never recovers — which is what a windowed run looked like
 * before this class coalesced. The property below is the fix, stated as a
 * test.</p>
 */
@DisplayName("RenderSubsystem")
final class RenderSubsystemTest
{
    /** How long a latched test waits before giving up, in seconds. */
    private static final long TIMEOUT_SECONDS = 10L;

    /** Requests the pile-up test publishes while one frame is in flight. */
    private static final int PILED_UP_REQUESTS = 50;

    /** An arbitrary tic index. */
    private static final int FIRST_TIC = 7;

    @Nested
    @DisplayName("coalescing")
    class Coalescing
    {
        @Test
        @DisplayName("one request renders exactly one frame, at the tic asked for")
        void shouldRenderTheRequestedFrame()
        {
            final RecordingPort port = new RecordingPort();

            final RenderSubsystem subsystem = started(port);

            subsystem.processEvent(renderFrame(FIRST_TIC));

            assertThat(port.rendered).containsExactly(FIRST_TIC);

            assertThat(subsystem.pendingFrame()).isEqualTo(-1);
        }

        @Test
        @DisplayName("requests arriving during a frame collapse to the newest one")
        void shouldRenderOnlyTheNewestRequestThatArrivedDuringAFrame()
                throws InterruptedException
        {
            // The renderer is held inside renderFrame while another thread
            // publishes fifty more requests. Only the last of them describes
            // where the camera actually is, so only the last may be rendered:
            // the other forty-nine would each cost a whole frame time to draw
            // something nobody will ever see.
            final BlockingPort port = new BlockingPort();

            final RenderSubsystem subsystem = started(port);

            final Thread first = new Thread(() -> subsystem.processEvent(renderFrame(0)),
                "coalesce-first");

            first.setDaemon(true);

            first.start();

            assertThat(port.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            for (int request = 1; request <= PILED_UP_REQUESTS; request++)
            {
                subsystem.processEvent(renderFrame(request));
            }

            port.release.countDown();

            first.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

            assertThat(port.rendered)
                .as("the frame in flight, then the newest request — never the queue")
                .containsExactly(0, PILED_UP_REQUESTS);
        }

        @Test
        @DisplayName("a request that loses the race is not rendered twice")
        void shouldNotRenderTheSameRequestTwice()
        {
            final RecordingPort port = new RecordingPort();

            final RenderSubsystem subsystem = started(port);

            subsystem.processEvent(renderFrame(1));

            subsystem.processEvent(renderFrame(2));

            subsystem.processEvent(renderFrame(3));

            assertThat(port.rendered).containsExactly(1, 2, 3);
        }

        @Test
        @DisplayName("no request is lost when nothing else is competing")
        void shouldLeaveNothingPendingAfterASerialBurst()
        {
            final RecordingPort port = new RecordingPort();

            final RenderSubsystem subsystem = started(port);

            for (int tic = 0; tic < PILED_UP_REQUESTS; tic++)
            {
                subsystem.processEvent(renderFrame(tic));
            }

            assertThat(port.rendered).hasSize(PILED_UP_REQUESTS);

            assertThat(subsystem.pendingFrame()).isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle
    {
        @Test
        @DisplayName("init and shutdown reach the port")
        void shouldForwardLifecycleToThePort()
        {
            final RecordingPort port = new RecordingPort();

            final RenderSubsystem subsystem = new RenderSubsystem(port);

            subsystem.init();

            subsystem.shutdown();

            assertThat(port.inits.get()).isEqualTo(1);

            assertThat(port.shutdowns.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("an event that is not a frame request renders nothing")
        void shouldIgnoreOtherEvents()
        {
            final RecordingPort port = new RecordingPort();

            final RenderSubsystem subsystem = started(port);

            subsystem.processEvent(new TickEvent(1L, 0L, 0, 0L));

            assertThat(port.rendered).isEmpty();
        }
    }

    private static RenderSubsystem started(final I_RenderPort port)
    {
        final RenderSubsystem subsystem = new RenderSubsystem(port);

        subsystem.init();

        return subsystem;
    }

    private static RenderFrameEvent renderFrame(final int tic)
    {
        return new RenderFrameEvent(tic, 0L, tic);
    }

    /** A render port that remembers which tics it was asked to draw. */
    private static class RecordingPort implements I_RenderPort
    {
        /** Tics rendered, in order. MUTABLE: appended per frame. */
        final List<Integer> rendered = new ArrayList<>();

        /** How many times init() was called. MUTABLE. */
        final AtomicInteger inits = new AtomicInteger();

        /** How many times shutdown() was called. MUTABLE. */
        final AtomicInteger shutdowns = new AtomicInteger();

        @Override
        public void renderFrame(final int ticIndex)
        {
            rendered.add(ticIndex);
        }

        @Override
        public void init()
        {
            inits.incrementAndGet();
        }

        @Override
        public void shutdown()
        {
            shutdowns.incrementAndGet();
        }
    }

    /** A render port whose first frame blocks until it is released. */
    private static final class BlockingPort extends RecordingPort
    {
        /** Opens once the first frame is inside renderFrame. */
        private final CountDownLatch entered = new CountDownLatch(1);

        /** Lets that first frame finish. */
        private final CountDownLatch release = new CountDownLatch(1);

        /** Whether the blocking frame has already run. MUTABLE. */
        private volatile boolean blocked;

        @Override
        public void renderFrame(final int ticIndex)
        {
            if (!blocked)
            {
                blocked = true;

                entered.countDown();

                await();
            }

            super.renderFrame(ticIndex);
        }

        private void await()
        {
            try
            {
                release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            catch (final InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }
    }
}
