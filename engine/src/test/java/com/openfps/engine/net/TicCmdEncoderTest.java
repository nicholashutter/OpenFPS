/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.openfps.engine.hal.port.InputState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TicCmdEncoder}.
 *
 * <p>Quantisation bugs are the subtle kind: they do not break a connection, they
 * make a remote player's aim drift a fraction of a degree per tic until two
 * peers disagree about whether a shot connected — and only in a networked game,
 * where reproducing it means running two processes. Every assertion below is
 * about a property that would otherwise only show up there.</p>
 */
@DisplayName("TicCmdEncoder")
class TicCmdEncoderTest
{
    /** One full turn, for the angle tests. */
    private static final float FULL_TURN = TicCmdEncoder.FULL_TURN_RADIANS;

    @Nested
    @DisplayName("movement axes")
    class Axes
    {
        @Test
        @DisplayName("centre encodes to zero and back")
        void shouldRoundTripTheCentre()
        {
            assertThat(TicCmdEncoder.encodeAxis(0.0f)).isZero();
            assertThat(TicCmdEncoder.decodeAxis(0)).isZero();
        }

        @Test
        @DisplayName("full deflection reaches the ends of the field, both ways")
        void shouldReachBothExtremes()
        {
            assertThat(TicCmdEncoder.encodeAxis(1.0f)).isEqualTo(TicCmd.MAX_AXIS);
            assertThat(TicCmdEncoder.encodeAxis(-1.0f)).isEqualTo(-TicCmd.MAX_AXIS);
        }

        @Test
        @DisplayName("clamps rather than wrapping when out of range")
        void shouldClampAnOutOfRangeAxis()
        {
            // Wrapping would turn a slightly-over-full push into full reverse,
            // which is the single worst way for this to be wrong.
            assertThat(TicCmdEncoder.encodeAxis(5.0f)).isEqualTo(TicCmd.MAX_AXIS);
            assertThat(TicCmdEncoder.encodeAxis(-5.0f)).isEqualTo(-TicCmd.MAX_AXIS);
        }

        @Test
        @DisplayName("round-trips to within one step across the range")
        void shouldRoundTripWithinOneStep()
        {
            final float step = 1.0f / TicCmdEncoder.AXIS_SCALE;
            for (float axis = -1.0f; axis <= 1.0f; axis += 0.01f)
            {
                final float back = TicCmdEncoder.decodeAxis(TicCmdEncoder.encodeAxis(axis));
                assertThat(back).as("axis %f", axis).isCloseTo(axis, within(step));
            }
        }

        @Test
        @DisplayName("rounds to nearest rather than toward zero")
        void shouldRoundToNearestNotTowardZero()
        {
            // Truncation biases every value toward zero, and a bias is not
            // noise: it accumulates in one direction on every tic, so a player
            // holding a steady turn would drift against their own input instead
            // of jittering around it.
            final float justOverHalfAStep = 1.6f / TicCmdEncoder.AXIS_SCALE;

            assertThat(TicCmdEncoder.encodeAxis(justOverHalfAStep)).isEqualTo(2);
            assertThat(TicCmdEncoder.encodeAxis(-justOverHalfAStep)).isEqualTo(-2);
        }

        @Test
        @DisplayName("treats NaN as centred rather than sending it")
        void shouldTreatNanAsCentred()
        {
            // A NaN on the wire decodes to a garbage displacement on every peer.
            assertThat(TicCmdEncoder.encodeAxis(Float.NaN)).isZero();
        }
    }

    @Nested
    @DisplayName("the yaw field")
    class Angle
    {
        @Test
        @DisplayName("covers the circle to better than a hundredth of a degree")
        void shouldResolveTheCircleFinely()
        {
            final float degreesPerStep = 360.0f / TicCmdEncoder.ANGLE_STEPS;

            // Far below what a hand can produce, which is the point: yaw is the
            // axis a shot is decided on.
            assertThat(degreesPerStep).isLessThan(0.01f);
        }

        @Test
        @DisplayName("wraps rather than clamping, because an angle is cyclic")
        void shouldWrapAnAngleBeyondOneTurn()
        {
            // Clamping would make a player who spun past the wrap point appear
            // to stop turning.
            final int once = TicCmdEncoder.encodeAngle(0.75f);
            final int again = TicCmdEncoder.encodeAngle(0.75f + FULL_TURN * 3.0f);

            assertThat(again).isEqualTo(once);
        }

        @Test
        @DisplayName("wraps a negative angle into the positive field")
        void shouldWrapANegativeAngle()
        {
            final int negative = TicCmdEncoder.encodeAngle(-0.5f);

            assertThat(negative).isBetween(0, TicCmd.MAX_ANGLE);
            assertThat(TicCmdEncoder.decodeAngle(negative))
                .isCloseTo(FULL_TURN - 0.5f, within(0.01f));
        }

        @Test
        @DisplayName("never produces a value the field cannot hold")
        void shouldStayInsideTheField()
        {
            // Rounding up from just under a full turn lands one past the field.
            // That value is the same heading as zero and must be reported as
            // zero, not as 65536 truncated to 0 by the wire encoder.
            for (float angle = -FULL_TURN * 2; angle <= FULL_TURN * 2; angle += 0.001f)
            {
                assertThat(TicCmdEncoder.encodeAngle(angle))
                    .as("angle %f", angle)
                    .isBetween(0, TicCmd.MAX_ANGLE);
            }
            assertThat(TicCmdEncoder.encodeAngle(FULL_TURN)).isZero();
        }

        @Test
        @DisplayName("round-trips to within one step")
        void shouldRoundTripAnAngle()
        {
            final float step = FULL_TURN / TicCmdEncoder.ANGLE_STEPS;
            for (float angle = 0.0f; angle < FULL_TURN; angle += 0.05f)
            {
                assertThat(TicCmdEncoder.decodeAngle(TicCmdEncoder.encodeAngle(angle)))
                    .as("angle %f", angle)
                    .isCloseTo(angle, within(step));
            }
        }

        @Test
        @DisplayName("survives an infinite angle rather than producing rubbish")
        void shouldHandleANonFiniteAngle()
        {
            assertThat(TicCmdEncoder.encodeAngle(Float.NaN)).isZero();
            assertThat(TicCmdEncoder.encodeAngle(Float.POSITIVE_INFINITY)).isZero();
        }
    }

    @Nested
    @DisplayName("the pitch field")
    class Pitch
    {
        @Test
        @DisplayName("spans exactly the band the controller can look through")
        void shouldSpanTheControllersPitchRange()
        {
            // A narrower band would clip a legal look; a wider one would spend
            // resolution on angles nothing can reach.
            assertThat(TicCmdEncoder.encodePitch(TicCmdEncoder.MAX_PITCH_RADIANS))
                .isEqualTo(TicCmd.MAX_PITCH);
            assertThat(TicCmdEncoder.encodePitch(-TicCmdEncoder.MAX_PITCH_RADIANS))
                .isEqualTo(-TicCmd.MAX_PITCH);
        }

        @Test
        @DisplayName("clamps beyond the band")
        void shouldClampAnExtremePitch()
        {
            assertThat(TicCmdEncoder.encodePitch(10.0f)).isEqualTo(TicCmd.MAX_PITCH);
            assertThat(TicCmdEncoder.encodePitch(-10.0f)).isEqualTo(-TicCmd.MAX_PITCH);
        }

        @Test
        @DisplayName("is symmetric — looking up and down are equally precise")
        void shouldQuantiseSymmetrically()
        {
            // MIN_PITCH (-128) is deliberately never produced. Using the full
            // two's-complement range would make the downward half one step finer
            // than the upward half — invisible in play, but it would break
            // decode(encode(x)) == -decode(encode(-x)), which is the property
            // that makes a mirrored replay of a match land in the same place.
            for (float pitch = 0.0f; pitch <= TicCmdEncoder.MAX_PITCH_RADIANS; pitch += 0.03f)
            {
                assertThat(TicCmdEncoder.encodePitch(-pitch))
                    .as("pitch %f is not the mirror of its negative", pitch)
                    .isEqualTo(-TicCmdEncoder.encodePitch(pitch));
            }
            assertThat(TicCmdEncoder.encodePitch(-100.0f))
                .as("the unused code must stay unused")
                .isNotEqualTo(TicCmd.MIN_PITCH);
        }

        @Test
        @DisplayName("resolves better than a degree, which a human-shaped target forgives")
        void shouldResolvePitchToUnderADegree()
        {
            final float degreesPerStep = 89.0f / TicCmd.MAX_PITCH;

            assertThat(degreesPerStep).isLessThan(1.0f);
        }

        @Test
        @DisplayName("round-trips to within one step")
        void shouldRoundTripPitch()
        {
            final float step = TicCmdEncoder.MAX_PITCH_RADIANS / TicCmd.MAX_PITCH;
            for (float pitch = -TicCmdEncoder.MAX_PITCH_RADIANS;
                pitch <= TicCmdEncoder.MAX_PITCH_RADIANS; pitch += 0.02f)
            {
                assertThat(TicCmdEncoder.decodePitch(TicCmdEncoder.encodePitch(pitch)))
                    .as("pitch %f", pitch)
                    .isCloseTo(pitch, within(step));
            }
        }
    }

    @Nested
    @DisplayName("the button field")
    class Buttons
    {
        @Test
        @DisplayName("each action has its own bit")
        void shouldGiveEachActionItsOwnBit()
        {
            // Overlapping bits would make one control silently trigger another,
            // and only on the remote end.
            assertThat(TicCmdEncoder.BUTTON_FIRE & TicCmdEncoder.BUTTON_JUMP).isZero();
            assertThat(TicCmdEncoder.BUTTON_FIRE & TicCmdEncoder.BUTTON_SPRINT).isZero();
            assertThat(TicCmdEncoder.BUTTON_JUMP & TicCmdEncoder.BUTTON_SPRINT).isZero();
        }

        @Test
        @DisplayName("packs and reads back every combination")
        void shouldRoundTripEveryCombination()
        {
            for (int mask = 0; mask < 8; mask++)
            {
                final boolean fire = (mask & 1) != 0;
                final boolean jump = (mask & 2) != 0;
                final boolean sprint = (mask & 4) != 0;
                final int packed = TicCmdEncoder.encodeButtons(
                    InputState.of(0.0f, 0.0f, 0.0f, 0.0f, fire, jump, sprint));

                assertThat(TicCmdEncoder.isDown(packed, TicCmdEncoder.BUTTON_FIRE))
                    .isEqualTo(fire);
                assertThat(TicCmdEncoder.isDown(packed, TicCmdEncoder.BUTTON_JUMP))
                    .isEqualTo(jump);
                assertThat(TicCmdEncoder.isDown(packed, TicCmdEncoder.BUTTON_SPRINT))
                    .isEqualTo(sprint);
            }
        }

        @Test
        @DisplayName("fits the eight bits the wire format allows")
        void shouldFitTheWireField()
        {
            final int all = TicCmdEncoder.encodeButtons(
                InputState.of(0.0f, 0.0f, 0.0f, 0.0f, true, true, true));

            assertThat(all).isBetween(0, TicCmd.ALL_BUTTONS);
        }

        @Test
        @DisplayName("a neutral snapshot packs to nothing held")
        void shouldPackNeutralAsZero()
        {
            assertThat(TicCmdEncoder.encodeButtons(InputState.NEUTRAL)).isZero();
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism
    {
        @Test
        @DisplayName("no java/lang/Math reference appears in the compiled class")
        void shouldNotReferenceMathWhenCompiled()
        {
            // The same flat guard the simulation classes carry. Math.round(float)
            // in particular is defined as floor(x + 0.5), which is NOT
            // round-to-nearest for the handful of values where that addition
            // itself rounds up — a documented quirk that would put two peers a
            // step apart on those inputs.
            assertThat(constantPoolOf(TicCmdEncoder.class))
                .as("TicCmdEncoder must not reference java.lang.Math anywhere")
                .doesNotContain("java/lang/Math");
        }

        @Test
        @DisplayName("the same input encodes identically every time")
        void shouldEncodeIdenticallyWhenRepeated()
        {
            for (float value = -1.0f; value <= 1.0f; value += 0.017f)
            {
                assertThat(TicCmdEncoder.encodeAxis(value))
                    .isEqualTo(TicCmdEncoder.encodeAxis(value));
                assertThat(TicCmdEncoder.encodeAngle(value))
                    .isEqualTo(TicCmdEncoder.encodeAngle(value));
            }
        }
    }

    // The class file bytes as Latin-1, so constant-pool entries survive as
    // literal characters and can be searched for.
    private static String constantPoolOf(final Class<?> type)
    {
        final String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource))
        {
            assertThat(in).as("class file for %s must be readable", type).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
        catch (final IOException e)
        {
            throw new IllegalStateException("could not read the class file for " + type, e);
        }
    }
}
