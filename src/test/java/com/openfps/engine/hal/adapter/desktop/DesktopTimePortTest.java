/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.desktop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DesktopTimePort}.
 *
 * Deterministic by construction: nothing here sleeps or asserts on a
 * specific duration. The properties under test are ordering (monotonic
 * never goes backwards), unit consistency (millis is nanos / 1e6), and
 * clock identity (epochMillis is a plausible wall-clock date, monotonic
 * readings are not).
 */
class DesktopTimePortTest
{
    /** Nanoseconds per millisecond — the documented millis() divisor. */
    private static final long NANOS_PER_MILLI = 1_000_000L;

    /** A wall-clock lower bound: 2020-01-01T00:00:00Z in epoch millis. */
    private static final long YEAR_2020_EPOCH_MS = 1_577_836_800_000L;

    /** Reads per monotonicity sweep — enough to catch a non-monotonic source. */
    private static final int MONOTONIC_SAMPLE_COUNT = 1000;

    /** One second of slack for the origin-reset assertion. */
    private static final long ORIGIN_RESET_SLACK_NANOS = 1_000_000_000L;

    private DesktopTimePort port;

    @BeforeEach
    void setUp()
    {
        port = new DesktopTimePort();
        port.init();
    }

    @AfterEach
    void tearDown()
    {
        port.shutdown();
    }

    @Test
    @DisplayName("nanos() never goes backwards across successive reads")
    void shouldReturnMonotonicNanos()
    {
        long previous = port.nanos();
        for (int i = 0; i < MONOTONIC_SAMPLE_COUNT; i++)
        {
            final long current = port.nanos();
            assertThat(current).isGreaterThanOrEqualTo(previous);
            previous = current;
        }
    }

    @Test
    @DisplayName("millis() never goes backwards across successive reads")
    void shouldReturnMonotonicMillis()
    {
        long previous = port.millis();
        for (int i = 0; i < MONOTONIC_SAMPLE_COUNT; i++)
        {
            final long current = port.millis();
            assertThat(current).isGreaterThanOrEqualTo(previous);
            previous = current;
        }
    }

    @Test
    @DisplayName("millis() is nanos() divided by one million")
    void shouldExpressMillisInTheSameUnitsAsNanos()
    {
        final long before = port.nanos();
        final long millis = port.millis();
        final long after = port.nanos();

        assertThat(millis).isBetween(before / NANOS_PER_MILLI, after / NANOS_PER_MILLI);
    }

    @Test
    @DisplayName("nanos() is measured from the init() origin, so it starts near zero")
    void shouldMeasureFromTheInitOrigin()
    {
        // The whole point of the origin: a fresh port reports a small
        // elapsed time, not the JVM-wide nanoTime value.
        assertThat(port.nanos()).isGreaterThanOrEqualTo(0L);
        assertThat(port.millis()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("epochMillis() is wall clock — a plausible present-day date")
    void shouldReturnWallClockEpochMillis()
    {
        final long epoch = port.epochMillis();
        assertThat(epoch).isGreaterThan(YEAR_2020_EPOCH_MS);
        assertThat(Instant.ofEpochMilli(epoch)).isNotNull();
    }

    @Test
    @DisplayName("epochMillis() and millis() are different clocks, not the same number")
    void shouldKeepTheTwoClocksDistinct()
    {
        // A monotonic reading from a just-initialized port is tiny; a
        // wall-clock reading is ~1.7e12. Confusing the two is the exact
        // bug I_TimePort warns about.
        assertThat(port.epochMillis()).isNotEqualTo(port.millis());
        assertThat(port.epochMillis()).isGreaterThan(port.millis());
    }

    @Test
    @DisplayName("shutdown() then a fresh init() restarts the monotonic origin")
    void shouldRestartTheOriginOnReinit()
    {
        final long beforeShutdown = port.nanos();
        port.shutdown();
        port.init();

        // A later origin means the elapsed reading resets rather than
        // carrying the previous run's accumulated time forward.
        assertThat(port.nanos()).isGreaterThanOrEqualTo(0L);
        assertThat(port.nanos()).isLessThan(beforeShutdown + ORIGIN_RESET_SLACK_NANOS);
    }
}
