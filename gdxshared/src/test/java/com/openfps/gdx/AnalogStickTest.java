/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for the shape of a thumbstick.
 *
 * <p>Everything {@link AnalogStick} decides is a number a player feels and CI
 * would otherwise never see, which is exactly why the arithmetic was separated
 * from the two backends that call it. All of it runs headless and none of it
 * needs a controller.</p>
 *
 * <p><b>What is not covered here:</b> whether a real pad actually rests inside
 * {@link AnalogStick#DEAD_ZONE}, whether {@link AnalogStick#LOOK_EXPONENT}
 * feels right in the hand, and whether GLFW reports the axes in the order this
 * project believes. Those need hardware. What is pinned down is that the
 * arithmetic does what it claims for any input a device might produce.</p>
 */
class AnalogStickTest
{
    /** Float comparison tolerance. */
    private static final float EPSILON = 1.0e-5f;

    /** 1/sqrt(2) — each component of a unit diagonal. */
    private static final float DIAGONAL = (float) (1.0 / Math.sqrt(2.0));

    // The magnitude a shaped pair comes out at.
    private static float shapedMagnitude(final float rawX, final float rawY,
        final float exponent)
    {
        final float x = AnalogStick.shape(rawX, rawY, exponent);

        final float y = AnalogStick.shape(rawY, rawX, exponent);

        return (float) Math.sqrt(x * x + y * y);
    }

    @Nested
    @DisplayName("the dead zone")
    class DeadZone
    {
        @Test
        @DisplayName("a stick at rest produces EXACTLY zero, not merely something small")
        void shouldProduceExactlyZeroAtRest()
        {
            // The distinction is the whole point. A value that merely rounds to
            // nothing still fails InputState.isNeutral(), so an idle player
            // sends a non-neutral tic command every single tic and the camera
            // drifts forever. Asserted with isZero() rather than a tolerance
            // deliberately — a tolerance here would pass the bug.
            assertThat(AnalogStick.shape(0.0f, 0.0f, AnalogStick.MOVE_EXPONENT)).isZero();

            assertThat(AnalogStick.responseScale(0.0f, 0.0f, AnalogStick.LOOK_EXPONENT))
                .isZero();
        }

        @Test
        @DisplayName("resting noise from a worn pad is swallowed entirely")
        void shouldSwallowRestingNoise()
        {
            // What a real potentiometer reports with nobody touching it, and
            // what a pad with a permanent off-centre bias reports.
            final float[][] noise =
            {
                {0.01f, -0.02f},
                {0.11f, 0.09f},
                {-0.19f, 0.0f},
                {0.0f, 0.19f},
            };

            for (final float[] sample : noise)
            {
                assertThat(AnalogStick.shape(sample[0], sample[1], AnalogStick.MOVE_EXPONENT))
                    .as("resting noise %s must read as centred", java.util.Arrays.toString(sample))
                    .isZero();
            }
        }

        @Test
        @DisplayName("just past the boundary the stick starts moving, from zero")
        void shouldBeContinuousAtTheBoundary()
        {
            // Rescaled rather than clipped: output rises from 0 rather than
            // jumping to DEAD_ZONE the moment the boundary is crossed. A clipped
            // dead zone reads as a stick that is stiff and then runs away.
            final float justInside = AnalogStick.DEAD_ZONE - 0.001f;

            final float justOutside = AnalogStick.DEAD_ZONE + 0.001f;

            assertThat(AnalogStick.shape(justInside, 0.0f, AnalogStick.MOVE_EXPONENT)).isZero();

            assertThat(AnalogStick.shape(justOutside, 0.0f, AnalogStick.MOVE_EXPONENT))
                .isPositive()
                .isLessThan(0.01f);
        }

        @Test
        @DisplayName("full deflection still reaches exactly 1 — the dead zone costs resolution, not range")
        void shouldStillReachTheStops()
        {
            assertThat(AnalogStick.shape(1.0f, 0.0f, AnalogStick.MOVE_EXPONENT))
                .isCloseTo(1.0f, within(EPSILON));

            assertThat(AnalogStick.shape(-1.0f, 0.0f, AnalogStick.LOOK_EXPONENT))
                .isCloseTo(-1.0f, within(EPSILON));
        }
    }

    @Nested
    @DisplayName("the dead zone is radial, on the pair")
    class RadialDeadZone
    {
        @Test
        @DisplayName("a diagonal inside the circle is centred, even though each axis alone would not be")
        void shouldTreatADiagonalByItsMagnitude()
        {
            // THE test for the per-axis mistake. Each component here is 0.15,
            // comfortably inside a 0.20 per-axis threshold — but the pair's
            // magnitude is 0.212, which is OUTSIDE the circle. A per-axis
            // implementation reports centred; a radial one reports movement.
            final float component = 0.15f;

            final float magnitude = (float) Math.sqrt(2.0) * component;

            assertThat(magnitude).isGreaterThan(AnalogStick.DEAD_ZONE);

            assertThat(AnalogStick.shape(component, component, AnalogStick.MOVE_EXPONENT))
                .as("a pair past the circle is moving, whatever each axis reads alone")
                .isPositive();
        }

        @Test
        @DisplayName("a pair whose magnitude is inside the circle is centred, whatever the direction")
        void shouldIgnoreDirectionInsideTheCircle()
        {
            // Every direction at the same physical distance must agree. A square
            // dead zone lets a diagonal out while holding a cardinal in.
            final float radius = AnalogStick.DEAD_ZONE - 0.01f;

            for (int degrees = 0; degrees < 360; degrees += 15)
            {
                final double radians = Math.toRadians(degrees);

                final float x = (float) (radius * Math.cos(radians));

                final float y = (float) (radius * Math.sin(radians));

                assertThat(AnalogStick.responseScale(x, y, AnalogStick.MOVE_EXPONENT))
                    .as("%d degrees at radius %s", Integer.valueOf(degrees), Float.valueOf(radius))
                    .isZero();
            }
        }

        @Test
        @DisplayName("every direction at the same deflection gives the same speed")
        void shouldGiveTheSameSpeedInEveryDirection()
        {
            // The other half of "radial": not just where the dead zone ends but
            // that the curve is applied to the magnitude. A per-axis curve makes
            // a diagonal a different speed from a cardinal at the same physical
            // deflection, which the player feels as the stick being faster
            // forwards than diagonally.
            final float radius = 0.6f;

            final float cardinal = shapedMagnitude(radius, 0.0f, AnalogStick.LOOK_EXPONENT);

            for (int degrees = 0; degrees < 360; degrees += 15)
            {
                final double radians = Math.toRadians(degrees);

                final float x = (float) (radius * Math.cos(radians));

                final float y = (float) (radius * Math.sin(radians));

                assertThat(shapedMagnitude(x, y, AnalogStick.LOOK_EXPONENT))
                    .as("%d degrees must be as fast as straight ahead", Integer.valueOf(degrees))
                    .isCloseTo(cardinal, within(EPSILON));
            }
        }

        @Test
        @DisplayName("direction survives the shaping — only length changes")
        void shouldPreserveDirection()
        {
            // Both components are multiplied by one scalar, which is what makes
            // the operation radial by construction. So the ratio between them is
            // untouched.
            final float rawX = 0.8f;

            final float rawY = 0.3f;

            final float x = AnalogStick.shape(rawX, rawY, AnalogStick.LOOK_EXPONENT);

            final float y = AnalogStick.shape(rawY, rawX, AnalogStick.LOOK_EXPONENT);

            assertThat(y / x).isCloseTo(rawY / rawX, within(EPSILON));
        }

        @Test
        @DisplayName("a square-gated pad reporting sqrt(2) on a diagonal comes back onto the circle")
        void shouldClampAnOverRangeDiagonal()
        {
            // A stick with a square gate reports up to 1 on BOTH axes at once.
            // Squared, 1.41 would become 2 — a diagonal twice as fast as a
            // cardinal, the mirror image of the bug the curve exists to avoid.
            assertThat(shapedMagnitude(1.0f, 1.0f, AnalogStick.LOOK_EXPONENT))
                .isCloseTo(1.0f, within(EPSILON));

            assertThat(AnalogStick.shape(1.0f, 1.0f, AnalogStick.LOOK_EXPONENT))
                .isCloseTo(DIAGONAL, within(EPSILON));
        }
    }

    @Nested
    @DisplayName("the response curve")
    class ResponseCurve
    {
        @Test
        @DisplayName("half the usable travel turns at a quarter speed, squared exactly")
        void shouldSquareTheRescaledMagnitude()
        {
            // Half of the range that survives the dead zone, not half of the
            // raw axis: rescaling is what makes the curve's input 0..1.
            final float halfway = AnalogStick.DEAD_ZONE
                + 0.5f * (1.0f - AnalogStick.DEAD_ZONE);

            assertThat(AnalogStick.shape(halfway, 0.0f, AnalogStick.LOOK_EXPONENT))
                .as("0.5 in must give 0.25 out")
                .isCloseTo(0.25f, within(EPSILON));
        }

        @Test
        @DisplayName("a curved stick is always slower than a linear one, except at the stops")
        void shouldBeSlowerThanLinearInTheMiddle()
        {
            // The property that buys aim precision: everywhere between the dead
            // zone and the stop, the squared response is below the linear one.
            for (float raw = 0.25f; raw < 1.0f; raw += 0.05f)
            {
                final float linear = AnalogStick.shape(raw, 0.0f, AnalogStick.MOVE_EXPONENT);

                final float curved = AnalogStick.shape(raw, 0.0f, AnalogStick.LOOK_EXPONENT);

                assertThat(curved)
                    .as("at raw %s the curve must aim finer than linear", Float.valueOf(raw))
                    .isLessThan(linear);
            }
        }

        @Test
        @DisplayName("the curve is monotonic — pushing further never turns slower")
        void shouldBeMonotonic()
        {
            float previous = -1.0f;

            for (float raw = 0.0f; raw <= 1.0f; raw += 0.01f)
            {
                final float shaped = AnalogStick.shape(raw, 0.0f, AnalogStick.LOOK_EXPONENT);

                assertThat(shaped)
                    .as("at raw %s", Float.valueOf(raw))
                    .isGreaterThanOrEqualTo(previous);

                previous = shaped;
            }
        }

        @Test
        @DisplayName("movement is linear — there is no precision task at the bottom of that stick")
        void shouldLeaveMovementLinear()
        {
            assertThat(AnalogStick.MOVE_EXPONENT).isEqualTo(1.0f);

            final float halfway = AnalogStick.DEAD_ZONE
                + 0.5f * (1.0f - AnalogStick.DEAD_ZONE);

            assertThat(AnalogStick.shape(halfway, 0.0f, AnalogStick.MOVE_EXPONENT))
                .isCloseTo(0.5f, within(EPSILON));
        }

        @Test
        @DisplayName("the sign of the raw axis is never touched")
        void shouldPreserveSign()
        {
            assertThat(AnalogStick.shape(-0.7f, 0.0f, AnalogStick.LOOK_EXPONENT)).isNegative();

            assertThat(AnalogStick.shape(0.7f, 0.0f, AnalogStick.LOOK_EXPONENT)).isPositive();
        }
    }

    @Nested
    @DisplayName("a trigger read as a button")
    class Trigger
    {
        @Test
        @DisplayName("half travel is a press; anything less is not")
        void shouldPressAtHalfTravel()
        {
            assertThat(AnalogStick.isTriggerPulled(0.0f)).isFalse();

            assertThat(AnalogStick.isTriggerPulled(0.49f)).isFalse();

            assertThat(AnalogStick.isTriggerPulled(AnalogStick.TRIGGER_THRESHOLD)).isTrue();

            assertThat(AnalogStick.isTriggerPulled(1.0f)).isTrue();
        }

        @Test
        @DisplayName("a GLFW trigger resting at -1 normalises to released, not half-pulled")
        void shouldNormaliseACentredTrigger()
        {
            // GLFW treats a trigger as a joystick axis, so it rests at -1. Read
            // without normalising, a released trigger would be -1 — which is
            // below the threshold and would look correct — but a HALF-pulled one
            // reads 0.0 and would also look released, and a barely-touched one
            // would fire. One threshold, one normalisation, both platforms.
            assertThat(AnalogStick.triggerFromCentred(-1.0f)).isCloseTo(0.0f, within(EPSILON));

            assertThat(AnalogStick.triggerFromCentred(0.0f)).isCloseTo(0.5f, within(EPSILON));

            assertThat(AnalogStick.triggerFromCentred(1.0f)).isCloseTo(1.0f, within(EPSILON));

            assertThat(AnalogStick.isTriggerPulled(AnalogStick.triggerFromCentred(-1.0f)))
                .as("released")
                .isFalse();

            assertThat(AnalogStick.isTriggerPulled(AnalogStick.triggerFromCentred(0.0f)))
                .as("half pulled")
                .isTrue();
        }

        @Test
        @DisplayName("an out-of-range reading is folded back rather than trusted")
        void shouldClampAWildTriggerReading()
        {
            assertThat(AnalogStick.triggerFromCentred(-9.0f)).isCloseTo(0.0f, within(EPSILON));

            assertThat(AnalogStick.triggerFromCentred(9.0f)).isCloseTo(1.0f, within(EPSILON));
        }
    }

    @Nested
    @DisplayName("bad input")
    class BadInput
    {
        @Test
        @DisplayName("a NaN axis is refused rather than passed to the simulation")
        void shouldRejectNaN()
        {
            // A driver reporting NaN poisons a simulation permanently, and the
            // damage is unbounded and untraceable. InputState.of makes the same
            // choice for the same reason.
            assertThatThrownBy(() ->
                AnalogStick.responseScale(Float.NaN, 0.0f, AnalogStick.MOVE_EXPONENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rawX");

            assertThatThrownBy(() ->
                AnalogStick.responseScale(0.0f, Float.NaN, AnalogStick.MOVE_EXPONENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rawY");
        }

        @Test
        @DisplayName("an infinite axis is refused too")
        void shouldRejectInfinity()
        {
            assertThatThrownBy(() ->
                AnalogStick.responseScale(Float.POSITIVE_INFINITY, 0.0f, 1.0f))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an exponent below 1 is refused — it would amplify the middle of the range")
        void shouldRejectAnExponentBelowOne()
        {
            assertThatThrownBy(() -> AnalogStick.responseScale(0.5f, 0.0f, 0.5f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exponent");
        }
    }
}
