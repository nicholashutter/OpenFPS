/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LogBusFactory}: the static accessor that hands
 * out the main bus and the per-subsystem buses.
 *
 * <p>The factory is lazy and idempotent. {@code resetForTesting()}
 * is the only way to clear state; the tests below use it before
 * and after each test so a {@code startDrainTask()} in one test
 * does not leak into the next.</p>
 */
@DisplayName("LogBusFactory")
class LogBusFactoryTest
{
    @BeforeEach
    void reset()
    {
        LogBusFactory.resetForTesting();
    }

    @AfterEach
    void teardown()
    {
        LogBusFactory.resetForTesting();
    }

    @Test
    @DisplayName("main() returns the same instance on repeat calls")
    void shouldReturnSameMainBusInstance()
    {
        final I_LogBus first = LogBusFactory.main();

        final I_LogBus second = LogBusFactory.main();

        assertThat(first).isSameAs(second);
    }

    @Test
    @DisplayName("subsystem() returns one of the registered per-subsystem buses")
    void shouldReturnRegisteredSubsystemBus()
    {
        final I_LogBus gameplay = LogBusFactory.subsystem("engine.gameplay");

        assertThat(gameplay).isNotNull();
    }

    @Test
    @DisplayName("subsystem() rejects null or blank source")
    void shouldRejectBadSource()
    {
        assertThatThrownBy(() -> LogBusFactory.subsystem(null))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> LogBusFactory.subsystem(""))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> LogBusFactory.subsystem("   "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("subsystem() for an unknown name returns null without crashing")
    void shouldReturnNullForUnknownName()
    {
        // The factory builds a fixed set of named buses; an
        // arbitrary name is not in the map.
        final I_LogBus unknown = LogBusFactory.subsystem("engine.unknown");

        assertThat(unknown).isNull();
    }

    @Test
    @DisplayName("allSubsystems() returns the canonical set")
    void shouldExposeCanonicalSubsystemSet()
    {
        assertThat(LogBusFactory.allSubsystems().keySet())
            .containsExactlyInAnyOrder(
                "engine.core",
                "engine.hal",
                "engine.memory",
                "engine.gameplay",
                "engine.net",
                "engine.audio",
                "engine.render",
                "engine.demo",
                "engine.map");
    }

    @Test
    @DisplayName("publish on a subsystem bus is visible via recent()")
    void shouldPublishAndReadBack()
    {
        final I_LogBus gameplay = LogBusFactory.subsystem("engine.gameplay");

        gameplay.publish(new LogEvent(System.currentTimeMillis(),
            "engine.gameplay", "com.openfps.engine.test", LogLevel.INFO,
            "spawn-bot", null));

        assertThat(gameplay.recent(8)).hasSize(1);
    }

    @Test
    @DisplayName("publish on a subsystem bus reaches the main bus")
    void shouldForwardSubsystemToMain()
    {
        // SubsystemLogBus.publish forwards directly to main, so
        // an event published on a subsystem bus is visible on
        // main() without the drain task running. The drain task
        // adds a SECOND copy (a known issue in the bus; see
        // LogBusFactory#drainLoop's comment) but at least one
        // copy is guaranteed.
        final I_LogBus gameplay = LogBusFactory.subsystem("engine.gameplay");

        gameplay.publish(new LogEvent(System.currentTimeMillis(),
            "engine.gameplay", "com.openfps.engine.test", LogLevel.INFO,
            "from-subsystem", null));

        // The direct forward is synchronous, so the event is
        // already on main by the time publish returns. We
        // assert directly rather than via a polling helper
        // because the bus's contract is synchronous-forwarding.
        final I_LogBus main = LogBusFactory.main();

        assertThat(main.recent(500).stream()
            .anyMatch(e -> "from-subsystem".equals(e.message())))
            .isTrue();
    }

    @Test
    @DisplayName("startDrainTask is idempotent")
    void shouldBeIdempotentOnStart()
    {
        LogBusFactory.startDrainTask();

        LogBusFactory.startDrainTask();

        // No exception; the factory keeps a single drain thread.
    }

    @Test
    @DisplayName("fileSink() returns null until installDefaultFileSink is called")
    void shouldHaveNoFileSinkByDefault()
    {
        assertThat(LogBusFactory.fileSink()).isNull();
    }

    @Test
    @DisplayName("installDefaultFileSink installs a sink and is idempotent")
    void shouldInstallFileSinkIdempotently()
    {
        final LogFileSink first = LogBusFactory.installDefaultFileSink();

        assertThat(first).isNotNull();

        final LogFileSink second = LogBusFactory.installDefaultFileSink();

        // The second call must return the same instance, not a
        // freshly-built one (which would spin up a second writer
        // thread and double-subscribe to the bus).
        assertThat(second).isSameAs(first);
    }

    @Test
    @DisplayName("closeFileSink() removes the sink and clears the field")
    void shouldCloseAndClearFileSink()
    {
        final LogFileSink installed = LogBusFactory.installDefaultFileSink();

        assertThat(installed).isNotNull();

        LogBusFactory.closeFileSink();

        assertThat(LogBusFactory.fileSink()).isNull();

        // A subsequent close is a no-op.
        LogBusFactory.closeFileSink();
    }

    @Test
    @DisplayName("resetForTesting clears the factory state")
    void shouldResetOnDemand()
    {
        // Touch the factory so it has state.
        final I_LogBus main = LogBusFactory.main();

        assertThat(main).isNotNull();

        LogBusFactory.resetForTesting();

        // A new main() after reset returns a different instance
        // (singleton is rebuilt).
        assertThat(LogBusFactory.main()).isNotSameAs(main);
    }
}
