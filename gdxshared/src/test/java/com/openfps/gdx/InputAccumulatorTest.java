/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import java.util.concurrent.atomic.AtomicInteger;

import com.openfps.engine.hal.port.InputState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for the libGDX-free half of desktop input.
 *
 * This is where the interesting behaviour actually lives — the reason
 * {@link GdxInputPort} was split in two. Everything here runs headless: no
 * window, no GL context, no display. What is <b>not</b> covered, and cannot be
 * without a human at a keyboard, is the other half: that {@code Gdx.input}
 * really reports the deltas we think it does, that cursor capture behaves, and
 * that the resulting camera feels right. See {@code GdxInputPortTest}.
 */
class InputAccumulatorTest
{
    /** A sensitivity of exactly 1 rad/px, so radians and pixels are the same number. */
    private static final float UNIT_SENSITIVITY = 1.0f;

    /** Float comparison tolerance. */
    private static final float EPSILON = 1.0e-5f;

    /** 1/sqrt(2) — each axis of a normalised diagonal. */
    private static final float DIAGONAL = (float) (1.0 / Math.sqrt(2.0));

    private static InputAccumulator unitAccumulator()
    {
        return new InputAccumulator(UNIT_SENSITIVITY);
    }

    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        @DisplayName("the default sensitivity is positive and in radians per pixel")
        void shouldDefaultToTheNamedSensitivity()
        {
            final InputAccumulator accumulator = new InputAccumulator();
            assertThat(accumulator.radiansPerPixel())
                .isEqualTo(InputAccumulator.DEFAULT_RADIANS_PER_PIXEL)
                .isPositive();
        }

        @Test
        @DisplayName("a full turn at the default sensitivity is a plausible amount of desk")
        void shouldNeedASaneNumberOfPixelsForAFullTurn()
        {
            final double pixelsPerTurn =
                2.0 * Math.PI / InputAccumulator.DEFAULT_RADIANS_PER_PIXEL;
            // Anything under ~1000 px is uncontrollably twitchy and anything
            // over ~10000 px means dragging the mouse off the desk twice.
            assertThat(pixelsPerTurn).isBetween(1000.0, 10000.0);
        }

        @Test
        @DisplayName("a zero, negative or non-finite sensitivity is rejected")
        void shouldRejectNonsenseSensitivity()
        {
            assertThatThrownBy(() -> new InputAccumulator(0.0f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sensitivity");
            assertThatThrownBy(() -> new InputAccumulator(-0.01f))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new InputAccumulator(Float.NaN))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new InputAccumulator(Float.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a fresh accumulator latches the neutral snapshot")
        void shouldStartAtRest()
        {
            assertThat(unitAccumulator().latch()).isEqualTo(InputState.NEUTRAL);
        }
    }

    @Nested
    @DisplayName("look accumulation and drain")
    class LookAccumulation
    {
        @Test
        @DisplayName("one poll's pixels become radians at the configured sensitivity")
        void shouldConvertPixelsToRadians()
        {
            final InputAccumulator accumulator = new InputAccumulator(0.01f);
            accumulator.accumulateLook(10, 0);
            assertThat(accumulator.latch().yawDelta()).isCloseTo(0.1f, within(EPSILON));
        }

        @Test
        @DisplayName("several polls between two latches are summed, not overwritten")
        void shouldSumPollsBetweenLatches()
        {
            final InputAccumulator accumulator = unitAccumulator();
            accumulator.accumulateLook(3, 0);
            accumulator.accumulateLook(4, 0);
            accumulator.accumulateLook(5, 0);
            assertThat(accumulator.pendingYawPixels()).isEqualTo(12);
            assertThat(accumulator.latch().yawDelta()).isCloseTo(12.0f, within(EPSILON));
        }

        @Test
        @DisplayName("latching drains, so the same motion is never delivered twice")
        void shouldDrainOnLatch()
        {
            final InputAccumulator accumulator = unitAccumulator();
            accumulator.accumulateLook(20, -8);

            final InputState first = accumulator.latch();
            assertThat(first.yawDelta()).isCloseTo(20.0f, within(EPSILON));
            assertThat(first.pitchDelta()).isCloseTo(8.0f, within(EPSILON));

            final InputState second = accumulator.latch();
            assertThat(second.yawDelta()).isZero();
            assertThat(second.pitchDelta()).isZero();
            assertThat(accumulator.pendingYawPixels()).isZero();
            assertThat(accumulator.pendingPitchPixels()).isZero();
        }

        @Test
        @DisplayName("a tic that falls between two frames reports no rotation, not stale rotation")
        void shouldReportZeroWhenNothingWasPolled()
        {
            final InputAccumulator accumulator = unitAccumulator();
            accumulator.accumulateLook(9, 0);
            accumulator.latch();
            assertThat(accumulator.latch().yawDelta()).isZero();
            assertThat(accumulator.latch().yawDelta()).isZero();
        }

        @Test
        @DisplayName("total rotation is the same whichever loop is running faster")
        void shouldBeIndependentOfTheTwoLoopRates()
        {
            // Render faster than the simulation: six frames feed one tic.
            final InputAccumulator renderFast = unitAccumulator();
            for (int frame = 0; frame < 6; frame++)
            {
                renderFast.accumulateLook(2, 0);
            }
            final float fastTotal = renderFast.latch().yawDelta();

            // Simulation faster than render: one frame, then three tics.
            final InputAccumulator simFast = unitAccumulator();
            simFast.accumulateLook(12, 0);
            float simTotal = 0.0f;
            for (int tic = 0; tic < 3; tic++)
            {
                simTotal += simFast.latch().yawDelta();
            }

            assertThat(fastTotal).isCloseTo(12.0f, within(EPSILON));
            assertThat(simTotal).isCloseTo(fastTotal, within(EPSILON));
        }

        @Test
        @DisplayName("resetLook throws away pending motion, as a cursor capture must")
        void shouldDiscardPendingLookOnReset()
        {
            final InputAccumulator accumulator = unitAccumulator();
            accumulator.accumulateLook(400, 300);
            accumulator.resetLook();
            assertThat(accumulator.pendingYawPixels()).isZero();
            assertThat(accumulator.latch().yawDelta()).isZero();
        }

        @Test
        @DisplayName("an implausible single-poll delta is clamped, not believed")
        void shouldClampAnImplausiblePoll()
        {
            final InputAccumulator accumulator = unitAccumulator();
            accumulator.accumulateLook(100000, -100000);
            assertThat(accumulator.pendingYawPixels())
                .isEqualTo(InputAccumulator.MAX_PIXELS_PER_POLL);
            assertThat(accumulator.pendingPitchPixels())
                .isEqualTo(-InputAccumulator.MAX_PIXELS_PER_POLL);
        }

        @Test
        @DisplayName("an un-drained accumulator saturates rather than overflowing to the wrong sign")
        void shouldSaturateRatherThanOverflow()
        {
            final InputAccumulator accumulator = unitAccumulator();
            final int pollsToSaturate =
                InputAccumulator.MAX_ACCUMULATED_PIXELS / InputAccumulator.MAX_PIXELS_PER_POLL;
            for (int poll = 0; poll < pollsToSaturate + 16; poll++)
            {
                accumulator.accumulateLook(InputAccumulator.MAX_PIXELS_PER_POLL, 0);
            }
            assertThat(accumulator.pendingYawPixels())
                .isEqualTo(InputAccumulator.MAX_ACCUMULATED_PIXELS);
        }
    }

    @Nested
    @DisplayName("look sign conventions")
    class LookSigns
    {
        @Test
        @DisplayName("moving the mouse right yields a positive yaw")
        void shouldTurnRightOnPositiveDeltaX()
        {
            final InputAccumulator accumulator = unitAccumulator();
            accumulator.accumulateLook(15, 0);
            assertThat(accumulator.latch().yawDelta()).isPositive();
        }

        @Test
        @DisplayName("moving the mouse left yields a negative yaw")
        void shouldTurnLeftOnNegativeDeltaX()
        {
            final InputAccumulator accumulator = unitAccumulator();
            accumulator.accumulateLook(-15, 0);
            assertThat(accumulator.latch().yawDelta()).isNegative();
        }

        @Test
        @DisplayName("a caller's negative deltaY tilts the view up")
        void shouldInvertPitchAgainstScreenOrientation()
        {
            final InputAccumulator accumulator = unitAccumulator();
            assertThat(accumulator.isInvertPitch())
                .as("the conventional scheme is the default")
                .isFalse();
            // The contract is "+y downward". A caller whose device reports the
            // other way round owes this class a negated delta — see
            // GdxInputPort.pollLook.
            accumulator.accumulateLook(0, -30);
            assertThat(accumulator.latch().pitchDelta())
                .as("a negative delta must look up")
                .isCloseTo(30.0f, within(EPSILON));
        }

        @Test
        @DisplayName("a caller's positive deltaY tilts the view down")
        void shouldLookDownOnPositiveDeltaY()
        {
            final InputAccumulator accumulator = unitAccumulator();
            accumulator.accumulateLook(0, 30);
            assertThat(accumulator.latch().pitchDelta())
                .isCloseTo(-30.0f, within(EPSILON));
        }

        @Test
        @DisplayName("the invert setting negates pitch and leaves yaw untouched")
        void shouldNegateOnlyPitchWhenInverted()
        {
            final InputAccumulator accumulator = unitAccumulator();
            accumulator.setInvertPitch(true);
            assertThat(accumulator.isInvertPitch()).isTrue();

            accumulator.accumulateLook(12, -30);
            final InputState inverted = accumulator.latch();
            assertThat(inverted.pitchDelta())
                .as("inverting flips the vertical axis")
                .isCloseTo(-30.0f, within(EPSILON));
            assertThat(inverted.yawDelta())
                .as("and does nothing at all to the horizontal one")
                .isCloseTo(12.0f, within(EPSILON));
        }

        @Test
        @DisplayName("the invert setting is exactly a sign flip, both ways")
        void shouldBeASignFlipBothWays()
        {
            final InputAccumulator plain = unitAccumulator();
            plain.accumulateLook(0, 42);
            final float upright = plain.latch().pitchDelta();

            final InputAccumulator flipped = unitAccumulator();
            flipped.setInvertPitch(true);
            flipped.accumulateLook(0, 42);
            assertThat(flipped.latch().pitchDelta())
                .isCloseTo(-upright, within(EPSILON));
        }

        @Test
        @DisplayName("yaw and pitch do not leak into each other")
        void shouldKeepTheAxesIndependent()
        {
            final InputAccumulator accumulator = unitAccumulator();
            accumulator.accumulateLook(7, 0);
            final InputState yawOnly = accumulator.latch();
            assertThat(yawOnly.pitchDelta()).isZero();

            accumulator.accumulateLook(0, 7);
            final InputState pitchOnly = accumulator.latch();
            assertThat(pitchOnly.yawDelta()).isZero();
        }
    }

    @Nested
    @DisplayName("movement axes")
    class MovementAxes
    {
        @Test
        @DisplayName("W and S drive the forward axis in opposite directions")
        void shouldMapForwardAndBack()
        {
            final InputAccumulator accumulator = unitAccumulator();
            accumulator.setMovementKeys(true, false, false, false);
            assertThat(accumulator.latch().forwardAxis()).isEqualTo(1.0f);

            accumulator.setMovementKeys(false, true, false, false);
            assertThat(accumulator.latch().forwardAxis()).isEqualTo(-1.0f);
        }

        @Test
        @DisplayName("A and D drive the strafe axis, positive to the right")
        void shouldMapStrafe()
        {
            final InputAccumulator accumulator = unitAccumulator();
            accumulator.setMovementKeys(false, false, false, true);
            assertThat(accumulator.latch().strafeAxis()).isEqualTo(1.0f);

            accumulator.setMovementKeys(false, false, true, false);
            assertThat(accumulator.latch().strafeAxis()).isEqualTo(-1.0f);
        }

        @Test
        @DisplayName("opposing keys cancel instead of fighting")
        void shouldCancelOpposingKeys()
        {
            final InputAccumulator accumulator = unitAccumulator();
            accumulator.setMovementKeys(true, true, true, true);
            final InputState state = accumulator.latch();
            assertThat(state.forwardAxis()).isZero();
            assertThat(state.strafeAxis()).isZero();
        }

        @Test
        @DisplayName("a diagonal is normalised, so W+D is not faster than W")
        void shouldNormaliseTheDiagonal()
        {
            final InputAccumulator accumulator = unitAccumulator();
            accumulator.setMovementKeys(true, false, false, true);
            final InputState diagonal = accumulator.latch();
            assertThat(diagonal.forwardAxis()).isCloseTo(DIAGONAL, within(EPSILON));
            assertThat(diagonal.strafeAxis()).isCloseTo(DIAGONAL, within(EPSILON));

            final double speed = Math.hypot(diagonal.forwardAxis(), diagonal.strafeAxis());
            assertThat(speed).isCloseTo(1.0, within(1.0e-5));
        }

        @Test
        @DisplayName("a held key survives a tic with no intervening poll")
        void shouldTreatMovementAsALevel()
        {
            final InputAccumulator accumulator = unitAccumulator();
            accumulator.setMovementKeys(true, false, false, false);
            assertThat(accumulator.latch().forwardAxis()).isEqualTo(1.0f);
            // No further poll — the key is still down in reality.
            assertThat(accumulator.latch().forwardAxis()).isEqualTo(1.0f);
        }
    }

    @Nested
    @DisplayName("action flags")
    class ActionFlags
    {
        @Test
        @DisplayName("a held action is reported on every tic")
        void shouldReportHeldActions()
        {
            final InputAccumulator accumulator = unitAccumulator();
            accumulator.setActionKeys(true, true, true);
            for (int tic = 0; tic < 3; tic++)
            {
                final InputState state = accumulator.latch();
                assertThat(state.fire()).isTrue();
                assertThat(state.jump()).isTrue();
                assertThat(state.sprint()).isTrue();
            }
        }

        @Test
        @DisplayName("a click shorter than one tic is still delivered — exactly once")
        void shouldNotDropAShortPress()
        {
            final InputAccumulator accumulator = unitAccumulator();
            // Two frames between one tic and the next: pressed, then released.
            accumulator.setActionKeys(true, false, false);
            accumulator.setActionKeys(false, false, false);

            assertThat(accumulator.latch().fire()).isTrue();
            assertThat(accumulator.latch().fire()).isFalse();
        }

        @Test
        @DisplayName("releasing a held action clears it on the following tic")
        void shouldClearReleasedActions()
        {
            final InputAccumulator accumulator = unitAccumulator();
            accumulator.setActionKeys(true, true, true);
            assertThat(accumulator.latch().fire()).isTrue();

            accumulator.setActionKeys(false, false, false);
            final InputState released = accumulator.latch();
            assertThat(released.fire()).isFalse();
            assertThat(released.jump()).isFalse();
            assertThat(released.sprint()).isFalse();
        }

        @Test
        @DisplayName("the three actions are independent")
        void shouldKeepActionsIndependent()
        {
            final InputAccumulator accumulator = unitAccumulator();
            accumulator.setActionKeys(false, true, false);
            final InputState state = accumulator.latch();
            assertThat(state.fire()).isFalse();
            assertThat(state.jump()).isTrue();
            assertThat(state.sprint()).isFalse();
        }
    }

    @Nested
    @DisplayName("clearAll")
    class ClearAll
    {
        @Test
        @DisplayName("returns everything to rest, so a released cursor cannot walk the player")
        void shouldReturnToRest()
        {
            final InputAccumulator accumulator = unitAccumulator();
            accumulator.accumulateLook(50, 50);
            accumulator.setMovementKeys(true, false, false, true);
            accumulator.setActionKeys(true, true, true);

            accumulator.clearAll();

            assertThat(accumulator.pendingYawPixels()).isZero();
            assertThat(accumulator.pendingPitchPixels()).isZero();
            assertThat(accumulator.latch()).isEqualTo(InputState.NEUTRAL);
        }

        @Test
        @DisplayName("also clears the sticky action bits, not just the levels")
        void shouldClearStickyActions()
        {
            final InputAccumulator accumulator = unitAccumulator();
            accumulator.setActionKeys(true, true, true);
            accumulator.setActionKeys(false, false, false);
            accumulator.clearAll();
            assertThat(accumulator.latch().fire()).isFalse();
        }
    }

    @Nested
    @DisplayName("cross-thread handoff")
    class CrossThread
    {
        /** Pixels the producer reports, chosen to stay exact in a float sum. */
        private static final int POLL_COUNT = 20000;

        @Test
        @DisplayName("no pixel is lost or double-counted when poll and latch race")
        void shouldConserveEveryPixelAcrossThreads() throws InterruptedException
        {
            // Sensitivity 1 rad/px and unit deltas, so the latched radians are
            // integers and the sum is exact in a float. The assertion is
            // deterministic even though the interleaving is not: whatever the
            // schedule, the two sides must agree to the pixel.
            final InputAccumulator accumulator = unitAccumulator();
            final AtomicInteger collected = new AtomicInteger();

            final Thread producer = new Thread(() -> pollRepeatedly(accumulator), "poll");
            final Thread consumer = new Thread(() -> latchUntil(accumulator, collected), "latch");

            producer.start();
            consumer.start();
            producer.join();
            consumer.join();

            // Anything the consumer stopped short of is still pending.
            collected.addAndGet(Math.round(accumulator.latch().yawDelta()));
            assertThat(collected.get()).isEqualTo(POLL_COUNT);
        }

        // Producer side: one pixel of rightward motion per simulated frame.
        private void pollRepeatedly(final InputAccumulator accumulator)
        {
            for (int poll = 0; poll < POLL_COUNT; poll++)
            {
                accumulator.accumulateLook(1, 0);
            }
        }

        // Consumer side: drains at its own pace, adding up what it sees.
        private void latchUntil(final InputAccumulator accumulator, final AtomicInteger total)
        {
            for (int tic = 0; tic < POLL_COUNT; tic++)
            {
                total.addAndGet(Math.round(accumulator.latch().yawDelta()));
            }
        }
    }
}
