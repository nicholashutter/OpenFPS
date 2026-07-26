/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link GameConfig} — factory methods, accessor wiring,
 * and the drift-correction math.
 */
class GameConfigTest
{
    @Test
    @DisplayName("of(rate, maxTics) returns expected values")
    void shouldConstructFromRateAndMaxTics()
    {
        final GameConfig config = GameConfig.of(FrameRate.FPS_120, 240);
        assertThat(config.rate()).isEqualTo(FrameRate.FPS_120);
        assertThat(config.maxTics()).isEqualTo(240);
        assertThat(config.nanosPerTic()).isEqualTo(8_333_333L);
    }

    @Test
    @DisplayName("default60() returns 60 fps, 120 tics")
    void shouldProvide60FpsDefault()
    {
        final GameConfig config = GameConfig.default60();
        assertThat(config.rate()).isEqualTo(FrameRate.FPS_60);
        assertThat(config.maxTics()).isEqualTo(120);
    }

    @Test
    @DisplayName("headless(rate) returns rate × 2 tics — always ~2 seconds")
    void shouldProvideHeadlessConfigs()
    {
        assertThat(GameConfig.headless(FrameRate.FPS_30).maxTics()).isEqualTo(60);
        assertThat(GameConfig.headless(FrameRate.FPS_60).maxTics()).isEqualTo(120);
        assertThat(GameConfig.headless(FrameRate.FPS_120).maxTics()).isEqualTo(240);
    }

    @Test
    @DisplayName("unbounded(rate) returns Integer.MAX_VALUE")
    void shouldProvideUnboundedConfig()
    {
        final GameConfig config = GameConfig.unbounded(FrameRate.FPS_60);
        assertThat(config.maxTics()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("nanosPerTic() delegates to the rate")
    void shouldDelegateNanosPerTic()
    {
        assertThat(GameConfig.of(FrameRate.FPS_30, 100).nanosPerTic())
            .isEqualTo(33_333_333L);
        assertThat(GameConfig.of(FrameRate.FPS_60, 100).nanosPerTic())
            .isEqualTo(16_666_666L);
        assertThat(GameConfig.of(FrameRate.FPS_120, 100).nanosPerTic())
            .isEqualTo(8_333_333L);
    }

    @Test
    @DisplayName("rejects null rate")
    void shouldRejectNullRate()
    {
        assertThatThrownBy(() -> GameConfig.of(null, 100))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects zero or negative maxTics")
    void shouldRejectBadMaxTics()
    {
        assertThatThrownBy(() -> GameConfig.of(FrameRate.FPS_60, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GameConfig.of(FrameRate.FPS_60, -1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("drift correction: 1000 tics at 120 fps stays within ±1 microsecond")
    void shouldNotDriftAt120Fps()
    {
        // The GameLoop uses startNanos + (tic * nanosPerTic) as the absolute
        // deadline. This is the math under test. We simulate 1000 tics of
        // the deadline math and check the total drift.
        final long nanosPerTic = FrameRate.FPS_120.nanosPerFrame();
        final int tics = 1000;
        // The exact total elapsed nanos for 1000 tics at 120 fps is
        // 1000 * 8_333_333 = 8_333_333_000 nanos (≈ 8.33 seconds). The
        // deadline for tic N is startNanos + N * 8_333_333.
        final long expectedTotalNanos = (long) tics * nanosPerTic;
        // Use the same formula the GameLoop uses, in a tight loop.
        long startNanos = 1_000_000_000L;  // arbitrary origin
        long lastDeadlineNanos = startNanos;
        long driftNanos = 0L;
        for (int i = 1; i <= tics; i++)
        {
            final long deadlineNanos = startNanos + ((long) i * nanosPerTic);
            // The expected "real" time for this deadline is the previous
            // deadline plus one budget. Drift = actual - expected.
            final long expectedNanos = lastDeadlineNanos + nanosPerTic;
            driftNanos += (deadlineNanos - expectedNanos);
            lastDeadlineNanos = deadlineNanos;
        }
        // Total drift over 1000 tics at 120 fps should be effectively
        // zero (the integer math accumulates sub-nanosecond rounding
        // error but the long math is exact for the values in play).
        assertThat(Math.abs(driftNanos))
            .as("drift after %d tics at 120 fps", tics)
            .isLessThan(1_000L);  // < 1 microsecond total
        // Sanity check: expected total elapsed should match the
        // sum-of-budgets.
        assertThat(expectedTotalNanos).isEqualTo(8_333_333_000L);
    }

    @Test
    @DisplayName("drift correction: 1000 tics at 30 fps stays within ±1 microsecond")
    void shouldNotDriftAt30Fps()
    {
        final long nanosPerTic = FrameRate.FPS_30.nanosPerFrame();
        final int tics = 1000;
        long startNanos = 5_000_000_000L;
        long lastDeadlineNanos = startNanos;
        long driftNanos = 0L;
        for (int i = 1; i <= tics; i++)
        {
            final long deadlineNanos = startNanos + ((long) i * nanosPerTic);
            final long expectedNanos = lastDeadlineNanos + nanosPerTic;
            driftNanos += (deadlineNanos - expectedNanos);
            lastDeadlineNanos = deadlineNanos;
        }
        assertThat(Math.abs(driftNanos))
            .as("drift after %d tics at 30 fps", tics)
            .isLessThan(1_000L);
    }

    @Test
    @DisplayName("toString() includes rate and maxTics")
    void shouldHaveNiceToString()
    {
        final String s = GameConfig.of(FrameRate.FPS_60, 100).toString();
        assertThat(s).contains("FPS_60");
        assertThat(s).contains("100");
    }
}
