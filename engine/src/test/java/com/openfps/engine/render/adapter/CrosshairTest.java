/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link Crosshair}.
 *
 * The two that carry the design are {@link Centring}, which pins the
 * even-dimension answer as exact mirror symmetry rather than as a tolerance,
 * and {@link Readability}, which asserts that no core pixel ever touches the
 * background — the property the whole green-inside-black scheme exists to
 * provide.
 */
@DisplayName("Crosshair")
final class CrosshairTest
{
    /**
     * The colour the buffer is cleared to before drawing. Deliberately neither
     * of the reticle's colours and nothing like them, so every drawn pixel is
     * identifiable and every untouched one is too.
     */
    private static final int BACKGROUND = Rgba.pack(0xA1, 0xA9, 0xCA, 0xFF);

    private static Framebuffer drawn(final int width, final int height)
    {
        final Framebuffer fb = new Framebuffer();
        fb.init(width, height);
        fb.clearColor(BACKGROUND);
        Crosshair.draw(fb);
        return fb;
    }

    // Inclusive bounding box of everything that is not the background, as
    // {minX, minY, maxX, maxY}, or null when nothing was drawn.
    private static int[] paintedBounds(final Framebuffer fb)
    {
        // MUTABLE locals — the running bounding box.
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int y = 0; y < fb.height(); y++)
        {
            for (int x = 0; x < fb.width(); x++)
            {
                if (fb.pixel(x, y) != BACKGROUND)
                {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < minX)
        {
            return null;
        }
        return new int[] {minX, minY, maxX, maxY};
    }

    private static int countOf(final Framebuffer fb, final int rgba)
    {
        // MUTABLE local — running tally.
        int found = 0;
        for (int y = 0; y < fb.height(); y++)
        {
            for (int x = 0; x < fb.width(); x++)
            {
                if (fb.pixel(x, y) == rgba)
                {
                    found++;
                }
            }
        }
        return found;
    }

    @Nested
    @DisplayName("centring")
    class Centring
    {
        @ParameterizedTest(name = "{0}x{1}")
        @CsvSource({"1280,720", "1920,1080", "641,361", "800,601", "1023,719", "320,241"})
        @DisplayName("is identical to its own mirror image, so it cannot be off centre")
        void shouldBeExactlySymmetricAboutBothAxes(final int width, final int height)
        {
            final Framebuffer fb = drawn(width, height);
            for (int y = 0; y < height; y++)
            {
                for (int x = 0; x < width; x++)
                {
                    assertThat(fb.pixel(x, y))
                        .as("(%d,%d) vs its horizontal mirror", x, y)
                        .isEqualTo(fb.pixel(width - 1 - x, y));
                    assertThat(fb.pixel(x, y))
                        .as("(%d,%d) vs its vertical mirror", x, y)
                        .isEqualTo(fb.pixel(x, height - 1 - y));
                }
            }
        }

        @ParameterizedTest(name = "{0}x{1}")
        @CsvSource({"1280,720", "1920,1080", "641,361", "800,601", "1023,719", "320,241"})
        @DisplayName("leaves exactly equal margins on opposite sides, on odd and even frames alike")
        void shouldLeaveEqualMarginsOnEverySide(final int width, final int height)
        {
            final Framebuffer fb = drawn(width, height);
            final int[] box = paintedBounds(fb);
            assertThat(box).as("something must have been drawn").isNotNull();
            assertThat(box[0]).as("left margin equals right margin")
                .isEqualTo(width - 1 - box[2]);
            assertThat(box[1]).as("top margin equals bottom margin")
                .isEqualTo(height - 1 - box[3]);
        }

        @Test
        @DisplayName("leaves the point of aim unpainted, so the reticle never covers the target")
        void shouldLeaveTheCentreGapEmptyOnAnEvenFrame()
        {
            final Framebuffer fb = drawn(1280, 720);
            // An even frame has no centre pixel; its geometric centre is the
            // corner shared by these four, and all four must be untouched.
            assertThat(fb.pixel(639, 359)).isEqualTo(BACKGROUND);
            assertThat(fb.pixel(640, 359)).isEqualTo(BACKGROUND);
            assertThat(fb.pixel(639, 360)).isEqualTo(BACKGROUND);
            assertThat(fb.pixel(640, 360)).isEqualTo(BACKGROUND);
        }

        @Test
        @DisplayName("leaves the centre pixel of an odd frame unpainted too")
        void shouldLeaveTheCentreGapEmptyOnAnOddFrame()
        {
            final Framebuffer fb = drawn(641, 361);
            assertThat(fb.pixel(320, 180)).isEqualTo(BACKGROUND);
        }

        @Test
        @DisplayName("thickens an arm by one pixel when parity demands it, rather than "
            + "sitting half a pixel off centre")
        void shouldMatchArmThicknessParityToItsAxis()
        {
            // The horizontal arm lies across Y, so its thickness follows the
            // HEIGHT's parity: 7 requested, 8 drawn in an even-height frame.
            assertThat(Crosshair.coreThickness(720)).isEqualTo(7);
            final Framebuffer even = drawn(1280, 720);
            assertThat(coreRunLengthDownColumn(even, 1280 / 2 - 50)).isEqualTo(8);

            // 721 asks for the same 7 and keeps it, because 7 and 721 agree.
            assertThat(Crosshair.coreThickness(721)).isEqualTo(7);
            final Framebuffer odd = drawn(1281, 721);
            assertThat(coreRunLengthDownColumn(odd, 1281 / 2 - 50)).isEqualTo(7);
        }

        // Core pixels in one column — the thickness of the horizontal arm
        // where that column crosses it.
        private int coreRunLengthDownColumn(final Framebuffer fb, final int x)
        {
            // MUTABLE local — running tally.
            int found = 0;
            for (int y = 0; y < fb.height(); y++)
            {
                if (fb.pixel(x, y) == Crosshair.CORE_COLOR)
                {
                    found++;
                }
            }
            return found;
        }
    }

    @Nested
    @DisplayName("readability against the demo scene")
    class Readability
    {
        @Test
        @DisplayName("paints both the green core and the black outline, not just one of them")
        void shouldPaintBothColours()
        {
            final Framebuffer fb = drawn(1280, 720);
            assertThat(countOf(fb, Crosshair.CORE_COLOR))
                .as("green core pixels").isGreaterThan(0);
            assertThat(countOf(fb, Crosshair.OUTLINE_COLOR))
                .as("black outline pixels").isGreaterThan(0);
        }

        @Test
        @DisplayName("never lets a core pixel touch the background, so a pale wall cannot "
            + "swallow the reticle")
        void shouldFullyEncloseTheCoreInOutline()
        {
            final Framebuffer fb = drawn(1280, 720);
            for (int y = 0; y < fb.height(); y++)
            {
                for (int x = 0; x < fb.width(); x++)
                {
                    if (fb.pixel(x, y) != Crosshair.CORE_COLOR)
                    {
                        continue;
                    }
                    assertNeighbourIsReticle(fb, x - 1, y);
                    assertNeighbourIsReticle(fb, x + 1, y);
                    assertNeighbourIsReticle(fb, x, y - 1);
                    assertNeighbourIsReticle(fb, x, y + 1);
                }
            }
        }

        @Test
        @DisplayName("uses colours whose luminances straddle the whole demo scene's range")
        void shouldStraddleTheSceneLuminanceRange()
        {
            // Measured from tools:demoPreview shot 01-down-room: the darkest
            // pixel in the frame is the doorway at luminance 28 and the
            // brightest is an amber crate decal at 201. Neither reticle colour
            // may sit inside that band, or some background could match it.
            assertThat(luminance(Crosshair.OUTLINE_COLOR)).isLessThan(28.0);
            assertThat(luminance(Crosshair.CORE_COLOR)).isGreaterThan(28.0);
            assertThat(luminance(Crosshair.CORE_COLOR) - luminance(Crosshair.OUTLINE_COLOR))
                .as("the reticle's own contrast must exceed the scene's whole range")
                .isGreaterThan(201.0 - 28.0);
        }

        @Test
        @DisplayName("uses a core hue the demo scene contains no pixel of, so it cannot be "
            + "confused with the orange weapon")
        void shouldUseAHueAbsentFromTheScene()
        {
            // Every demo surface is blue-grey or orange-to-amber; a sweep of
            // all 230,400 pixels of shot 01-down-room found zero in which
            // green was the dominant channel. That is the property being
            // relied on, so the core must actually be green-dominant.
            assertThat(Rgba.green(Crosshair.CORE_COLOR))
                .isGreaterThan(Rgba.red(Crosshair.CORE_COLOR))
                .isGreaterThan(Rgba.blue(Crosshair.CORE_COLOR));
            assertThat(Rgba.alpha(Crosshair.CORE_COLOR)).isEqualTo(0xFF);
            assertThat(Rgba.alpha(Crosshair.OUTLINE_COLOR)).isEqualTo(0xFF);
        }

        @Test
        @DisplayName("is big — at least a sixth of the frame height from tip to tip")
        void shouldBeUnmistakablyLarge()
        {
            final Framebuffer fb = drawn(1280, 720);
            final int[] box = paintedBounds(fb);
            assertThat(box).isNotNull();
            assertThat(box[2] - box[0] + 1).as("width from tip to tip")
                .isGreaterThan(720 / 6);
            assertThat(box[3] - box[1] + 1).as("height from tip to tip")
                .isGreaterThan(720 / 6);
        }

        private double luminance(final int rgba)
        {
            return 0.2126 * Rgba.red(rgba)
                + 0.7152 * Rgba.green(rgba)
                + 0.0722 * Rgba.blue(rgba);
        }

        private void assertNeighbourIsReticle(final Framebuffer fb, final int x, final int y)
        {
            if (x < 0 || x >= fb.width() || y < 0 || y >= fb.height())
            {
                return;
            }
            assertThat(fb.pixel(x, y))
                .as("neighbour (%d,%d) of a core pixel", x, y)
                .isIn(Crosshair.CORE_COLOR, Crosshair.OUTLINE_COLOR);
        }
    }

    @Nested
    @DisplayName("scaling with frame height")
    class Scaling
    {
        @Test
        @DisplayName("doubles every dimension when the frame height doubles, so it is neither "
            + "a dot at 1440p nor overwhelming at 720p")
        void shouldScaleProportionallyFrom720pTo1440p()
        {
            assertThat(Crosshair.armLength(1440)).isEqualTo(2 * Crosshair.armLength(720));
            assertThat(Crosshair.centreGap(1440)).isEqualTo(2 * Crosshair.centreGap(720));
            assertThat(Crosshair.coreThickness(1440))
                .isEqualTo(2 * Crosshair.coreThickness(720));
            assertThat(Crosshair.outlineThickness(1440))
                .isEqualTo(2 * Crosshair.outlineThickness(720));
            assertThat(Crosshair.totalSpan(1440)).isEqualTo(2 * Crosshair.totalSpan(720));
        }

        @Test
        @DisplayName("draws an actually-doubled figure, not merely doubled constants")
        void shouldDrawProportionallyAtBothResolutions()
        {
            final int[] small = paintedBounds(drawn(1280, 720));
            final int[] large = paintedBounds(drawn(2560, 1440));
            assertThat(small).isNotNull();
            assertThat(large).isNotNull();
            assertThat(large[2] - large[0] + 1).isEqualTo(2 * (small[2] - small[0] + 1));
            assertThat(large[3] - large[1] + 1).isEqualTo(2 * (small[3] - small[1] + 1));
        }

        @Test
        @DisplayName("subtends the same fraction of frame height at every resolution")
        void shouldHoldAConstantFractionOfFrameHeight()
        {
            assertThat(Crosshair.totalSpan(720) / 720.0)
                .isCloseTo(Crosshair.totalSpan(1440) / 1440.0, within(0.005));
            assertThat(Crosshair.totalSpan(720) / 720.0)
                .isCloseTo(Crosshair.totalSpan(2160) / 2160.0, within(0.005));
        }

        @Test
        @DisplayName("keeps every part at least one pixel, so a small window gets a miniature "
            + "rather than a broken figure")
        void shouldNeverDegenerateToZeroThickness()
        {
            for (int height = 1; height <= 400; height++)
            {
                assertThat(Crosshair.coreThickness(height)).isGreaterThanOrEqualTo(1);
                assertThat(Crosshair.armLength(height)).isGreaterThanOrEqualTo(1);
                assertThat(Crosshair.outlineThickness(height)).isGreaterThanOrEqualTo(1);
                assertThat(Crosshair.centreGap(height))
                    .as("the gap must survive the outline eating into it at height %d", height)
                    .isGreaterThan(Crosshair.outlineThickness(height));
            }
        }
    }

    @Nested
    @DisplayName("bounds and clipping")
    class Bounds
    {
        @ParameterizedTest(name = "{0}x{1}")
        @CsvSource({"64,64", "1,1", "2,2", "4,400", "400,4", "16,16", "17,3", "3,17"})
        @DisplayName("draws a cropped fragment rather than throwing when the frame is smaller "
            + "than the reticle")
        void shouldClipToTinyFramesWithoutThrowing(final int width, final int height)
        {
            assertThatCode(() -> drawn(width, height)).doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "{0}x{1}")
        @CsvSource({"1280,720", "64,64", "4,400", "400,4", "17,3", "641,361"})
        @DisplayName("writes nothing into the stride padding, which belongs to no tile")
        void shouldNeverWriteOutsideTheVisibleRectangle(final int width, final int height)
        {
            final Framebuffer fb = new Framebuffer();
            fb.init(width, height);
            final int sentinel = Rgba.pack(0x11, 0x22, 0x33, 0x44);
            Arrays.fill(fb.colorBuffer(), sentinel);
            Crosshair.draw(fb);

            final int[] color = fb.colorBuffer();
            for (int y = 0; y < height; y++)
            {
                for (int x = width; x < fb.strideInPixels(); x++)
                {
                    assertThat(color[y * fb.strideInPixels() + x])
                        .as("padding pixel (%d,%d)", x, y)
                        .isEqualTo(sentinel);
                }
            }
        }

        @Test
        @DisplayName("still paints something on a frame far smaller than the reticle")
        void shouldStillPaintOnATinyFrame()
        {
            final Framebuffer fb = drawn(64, 64);
            assertThat(paintedBounds(fb)).as("a 64x64 frame must still show a reticle")
                .isNotNull();
        }

        @Test
        @DisplayName("refuses a framebuffer that has no buffers, rather than throwing NPE deep "
            + "inside a fill")
        void shouldRejectAnUninitializedFramebuffer()
        {
            assertThatThrownBy(() -> Crosshair.draw(new Framebuffer()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("READY");
        }

        @Test
        @DisplayName("refuses a null framebuffer")
        void shouldRejectNull()
        {
            assertThatThrownBy(() -> Crosshair.draw(null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("purity")
    class Purity
    {
        @Test
        @DisplayName("produces byte-identical output from identical input, every time")
        void shouldBeDeterministic()
        {
            final Framebuffer first = drawn(1280, 720);
            final Framebuffer second = drawn(1280, 720);
            assertThat(second.colorBuffer()).isEqualTo(first.colorBuffer());
        }

        @Test
        @DisplayName("is idempotent — drawing twice is indistinguishable from drawing once, "
            + "because every write is an unconditional store and nothing blends")
        void shouldBeIdempotent()
        {
            final Framebuffer once = drawn(1280, 720);
            final int[] afterOne = once.colorBuffer().clone();
            Crosshair.draw(once);
            assertThat(once.colorBuffer()).isEqualTo(afterOne);
            Crosshair.draw(once);
            Crosshair.draw(once);
            assertThat(once.colorBuffer()).isEqualTo(afterOne);
        }

        @Test
        @DisplayName("leaves the depth buffer alone, because the reticle is not in the world")
        void shouldNotTouchDepth()
        {
            final Framebuffer fb = new Framebuffer();
            fb.init(1280, 720);
            fb.clear();
            final float[] before = fb.depthBuffer().clone();
            Crosshair.draw(fb);
            assertThat(fb.depthBuffer()).isEqualTo(before);
        }

        @Test
        @DisplayName("is not instantiable — it is one drawing routine, not an object")
        void shouldNotBeInstantiable()
        {
            assertThat(Crosshair.class.getDeclaredConstructors()).hasSize(1);
        }
    }
}
