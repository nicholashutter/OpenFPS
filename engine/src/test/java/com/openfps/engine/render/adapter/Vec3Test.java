/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Vec3}.
 */
@DisplayName("Vec3")
class Vec3Test
{
    @Nested
    @DisplayName("arithmetic")
    class Arithmetic
    {
        @Test
        @DisplayName("adds and subtracts component-wise")
        void shouldAddAndSubtractComponentWiseWhenGivenTwoVectors()
        {
            final Vec3 a = new Vec3(1.0f, 2.0f, 3.0f);

            final Vec3 b = new Vec3(10.0f, 20.0f, 30.0f);

            assertThat(a.add(b).x()).isEqualTo(11.0f);

            assertThat(a.add(b).y()).isEqualTo(22.0f);

            assertThat(a.add(b).z()).isEqualTo(33.0f);

            assertThat(b.subtract(a).x()).isEqualTo(9.0f);

            assertThat(b.subtract(a).y()).isEqualTo(18.0f);

            assertThat(b.subtract(a).z()).isEqualTo(27.0f);
        }

        @Test
        @DisplayName("scales every component")
        void shouldScaleEveryComponentWhenGivenAFactor()
        {
            final Vec3 scaled = new Vec3(1.0f, -2.0f, 4.0f).scale(2.5f);

            assertThat(scaled.x()).isEqualTo(2.5f);

            assertThat(scaled.y()).isEqualTo(-5.0f);

            assertThat(scaled.z()).isEqualTo(10.0f);
        }

        @Test
        @DisplayName("dots to zero for perpendicular vectors")
        void shouldDotToZeroWhenVectorsArePerpendicular()
        {
            final Vec3 a = new Vec3(1.0f, 0.0f, 0.0f);

            final Vec3 b = new Vec3(0.0f, 1.0f, 0.0f);

            assertThat(a.dot(b)).isEqualTo(0.0f);

            assertThat(a.dot(a)).isEqualTo(1.0f);

            assertThat(new Vec3(1.0f, 2.0f, 3.0f).dot(new Vec3(4.0f, 5.0f, 6.0f)))
                .isEqualTo(32.0f);
        }

        @Test
        @DisplayName("leaves a vector unchanged when added to zero")
        void shouldLeaveVectorUnchangedWhenAddedToZero()
        {
            final Vec3 a = new Vec3(-3.5f, 0.25f, 17.0f);

            final Vec3 sum = a.add(Vec3.ZERO);

            assertThat(sum.x()).isEqualTo(a.x());

            assertThat(sum.y()).isEqualTo(a.y());

            assertThat(sum.z()).isEqualTo(a.z());
        }
    }

    @Nested
    @DisplayName("cross product")
    class Cross
    {
        @Test
        @DisplayName("produces the third axis from the first two")
        void shouldProduceThirdAxisWhenCrossingUnitAxes()
        {
            final Vec3 x = new Vec3(1.0f, 0.0f, 0.0f);

            final Vec3 y = new Vec3(0.0f, 1.0f, 0.0f);

            final Vec3 z = x.cross(y);

            assertThat(z.x()).isEqualTo(0.0f);

            assertThat(z.y()).isEqualTo(0.0f);

            assertThat(z.z()).isEqualTo(1.0f);
        }

        @Test
        @DisplayName("anticommutes")
        void shouldAnticommuteWhenOperandsAreSwapped()
        {
            final Vec3 a = new Vec3(1.0f, 2.0f, 3.0f);

            final Vec3 b = new Vec3(-4.0f, 5.0f, 6.0f);

            final Vec3 ab = a.cross(b);

            final Vec3 ba = b.cross(a);

            assertThat(ab.x()).isEqualTo(-ba.x());

            assertThat(ab.y()).isEqualTo(-ba.y());

            assertThat(ab.z()).isEqualTo(-ba.z());
        }

        @Test
        @DisplayName("is perpendicular to both operands")
        void shouldBePerpendicularToBothOperandsWhenCrossed()
        {
            final Vec3 a = new Vec3(1.0f, 2.0f, 3.0f);

            final Vec3 b = new Vec3(-4.0f, 5.0f, 6.0f);

            final Vec3 n = a.cross(b);

            assertThat(n.dot(a)).isCloseTo(0.0f, within(RenderTestEpsilon.EPSILON));

            assertThat(n.dot(b)).isCloseTo(0.0f, within(RenderTestEpsilon.EPSILON));
        }

        @Test
        @DisplayName("is zero for parallel vectors")
        void shouldBeZeroWhenOperandsAreParallel()
        {
            final Vec3 a = new Vec3(0.0f, 1.0f, 0.0f);

            final Vec3 n = a.cross(a.scale(3.0f));

            assertThat(n.length()).isEqualTo(0.0f);
        }
    }

    @Nested
    @DisplayName("length and normalization")
    class Length
    {
        @Test
        @DisplayName("computes a 3-4-5 length exactly")
        void shouldComputeLengthWhenComponentsFormAPythagoreanTriple()
        {
            assertThat(new Vec3(3.0f, 4.0f, 0.0f).length()).isEqualTo(5.0f);

            assertThat(Vec3.ZERO.length()).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("normalizes to unit length while preserving direction")
        void shouldNormalizeToUnitLengthWhenVectorIsNonZero()
        {
            final Vec3 a = new Vec3(0.0f, 0.0f, -7.0f);

            final Vec3 unit = a.normalized();

            assertThat(unit.length()).isCloseTo(1.0f, within(RenderTestEpsilon.EPSILON));

            assertThat(unit.z()).isCloseTo(-1.0f, within(RenderTestEpsilon.EPSILON));
        }

        @Test
        @DisplayName("rejects normalizing the zero vector")
        void shouldRejectNormalizationWhenVectorIsZero()
        {
            assertThatThrownBy(Vec3.ZERO::normalized)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero-length");
        }
    }
}
