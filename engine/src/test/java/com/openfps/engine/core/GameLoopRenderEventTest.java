/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.openfps.engine.core.event.EventFactory;
import com.openfps.engine.core.event.I_EngineEvent;
import com.openfps.engine.core.event.RenderFrameEvent;
import com.openfps.engine.core.event.TickEvent;
import com.openfps.engine.core.eventbus.EventBusFactory;
import com.openfps.engine.core.eventbus.I_EventBusPort;
import com.openfps.engine.core.subsystem.SubsystemId;
import com.openfps.engine.hal.adapter.nulladapter.NullTimePort;
import com.openfps.engine.hal.port.I_TimePort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that the game loop actually asks for frames.
 *
 * <p><b>Why this test exists.</b> {@link RenderFrameEvent} had an event class,
 * a factory method on {@link EventFactory}, a {@link SubsystemId#R_} target and
 * a {@code RenderSubsystem} branch waiting for it — and no producer anywhere in
 * the engine. Every part of the render path looked wired and none of it ever
 * ran, which is exactly the kind of gap unit tests on the individual pieces
 * cannot see. Nothing but an assertion on the bus catches it, so here it
 * is.</p>
 */
@DisplayName("GameLoop render events")
final class GameLoopRenderEventTest
{
    /** Tics the loop runs before shutting itself down. */
    private static final int TICS = 3;

    /** Bus capacity; comfortably more than the run publishes. */
    private static final int CAPACITY = 64;

    /** How long to wait for the loop thread to finish, in milliseconds. */
    private static final long JOIN_MS = 5000L;

    @Test
    @DisplayName("every tic publishes a render frame alongside the tick")
    void shouldPublishOneRenderFrameEventPerTic() throws InterruptedException
    {
        final I_TimePort time = new NullTimePort();

        time.init();

        final I_EventBusPort bus = EventBusFactory.createShared();

        bus.init(CAPACITY);

        final GameLoop loop = new GameLoop(time, bus, new EventFactory(time),
            GameConfig.of(FrameRate.FPS_120, TICS));

        final Thread thread = new Thread(loop, "gameloop-test");

        thread.start();

        thread.join(JOIN_MS);

        final List<I_EngineEvent> published = drain(bus, TICS * 2);

        final List<RenderFrameEvent> frames = new ArrayList<>();

        // MUTABLE local — how many ticks were seen, for the pairing assertion.
        int ticks = 0;

        for (final I_EngineEvent event : published)
        {
            if (event instanceof RenderFrameEvent frame)
            {
                frames.add(frame);
            }

            if (event instanceof TickEvent)
            {
                ticks++;
            }
        }

        assertThat(frames).as("one render frame per tic").hasSize(TICS);

        assertThat(ticks).isEqualTo(TICS);

        assertThat(frames.get(0).targetSubsystem()).isEqualTo(SubsystemId.R_);

        // The frame number carries the tic, which is what drives the default
        // orbit camera in SoftwareRenderPort.
        assertThat(frames.get(0).frameNumber()).isZero();

        assertThat(frames.get(TICS - 1).frameNumber()).isEqualTo(TICS - 1);

        bus.shutdown();

        time.shutdown();
    }

    // Takes exactly `count` events off the bus. The loop is bounded and has
    // already finished, so they are all present and take() never blocks.
    private static List<I_EngineEvent> drain(final I_EventBusPort bus, final int count)
        throws InterruptedException
    {
        final List<I_EngineEvent> events = new ArrayList<>();

        for (int i = 0; i < count; i++)
        {
            events.add(bus.take());
        }

        return events;
    }
}
