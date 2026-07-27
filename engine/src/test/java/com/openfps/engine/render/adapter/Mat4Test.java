/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import static com.openfps.engine.render.adapter.RenderTestEpsilon.EPSILON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Mat4}.
 */
@DisplayName("Mat4")
class Mat4Test
{
    // A matrix with 16 distinct elements, so a transposition or an index slip
    // cannot pass by coincidence.
    private static final float[] COUNTING = {
        1.0f, 2.0f, 3.0f, 4.0f,
        5.0f, 6.0f, 7.0f, 8.0f,
        9.0f, 10.0f, 11.0f, 12.0f,
        13.0f, 14.0f, 15.0f, 16.0f,
    };

    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        @DisplayName("stores elements row-major")
        void shouldStoreElementsRowMajorWhenBuiltFromAnArray()
        {
            final Mat4 m = Mat4.ofRowMajor(COUNTING);

            for (int row = 0; row < Mat4.ORDER; row++)
            {
                for (int column = 0; column < Mat4.ORDER; column++)
                {
                    assertThat(m.get(row, column)).isEqualTo(COUNTING[row * Mat4.ORDER + column]);
                }
            }
        }

        @Test
        @DisplayName("copies its source array so later mutation cannot reach in")
        void shouldCopySourceArrayWhenBuiltFromAnArray()
        {
            final float[] source = COUNTING.clone();
            final Mat4 m = Mat4.ofRowMajor(source);
            source[0] = -999.0f;

            assertThat(m.get(0, 0)).isEqualTo(1.0f);
        }

        @Test
        @DisplayName("rejects an array that is not 16 elements")
        void shouldRejectWhenElementCountIsWrong()
        {
            assertThatThrownBy(() -> Mat4.ofRowMajor(new float[15]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16");
        }

        @Test
        @DisplayName("builds the identity")
        void shouldBuildIdentityWhenAsked()
        {
            final Mat4 identity = Mat4.identity();

            for (int row = 0; row < Mat4.ORDER; row++)
            {
                for (int column = 0; column < Mat4.ORDER; column++)
                {
                    if (row == column)
                    {
                        assertThat(identity.get(row, column)).isEqualTo(1.0f);
                    }
                    else
                    {
                        assertThat(identity.get(row, column)).isEqualTo(0.0f);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("multiply")
    class Multiply
    {
        @Test
        @DisplayName("leaves a matrix unchanged when multiplied by the identity")
        void shouldLeaveMatrixUnchangedWhenMultipliedByIdentity()
        {
            final Mat4 m = Mat4.ofRowMajor(COUNTING);
            final Mat4 left = Mat4.identity().multiply(m);
            final Mat4 right = m.multiply(Mat4.identity());

            for (int i = 0; i < Mat4.ELEMENTS; i++)
            {
                final int row = i / Mat4.ORDER;
                final int column = i % Mat4.ORDER;
                assertThat(left.get(row, column)).isEqualTo(COUNTING[i]);
                assertThat(right.get(row, column)).isEqualTo(COUNTING[i]);
            }
        }

        @Test
        @DisplayName("computes a known row-times-column product")
        void shouldComputeKnownProductWhenSquaringTheCountingMatrix()
        {
            // (COUNTING x COUNTING)[0][0] = 1*1 + 2*5 + 3*9 + 4*13 = 90.
            // [1][2] = 5*3 + 6*7 + 7*11 + 8*15 = 15 + 42 + 77 + 120 = 254.
            // [3][3] = 13*4 + 14*8 + 15*12 + 16*16 = 52 + 112 + 180 + 256 = 600.
            final Mat4 square = Mat4.ofRowMajor(COUNTING).multiply(Mat4.ofRowMajor(COUNTING));

            assertThat(square.get(0, 0)).isEqualTo(90.0f);
            assertThat(square.get(1, 2)).isEqualTo(254.0f);
            assertThat(square.get(3, 3)).isEqualTo(600.0f);
        }

        @Test
        @DisplayName("composes right to left")
        void shouldComposeRightToLeftWhenTranslationsAreChained()
        {
            // (A * B) applied to p must equal A applied to (B applied to p).
            final Mat4 a = Mat4.translation(1.0f, 2.0f, 3.0f);
            final Mat4 b = Mat4.translation(10.0f, 20.0f, 30.0f);
            final float[] composed = new float[Mat4.ORDER];
            final float[] stepwise = new float[Mat4.ORDER];

            a.multiply(b).transformPoint(0.0f, 0.0f, 0.0f, composed, 0);
            b.transformPoint(0.0f, 0.0f, 0.0f, stepwise, 0);
            a.transformPoint(stepwise[0], stepwise[1], stepwise[2], stepwise, 0);

            assertThat(composed[0]).isCloseTo(stepwise[0], within(EPSILON));
            assertThat(composed[1]).isCloseTo(stepwise[1], within(EPSILON));
            assertThat(composed[2]).isCloseTo(stepwise[2], within(EPSILON));
            assertThat(composed[0]).isEqualTo(11.0f);
        }
    }

    @Nested
    @DisplayName("transformPoint")
    class TransformPoint
    {
        @Test
        @DisplayName("applies the translation column to an implicit w of 1")
        void shouldApplyTranslationWhenPointIsTransformed()
        {
            final float[] out = new float[Mat4.ORDER];
            Mat4.translation(5.0f, -6.0f, 7.0f).transformPoint(1.0f, 1.0f, 1.0f, out, 0);

            assertThat(out[0]).isEqualTo(6.0f);
            assertThat(out[1]).isEqualTo(-5.0f);
            assertThat(out[2]).isEqualTo(8.0f);
            assertThat(out[3]).isEqualTo(1.0f);
        }

        @Test
        @DisplayName("writes at the requested offset and nowhere else")
        void shouldWriteOnlyAtRequestedOffsetWhenGivenAnOffset()
        {
            final float[] out = new float[8];
            java.util.Arrays.fill(out, Float.NaN);
            Mat4.identity().transformPoint(1.0f, 2.0f, 3.0f, out, 2);

            assertThat(out[0]).isNaN();
            assertThat(out[1]).isNaN();
            assertThat(out[2]).isEqualTo(1.0f);
            assertThat(out[3]).isEqualTo(2.0f);
            assertThat(out[4]).isEqualTo(3.0f);
            assertThat(out[5]).isEqualTo(1.0f);
            assertThat(out[6]).isNaN();
            assertThat(out[7]).isNaN();
        }

        @Test
        @DisplayName("uses rows, not columns, for the dot products")
        void shouldUseRowsWhenTransformingAPoint()
        {
            // With COUNTING and p = (1, 0, 0, 1), row 0 gives 1*1 + 4 = 5.
            // A column-major reading would give 1*1 + 13 = 14 instead.
            final float[] out = new float[Mat4.ORDER];
            Mat4.ofRowMajor(COUNTING).transformPoint(1.0f, 0.0f, 0.0f, out, 0);

            assertThat(out[0]).isEqualTo(5.0f);
            assertThat(out[1]).isEqualTo(13.0f);
        }
    }

    @Nested
    @DisplayName("copyRowMajorInto")
    class CopyOut
    {
        @Test
        @DisplayName("writes 16 elements at the requested offset")
        void shouldWriteSixteenElementsWhenCopiedOut()
        {
            final float[] dest = new float[Mat4.ELEMENTS + 2];
            java.util.Arrays.fill(dest, Float.NaN);
            Mat4.ofRowMajor(COUNTING).copyRowMajorInto(dest, 1);

            assertThat(dest[0]).isNaN();
            assertThat(dest[Mat4.ELEMENTS + 1]).isNaN();
            for (int i = 0; i < Mat4.ELEMENTS; i++)
            {
                assertThat(dest[i + 1]).isEqualTo(COUNTING[i]);
            }
        }
    }
}
