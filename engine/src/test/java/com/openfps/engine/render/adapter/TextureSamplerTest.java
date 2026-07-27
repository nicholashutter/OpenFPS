/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for bilinear sampling, mip selection and texel unpacking.
 *
 * Three things are being defended here.
 *
 * The first is the half-texel offset. Bilinear weights are defined between
 * texel CENTRES, so a sample taken exactly at a centre must return that texel
 * bit-for-bit. Drop the offset and every texture slides half a texel — a
 * defect that looks like mild blurriness rather than an error, and therefore
 * ships. {@code shouldReturnTexelUnchangedAtItsCentre} is the sharp test of it.
 *
 * The second is that the packed integer blend is a rearrangement of the
 * obvious scalar one, not an approximation. The scalar version lives in this
 * file as {@link #referenceBlend} and the two are compared across every weight
 * pair in the domain. If someone later "improves" the packed arithmetic and
 * changes a single output value, that sweep fails.
 *
 * The third is the derivative-to-LOD relationship and its clamps at both ends
 * of a possibly-partial chain.
 */
class TextureSamplerTest
{
    /** Filter weight of 1.0 in the sampler's 0.8 fixed point. */
    private static final int WEIGHT_ONE = 256;

    /** Rounding bias used by the reference blend, matching the sampler. */
    private static final int WEIGHT_ROUND = 128;

    /** Fractional bits in a filter weight. */
    private static final int WEIGHT_SHIFT = 8;

    /** Deterministic seed for the pseudo-random fixtures. */
    private static final long SEED = 0x5EED1234L;

    @Nested
    @DisplayName("texel centres and the half-texel offset")
    class TexelCentres
    {
        @Test
        @DisplayName("sampling exactly at a texel centre returns that texel unchanged")
        void shouldReturnTexelUnchangedAtItsCentre()
        {
            final int size = 4;
            final MipChain chain = TextureFixtures.single(size, size,
                TextureFixtures.pseudoRandomTexels(size, size, SEED));

            for (int y = 0; y < size; y++)
            {
                for (int x = 0; x < size; x++)
                {
                    final float u = (x + 0.5f) / size;
                    final float v = (y + 0.5f) / size;

                    assertThat(TextureSampler.sampleLevel(chain, u, v, 0))
                        .describedAs("texel (%d, %d)", x, y)
                        .isEqualTo(chain.texel(0, x, y));
                }
            }
        }

        @Test
        @DisplayName("a texture sampled at 1:1 scale is reproduced exactly, not blurred")
        void shouldReproduceTextureAtUnitScale()
        {
            final int size = 8;
            final MipChain chain = TextureFixtures.single(size, size,
                TextureFixtures.pseudoRandomTexels(size, size, SEED));

            // MUTABLE local — counts texels that came back altered.
            int altered = 0;
            for (int i = 0; i < size * size; i++)
            {
                final int x = i % size;
                final int y = i / size;
                final int sampled = TextureSampler.sampleLevel(chain,
                    (x + 0.5f) / size, (y + 0.5f) / size, 0);
                if (sampled != chain.texel(0, x, y))
                {
                    altered++;
                }
            }

            assertThat(altered).isZero();
        }

        @Test
        @DisplayName("the texture corner sits half a texel before the first centre")
        void shouldBlendAcrossTheWrapAtTheCorner()
        {
            final int size = 4;
            final int[] texels = TextureFixtures.coordinateTexels(size, size);
            final MipChain chain = TextureFixtures.single(size, size, texels);

            // u = v = 0 is the texture corner, half a texel before centre (0, 0).
            // With repeat addressing the other half of the footprint is the
            // LAST row and column, so this is a four-way blend of the corners.
            final int sampled = TextureSampler.sampleLevel(chain, 0.0f, 0.0f, 0);
            final int expected = TextureSampler.blend(
                chain.texel(0, 3, 3), chain.texel(0, 0, 3),
                chain.texel(0, 3, 0), chain.texel(0, 0, 0),
                WEIGHT_ONE / 2, WEIGHT_ONE / 2);

            assertThat(sampled).isEqualTo(expected);
            assertThat(sampled).isNotEqualTo(chain.texel(0, 0, 0));
        }

        @Test
        @DisplayName("sampling midway between two centres averages exactly those two")
        void shouldAverageAtTheMidpointOfTwoCentres()
        {
            final int size = 4;
            final int left = TextureFixtures.rgba(10, 20, 30, 40);
            final int right = TextureFixtures.rgba(20, 40, 60, 80);
            final int[] texels = new int[size * size];
            for (int y = 0; y < size; y++)
            {
                texels[y * size + 1] = left;
                texels[y * size + 2] = right;
            }
            final MipChain chain = TextureFixtures.single(size, size, texels);

            // Boundary between texel 1 and texel 2 — u = 2/4.
            final int sampled = TextureSampler.sampleLevel(chain, 2.0f / size, 0.5f / size, 0);

            assertThat(TextureSampler.red(sampled)).isEqualTo(15);
            assertThat(TextureSampler.green(sampled)).isEqualTo(30);
            assertThat(TextureSampler.blue(sampled)).isEqualTo(45);
            assertThat(TextureSampler.alpha(sampled)).isEqualTo(60);
        }

        @Test
        @DisplayName("a one-texel-wide texture returns its single column everywhere")
        void shouldHandleOneByOneLevel()
        {
            final int colour = TextureFixtures.rgba(9, 8, 7, 6);
            final MipChain chain = TextureFixtures.single(1, 1, new int[] {colour});

            assertThat(TextureSampler.sampleLevel(chain, 0.0f, 0.0f, 0)).isEqualTo(colour);
            assertThat(TextureSampler.sampleLevel(chain, 0.5f, 0.5f, 0)).isEqualTo(colour);
            assertThat(TextureSampler.sampleLevel(chain, 123.75f, -4.25f, 0)).isEqualTo(colour);
        }
    }

    @Nested
    @DisplayName("addressing at texture edges")
    class Addressing
    {
        @Test
        @DisplayName("u = 1.0 samples the same point as u = 0.0")
        void shouldRepeatAtOne()
        {
            final MipChain chain = TextureFixtures.single(8, 8,
                TextureFixtures.pseudoRandomTexels(8, 8, SEED));

            assertThat(TextureSampler.sampleLevel(chain, 1.0f, 0.25f, 0))
                .isEqualTo(TextureSampler.sampleLevel(chain, 0.0f, 0.25f, 0));
            assertThat(TextureSampler.sampleLevel(chain, 0.25f, 1.0f, 0))
                .isEqualTo(TextureSampler.sampleLevel(chain, 0.25f, 0.0f, 0));
        }

        @Test
        @DisplayName("a whole-texture offset changes nothing")
        void shouldRepeatOnWholeTextureOffsets()
        {
            final MipChain chain = TextureFixtures.single(8, 8,
                TextureFixtures.pseudoRandomTexels(8, 8, SEED));
            final float u = 1.5f / 8.0f;
            final float v = 3.5f / 8.0f;

            assertThat(TextureSampler.sampleLevel(chain, u + 1.0f, v, 0))
                .isEqualTo(TextureSampler.sampleLevel(chain, u, v, 0));
            assertThat(TextureSampler.sampleLevel(chain, u, v + 3.0f, 0))
                .isEqualTo(TextureSampler.sampleLevel(chain, u, v, 0));
        }

        @Test
        @DisplayName("a negative coordinate repeats rather than clamping")
        void shouldRepeatNegativeCoordinates()
        {
            final MipChain chain = TextureFixtures.single(8, 8,
                TextureFixtures.pseudoRandomTexels(8, 8, SEED));

            assertThat(TextureSampler.sampleLevel(chain, -0.5f / 8.0f, 0.5f / 8.0f, 0))
                .isEqualTo(chain.texel(0, 7, 0));
            assertThat(TextureSampler.sampleLevel(chain, 0.5f / 8.0f, -0.5f / 8.0f, 0))
                .isEqualTo(chain.texel(0, 0, 7));
        }

        @Test
        @DisplayName("the footprint just inside the right edge reaches around to column 0")
        void shouldBlendLastColumnWithFirst()
        {
            final int size = 4;
            final int[] texels = TextureFixtures.coordinateTexels(size, size);
            final MipChain chain = TextureFixtures.single(size, size, texels);

            // Halfway between the centre of the last column and the centre of
            // the (wrapped) first column, on the centre line of row 0.
            final int sampled = TextureSampler.sampleLevel(chain, 4.0f / size, 0.5f / size, 0);
            final int expected = TextureSampler.blend(
                chain.texel(0, 3, 0), chain.texel(0, 0, 0),
                chain.texel(0, 3, 0), chain.texel(0, 0, 0),
                WEIGHT_ONE / 2, 0);

            assertThat(sampled).isEqualTo(expected);
            // Clamp addressing would have returned column 3 untouched.
            assertThat(sampled).isNotEqualTo(chain.texel(0, 3, 0));
        }
    }

    @Nested
    @DisplayName("filter arithmetic")
    class Filtering
    {
        @Test
        @DisplayName("the packed blend equals the scalar reference for every weight pair")
        void shouldMatchReferenceAcrossAllWeights()
        {
            final int[][] quads = {
                {0x00000000, 0xFFFFFFFF, 0x7F7F7F7F, 0x80808080},
                {0xFF000000, 0x00FF0000, 0x0000FF00, 0x000000FF},
                {0x01020304, 0xFEFDFCFB, 0x11223344, 0xAABBCCDD},
                {0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF},
            };

            // MUTABLE local — counts disagreements so a failure reports how bad.
            int mismatches = 0;
            for (final int[] quad : quads)
            {
                for (int weightX = 0; weightX <= WEIGHT_ONE; weightX++)
                {
                    for (int weightY = 0; weightY <= WEIGHT_ONE; weightY++)
                    {
                        final int packed = TextureSampler.blend(
                            quad[0], quad[1], quad[2], quad[3], weightX, weightY);
                        final int scalar = referenceBlend(
                            quad[0], quad[1], quad[2], quad[3], weightX, weightY);
                        if (packed != scalar)
                        {
                            mismatches++;
                        }
                    }
                }
            }

            assertThat(mismatches).isZero();
        }

        @Test
        @DisplayName("the packed blend equals the scalar reference over pseudo-random texels")
        void shouldMatchReferenceOverRandomTexels()
        {
            final int[] texels = TextureFixtures.pseudoRandomTexels(64, 64, SEED);

            // MUTABLE local — counts disagreements.
            int mismatches = 0;
            for (int i = 0; i + 3 < texels.length; i += 4)
            {
                for (int weight = 0; weight <= WEIGHT_ONE; weight += 7)
                {
                    final int packed = TextureSampler.blend(
                        texels[i], texels[i + 1], texels[i + 2], texels[i + 3],
                        weight, WEIGHT_ONE - weight);
                    final int scalar = referenceBlend(
                        texels[i], texels[i + 1], texels[i + 2], texels[i + 3],
                        weight, WEIGHT_ONE - weight);
                    if (packed != scalar)
                    {
                        mismatches++;
                    }
                }
            }

            assertThat(mismatches).isZero();
        }

        @Test
        @DisplayName("the blend stays within one unit of the exact real-valued result")
        void shouldStayWithinOneUnitOfExactBilinear()
        {
            final int c00 = TextureFixtures.rgba(0, 255, 17, 200);
            final int c10 = TextureFixtures.rgba(255, 0, 240, 3);
            final int c01 = TextureFixtures.rgba(64, 128, 192, 255);
            final int c11 = TextureFixtures.rgba(200, 33, 7, 0);

            for (int weightX = 0; weightX <= WEIGHT_ONE; weightX += 3)
            {
                for (int weightY = 0; weightY <= WEIGHT_ONE; weightY += 3)
                {
                    final int packed = TextureSampler.blend(c00, c10, c01, c11,
                        weightX, weightY);
                    final double fx = weightX / (double) WEIGHT_ONE;
                    final double fy = weightY / (double) WEIGHT_ONE;
                    final double exact = exactChannel(
                        TextureSampler.red(c00), TextureSampler.red(c10),
                        TextureSampler.red(c01), TextureSampler.red(c11), fx, fy);

                    assertThat((double) TextureSampler.red(packed))
                        .describedAs("weights (%d, %d)", weightX, weightY)
                        .isCloseTo(exact, within(1.0));
                }
            }
        }

        @Test
        @DisplayName("a uniform footprint returns its colour untouched at every weight")
        void shouldNotDriftOnAUniformFootprint()
        {
            final int colour = TextureFixtures.rgba(37, 211, 5, 254);

            // MUTABLE local — counts weights that perturbed a flat colour.
            int drifted = 0;
            for (int weightX = 0; weightX <= WEIGHT_ONE; weightX++)
            {
                for (int weightY = 0; weightY <= WEIGHT_ONE; weightY++)
                {
                    if (TextureSampler.blend(colour, colour, colour, colour,
                        weightX, weightY) != colour)
                    {
                        drifted++;
                    }
                }
            }

            assertThat(drifted).isZero();
        }

        @Test
        @DisplayName("components do not bleed into one another")
        void shouldKeepComponentsIndependent()
        {
            final int redOnly = TextureFixtures.rgba(255, 0, 0, 0);
            final int alphaOnly = TextureFixtures.rgba(0, 0, 0, 255);

            final int blended = TextureSampler.blend(redOnly, 0, 0, alphaOnly,
                WEIGHT_ONE / 2, WEIGHT_ONE / 2);

            assertThat(TextureSampler.red(blended)).isEqualTo(64);
            assertThat(TextureSampler.green(blended)).isZero();
            assertThat(TextureSampler.blue(blended)).isZero();
            assertThat(TextureSampler.alpha(blended)).isEqualTo(64);
        }

        @Test
        @DisplayName("a saturated footprint never overflows past 255")
        void shouldNotOverflowOnSaturatedInput()
        {
            // MUTABLE local — counts weights producing anything but full white.
            int wrong = 0;
            for (int weightX = 0; weightX <= WEIGHT_ONE; weightX++)
            {
                for (int weightY = 0; weightY <= WEIGHT_ONE; weightY++)
                {
                    if (TextureSampler.blend(-1, -1, -1, -1, weightX, weightY) != -1)
                    {
                        wrong++;
                    }
                }
            }

            assertThat(wrong).isZero();
        }

        @Test
        @DisplayName("a weight of zero takes the near texel and 256 takes the far one")
        void shouldHonourWeightExtremes()
        {
            final int c00 = 0x11223344;
            final int c10 = 0x55667788;
            final int c01 = 0x99AABBCC;
            final int c11 = 0xDDEEFF00;

            assertThat(TextureSampler.blend(c00, c10, c01, c11, 0, 0)).isEqualTo(c00);
            assertThat(TextureSampler.blend(c00, c10, c01, c11, WEIGHT_ONE, 0)).isEqualTo(c10);
            assertThat(TextureSampler.blend(c00, c10, c01, c11, 0, WEIGHT_ONE)).isEqualTo(c01);
            assertThat(TextureSampler.blend(c00, c10, c01, c11, WEIGHT_ONE, WEIGHT_ONE))
                .isEqualTo(c11);
        }

        @Test
        @DisplayName("a full bilinear sample matches an independent float implementation")
        void shouldMatchFloatBilinearEndToEnd()
        {
            final int size = 8;
            final int[] texels = TextureFixtures.pseudoRandomTexels(size, size, SEED);
            final MipChain chain = TextureFixtures.single(size, size, texels);
            final int steps = 37;

            for (int i = 0; i < steps; i++)
            {
                for (int j = 0; j < steps; j++)
                {
                    final float u = i / (float) steps;
                    final float v = j / (float) steps;
                    final int sampled = TextureSampler.sampleLevel(chain, u, v, 0);
                    final double exact = exactSampleRed(chain, size, size, u, v);

                    // Tolerance 2: one unit from quantising both weights to
                    // eight fractional bits, one from the two rounding stages.
                    assertThat((double) TextureSampler.red(sampled))
                        .describedAs("uv (%f, %f)", u, v)
                        .isCloseTo(exact, within(2.0));
                }
            }
        }
    }

    @Nested
    @DisplayName("mip level selection")
    class LevelSelection
    {
        @Test
        @DisplayName("a footprint of one texel per pixel is magnification and selects level 0")
        void shouldSelectLevelZeroForUnitFootprint()
        {
            final MipChain chain = TextureFixtures.solid(8, 8, 4, 0);

            assertThat(TextureSampler.lodFromDerivatives(chain, 1.0f / 8.0f, 0.0f, 0.0f,
                1.0f / 8.0f)).isZero();
            assertThat(TextureSampler.lodFromDerivatives(chain, 0.0f, 0.0f, 0.0f, 0.0f))
                .isZero();
            assertThat(TextureSampler.lodFromDerivatives(chain, 0.001f, 0.0f, 0.0f, 0.001f))
                .isZero();
        }

        @Test
        @DisplayName("each doubling of the footprint adds one to the LOD")
        void shouldAddOneLodPerDoubling()
        {
            final MipChain chain = TextureFixtures.solid(64, 64, 7, 0);

            assertThat(TextureSampler.lodFromDerivatives(chain, 2.0f / 64.0f, 0.0f, 0.0f, 0.0f))
                .isCloseTo(1.0f, within(1.0e-5f));
            assertThat(TextureSampler.lodFromDerivatives(chain, 4.0f / 64.0f, 0.0f, 0.0f, 0.0f))
                .isCloseTo(2.0f, within(1.0e-5f));
            assertThat(TextureSampler.lodFromDerivatives(chain, 8.0f / 64.0f, 0.0f, 0.0f, 0.0f))
                .isCloseTo(3.0f, within(1.0e-5f));
        }

        @Test
        @DisplayName("the LOD is driven by the longer of the two screen axes")
        void shouldTakeTheLongerScreenAxis()
        {
            final MipChain chain = TextureFixtures.solid(64, 64, 7, 0);

            final float fromX = TextureSampler.lodFromDerivatives(chain,
                8.0f / 64.0f, 0.0f, 1.0f / 64.0f, 0.0f);
            final float fromY = TextureSampler.lodFromDerivatives(chain,
                1.0f / 64.0f, 0.0f, 0.0f, 8.0f / 64.0f);

            assertThat(fromX).isCloseTo(3.0f, within(1.0e-5f));
            assertThat(fromY).isCloseTo(3.0f, within(1.0e-5f));
        }

        @Test
        @DisplayName("a diagonal footprint uses its length, not one component")
        void shouldUseFootprintLength()
        {
            final MipChain chain = TextureFixtures.solid(64, 64, 7, 0);

            // (3, 4) texels per pixel step in screen x — length 5.
            final float lod = TextureSampler.lodFromDerivatives(chain,
                3.0f / 64.0f, 4.0f / 64.0f, 0.0f, 0.0f);

            assertThat(lod).isCloseTo((float) (Math.log(5.0) / Math.log(2.0)), within(1.0e-5f));
        }

        @Test
        @DisplayName("the LOD scales with the level-0 dimensions, not with normalised UV")
        void shouldScaleDerivativesByTextureSize()
        {
            final MipChain small = TextureFixtures.solid(8, 8, 4, 0);
            final MipChain large = TextureFixtures.solid(64, 64, 7, 0);
            final float derivative = 4.0f / 64.0f;

            assertThat(large.levelCount()).isEqualTo(7);
            assertThat(TextureSampler.lodFromDerivatives(large, derivative, 0.0f, 0.0f, 0.0f))
                .isCloseTo(2.0f, within(1.0e-5f));
            // The same UV derivative on an 8x8 texture is only half a texel.
            assertThat(TextureSampler.lodFromDerivatives(small, derivative, 0.0f, 0.0f, 0.0f))
                .isZero();
        }

        @Test
        @DisplayName("a LOD past the end of a partial chain clamps to the smallest level")
        void shouldClampLodToPartialChain()
        {
            final int[][] levels = {new int[64 * 64], new int[32 * 32], new int[16 * 16]};
            final MipChain partial = TextureFixtures.chain(64, 64, levels);

            assertThat(TextureSampler.lodFromDerivatives(partial, 1.0f, 0.0f, 0.0f, 0.0f))
                .isEqualTo(2.0f);
            assertThat(TextureSampler.levelForLod(partial, 99.0f)).isEqualTo(2);
        }

        @Test
        @DisplayName("degenerate derivatives resolve to level 0 rather than an exception")
        void shouldSurviveDegenerateDerivatives()
        {
            final MipChain chain = TextureFixtures.solid(8, 8, 4, 0);

            assertThat(TextureSampler.lodFromDerivatives(chain, Float.NaN, 0.0f, 0.0f, 0.0f))
                .isZero();
            assertThat(TextureSampler.lodFromDerivatives(chain,
                Float.POSITIVE_INFINITY, 0.0f, 0.0f, 0.0f)).isEqualTo(3.0f);
        }

        @Test
        @DisplayName("the sign of a derivative does not affect the LOD, only its magnitude")
        void shouldIgnoreDerivativeSign()
        {
            final MipChain chain = TextureFixtures.solid(64, 64, 7, 0);

            assertThat(TextureSampler.lodFromDerivatives(chain, -4.0f / 64.0f, 0.0f, 0.0f, 0.0f))
                .isEqualTo(TextureSampler.lodFromDerivatives(chain,
                    4.0f / 64.0f, 0.0f, 0.0f, 0.0f))
                .isCloseTo(2.0f, within(1.0e-5f));
        }

        @Test
        @DisplayName("derivatives feed straight through to the level a sample reads")
        void shouldDriveSampledLevelFromDerivatives()
        {
            final int[][] levels = {
                filled(8 * 8, TextureFixtures.rgba(255, 0, 0, 255)),
                filled(4 * 4, TextureFixtures.rgba(0, 255, 0, 255)),
                filled(2 * 2, TextureFixtures.rgba(0, 0, 255, 255)),
                filled(1, TextureFixtures.rgba(255, 255, 255, 255)),
            };
            final MipChain chain = TextureFixtures.chain(8, 8, levels);

            // One texel per pixel: magnification, level 0.
            final float near = TextureSampler.lodFromDerivatives(chain,
                1.0f / 8.0f, 0.0f, 0.0f, 1.0f / 8.0f);
            // Four texels per pixel: level 2.
            final float far = TextureSampler.lodFromDerivatives(chain,
                4.0f / 8.0f, 0.0f, 0.0f, 4.0f / 8.0f);

            assertThat(TextureSampler.sample(chain, 0.5f, 0.5f, near)).isEqualTo(levels[0][0]);
            assertThat(TextureSampler.sample(chain, 0.5f, 0.5f, far)).isEqualTo(levels[2][0]);
        }

        @Test
        @DisplayName("levelForLod rounds to nearest, with ties going up")
        void shouldRoundLodToNearestLevel()
        {
            final MipChain chain = TextureFixtures.solid(64, 64, 7, 0);

            assertThat(TextureSampler.levelForLod(chain, 0.0f)).isZero();
            assertThat(TextureSampler.levelForLod(chain, 0.49f)).isZero();
            assertThat(TextureSampler.levelForLod(chain, 0.5f)).isEqualTo(1);
            assertThat(TextureSampler.levelForLod(chain, 1.49f)).isEqualTo(1);
            assertThat(TextureSampler.levelForLod(chain, 1.5f)).isEqualTo(2);
        }

        @Test
        @DisplayName("levelForLod clamps a negative, NaN or infinite LOD")
        void shouldClampLevelForDegenerateLod()
        {
            final MipChain chain = TextureFixtures.solid(8, 8, 4, 0);

            assertThat(TextureSampler.levelForLod(chain, -3.0f)).isZero();
            assertThat(TextureSampler.levelForLod(chain, Float.NaN)).isZero();
            assertThat(TextureSampler.levelForLod(chain, Float.POSITIVE_INFINITY)).isEqualTo(3);
            assertThat(TextureSampler.levelForLod(chain, Float.NEGATIVE_INFINITY)).isZero();
        }

        @Test
        @DisplayName("a single-level chain always resolves to level 0")
        void shouldClampToTheOnlyLevel()
        {
            final MipChain chain = TextureFixtures.single(4, 4, new int[16]);

            assertThat(TextureSampler.levelForLod(chain, 7.5f)).isZero();
            assertThat(TextureSampler.lodFromDerivatives(chain, 1.0f, 1.0f, 1.0f, 1.0f))
                .isZero();
        }
    }

    @Nested
    @DisplayName("sampling through a chain")
    class ChainSampling
    {
        @Test
        @DisplayName("sample reads the level that levelForLod selects")
        void shouldSampleTheSelectedLevel()
        {
            final int[][] levels = {
                filled(8 * 8, TextureFixtures.rgba(255, 0, 0, 255)),
                filled(4 * 4, TextureFixtures.rgba(0, 255, 0, 255)),
                filled(2 * 2, TextureFixtures.rgba(0, 0, 255, 255)),
                filled(1, TextureFixtures.rgba(255, 255, 255, 255)),
            };
            final MipChain chain = TextureFixtures.chain(8, 8, levels);

            assertThat(TextureSampler.sample(chain, 0.3f, 0.7f, 0.0f))
                .isEqualTo(levels[0][0]);
            assertThat(TextureSampler.sample(chain, 0.3f, 0.7f, 1.0f))
                .isEqualTo(levels[1][0]);
            assertThat(TextureSampler.sample(chain, 0.3f, 0.7f, 2.4f))
                .isEqualTo(levels[2][0]);
            assertThat(TextureSampler.sample(chain, 0.3f, 0.7f, 50.0f))
                .isEqualTo(levels[3][0]);
        }

        @Test
        @DisplayName("the 1x1 top mip returns its colour for any coordinate")
        void shouldSampleTheTopMipEverywhere()
        {
            final MipChain chain = TextureFixtures.pyramid(8, 8,
                TextureFixtures.pseudoRandomTexels(8, 8, SEED));
            final int top = chain.levelCount() - 1;
            final int only = chain.texel(top, 0, 0);

            assertThat(chain.width(top)).isEqualTo(1);
            assertThat(TextureSampler.sampleLevel(chain, 0.0f, 0.0f, top)).isEqualTo(only);
            assertThat(TextureSampler.sampleLevel(chain, 0.87f, 0.13f, top)).isEqualTo(only);
            assertThat(TextureSampler.sample(chain, 0.87f, 0.13f, 100.0f)).isEqualTo(only);
        }

        @Test
        @DisplayName("a non-square texture indexes rows by its own width")
        void shouldSampleNonSquareTexture()
        {
            final MipChain chain = TextureFixtures.single(8, 2,
                TextureFixtures.coordinateTexels(8, 2));

            for (int y = 0; y < 2; y++)
            {
                for (int x = 0; x < 8; x++)
                {
                    final int sampled = TextureSampler.sampleLevel(chain,
                        (x + 0.5f) / 8.0f, (y + 0.5f) / 2.0f, 0);

                    assertThat(TextureSampler.red(sampled))
                        .describedAs("texel (%d, %d) x", x, y).isEqualTo(x);
                    assertThat(TextureSampler.green(sampled))
                        .describedAs("texel (%d, %d) y", x, y).isEqualTo(y);
                }
            }
        }

        @Test
        @DisplayName("a smaller level is addressed in its own texel space, not level 0's")
        void shouldUsePerLevelDimensions()
        {
            final int[][] levels = {
                TextureFixtures.coordinateTexels(4, 4),
                TextureFixtures.coordinateTexels(2, 2),
                TextureFixtures.coordinateTexels(1, 1),
            };
            final MipChain chain = TextureFixtures.chain(4, 4, levels);

            // Centre of texel (1, 1) of level 1 is u = v = 0.75.
            final int sampled = TextureSampler.sampleLevel(chain, 0.75f, 0.75f, 1);

            assertThat(TextureSampler.red(sampled)).isEqualTo(1);
            assertThat(TextureSampler.green(sampled)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("texel packing")
    class Packing
    {
        @Test
        @DisplayName("RGBA8888 puts red in the most significant byte and alpha in the least")
        void shouldPackRgba8888()
        {
            assertThat(TextureFixtures.rgba(0xAA, 0xBB, 0xCC, 0xDD)).isEqualTo(0xAABBCCDD);
        }

        @Test
        @DisplayName("each accessor reads only its own component")
        void shouldUnpackEachComponent()
        {
            final int texel = 0xAABBCCDD;

            assertThat(TextureSampler.red(texel)).isEqualTo(0xAA);
            assertThat(TextureSampler.green(texel)).isEqualTo(0xBB);
            assertThat(TextureSampler.blue(texel)).isEqualTo(0xCC);
            assertThat(TextureSampler.alpha(texel)).isEqualTo(0xDD);
        }

        @Test
        @DisplayName("unpacking never returns a negative component")
        void shouldUnpackAsUnsigned()
        {
            final int texel = 0xFFFFFFFF;

            assertThat(TextureSampler.red(texel)).isEqualTo(255);
            assertThat(TextureSampler.green(texel)).isEqualTo(255);
            assertThat(TextureSampler.blue(texel)).isEqualTo(255);
            assertThat(TextureSampler.alpha(texel)).isEqualTo(255);
        }

        @Test
        @DisplayName("pack and unpack round-trip every component")
        void shouldRoundTripPackAndUnpack()
        {
            // MUTABLE local — counts components that failed to survive the trip.
            int broken = 0;
            for (int value = 0; value <= 255; value++)
            {
                final int texel = TextureSampler.pack(value, 255 - value, value / 2, 255 - value / 2);
                if (TextureSampler.red(texel) != value
                    || TextureSampler.green(texel) != 255 - value
                    || TextureSampler.blue(texel) != value / 2
                    || TextureSampler.alpha(texel) != 255 - value / 2)
                {
                    broken++;
                }
            }

            assertThat(broken).isZero();
        }
    }

    // ---- test oracle ----

    // The obvious, scalar, one-channel-at-a-time bilinear blend. This is the
    // reference the packed implementation in TextureSampler must reproduce
    // exactly; it is deliberately written the slow, readable way.
    private static int referenceBlend(final int c00, final int c10, final int c01,
        final int c11, final int weightX, final int weightY)
    {
        final int red = referenceChannel(TextureSampler.red(c00), TextureSampler.red(c10),
            TextureSampler.red(c01), TextureSampler.red(c11), weightX, weightY);
        final int green = referenceChannel(TextureSampler.green(c00), TextureSampler.green(c10),
            TextureSampler.green(c01), TextureSampler.green(c11), weightX, weightY);
        final int blue = referenceChannel(TextureSampler.blue(c00), TextureSampler.blue(c10),
            TextureSampler.blue(c01), TextureSampler.blue(c11), weightX, weightY);
        final int alpha = referenceChannel(TextureSampler.alpha(c00), TextureSampler.alpha(c10),
            TextureSampler.alpha(c01), TextureSampler.alpha(c11), weightX, weightY);
        return TextureSampler.pack(red, green, blue, alpha);
    }

    // Two-stage lerp of one 8-bit channel with 0.8 fixed-point weights.
    private static int referenceChannel(final int a00, final int a10, final int a01,
        final int a11, final int weightX, final int weightY)
    {
        final int inverseX = WEIGHT_ONE - weightX;
        final int inverseY = WEIGHT_ONE - weightY;
        final int top = (a00 * inverseX + a10 * weightX + WEIGHT_ROUND) >> WEIGHT_SHIFT;
        final int bottom = (a01 * inverseX + a11 * weightX + WEIGHT_ROUND) >> WEIGHT_SHIFT;
        return (top * inverseY + bottom * weightY + WEIGHT_ROUND) >> WEIGHT_SHIFT;
    }

    // Exact real-valued bilinear interpolation of one channel.
    private static double exactChannel(final double a00, final double a10, final double a01,
        final double a11, final double fx, final double fy)
    {
        final double top = a00 * (1.0 - fx) + a10 * fx;
        final double bottom = a01 * (1.0 - fx) + a11 * fx;
        return top * (1.0 - fy) + bottom * fy;
    }

    // Exact real-valued bilinear sample of the red channel, computed
    // independently of the sampler: half-texel offset, repeat wrap, floats
    // throughout.
    private static double exactSampleRed(final MipChain chain, final int width,
        final int height, final float u, final float v)
    {
        final double x = u * width - 0.5;
        final double y = v * height - 0.5;
        final int x0 = (int) Math.floor(x);
        final int y0 = (int) Math.floor(y);
        final double fx = x - x0;
        final double fy = y - y0;
        return exactChannel(
            TextureSampler.red(chain.texel(0, Math.floorMod(x0, width),
                Math.floorMod(y0, height))),
            TextureSampler.red(chain.texel(0, Math.floorMod(x0 + 1, width),
                Math.floorMod(y0, height))),
            TextureSampler.red(chain.texel(0, Math.floorMod(x0, width),
                Math.floorMod(y0 + 1, height))),
            TextureSampler.red(chain.texel(0, Math.floorMod(x0 + 1, width),
                Math.floorMod(y0 + 1, height))),
            fx, fy);
    }

    // Builds a level of the given length filled with one colour.
    private static int[] filled(final int length, final int colour)
    {
        final int[] level = new int[length];
        for (int i = 0; i < length; i++)
        {
            level[i] = colour;
        }
        return level;
    }
}
