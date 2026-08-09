/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem;

import com.openfps.engine.core.event.EventFactory;
import com.openfps.engine.core.event.I_EngineEvent;
import com.openfps.engine.core.event.TickEvent;
import com.openfps.engine.hal.adapter.nulladapter.NullTimePort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for the {@link Subsystem} state machine.
 */
class SubsystemStateTest
{
    private final EventFactory factory = new EventFactory(new NullTimePort());

    @Test
    @DisplayName("newly constructed subsystem is UNINITIALIZED")
    void shouldStartUninitialized()
    {
        final Subsystem s = new TestSubsystem(SubsystemId.P_);

        assertThat(s.state()).isEqualTo(SubsystemState.UNINITIALIZED);
    }

    @Test
    @DisplayName("init() moves UNINITIALIZED → READY")
    void shouldInitToReady()
    {
        final Subsystem s = new TestSubsystem(SubsystemId.P_);

        s.init();

        assertThat(s.state()).isEqualTo(SubsystemState.READY);
    }

    @Test
    @DisplayName("processEvent() leaves the subsystem in READY after a normal event")
    void shouldStayReadyAfterEvent()
    {
        final Subsystem s = new TestSubsystem(SubsystemId.P_);

        s.init();

        s.processEvent(factory.newTick(0, 0));

        assertThat(s.state()).isEqualTo(SubsystemState.READY);
    }

    @Test
    @DisplayName("handler exception is logged but the subsystem stays in READY")
    void shouldLogAndStayReadyOnException()
    {
        final Subsystem s = new Subsystem(SubsystemId.P_)
        {
            @Override
            protected void onEvent(final I_EngineEvent event)
            {
                throw new RuntimeException("intentional");
            }
        };

        s.init();

        assertThatThrownBy(() -> s.processEvent(factory.newTick(0, 0)))
            .isInstanceOf(RuntimeException.class);

        // Worker keeps going — subsystem remains READY
        assertThat(s.state()).isEqualTo(SubsystemState.READY);
    }

    @Test
    @DisplayName("init() throws if called twice")
    void shouldThrowOnDoubleInit()
    {
        final Subsystem s = new TestSubsystem(SubsystemId.P_);

        s.init();

        assertThatThrownBy(s::init).isInstanceOf(SubsystemException.class);
    }

    @Test
    @DisplayName("processEvent() throws if not READY")
    void shouldThrowOnEventWhenNotReady()
    {
        final Subsystem s = new TestSubsystem(SubsystemId.P_);

        // UNINITIALIZED
        assertThatThrownBy(() -> s.processEvent(factory.newTick(0, 0)))
            .isInstanceOf(SubsystemException.class);
    }

    @Test
    @DisplayName("shutdown() moves READY → SHUTDOWN")
    void shouldShutdown()
    {
        final Subsystem s = new TestSubsystem(SubsystemId.P_);

        s.init();

        s.shutdown();

        assertThat(s.state()).isEqualTo(SubsystemState.SHUTDOWN);
    }

    @Test
    @DisplayName("double shutdown throws")
    void shouldThrowOnDoubleShutdown()
    {
        final Subsystem s = new TestSubsystem(SubsystemId.P_);

        s.init();

        s.shutdown();

        assertThatThrownBy(s::shutdown).isInstanceOf(SubsystemException.class);
    }

    @Test
    @DisplayName("shutdown() from SHUTDOWN state throws")
    void shouldThrowOnShutdownFromShutdown()
    {
        final Subsystem s = new TestSubsystem(SubsystemId.P_);

        s.init();

        s.shutdown();

        assertThatThrownBy(s::shutdown).isInstanceOf(SubsystemException.class);
    }

    @Test
    @DisplayName("transition validation table is correct")
    void shouldValidateTransitions()
    {
        assertThat(Subsystem.isValidTransition(SubsystemState.UNINITIALIZED, SubsystemState.READY)).isTrue();

        assertThat(Subsystem.isValidTransition(SubsystemState.UNINITIALIZED, SubsystemState.SHUTDOWN)).isTrue();

        assertThat(Subsystem.isValidTransition(SubsystemState.UNINITIALIZED, SubsystemState.ERROR)).isTrue();

        assertThat(Subsystem.isValidTransition(SubsystemState.READY, SubsystemState.SHUTDOWN)).isTrue();

        assertThat(Subsystem.isValidTransition(SubsystemState.READY, SubsystemState.ERROR)).isTrue();

        assertThat(Subsystem.isValidTransition(SubsystemState.READY, SubsystemState.UNINITIALIZED)).isFalse();

        assertThat(Subsystem.isValidTransition(SubsystemState.ERROR, SubsystemState.UNINITIALIZED)).isTrue();

        assertThat(Subsystem.isValidTransition(SubsystemState.ERROR, SubsystemState.READY)).isFalse();

        // SHUTDOWN is terminal — no transitions out
        assertThat(Subsystem.isValidTransition(SubsystemState.SHUTDOWN, SubsystemState.READY)).isFalse();

        assertThat(Subsystem.isValidTransition(SubsystemState.SHUTDOWN, SubsystemState.UNINITIALIZED)).isFalse();
    }

    /** Minimal concrete subclass for tests that don't care about events. */
    private static final class TestSubsystem extends Subsystem
    {
        TestSubsystem(final SubsystemId id)
        {
            super(id);
        }
    }
}
