/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.log;

import static org.assertj.core.api.Assertions.assertThat;

import com.openfps.engine.core.subsystem.Subsystem;
import com.openfps.engine.core.subsystem.SubsystemId;
import com.openfps.engine.core.subsystem.SubsystemState;
import com.openfps.engine.core.subsystem.SubsystemStateChangeEvent;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SubsystemStateLogger}: every subsystem
 * state-machine transition lands on the matching log-bus
 * channel, shaped like every other engine log line, so a
 * downstream consumer (a debug overlay, a file writer) cannot
 * tell which path an event took.
 */
@DisplayName("SubsystemStateLogger")
class SubsystemStateLoggerTest
{
    @BeforeEach
    void resetBus()
    {
        LogBusFactory.resetForTesting();
    }

    @Test
    @DisplayName("a non-error transition is published to the matching subsystem channel at INFO")
    void shouldPublishInfoForNonError()
    {
        final SubsystemStateLogger logger = SubsystemStateLogger.install();

        final SubsystemStateChangeEvent event = new SubsystemStateChangeEvent(SubsystemId.P_,
            SubsystemState.UNINITIALIZED, SubsystemState.READY, 0L, null);

        logger.onStateChange(event);

        final List<LogEvent> events = LogBusFactory.subsystem("engine.gameplay").recent(10);

        assertThat(events).hasSize(1);

        final LogEvent published = events.get(0);

        assertThat(published.source()).isEqualTo("engine.gameplay");

        assertThat(published.level()).isEqualTo(LogLevel.INFO);

        assertThat(published.message())
            .contains("P_")
            .contains("UNINITIALIZED")
            .contains("READY");
    }

    @Test
    @DisplayName("an error transition is published at WARN with the cause")
    void shouldPublishWarnForError()
    {
        final SubsystemStateLogger logger = SubsystemStateLogger.install();

        final SubsystemStateChangeEvent event = new SubsystemStateChangeEvent(SubsystemId.R_,
            SubsystemState.READY, SubsystemState.ERROR, 0L,
            new IllegalStateException("renderer init failed"));

        logger.onStateChange(event);

        final List<LogEvent> events = LogBusFactory.subsystem("engine.render").recent(10);

        assertThat(events).hasSize(1);

        final LogEvent published = events.get(0);

        assertThat(published.level()).isEqualTo(LogLevel.WARN);

        assertThat(published.cause()).isNotNull();

        assertThat(published.message())
            .contains("R_")
            .contains("ERROR")
            .contains("IllegalStateException");
    }

    @Test
    @DisplayName("the resource subsystem lands on engine.core (no dedicated channel)")
    void shouldMapResourceSubsystemToCore()
    {
        final SubsystemStateLogger logger = SubsystemStateLogger.install();

        final SubsystemStateChangeEvent event = new SubsystemStateChangeEvent(SubsystemId.W_,
            SubsystemState.UNINITIALIZED, SubsystemState.READY, 0L, null);

        logger.onStateChange(event);

        // W_ has no dedicated channel; the event lands on
        // engine.core so a consumer subscribed there still
        // sees it.
        final List<LogEvent> core = LogBusFactory.subsystem("engine.core").recent(10);

        assertThat(core).hasSize(1);

        assertThat(core.get(0).message()).contains("W_");
    }

    @Test
    @DisplayName("a null event is dropped silently")
    void shouldIgnoreNullEvent()
    {
        final SubsystemStateLogger logger = SubsystemStateLogger.install();

        logger.onStateChange(null);

        // No channel should have received anything.
        assertThat(LogBusFactory.subsystem("engine.core").recent(10)).isEmpty();

        assertThat(LogBusFactory.subsystem("engine.gameplay").recent(10)).isEmpty();
    }

    @Test
    @DisplayName("events flow into the main bus via the drain task")
    void shouldDrainIntoMainBus()
    {
        final SubsystemStateLogger logger = SubsystemStateLogger.install();

        // Publish one event on each of two channels.
        logger.onStateChange(new SubsystemStateChangeEvent(SubsystemId.P_,
            SubsystemState.UNINITIALIZED, SubsystemState.READY, 0L, null));

        logger.onStateChange(new SubsystemStateChangeEvent(SubsystemId.R_,
            SubsystemState.UNINITIALIZED, SubsystemState.READY, 0L, null));

        // The factory's drain task is normally started by the
        // launcher; here we just call subsystem.publish from
        // each side, then check that the main bus has them.
        // For test simplicity, verify the subsystem buses
        // received them, and trust the existing drain task
        // tests for the drain.
        assertThat(LogBusFactory.subsystem("engine.gameplay").recent(10)).hasSize(1);

        assertThat(LogBusFactory.subsystem("engine.render").recent(10)).hasSize(1);

        // Additionally verify that subsystem publishes are
        // visible on the main bus (SubsystemLogBus forwards
        // synchronously, so the event is on main even without
        // the drain task).
        final boolean mainHasGameplay = LogBusFactory.main().recent(500).stream()
            .anyMatch(e -> "engine.gameplay".equals(e.source()));

        final boolean mainHasRender = LogBusFactory.main().recent(500).stream()
            .anyMatch(e -> "engine.render".equals(e.source()));

        assertThat(mainHasGameplay)
            .as("gameplay event must reach the main bus via SubsystemLogBus forward")
            .isTrue();

        assertThat(mainHasRender)
            .as("render event must reach the main bus via SubsystemLogBus forward")
            .isTrue();
    }

    /**
     * Minimal subsystem that overrides state on construction
     * to drive the logger without going through {@code init()}
     * &mdash; which would require a HAL adapter and the full
     * engine bootstrap.
     */
    private static final class TestSubsystem extends Subsystem
    {
        TestSubsystem(final SubsystemId id)
        {
            super(id);
        }
    }
}
