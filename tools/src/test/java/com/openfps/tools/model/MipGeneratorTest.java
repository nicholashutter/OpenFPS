/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.model;

import java.util.Arrays;

import com.openfps.engine.render.adapter.MipChain;
import com.openfps.engine.render.adapter.Rgba;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for build-time mip pyramid generation.
 *
 * <p>The load-bearing property is not the filter's exact arithmetic — it is
 * that the pyramid's shape is one {@link MipChain} will accept. A converter
 * that produced a subtly wrong level size would fail at runtime, on a
 * player's machine, with the asset already shipped; a test that constructs a
 * real {@code MipChain} from the generator's output catches that here.</p>
 */
class MipGeneratorTest
{
    @Nested
    @DisplayName("pyramid shape")
    class PyramidShape
    {
        @Test
        @DisplayName("a square texture yields levels down to 1x1")
        void shouldCountSquareLevels()
        {
            assertThat(MipGenerator.levelCount(256, 256)).isEqualTo(9);

            assertThat(MipGenerator.levelCount(512, 512)).isEqualTo(10);

            assertThat(MipGenerator.levelCount(1, 1)).isEqualTo(1);
        }

        @Test
        @DisplayName("a non-square texture keeps going until its longest edge reaches 1")
        void shouldCountNonSquareLevels()
        {
            assertThat(MipGenerator.levelCount(256, 64)).isEqualTo(9);

            assertThat(MipGenerator.levelCount(2, 8)).isEqualTo(4);
        }

        @Test
        @DisplayName("every level measures max(1, w >> k) by max(1, h >> k)")
        void shouldSizeEveryLevel()
        {
            final int[][] levels = MipGenerator.generate(8, 2, new int[16]);

            assertThat(levels.length).isEqualTo(4);

            assertThat(levels[0]).hasSize(8 * 2);

            assertThat(levels[1]).hasSize(4 * 1);

            assertThat(levels[2]).hasSize(2 * 1);

            assertThat(levels[3]).hasSize(1);
        }

        @Test
        @DisplayName("the generated pyramid is accepted by the runtime MipChain")
        void shouldProduceARuntimeChain()
        {
            final int[] level0 = new int[64 * 32];

            Arrays.fill(level0, Rgba.OPAQUE_BLACK);

            final int[][] levels = MipGenerator.generate(64, 32, level0);

            assertThatCode(() -> new MipChain(64, 32, levels)).doesNotThrowAnyException();

            assertThat(new MipChain(64, 32, levels).levelCount())
                .isEqualTo(MipGenerator.levelCount(64, 32));
        }

        @Test
        @DisplayName("level 0 is copied, so the caller's array stays its own")
        void shouldCopyLevelZero()
        {
            final int[] level0 = {1, 2, 3, 4};

            final int[][] levels = MipGenerator.generate(2, 2, level0);

            level0[0] = 99;

            assertThat(levels[0][0]).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the box filter")
    class BoxFilter
    {
        @Test
        @DisplayName("a 2x2 block averages to the mean of its four texels per channel")
        void shouldAverageFourTexels()
        {
            final int[] level0 =
            {
                Rgba.pack(0, 0, 0, 255), Rgba.pack(100, 0, 0, 255),
                Rgba.pack(0, 200, 0, 255), Rgba.pack(100, 200, 0, 255),
            };

            final int[] level1 = MipGenerator.downsample(level0, 2, 2);

            assertThat(level1).hasSize(1);

            assertThat(Rgba.red(level1[0])).isEqualTo(50);

            assertThat(Rgba.green(level1[0])).isEqualTo(100);

            assertThat(Rgba.blue(level1[0])).isZero();

            assertThat(Rgba.alpha(level1[0])).isEqualTo(255);
        }

        @Test
        @DisplayName("a uniform image stays exactly uniform at every level")
        void shouldPreserveUniformColour()
        {
            final int colour = Rgba.pack(37, 211, 5, 255);

            final int[] level0 = new int[16 * 16];

            Arrays.fill(level0, colour);

            final int[][] levels = MipGenerator.generate(16, 16, level0);

            for (final int[] level : levels)
            {
                assertThat(level).containsOnly(colour);
            }
        }

        @Test
        @DisplayName("a collapsed axis averages along the surviving one, not out of bounds")
        void shouldHandleCollapsedAxis()
        {
            final int[] source = {Rgba.pack(0, 0, 0, 0), Rgba.pack(200, 200, 200, 200)};

            final int[] halved = MipGenerator.downsample(source, 2, 1);

            assertThat(halved).hasSize(1);

            assertThat(Rgba.red(halved[0])).isEqualTo(100);
        }

        @Test
        @DisplayName("averaging rounds half up rather than truncating")
        void shouldRoundHalfUp()
        {
            final int[] level0 =
            {
                Rgba.pack(1, 0, 0, 0), Rgba.pack(1, 0, 0, 0),
                Rgba.pack(2, 0, 0, 0), Rgba.pack(2, 0, 0, 0),
            };

            assertThat(Rgba.red(MipGenerator.downsample(level0, 2, 2)[0])).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("rejections")
    class Rejections
    {
        @Test
        @DisplayName("a non-power-of-two dimension is refused by name")
        void shouldRejectNonPowerOfTwo()
        {
            assertThatThrownBy(() -> MipGenerator.generate(3, 4, new int[12]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("width")
                .hasMessageContaining("3");

            assertThatThrownBy(() -> MipGenerator.generate(4, 6, new int[24]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("height");
        }

        @Test
        @DisplayName("a level 0 array of the wrong length is refused")
        void shouldRejectWrongSizedLevelZero()
        {
            assertThatThrownBy(() -> MipGenerator.generate(4, 4, new int[15]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16");

            assertThatThrownBy(() -> MipGenerator.generate(4, 4, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
        }
    }
}
