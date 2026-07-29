/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the part of {@link TouchOverlay} that does not need a GPU.
 *
 * <p>Almost all of that class is draw calls, and a draw call needs a GL context
 * this module's tests deliberately do not have — the pixmap builders included,
 * since {@code Pixmap} allocates through libGDX's natives. What is left is
 * small and worth holding on to anyway: the <b>glyph table</b>, which is the
 * one thing here that can silently fall out of step with
 * {@link TouchLayout#buttonRegions()}. A button added to the layout and
 * forgotten here draws a blank disc, which looks exactly like a button whose
 * artwork failed to load and is nobody's first guess.</p>
 */
@DisplayName("TouchOverlay")
class TouchOverlayTest
{
    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        @DisplayName("an overlay with no geometry to draw is refused")
        void shouldRejectANullLayout()
        {
            assertThatThrownBy(() -> new TouchOverlay(null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("the glyph table")
    class Glyphs
    {
        @Test
        @DisplayName("every button the layout offers has a symbol on it")
        void shouldDrawSomethingOnEveryButton()
        {
            for (final int region : TouchLayout.buttonRegions())
            {
                assertThat(TouchOverlay.glyphShapeFor(region))
                    .as("glyph for region %d", region)
                    .isNotEmpty();
            }
        }

        @Test
        @DisplayName("every glyph is whole segments, inside the texture that holds it")
        void shouldStayInsideItsTexture()
        {
            // Coordinates are fractions of the glyph texture, so anything
            // outside 0..1 is a stroke drawn off the edge — which appears as a
            // symbol with a piece missing rather than as any kind of error.
            for (final int region : TouchLayout.buttonRegions())
            {
                final float[] shape = TouchOverlay.glyphShapeFor(region);

                assertThat(shape.length % 4)
                    .as("region %d is x0,y0,x1,y1 quadruples", region)
                    .isZero();
                for (int index = 0; index < shape.length; index++)
                {
                    assertThat(shape[index]).isBetween(0.0f, 1.0f);
                }
            }
        }

        @Test
        @DisplayName("a region that is not a button draws no symbol")
        void shouldDrawNothingForANonButton()
        {
            assertThat(TouchOverlay.glyphShapeFor(TouchLayout.REGION_LOOK)).isEmpty();
            assertThat(TouchOverlay.glyphShapeFor(TouchLayout.REGION_MOVE_STICK)).isEmpty();
            assertThat(TouchOverlay.glyphShapeFor(TouchLayout.REGION_NONE)).isEmpty();
        }
    }
}
