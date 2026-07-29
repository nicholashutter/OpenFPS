/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link RenderMode} — the surface-to-framebuffer arithmetic.
 *
 * <p><b>This is the part of the render-resolution work that can be wrong
 * silently.</b> A stretched frame does not look like a bug; it looks like a
 * slightly odd field of view, because the camera's aspect is derived from the
 * same two numbers the framebuffer uses ({@code DemoGameplayPort.aimCamera}), so
 * a distorted framebuffer produces a consistently distorted projection rather
 * than an obviously squashed picture. Nothing on screen would report it. So the
 * arithmetic is asserted here, where it is pure, rather than trusted to a
 * screenshot.</p>
 *
 * <p>Four properties, in the order they would hurt: the aspect survives, a mode
 * never enlarges, no dimension is ever zero or negative, and {@code NATIVE} is
 * the surface exactly rather than a factor of one applied and rounded.</p>
 */
@DisplayName("RenderMode")
class RenderModeTest
{
    /** The phone that prompted the whole exercise. */
    private static final int PHONE_WIDTH = 2400;

    /** Its shorter edge. */
    private static final int PHONE_HEIGHT = 1080;

    /** The desktop window's width. */
    private static final int DESKTOP_WIDTH = 1280;

    /** The desktop window's height. */
    private static final int DESKTOP_HEIGHT = 720;

    /** How far the render aspect may drift from the surface aspect, as a ratio. */
    private static final double ASPECT_TOLERANCE = 0.002;

    @Nested
    @DisplayName("the sizes that prompted this")
    class KnownSizes
    {
        @Test
        @DisplayName("2400x1080 at 480p is 1067x480 — the short edge lands exactly")
        void shouldScaleThePhone()
        {
            // 1080 -> 480 is the promise "480p" makes; 2400 * 480 / 1080 is
            // 1066.67, and the nearest whole pixel is where the entire rounding
            // error is deliberately concentrated.
            assertThat(RenderMode.P480.widthFor(PHONE_WIDTH, PHONE_HEIGHT)).isEqualTo(1067);
            assertThat(RenderMode.P480.heightFor(PHONE_WIDTH, PHONE_HEIGHT)).isEqualTo(480);
        }

        @Test
        @DisplayName("2400x1080 at 480p is 5.4x fewer pixels, which is the whole point")
        void shouldCutThePixelCount()
        {
            final long before = (long) PHONE_WIDTH * PHONE_HEIGHT;
            final long after = (long) RenderMode.P480.widthFor(PHONE_WIDTH, PHONE_HEIGHT)
                * RenderMode.P480.heightFor(PHONE_WIDTH, PHONE_HEIGHT);
            assertThat((double) before / (double) after).isGreaterThan(5.0);
        }

        @Test
        @DisplayName("2400x1080 at 720p is 1600x720")
        void shouldScaleThePhoneTo720()
        {
            assertThat(RenderMode.P720.widthFor(PHONE_WIDTH, PHONE_HEIGHT)).isEqualTo(1600);
            assertThat(RenderMode.P720.heightFor(PHONE_WIDTH, PHONE_HEIGHT)).isEqualTo(720);
        }

        @Test
        @DisplayName("the 1280x720 desktop window at 480p is 853x480")
        void shouldScaleTheDesktopWindow()
        {
            assertThat(RenderMode.P480.widthFor(DESKTOP_WIDTH, DESKTOP_HEIGHT)).isEqualTo(853);
            assertThat(RenderMode.P480.heightFor(DESKTOP_WIDTH, DESKTOP_HEIGHT)).isEqualTo(480);
        }

        @Test
        @DisplayName("the 1280x720 desktop window at 720p is left alone")
        void shouldLeaveTheDesktopWindowAt720()
        {
            // Equal, not greater — the ceiling is inclusive, so the window whose
            // height IS the mode pays nothing at all for the feature.
            assertThat(RenderMode.P720.widthFor(DESKTOP_WIDTH, DESKTOP_HEIGHT))
                .isEqualTo(DESKTOP_WIDTH);
            assertThat(RenderMode.P720.heightFor(DESKTOP_WIDTH, DESKTOP_HEIGHT))
                .isEqualTo(DESKTOP_HEIGHT);
        }
    }

    @Nested
    @DisplayName("the aspect ratio")
    class Aspect
    {
        @ParameterizedTest(name = "{0}x{1}")
        @CsvSource({
            "2400, 1080",
            "1920, 1080",
            "1280, 720",
            "2340, 1080",
            "3200, 1440",
            "1179, 2556",
            "1001, 999",
            "1000, 1000",
            "4000, 501",
            "501, 4000",
            "3841, 1081",
        })
        @DisplayName("survives 480p to within a fifth of a percent")
        void shouldPreserveAspectAt480(final int surfaceWidth, final int surfaceHeight)
        {
            assertAspectSurvives(RenderMode.P480, surfaceWidth, surfaceHeight);
        }

        @ParameterizedTest(name = "{0}x{1}")
        @CsvSource({
            "2400, 1080",
            "3840, 2160",
            "2560, 1600",
            "1179, 2556",
            "2001, 1441",
            "7000, 721",
        })
        @DisplayName("survives 720p too")
        void shouldPreserveAspectAt720(final int surfaceWidth, final int surfaceHeight)
        {
            assertAspectSurvives(RenderMode.P720, surfaceWidth, surfaceHeight);
        }

        @Test
        @DisplayName("an odd surface is not rounded onto a nicer ratio")
        void shouldNotTidyAnOddSurface()
        {
            // 1367x769 is nobody's resolution, which is the point: the mode must
            // reproduce whatever shape it is handed rather than snapping to one
            // it recognises.
            assertAspectSurvives(RenderMode.P480, 1367, 769);
            assertThat(RenderMode.P480.heightFor(1367, 769)).isEqualTo(480);
        }

        // Both edges came from one ratio, so the shapes must match. The bound is
        // half a pixel on the long edge expressed as a ratio, and it is asserted
        // rather than argued because "the aspect is preserved" is the one claim
        // no screenshot would falsify.
        private void assertAspectSurvives(final RenderMode mode, final int surfaceWidth,
            final int surfaceHeight)
        {
            final int renderWidth = mode.widthFor(surfaceWidth, surfaceHeight);
            final int renderHeight = mode.heightFor(surfaceWidth, surfaceHeight);
            final double surfaceAspect = (double) surfaceWidth / (double) surfaceHeight;
            final double renderAspect = (double) renderWidth / (double) renderHeight;
            assertThat(renderAspect / surfaceAspect).isCloseTo(1.0, within(ASPECT_TOLERANCE));
        }
    }

    @Nested
    @DisplayName("the no-upscale rule")
    class NeverEnlarges
    {
        @ParameterizedTest(name = "{0}x{1}")
        @CsvSource({
            "640, 320",
            "320, 240",
            "400, 480",
            "17, 3",
            "1, 1",
            "1000, 479",
            "479, 1000",
        })
        @DisplayName("a surface already inside 480p is left exactly alone")
        void shouldNotEnlargeASmallSurface(final int surfaceWidth, final int surfaceHeight)
        {
            // Rendering 480 rows into a 320-row window would cost more pixels
            // than the window has and then throw them away on the blit: slower
            // AND blurrier, which is the worst of both.
            assertThat(RenderMode.P480.widthFor(surfaceWidth, surfaceHeight))
                .isEqualTo(surfaceWidth);
            assertThat(RenderMode.P480.heightFor(surfaceWidth, surfaceHeight))
                .isEqualTo(surfaceHeight);
            assertThat(RenderMode.P480.isNativeFor(surfaceWidth, surfaceHeight)).isTrue();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(RenderMode.class)
        @DisplayName("no mode ever returns more pixels than the surface has")
        void shouldNeverEnlargeAnything(final RenderMode mode)
        {
            for (int width = 1; width <= 4000; width += 137)
            {
                for (int height = 1; height <= 4000; height += 191)
                {
                    assertThat(mode.widthFor(width, height)).isLessThanOrEqualTo(width);
                    assertThat(mode.heightFor(width, height)).isLessThanOrEqualTo(height);
                }
            }
        }
    }

    @Nested
    @DisplayName("degenerate and extreme surfaces")
    class Extremes
    {
        @ParameterizedTest(name = "{0}")
        @EnumSource(RenderMode.class)
        @DisplayName("never produce a zero or negative dimension from a real surface")
        void shouldNeverProduceAnEmptyFramebuffer(final RenderMode mode)
        {
            // A framebuffer with a zero edge is an allocation of no pixels that
            // every downstream divide then trips over, and the aspect ratios
            // that get there — a 4000x3 letterbox, a 3x4000 column — are exactly
            // the ones nobody tries by hand.
            final int[] widths = {1, 2, 3, 17, 479, 480, 481, 1080, 2400, 4000, 7680};
            final int[] heights = {1, 2, 3, 17, 479, 480, 481, 1080, 2400, 4000, 7680};
            for (final int width : widths)
            {
                for (final int height : heights)
                {
                    assertThat(mode.widthFor(width, height)).isGreaterThan(0);
                    assertThat(mode.heightFor(width, height)).isGreaterThan(0);
                }
            }
        }

        @Test
        @DisplayName("an extreme letterbox keeps both edges and its shape")
        void shouldSurviveAnExtremeLetterbox()
        {
            // 4000x3 is already inside every ceiling — its SHORT edge is 3 — so
            // the no-upscale rule catches it before any arithmetic does. That is
            // the interesting part: the rule that protects small windows is the
            // same one that protects absurd aspects.
            assertThat(RenderMode.P480.widthFor(4000, 3)).isEqualTo(4000);
            assertThat(RenderMode.P480.heightFor(4000, 3)).isEqualTo(3);
        }

        @Test
        @DisplayName("a wildly wide surface that IS scaled keeps a positive short edge")
        void shouldKeepAPositiveShortEdge()
        {
            // 20000x1000 does scale — the short edge is over 480 — and the long
            // edge lands at 9600. Nothing here rounds to nothing, but the floor
            // exists so that a future ceiling smaller than 480 could not.
            assertThat(RenderMode.P480.heightFor(20000, 1000)).isEqualTo(480);
            assertThat(RenderMode.P480.widthFor(20000, 1000)).isEqualTo(9600);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(RenderMode.class)
        @DisplayName("a surface with no size is passed straight back")
        void shouldPassThroughANonSurface(final RenderMode mode)
        {
            // The presenter refuses these before ever asking, but a mode that
            // invented a size for them would hide that guard rather than rely
            // on it.
            assertThat(mode.widthFor(0, 0)).isZero();
            assertThat(mode.heightFor(0, 0)).isZero();
            assertThat(mode.widthFor(-4, 900)).isEqualTo(-4);
        }
    }

    @Nested
    @DisplayName("NATIVE")
    class Native
    {
        @ParameterizedTest(name = "{0}x{1}")
        @CsvSource({
            "2400, 1080",
            "1280, 720",
            "1, 1",
            "3841, 2161",
        })
        @DisplayName("is the surface exactly, not a rounded approximation of it")
        void shouldBeTheSurfaceExactly(final int surfaceWidth, final int surfaceHeight)
        {
            // Today's behaviour has to survive bit for bit: NATIVE returns the
            // surface's own two integers rather than applying a factor of 1.0
            // and rounding, so no odd size can drift by a pixel.
            assertThat(RenderMode.NATIVE.widthFor(surfaceWidth, surfaceHeight))
                .isEqualTo(surfaceWidth);
            assertThat(RenderMode.NATIVE.heightFor(surfaceWidth, surfaceHeight))
                .isEqualTo(surfaceHeight);
            assertThat(RenderMode.NATIVE.isNativeFor(surfaceWidth, surfaceHeight)).isTrue();
        }

        @Test
        @DisplayName("has no ceiling")
        void shouldHaveNoCeiling()
        {
            assertThat(RenderMode.NATIVE.shortEdgePixels()).isEqualTo(RenderMode.NO_CEILING);
        }
    }

    @Nested
    @DisplayName("the modes themselves")
    class Modes
    {
        @Test
        @DisplayName("480p is the default on every platform")
        void shouldDefaultTo480()
        {
            assertThat(RenderMode.DEFAULT).isEqualTo(RenderMode.P480);
        }

        @Test
        @DisplayName("cycle through in declaration order and wrap")
        void shouldCycle()
        {
            assertThat(RenderMode.P480.next()).isEqualTo(RenderMode.P720);
            assertThat(RenderMode.P720.next()).isEqualTo(RenderMode.NATIVE);
            assertThat(RenderMode.NATIVE.next()).isEqualTo(RenderMode.P480);
        }

        @Test
        @DisplayName("carry the label the settings screen shows")
        void shouldCarryLabels()
        {
            assertThat(RenderMode.P480.label()).isEqualTo("480P");
            assertThat(RenderMode.P720.label()).isEqualTo("720P");
            assertThat(RenderMode.NATIVE.label()).isEqualTo("NATIVE");
        }

        @Test
        @DisplayName("name the SHORT edge, not the long one")
        void shouldNameTheShortEdge()
        {
            // The distinction the whole class turns on. In portrait the mode
            // caps the WIDTH, because that is the short edge there — a mode that
            // meant "the long edge" would render a phone held upright at four
            // times the pixels of the same phone held sideways.
            assertThat(RenderMode.P480.widthFor(1080, 2400)).isEqualTo(480);
            assertThat(RenderMode.P480.heightFor(1080, 2400)).isEqualTo(1067);
        }

        @Test
        @DisplayName("a square surface takes the exact path on both edges")
        void shouldHandleASquare()
        {
            assertThat(RenderMode.P480.widthFor(1000, 1000)).isEqualTo(480);
            assertThat(RenderMode.P480.heightFor(1000, 1000)).isEqualTo(480);
        }
    }

    @Nested
    @DisplayName("the startup property")
    class Configured
    {
        @AfterEach
        void clearProperty()
        {
            System.clearProperty(RenderMode.MODE_PROPERTY);
        }

        @Test
        @DisplayName("is 480p when nothing asks otherwise")
        void shouldDefaultWithNoProperty()
        {
            System.clearProperty(RenderMode.MODE_PROPERTY);
            assertThat(RenderMode.configured()).isEqualTo(RenderMode.P480);
        }

        @Test
        @DisplayName("takes the words the settings screen shows, in any case")
        void shouldAcceptTheScreenLabels()
        {
            // The property and the button have to agree, or the flag that
            // reproduces what a player did would not reproduce it.
            System.setProperty(RenderMode.MODE_PROPERTY, "native");
            assertThat(RenderMode.configured()).isEqualTo(RenderMode.NATIVE);
            System.setProperty(RenderMode.MODE_PROPERTY, "720P");
            assertThat(RenderMode.configured()).isEqualTo(RenderMode.P720);
            System.setProperty(RenderMode.MODE_PROPERTY, "  480p  ");
            assertThat(RenderMode.configured()).isEqualTo(RenderMode.P480);
        }

        @Test
        @DisplayName("falls back rather than failing on a value it cannot read")
        void shouldIgnoreRubbish()
        {
            // A bad diagnostic flag must not stop a window opening.
            System.setProperty(RenderMode.MODE_PROPERTY, "1080p");
            assertThat(RenderMode.configured()).isEqualTo(RenderMode.DEFAULT);
        }
    }
}
