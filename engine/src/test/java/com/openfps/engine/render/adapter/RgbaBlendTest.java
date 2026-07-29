/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The source-over compositing arithmetic, on its own.
 *
 * <p>Deliberately separate from the span loop. Blending is a pure function and
 * this is the piece that has to be right before any of it goes near tile-
 * parallel code, where a rounding mistake would show up as a worker-count
 * dependent frame and be attributed to threading.</p>
 */
@DisplayName("Rgba source-over blending")
final class RgbaBlendTest
{
    /** Fully opaque. */
    private static final int FULL = 255;

    /** Half coverage, the value a naive shift gets wrong. */
    private static final int HALF = 128;

    private static final int RED = Rgba.pack(255, 0, 0, 255);
    private static final int BLUE = Rgba.pack(0, 0, 255, 255);
    private static final int WHITE = Rgba.pack(255, 255, 255, 255);

    @Nested
    @DisplayName("the two endpoints")
    final class Endpoints
    {
        @Test
        @DisplayName("full coverage reproduces the source exactly, channel for channel")
        void shouldReproduceSourceAtFullAlpha()
        {
            // The reason div255 is not `>> 8`. Dividing by 256 loses a step and
            // this assertion is what catches it: 255*255 >> 8 is 254, not 255.
            assertThat(Rgba.red(Rgba.srcOver(RED, BLUE, FULL))).isEqualTo(255);
            assertThat(Rgba.green(Rgba.srcOver(RED, BLUE, FULL))).isZero();
            assertThat(Rgba.blue(Rgba.srcOver(RED, BLUE, FULL))).isZero();
        }

        @Test
        @DisplayName("zero coverage leaves the destination untouched")
        void shouldLeaveDestinationAtZeroAlpha()
        {
            assertThat(Rgba.srcOver(RED, BLUE, 0)).isEqualTo(BLUE);
        }

        @Test
        @DisplayName("every channel value survives a full-coverage round trip")
        void shouldRoundTripEveryChannelValue()
        {
            for (int value = 0; value <= 255; value++)
            {
                final int source = Rgba.pack(value, value, value, 255);
                assertThat(Rgba.red(Rgba.srcOver(source, BLUE, FULL)))
                    .as("channel value %d must survive intact", value)
                    .isEqualTo(value);
            }
        }
    }

    @Nested
    @DisplayName("the middle")
    final class Midpoint
    {
        @Test
        @DisplayName("half coverage lands halfway between the two colours")
        void shouldBlendHalfway()
        {
            final int blended = Rgba.srcOver(WHITE, Rgba.pack(0, 0, 0, 255), HALF);
            // 255*128 + 0*127 = 32640, rounded /255 = 128.
            assertThat(Rgba.red(blended)).isEqualTo(128);
            assertThat(Rgba.green(blended)).isEqualTo(128);
            assertThat(Rgba.blue(blended)).isEqualTo(128);
        }

        @Test
        @DisplayName("the result never leaves the 0-255 range, at any input")
        void shouldStayInRange()
        {
            for (int coverage = 0; coverage <= 255; coverage++)
            {
                final int blended = Rgba.srcOver(WHITE, RED, coverage);
                assertThat(Rgba.red(blended)).isBetween(0, 255);
                assertThat(Rgba.green(blended)).isBetween(0, 255);
                assertThat(Rgba.blue(blended)).isBetween(0, 255);
            }
        }

        @Test
        @DisplayName("blending is monotonic in coverage")
        void shouldBeMonotonic()
        {
            // Sweeping coverage from 0 to 255 between black and white must
            // never step backwards. A rounding bug that is invisible at the
            // endpoints shows up here as a dip.
            final int black = Rgba.pack(0, 0, 0, 255);
            // MUTABLE: the previous channel value in the sweep.
            int previous = -1;
            for (int coverage = 0; coverage <= 255; coverage++)
            {
                final int value = Rgba.red(Rgba.srcOver(WHITE, black, coverage));
                assertThat(value)
                    .as("coverage %d must not be darker than coverage %d",
                        coverage, coverage - 1)
                    .isGreaterThanOrEqualTo(previous);
                previous = value;
            }
        }
    }

    @Nested
    @DisplayName("what it must not do")
    final class Invariants
    {
        @Test
        @DisplayName("the destination's alpha is carried, so the frame stays opaque")
        void shouldKeepDestinationAlpha()
        {
            final int translucentSource = Rgba.pack(255, 0, 0, 0);
            assertThat(Rgba.alpha(Rgba.srcOver(translucentSource, BLUE, HALF)))
                .as("an opaque buffer must not become translucent")
                .isEqualTo(255);
        }

        @Test
        @DisplayName("the source's own alpha channel does not affect the result")
        void shouldIgnoreSourceAlpha()
        {
            final int opaqueSource = Rgba.pack(10, 20, 30, 255);
            final int clearSource = Rgba.pack(10, 20, 30, 0);
            assertThat(Rgba.srcOver(clearSource, BLUE, HALF))
                .as("coverage is the only thing that decides the mix")
                .isEqualTo(Rgba.srcOver(opaqueSource, BLUE, HALF));
        }

        @Test
        @DisplayName("blending a colour over itself is that colour, at every coverage")
        void shouldBeIdempotentOverItself()
        {
            for (int coverage = 0; coverage <= 255; coverage++)
            {
                assertThat(Rgba.srcOver(RED, RED, coverage))
                    .as("coverage %d over an identical destination", coverage)
                    .isEqualTo(RED);
            }
        }
    }
}
