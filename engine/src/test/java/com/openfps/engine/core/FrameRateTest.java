/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link FrameRate} — validates the per-rate math and the
 * string parser.
 */
class FrameRateTest
{
    @Test
    @DisplayName("FPS_30 produces 33,333,333 nanos per frame")
    void shouldProduceCorrectBudgetFor30()
    {
        assertThat(FrameRate.FPS_30.fps()).isEqualTo(30);

        assertThat(FrameRate.FPS_30.nanosPerFrame()).isEqualTo(33_333_333L);

        assertThat(FrameRate.FPS_30.microsPerFrame()).isEqualTo(33_333L);
    }

    @Test
    @DisplayName("FPS_60 produces 16,666,666 nanos per frame")
    void shouldProduceCorrectBudgetFor60()
    {
        assertThat(FrameRate.FPS_60.fps()).isEqualTo(60);

        assertThat(FrameRate.FPS_60.nanosPerFrame()).isEqualTo(16_666_666L);

        assertThat(FrameRate.FPS_60.microsPerFrame()).isEqualTo(16_666L);
    }

    @Test
    @DisplayName("FPS_120 produces 8,333,333 nanos per frame")
    void shouldProduceCorrectBudgetFor120()
    {
        assertThat(FrameRate.FPS_120.fps()).isEqualTo(120);

        assertThat(FrameRate.FPS_120.nanosPerFrame()).isEqualTo(8_333_333L);

        assertThat(FrameRate.FPS_120.microsPerFrame()).isEqualTo(8_333L);
    }

    @Test
    @DisplayName("fromString accepts numeric forms")
    void shouldParseNumericStrings()
    {
        assertThat(FrameRate.fromString("30")).isEqualTo(FrameRate.FPS_30);

        assertThat(FrameRate.fromString("60")).isEqualTo(FrameRate.FPS_60);

        assertThat(FrameRate.fromString("120")).isEqualTo(FrameRate.FPS_120);
    }

    @Test
    @DisplayName("fromString accepts enum-name forms")
    void shouldParseEnumNameStrings()
    {
        assertThat(FrameRate.fromString("FPS_30")).isEqualTo(FrameRate.FPS_30);

        assertThat(FrameRate.fromString("FPS_60")).isEqualTo(FrameRate.FPS_60);

        assertThat(FrameRate.fromString("FPS_120")).isEqualTo(FrameRate.FPS_120);
    }

    @Test
    @DisplayName("fromString is case-insensitive and tolerates whitespace")
    void shouldBeCaseInsensitive()
    {
        assertThat(FrameRate.fromString("fps_60")).isEqualTo(FrameRate.FPS_60);

        assertThat(FrameRate.fromString("  60  ")).isEqualTo(FrameRate.FPS_60);

        assertThat(FrameRate.fromString("FPS_120")).isEqualTo(FrameRate.FPS_120);
    }

    @Test
    @DisplayName("fromString rejects unsupported rates")
    void shouldRejectUnsupportedRates()
    {
        assertThatThrownBy(() -> FrameRate.fromString("144"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("144");

        assertThatThrownBy(() -> FrameRate.fromString("240"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> FrameRate.fromString("0"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> FrameRate.fromString("-1"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("fromString rejects garbage input")
    void shouldRejectGarbage()
    {
        assertThatThrownBy(() -> FrameRate.fromString("abc"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> FrameRate.fromString(""))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> FrameRate.fromString("FPS_999"))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> FrameRate.fromString(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("only the three documented rates are exposed")
    void shouldHaveExactlyThreeRates()
    {
        assertThat(FrameRate.values()).hasSize(3);
    }
}
