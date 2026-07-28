/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.port;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for the per-tic input snapshot: the axis invariants it enforces, the
 * sign conventions it fixes, and the immutability every consumer relies on.
 */
class InputStateTest
{
    /** Float comparison tolerance. Axis maths is a sqrt and a divide, nothing worse. */
    private static final float EPSILON = 1.0e-6f;

    /** 1/sqrt(2) — what each axis of a normalised diagonal must come to. */
    private static final float DIAGONAL = (float) (1.0 / Math.sqrt(2.0));

    private static InputState move(final float forward, final float strafe)
    {
        return InputState.of(forward, strafe, 0.0f, 0.0f, false, false, false);
    }

    @Nested
    @DisplayName("the neutral snapshot")
    class Neutral
    {
        @Test
        @DisplayName("is all zeroes and nothing pressed")
        void shouldBeEntirelyZero()
        {
            assertThat(InputState.NEUTRAL.forwardAxis()).isZero();
            assertThat(InputState.NEUTRAL.strafeAxis()).isZero();
            assertThat(InputState.NEUTRAL.yawDelta()).isZero();
            assertThat(InputState.NEUTRAL.pitchDelta()).isZero();
            assertThat(InputState.NEUTRAL.fire()).isFalse();
            assertThat(InputState.NEUTRAL.jump()).isFalse();
            assertThat(InputState.NEUTRAL.sprint()).isFalse();
            assertThat(InputState.NEUTRAL.isNeutral()).isTrue();
        }

        @Test
        @DisplayName("equals a snapshot built from zeroes, so it can be shared")
        void shouldEqualAFreshlyBuiltZeroSnapshot()
        {
            final InputState built =
                InputState.of(0.0f, 0.0f, 0.0f, 0.0f, false, false, false);
            assertThat(built).isEqualTo(InputState.NEUTRAL);
            assertThat(built.hashCode()).isEqualTo(InputState.NEUTRAL.hashCode());
        }

        @Test
        @DisplayName("any single non-zero component makes a snapshot non-neutral")
        void shouldNotCallAnythingElseNeutral()
        {
            assertThat(move(0.5f, 0.0f).isNeutral()).isFalse();
            assertThat(move(0.0f, -0.5f).isNeutral()).isFalse();
            assertThat(InputState.of(0.0f, 0.0f, 0.01f, 0.0f, false, false, false)
                .isNeutral()).isFalse();
            assertThat(InputState.of(0.0f, 0.0f, 0.0f, 0.01f, false, false, false)
                .isNeutral()).isFalse();
            assertThat(InputState.of(0.0f, 0.0f, 0.0f, 0.0f, true, false, false)
                .isNeutral()).isFalse();
            assertThat(InputState.of(0.0f, 0.0f, 0.0f, 0.0f, false, true, false)
                .isNeutral()).isFalse();
            assertThat(InputState.of(0.0f, 0.0f, 0.0f, 0.0f, false, false, true)
                .isNeutral()).isFalse();
        }
    }

    @Nested
    @DisplayName("movement axis normalisation")
    class AxisNormalisation
    {
        @Test
        @DisplayName("a single axis at full deflection is left alone")
        void shouldNotScaleASingleAxis()
        {
            assertThat(move(1.0f, 0.0f).forwardAxis()).isEqualTo(1.0f);
            assertThat(move(1.0f, 0.0f).strafeAxis()).isZero();
            assertThat(move(0.0f, -1.0f).strafeAxis()).isEqualTo(-1.0f);
        }

        @Test
        @DisplayName("a diagonal is scaled to unit length, so it is not faster")
        void shouldNormaliseTheDiagonal()
        {
            final InputState diagonal = move(1.0f, 1.0f);
            assertThat(diagonal.forwardAxis()).isCloseTo(DIAGONAL, within(EPSILON));
            assertThat(diagonal.strafeAxis()).isCloseTo(DIAGONAL, within(EPSILON));
            assertThat(magnitude(diagonal)).isCloseTo(1.0f, within(EPSILON));
        }

        @Test
        @DisplayName("every diagonal quadrant keeps its signs and its length")
        void shouldNormaliseEveryQuadrant()
        {
            assertThat(magnitude(move(1.0f, -1.0f))).isCloseTo(1.0f, within(EPSILON));
            assertThat(magnitude(move(-1.0f, 1.0f))).isCloseTo(1.0f, within(EPSILON));
            assertThat(magnitude(move(-1.0f, -1.0f))).isCloseTo(1.0f, within(EPSILON));

            assertThat(move(-1.0f, 1.0f).forwardAxis()).isCloseTo(-DIAGONAL, within(EPSILON));
            assertThat(move(-1.0f, 1.0f).strafeAxis()).isCloseTo(DIAGONAL, within(EPSILON));
        }

        @Test
        @DisplayName("a diagonal is exactly as fast as a straight line")
        void shouldMakeDiagonalSpeedEqualStraightSpeed()
        {
            assertThat(magnitude(move(1.0f, 1.0f)))
                .isCloseTo(magnitude(move(1.0f, 0.0f)), within(EPSILON));
        }

        @Test
        @DisplayName("a partial diagonal inside the unit circle is untouched")
        void shouldLeaveShortVectorsAlone()
        {
            final InputState partial = move(0.5f, 0.5f);
            assertThat(partial.forwardAxis()).isEqualTo(0.5f);
            assertThat(partial.strafeAxis()).isEqualTo(0.5f);
        }

        @Test
        @DisplayName("an out-of-range axis is clamped before anything else")
        void shouldClampOutOfRangeAxes()
        {
            assertThat(move(4.0f, 0.0f).forwardAxis()).isEqualTo(1.0f);
            assertThat(move(-4.0f, 0.0f).forwardAxis()).isEqualTo(-1.0f);
            assertThat(move(0.0f, 9.0f).strafeAxis()).isEqualTo(1.0f);
            assertThat(magnitude(move(7.0f, 7.0f))).isCloseTo(1.0f, within(EPSILON));
        }

        @Test
        @DisplayName("NaN and infinity are rejected rather than fed to the simulation")
        void shouldRejectNonFiniteValues()
        {
            assertThatThrownBy(() -> move(Float.NaN, 0.0f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forwardAxis");
            assertThatThrownBy(() -> move(0.0f, Float.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strafeAxis");
            assertThatThrownBy(
                () -> InputState.of(0.0f, 0.0f, Float.NaN, 0.0f, false, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yawDelta");
            assertThatThrownBy(
                () -> InputState.of(0.0f, 0.0f, 0.0f, Float.NaN, false, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pitchDelta");
        }

        private float magnitude(final InputState state)
        {
            final float forward = state.forwardAxis();
            final float strafe = state.strafeAxis();
            return (float) Math.sqrt(forward * forward + strafe * strafe);
        }
    }

    @Nested
    @DisplayName("look deltas")
    class LookDeltas
    {
        @Test
        @DisplayName("are carried through untouched — a fast flick is a big angle")
        void shouldNotClampRotation()
        {
            final InputState fast =
                InputState.of(0.0f, 0.0f, 12.5f, -3.25f, false, false, false);
            assertThat(fast.yawDelta()).isEqualTo(12.5f);
            assertThat(fast.pitchDelta()).isEqualTo(-3.25f);
        }

        @Test
        @DisplayName("positive yaw turns right and positive pitch tilts up")
        void shouldFixTheSignConvention()
        {
            final InputState rightAndUp =
                InputState.of(0.0f, 0.0f, 0.5f, 0.5f, false, false, false);
            assertThat(rightAndUp.yawDelta()).isPositive();
            assertThat(rightAndUp.pitchDelta()).isPositive();
        }

        @Test
        @DisplayName("withoutLook keeps the levels and drops the integral")
        void shouldStripRotationOnly()
        {
            final InputState sampled =
                InputState.of(1.0f, 0.0f, 0.4f, -0.2f, true, false, true);
            final InputState stripped = sampled.withoutLook();

            assertThat(stripped.yawDelta()).isZero();
            assertThat(stripped.pitchDelta()).isZero();
            assertThat(stripped.forwardAxis()).isEqualTo(sampled.forwardAxis());
            assertThat(stripped.fire()).isTrue();
            assertThat(stripped.sprint()).isTrue();
            // The original is untouched — that is the whole point of a copy.
            assertThat(sampled.yawDelta()).isEqualTo(0.4f);
        }
    }

    @Nested
    @DisplayName("immutability")
    class Immutability
    {
        @Test
        @DisplayName("every instance field is private and final")
        void shouldHaveOnlyFinalPrivateInstanceFields()
        {
            for (final Field field : InputState.class.getDeclaredFields())
            {
                if (Modifier.isStatic(field.getModifiers()))
                {
                    continue;
                }
                assertThat(Modifier.isFinal(field.getModifiers()))
                    .as("%s must be final", field.getName())
                    .isTrue();
                assertThat(Modifier.isPrivate(field.getModifiers()))
                    .as("%s must be private", field.getName())
                    .isTrue();
            }
        }

        @Test
        @DisplayName("every field is a primitive, so nothing is shared by reference")
        void shouldHoldOnlyPrimitives()
        {
            for (final Field field : InputState.class.getDeclaredFields())
            {
                if (Modifier.isStatic(field.getModifiers()))
                {
                    continue;
                }
                assertThat(field.getType().isPrimitive())
                    .as("%s must be a primitive — no boxing", field.getName())
                    .isTrue();
            }
        }

        @Test
        @DisplayName("exposes no mutator")
        void shouldExposeNoSetters()
        {
            assertThat(InputState.class.getMethods())
                .filteredOn(method -> method.getName().startsWith("set"))
                .isEmpty();
        }

        @Test
        @DisplayName("the class itself is final, so no subclass can add state")
        void shouldBeFinal()
        {
            assertThat(Modifier.isFinal(InputState.class.getModifiers())).isTrue();
        }

        @Test
        @DisplayName("repeated reads return the same values")
        void shouldReadStable()
        {
            final InputState state =
                InputState.of(0.25f, -0.5f, 1.5f, -0.75f, true, true, false);
            for (int repeat = 0; repeat < 3; repeat++)
            {
                assertThat(state.forwardAxis()).isEqualTo(0.25f);
                assertThat(state.strafeAxis()).isEqualTo(-0.5f);
                assertThat(state.yawDelta()).isEqualTo(1.5f);
                assertThat(state.pitchDelta()).isEqualTo(-0.75f);
                assertThat(state.fire()).isTrue();
                assertThat(state.jump()).isTrue();
                assertThat(state.sprint()).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("value semantics")
    class ValueSemantics
    {
        @Test
        @DisplayName("two snapshots with the same components are equal")
        void shouldCompareByValue()
        {
            final InputState first =
                InputState.of(0.5f, 0.25f, 0.1f, 0.2f, true, false, true);
            final InputState second =
                InputState.of(0.5f, 0.25f, 0.1f, 0.2f, true, false, true);
            assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        }

        @Test
        @DisplayName("a differing flag or axis breaks equality")
        void shouldDistinguishDifferentComponents()
        {
            final InputState base =
                InputState.of(0.5f, 0.25f, 0.1f, 0.2f, true, false, true);
            assertThat(base).isNotEqualTo(
                InputState.of(0.5f, 0.25f, 0.1f, 0.2f, true, true, true));
            assertThat(base).isNotEqualTo(
                InputState.of(0.5f, 0.25f, 0.1f, 0.3f, true, false, true));
            assertThat(base).isNotEqualTo("not a snapshot");
        }

        @Test
        @DisplayName("toString names every component")
        void shouldDescribeItself()
        {
            assertThat(InputState.NEUTRAL.toString())
                .contains("forward", "strafe", "yaw", "pitch", "fire", "jump", "sprint");
        }
    }
}
