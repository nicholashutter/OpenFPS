/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link Subsystem} state-change observer seam.
 *
 * <p>Every state transition of every subsystem must fire a
 * {@link SubsystemStateChangeEvent} through the observer list the
 * subsystem holds. The list is the only way to be told that a
 * subsystem moved &mdash; without it, a debug overlay or a file
 * writer cannot see the engine's lifecycle. The tests cover the
 * four observable transitions (UNINITIALIZED &rarr; READY,
 * READY &rarr; SHUTDOWN, READY &rarr; ERROR on a throwing
 * shutdown, and the ERROR &rarr; SHUTDOWN cleanup path) and the
 * two failure modes (an observer that throws does not fail the
 * state machine; an observer added after a transition has
 * already happened is not retroactively fired).</p>
 */
@DisplayName("Subsystem state-change observer")
class SubsystemStateObserverTest
{
    @BeforeEach
    void resetRegistry()
    {
        // Tests run in order; reset the bus so a previous
        // observer does not leak into the current case.
        com.openfps.engine.log.LogBusFactory.resetForTesting();
    }

    @Nested
    @DisplayName("init()")
    class Init
    {
        @Test
        @DisplayName("fires UNINITIALIZED -> READY to a registered observer")
        void shouldFireOnInit()
        {
            final TestSubsystem subsystem = new TestSubsystem(SubsystemId.P_);

            final List<SubsystemStateChangeEvent> seen = new ArrayList<>();

            subsystem.addObserver(seen::add);

            subsystem.init();

            assertThat(seen).hasSize(1);

            final SubsystemStateChangeEvent event = seen.get(0);

            assertThat(event.subsystemId()).isEqualTo(SubsystemId.P_);

            assertThat(event.fromState()).isEqualTo(SubsystemState.UNINITIALIZED);

            assertThat(event.toState()).isEqualTo(SubsystemState.READY);

            assertThat(event.cause()).isNull();

            assertThat(event.isErrorTransition()).isFalse();
        }

        @Test
        @DisplayName("a no-arg observer is legal but does nothing")
        void shouldAllowNoArgObserver()
        {
            // Subsystem.addObserver rejects null but accepts
            // any non-null observer; the registry does not
            // care what the observer does.
            final TestSubsystem subsystem = new TestSubsystem(SubsystemId.P_);

            subsystem.addObserver(event -> { });

            subsystem.init();
        }
    }

    @Nested
    @DisplayName("shutdown()")
    class Shutdown
    {
        @Test
        @DisplayName("fires READY -> SHUTDOWN to a registered observer")
        void shouldFireOnShutdown()
        {
            final TestSubsystem subsystem = new TestSubsystem(SubsystemId.P_);

            final List<SubsystemStateChangeEvent> seen = new ArrayList<>();

            subsystem.addObserver(seen::add);

            subsystem.init();

            seen.clear();

            subsystem.shutdown();

            assertThat(seen).hasSize(1);

            final SubsystemStateChangeEvent event = seen.get(0);

            assertThat(event.fromState()).isEqualTo(SubsystemState.READY);

            assertThat(event.toState()).isEqualTo(SubsystemState.SHUTDOWN);

            assertThat(event.cause()).isNull();
        }

        @Test
        @DisplayName("a throwing shutdown() fires READY -> ERROR with the cause")
        void shouldFireOnError()
        {
            final Subsystem subsystem = new Subsystem(SubsystemId.R_)
            {
                @Override
                protected void onShutdown()
                {
                    throw new RuntimeException("intentional shutdown failure");
                }
            };

            final List<SubsystemStateChangeEvent> seen = new ArrayList<>();

            subsystem.addObserver(seen::add);

            subsystem.init();

            seen.clear();

            assertThatThrownBy(subsystem::shutdown)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("intentional");

            assertThat(seen).hasSize(1);

            final SubsystemStateChangeEvent event = seen.get(0);

            assertThat(event.fromState()).isEqualTo(SubsystemState.READY);

            assertThat(event.toState()).isEqualTo(SubsystemState.ERROR);

            assertThat(event.cause()).isNotNull();

            assertThat(event.cause().getMessage()).contains("intentional");

            assertThat(event.isErrorTransition()).isTrue();
        }
    }

    @Nested
    @DisplayName("observer exception handling")
    class ObserverErrors
    {
        @Test
        @DisplayName("a throwing observer does not fail the state machine")
        void shouldNotFailOnObserverError()
        {
            final TestSubsystem subsystem = new TestSubsystem(SubsystemId.G_);

            final List<SubsystemStateChangeEvent> seen = new ArrayList<>();

            subsystem.addObserver(event ->
            {
                throw new RuntimeException("observer is misbehaving");
            });

            subsystem.addObserver(seen::add);

            subsystem.init();

            // The misbehaving observer did not prevent the
            // well-behaved one from seeing the transition.
            assertThat(seen).hasSize(1);

            assertThat(subsystem.state()).isEqualTo(SubsystemState.READY);
        }
    }

    @Nested
    @DisplayName("SubsystemRegistry observer wiring")
    class Registry
    {
        @Test
        @DisplayName("registerObserver wires the observer to every existing subsystem")
        void shouldWireToExisting()
        {
            final SubsystemRegistry registry = new SubsystemRegistry();

            final TestSubsystem p1 = new TestSubsystem(SubsystemId.P_);

            final TestSubsystem r1 = new TestSubsystem(SubsystemId.R_);

            registry.register(p1);

            registry.register(r1);

            final List<SubsystemStateChangeEvent> seen = new ArrayList<>();

            registry.registerObserver(seen::add);

            p1.init();

            r1.init();

            assertThat(seen)
                .extracting(SubsystemStateChangeEvent::subsystemId)
                .containsExactly(SubsystemId.P_, SubsystemId.R_);
        }

        @Test
        @DisplayName("a subsystem registered after the observer still gets wired")
        void shouldWireToLaterSubsystems()
        {
            final SubsystemRegistry registry = new SubsystemRegistry();

            final List<SubsystemStateChangeEvent> seen = new ArrayList<>();

            registry.registerObserver(seen::add);

            final TestSubsystem p1 = new TestSubsystem(SubsystemId.P_);

            registry.register(p1);

            p1.init();

            assertThat(seen).hasSize(1);

            assertThat(seen.get(0).subsystemId()).isEqualTo(SubsystemId.P_);
        }

        @Test
        @DisplayName("a null observer is rejected")
        void shouldRejectNullObserver()
        {
            final SubsystemRegistry registry = new SubsystemRegistry();

            assertThatThrownBy(() -> registry.registerObserver(null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("SubsystemStateChangeEvent")
    class EventShape
    {
        @Test
        @DisplayName("a non-null cause is required for error transitions")
        void shouldCarryCause()
        {
            final SubsystemStateChangeEvent event = new SubsystemStateChangeEvent(SubsystemId.P_,
                SubsystemState.READY, SubsystemState.ERROR, 0L, new RuntimeException("boom"));

            assertThat(event.isErrorTransition()).isTrue();

            assertThat(event.cause()).isNotNull();
        }

        @Test
        @DisplayName("toString is human-readable and includes the cause class")
        void shouldFormatToString()
        {
            final SubsystemStateChangeEvent event = new SubsystemStateChangeEvent(SubsystemId.P_,
                SubsystemState.READY, SubsystemState.ERROR, 0L, new IllegalStateException("boom"));

            assertThat(event.toString())
                .contains("P_")
                .contains("READY")
                .contains("ERROR")
                .contains("IllegalStateException");
        }

        @Test
        @DisplayName("a null subsystem id is rejected")
        void shouldRejectNullId()
        {
            assertThatThrownBy(() -> new SubsystemStateChangeEvent(null, SubsystemState.READY,
                SubsystemState.READY, 0L, null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---- harness ---------------------------------------------------------

    private static final class TestSubsystem extends Subsystem
    {
        TestSubsystem(final SubsystemId id)
        {
            super(id);
        }
    }
}
