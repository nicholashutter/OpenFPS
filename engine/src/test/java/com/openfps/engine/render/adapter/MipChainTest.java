/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the mip chain container.
 *
 * The chain's job is to reject a malformed pyramid at load time and to fetch
 * a texel correctly forever after. Both halves are covered here: the
 * validation is what stops a converter bug reaching the inner loop, and the
 * repeat wrapping is what the whole sampler is built on.
 */
class MipChainTest
{
    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        @DisplayName("a non-power-of-two width is rejected by name")
        void shouldRejectNonPowerOfTwoWidth()
        {
            assertThatThrownBy(() -> TextureFixtures.single(3, 4, new int[12]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("width")
                .hasMessageContaining("3");
        }

        @Test
        @DisplayName("a non-power-of-two height is rejected by name")
        void shouldRejectNonPowerOfTwoHeight()
        {
            assertThatThrownBy(() -> TextureFixtures.single(4, 6, new int[24]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("height")
                .hasMessageContaining("6");
        }

        @Test
        @DisplayName("a zero or negative dimension is rejected")
        void shouldRejectNonPositiveDimension()
        {
            assertThatThrownBy(() -> TextureFixtures.single(0, 4, new int[0]))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> TextureFixtures.single(4, -4, new int[16]))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an empty or null level list is rejected")
        void shouldRejectEmptyChain()
        {
            assertThatThrownBy(() -> TextureFixtures.chain(4, 4, new int[0][]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one level");

            assertThatThrownBy(() -> TextureFixtures.chain(4, 4, null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a level whose texel count does not match its dimensions is rejected")
        void shouldRejectMisSizedLevel()
        {
            final int[][] levels = {new int[16], new int[3]};

            assertThatThrownBy(() -> TextureFixtures.chain(4, 4, levels))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("level 1")
                .hasMessageContaining("2x2");
        }

        @Test
        @DisplayName("a null level is rejected, naming its index")
        void shouldRejectNullLevel()
        {
            final int[][] levels = {new int[16], null};

            assertThatThrownBy(() -> TextureFixtures.chain(4, 4, levels))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("level 1");
        }

        @Test
        @DisplayName("a chain running past 1x1 is rejected")
        void shouldRejectTooManyLevels()
        {
            final int[][] levels = {new int[16], new int[4], new int[1], new int[1]};

            assertThatThrownBy(() -> TextureFixtures.chain(4, 4, levels))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 3 levels");
        }

        @Test
        @DisplayName("a partial chain is legal")
        void shouldAcceptPartialChain()
        {
            final int[][] levels = {new int[64], new int[16]};

            final MipChain chain = TextureFixtures.chain(8, 8, levels);

            assertThat(chain.levelCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("a single 1x1 level is legal")
        void shouldAcceptOneByOneChain()
        {
            final MipChain chain = TextureFixtures.single(1, 1, new int[] {0x11223344});

            assertThat(chain.levelCount()).isEqualTo(1);

            assertThat(chain.width(0)).isEqualTo(1);

            assertThat(chain.texel(0, 0, 0)).isEqualTo(0x11223344);
        }

        @Test
        @DisplayName("level data is copied, so later caller mutation cannot leak in")
        void shouldCopyLevelData()
        {
            final int[] base = {1, 2, 3, 4};

            final MipChain chain = TextureFixtures.single(2, 2, base);

            base[0] = 0xDEADBEEF;

            assertThat(chain.texel(0, 0, 0)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("dimensions")
    class Dimensions
    {
        @Test
        @DisplayName("each level is half the previous, in both axes")
        void shouldHalveEachLevel()
        {
            final MipChain chain = TextureFixtures.solid(8, 8, 4, 0);

            assertThat(chain.width(0)).isEqualTo(8);

            assertThat(chain.width(1)).isEqualTo(4);

            assertThat(chain.width(2)).isEqualTo(2);

            assertThat(chain.width(3)).isEqualTo(1);

            assertThat(chain.height(3)).isEqualTo(1);
        }

        @Test
        @DisplayName("a non-square texture floors the short axis at 1 and keeps going")
        void shouldFloorShortAxisAtOne()
        {
            final MipChain chain = TextureFixtures.solid(8, 2, 4, 0);

            assertThat(chain.width(0)).isEqualTo(8);

            assertThat(chain.height(0)).isEqualTo(2);

            assertThat(chain.width(1)).isEqualTo(4);

            assertThat(chain.height(1)).isEqualTo(1);

            assertThat(chain.width(2)).isEqualTo(2);

            assertThat(chain.height(2)).isEqualTo(1);

            assertThat(chain.width(3)).isEqualTo(1);

            assertThat(chain.height(3)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("texel fetch")
    class Fetch
    {
        @Test
        @DisplayName("a fetch reads row-major, so x is the fast axis")
        void shouldFetchRowMajor()
        {
            final MipChain chain = TextureFixtures.single(4, 2,
                TextureFixtures.coordinateTexels(4, 2));

            assertThat(TextureSampler.red(chain.texel(0, 3, 1))).isEqualTo(3);

            assertThat(TextureSampler.green(chain.texel(0, 3, 1))).isEqualTo(1);
        }

        @Test
        @DisplayName("an x past the right edge repeats to the left edge")
        void shouldRepeatInX()
        {
            final MipChain chain = TextureFixtures.single(4, 4,
                TextureFixtures.coordinateTexels(4, 4));

            assertThat(chain.texel(0, 4, 2)).isEqualTo(chain.texel(0, 0, 2));

            assertThat(chain.texel(0, 5, 2)).isEqualTo(chain.texel(0, 1, 2));
        }

        @Test
        @DisplayName("a y past the bottom edge repeats to the top edge")
        void shouldRepeatInY()
        {
            final MipChain chain = TextureFixtures.single(4, 4,
                TextureFixtures.coordinateTexels(4, 4));

            assertThat(chain.texel(0, 1, 4)).isEqualTo(chain.texel(0, 1, 0));

            assertThat(chain.texel(0, 1, 7)).isEqualTo(chain.texel(0, 1, 3));
        }

        @Test
        @DisplayName("a negative coordinate repeats from the far edge")
        void shouldRepeatNegativeCoordinates()
        {
            final MipChain chain = TextureFixtures.single(4, 4,
                TextureFixtures.coordinateTexels(4, 4));

            assertThat(chain.texel(0, -1, 0)).isEqualTo(chain.texel(0, 3, 0));

            assertThat(chain.texel(0, 0, -1)).isEqualTo(chain.texel(0, 0, 3));

            assertThat(chain.texel(0, -5, -5)).isEqualTo(chain.texel(0, 3, 3));
        }

        @Test
        @DisplayName("every coordinate of a 1x1 level reads the one texel")
        void shouldRepeatOneByOne()
        {
            final MipChain chain = TextureFixtures.solid(8, 8, 4, 0x00000000);

            final MipChain single = TextureFixtures.single(1, 1, new int[] {0x0A0B0C0D});

            assertThat(single.texel(0, 17, -3)).isEqualTo(0x0A0B0C0D);

            assertThat(chain.texel(3, 100, 100)).isZero();
        }

        @Test
        @DisplayName("levels are addressed independently, not as one flat image")
        void shouldFetchPerLevel()
        {
            final int[][] levels = {
                TextureFixtures.coordinateTexels(4, 4),
                new int[] {10, 11, 12, 13},
                new int[] {99},
            };

            final MipChain chain = TextureFixtures.chain(4, 4, levels);

            assertThat(chain.texel(1, 0, 0)).isEqualTo(10);

            assertThat(chain.texel(1, 1, 1)).isEqualTo(13);

            assertThat(chain.texel(2, 0, 0)).isEqualTo(99);

            assertThat(chain.texel(2, 5, 5)).isEqualTo(99);
        }
    }
}
