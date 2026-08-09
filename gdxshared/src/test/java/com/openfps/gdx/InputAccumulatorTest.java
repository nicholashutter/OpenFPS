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

    @Nested
    @DisplayName("stick look is a RATE, and that is what makes it frame-rate independent")
    class StickLookIsARate
    {
        /** 60 Hz, the engine's default tic rate. */
        private static final float TIC_60 = 1.0f / 60.0f;

        /** One second of play, in tics, at 60 Hz. */
        private static final int TICS_PER_SECOND_AT_60 = 60;

        // Runs one second of wall-clock play at 60 tics/sec, polling the stick
        // `pollsPerTic` times per tic, and returns the total yaw turned.
        //
        // This models the real threading exactly: the platform polls on the
        // render thread at whatever rate vsync gives it, the game loop latches
        // once per tic at a fixed rate, and the two are unrelated. Only the poll
        // rate differs between the two runs below.
        private float yawOverOneSecond(final int pollsPerTic, final float deflection)
        {
            final InputAccumulator accumulator = unitAccumulator();

            accumulator.setTicDuration(TIC_60);

            float total = 0.0f;

            for (int tic = 0; tic < TICS_PER_SECOND_AT_60; tic++)
            {
                for (int poll = 0; poll < pollsPerTic; poll++)
                {
                    // A held stick reports the same POSITION every poll. This is
                    // the line that would be a summing accumulate() in the bug.
                    accumulator.setGamepadLookAxes(deflection, 0.0f);
                }

                total = total + accumulator.latch().yawDelta();
            }

            return total;
        }

        @Test
        @DisplayName("THE headline: the same deflection for the same duration turns the same amount at two poll rates")
        void shouldTurnTheSameAmountAtAnyPollRate()
        {
            // The bug this exists to make impossible: feeding a held stick into
            // the pixel accumulator, which SUMS. A 144 Hz machine polls roughly
            // twice as often as a 72 Hz one, so it would bank twice as many
            // contributions per tic and the player would spin twice as fast for
            // the same thumb — same game, same settings, different monitor.
            //
            // Two runs of one second of play, identical except for how often the
            // stick is read: 144 polls/sec against 72 (so at 60 tics/sec, 2 and
            // 1 polls per tic... expressed as whole polls per tic below).
            final float fast = yawOverOneSecond(4, 1.0f);

            final float slow = yawOverOneSecond(1, 1.0f);

            assertThat(fast)
                .as("four polls per tic must turn exactly as far as one")
                .isCloseTo(slow, within(EPSILON));

            // And the absolute figure is the documented rate times the duration,
            // not an accident of either poll count. One second at the stop.
            assertThat(slow)
                .as("one second at full deflection is one second's worth of rate")
                .isCloseTo(InputAccumulator.GAMEPAD_LOOK_RADIANS_PER_SECOND, within(1.0e-3f));
        }

        @Test
        @DisplayName("holding the stick twice as long turns twice as far")
        void shouldIntegrateOverDuration()
        {
            // The other half of "it is a rate": the angle is proportional to
            // TIME, which is the property a summed-per-poll implementation loses.
            final InputAccumulator accumulator = unitAccumulator();

            accumulator.setTicDuration(TIC_60);

            accumulator.setGamepadLookAxes(1.0f, 0.0f);

            final float oneTic = accumulator.latch().yawDelta();

            final float twoTics = accumulator.latch().yawDelta()
                + accumulator.latch().yawDelta();

            assertThat(twoTics).isCloseTo(2.0f * oneTic, within(EPSILON));
        }

        @Test
        @DisplayName("a 30 Hz tic turns twice as far per tic as a 60 Hz one, and the same per second")
        void shouldScaleWithTheTicDuration()
        {
            // A longer tic covers more simulated time, so it integrates more
            // rotation. Per SECOND the two agree, which is the whole point of
            // the units — radians per second times seconds.
            final InputAccumulator slow = unitAccumulator();

            slow.setTicDuration(1.0f / 30.0f);

            slow.setGamepadLookAxes(1.0f, 0.0f);

            final InputAccumulator fast = unitAccumulator();

            fast.setTicDuration(TIC_60);

            fast.setGamepadLookAxes(1.0f, 0.0f);

            final float perTicAt30 = slow.latch().yawDelta();

            final float perTicAt60 = fast.latch().yawDelta();

            assertThat(perTicAt30).isCloseTo(2.0f * perTicAt60, within(EPSILON));

            // 30 tics at 30 Hz and 60 tics at 60 Hz are both one second.
            assertThat(30.0f * perTicAt30)
                .isCloseTo(60.0f * perTicAt60, within(EPSILON));
        }

        @Test
        @DisplayName("a look stick is NOT drained — a held stick keeps turning without being re-polled")
        void shouldNotConsumeTheDeflection()
        {
            // The difference from a mouse, stated as a test. A mouse delta is an
            // integral and latch() takes it away; a stick deflection is a level
            // and describes reality until the device says otherwise. If the game
            // loop outruns the render thread, a held stick must still be held.
            final InputAccumulator accumulator = unitAccumulator();

            accumulator.setTicDuration(TIC_60);

            accumulator.setGamepadLookAxes(1.0f, 0.0f);

            final float first = accumulator.latch().yawDelta();

            final float second = accumulator.latch().yawDelta();

            final float third = accumulator.latch().yawDelta();

            assertThat(first).isPositive();

            assertThat(second).isCloseTo(first, within(EPSILON));

            assertThat(third).isCloseTo(first, within(EPSILON));

            assertThat(accumulator.gamepadYawAxis()).isCloseTo(1.0f, within(EPSILON));
        }

        @Test
        @DisplayName("a mouse delta IS drained, in the same accumulator, in the same tic")
        void shouldStillDrainTheMouse()
        {
            // Proof the two kinds of quantity coexist rather than one having
            // been converted into the other.
            final InputAccumulator accumulator = unitAccumulator();

            accumulator.setTicDuration(TIC_60);

            accumulator.accumulateLook(10, 0);

            accumulator.setGamepadLookAxes(1.0f, 0.0f);

            final float stickOnly = InputAccumulator.GAMEPAD_LOOK_RADIANS_PER_SECOND * TIC_60;

            assertThat(accumulator.latch().yawDelta())
                .as("mouse pixels plus one tic of stick")
                .isCloseTo(10.0f + stickOnly, within(1.0e-4f));

            assertThat(accumulator.latch().yawDelta())
                .as("the pixels are gone; the stick is not")
                .isCloseTo(stickOnly, within(1.0e-4f));
        }

        @Test
        @DisplayName("a resting stick turns the view by exactly nothing, forever")
        void shouldNotDriftAtRest()
        {
            final InputAccumulator accumulator = unitAccumulator();

            accumulator.setTicDuration(TIC_60);

            // Resting noise from a real pad, re-reported every poll.
            for (int tic = 0; tic < 600; tic++)
            {
                accumulator.setGamepadLookAxes(0.04f, -0.03f);

                final InputState snapshot = accumulator.latch();

                assertThat(snapshot.yawDelta()).isZero();

                assertThat(snapshot.pitchDelta()).isZero();
            }
        }

        @Test
        @DisplayName("the vertical stick obeys the mouse's sign convention, so one invert flag serves both")
        void shouldShareThePitchConvention()
        {
            final InputAccumulator accumulator = unitAccumulator();

            accumulator.setTicDuration(TIC_60);

            // Positive is DOWNWARD on the way in, exactly as for mouse pixels.
            // A stick pushed away from the player reports negative Y on GLFW and
            // on Android alike, so a backend passes it through untouched.
            accumulator.setGamepadLookAxes(0.0f, -1.0f);

            assertThat(accumulator.latch().pitchDelta())
                .as("stick pushed away aims up")
                .isPositive();

            accumulator.setGamepadLookAxes(0.0f, 1.0f);

            assertThat(accumulator.latch().pitchDelta())
                .as("stick pulled back aims down")
                .isNegative();
        }

        @Test
        @DisplayName("the invert preference flips the stick and the mouse together")
        void shouldInvertBothDevices()
        {
            final InputAccumulator accumulator = unitAccumulator();

            accumulator.setTicDuration(TIC_60);

            accumulator.setInvertPitch(true);

            accumulator.setGamepadLookAxes(0.0f, -1.0f);

            assertThat(accumulator.latch().pitchDelta())
                .as("inverted: stick pushed away now aims down")
                .isNegative();

            accumulator.accumulateLook(0, -30);

            assertThat(accumulator.latch().pitchDelta())
                .as("inverted: and so does the mouse")
                .isNegative();
        }

        @Test
        @DisplayName("a tic duration of zero or worse is refused")
        void shouldRejectABadTicDuration()
        {
            final InputAccumulator accumulator = unitAccumulator();

            assertThat(accumulator.ticDurationSeconds())
                .isEqualTo(InputAccumulator.DEFAULT_TIC_SECONDS);

            assertThatThrownBy(() -> accumulator.setTicDuration(0.0f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tic duration");

            assertThatThrownBy(() -> accumulator.setTicDuration(-1.0f))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> accumulator.setTicDuration(Float.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("a gamepad is a second channel, not a second mode")
    class GamepadChannel
    {
        @Test
        @DisplayName("stick and keys sum rather than overwriting each other")
        void shouldSumBothMovementChannels()
        {
            // The requirement stated as a test: a pad is an ADDITIONAL path. If
            // one channel overwrote the other, whichever was polled second would
            // win and the game would effectively be in a mode.
            final InputAccumulator accumulator = unitAccumulator();

            accumulator.setMovementKeys(false, false, true, false);

            accumulator.setGamepadMovementAxes(1.0f, 0.0f);

            final InputState snapshot = accumulator.latch();

            assertThat(snapshot.forwardAxis()).as("from the stick").isPositive();

            assertThat(snapshot.strafeAxis()).as("from the keyboard").isNegative();
        }

        @Test
        @DisplayName("opposing devices cancel, exactly as opposing keys do")
        void shouldCancelOpposingDevices()
        {
            final InputAccumulator accumulator = unitAccumulator();

            accumulator.setMovementKeys(false, true, false, false);

            accumulator.setGamepadMovementAxes(1.0f, 0.0f);

            assertThat(accumulator.latch().forwardAxis()).isCloseTo(0.0f, within(EPSILON));
        }

        @Test
        @DisplayName("both channels at once stay inside the unit disc")
        void shouldClampTheCombinedMagnitude()
        {
            // Two devices pushing the same way is 2.0 before InputState.of, and
            // the disc clamp is that class's job — not repeated here, and still
            // enforced.
            final InputAccumulator accumulator = unitAccumulator();

            accumulator.setMovementKeys(true, false, false, true);

            accumulator.setGamepadMovementAxes(1.0f, 1.0f);

            final InputState snapshot = accumulator.latch();

            final float magnitude = (float) Math.hypot(snapshot.forwardAxis(),
                snapshot.strafeAxis());

            assertThat(magnitude).isLessThanOrEqualTo(1.0f + EPSILON);
        }

        @Test
        @DisplayName("a pad button fires without the keyboard's help, and vice versa")
        void shouldOrTheActionChannels()
        {
            final InputAccumulator withPad = unitAccumulator();

            withPad.setGamepadActions(true, false, false);

            assertThat(withPad.latch().fire()).isTrue();

            final InputAccumulator withKeys = unitAccumulator();

            withKeys.setActionKeys(false, true, false);

            assertThat(withKeys.latch().jump()).isTrue();
        }

        @Test
        @DisplayName("releasing the pad's trigger does not release the keyboard's")
        void shouldNotLetOneDeviceReleaseTheOther()
        {
            final InputAccumulator accumulator = unitAccumulator();

            accumulator.setActionKeys(true, false, false);

            // The pad is polled afterwards and reports nothing pressed. A shared
            // field would clear the key that is still physically held.
            accumulator.setGamepadActions(false, false, false);

            assertThat(accumulator.latch().fire())
                .as("the key is still down")
                .isTrue();
        }

        @Test
        @DisplayName("the movement stick is dead-zoned on the way in, radially")
        void shouldShapeTheMovementStick()
        {
            // Shaped inside the setter rather than by each backend, for the
            // reason InputState.of clamps the disc: a third backend cannot
            // forget what it never had to remember.
            final InputAccumulator accumulator = unitAccumulator();

            accumulator.setGamepadMovementAxes(0.1f, -0.1f);

            assertThat(accumulator.gamepadForwardAxis()).isZero();

            assertThat(accumulator.gamepadStrafeAxis()).isZero();

            assertThat(accumulator.latch()).isEqualTo(InputState.NEUTRAL);
        }

        @Test
        @DisplayName("the look stick is curved on the way in")
        void shouldShapeTheLookStick()
        {
            final InputAccumulator accumulator = unitAccumulator();

            final float halfway = AnalogStick.DEAD_ZONE
                + 0.5f * (1.0f - AnalogStick.DEAD_ZONE);

            accumulator.setGamepadLookAxes(halfway, 0.0f);

            assertThat(accumulator.gamepadYawAxis()).isCloseTo(0.25f, within(EPSILON));
        }
    }

    @Nested
    @DisplayName("hot-plug: a controller that vanishes must not walk the player into a wall")
    class HotPlug
    {
        @Test
        @DisplayName("clearGamepad drops a stick left at full deflection")
        void shouldDropAStaleAxis()
        {
            // The failure this exists for. An axis is a LEVEL: it persists by
            // design, and nothing overwrites it once the device that was writing
            // it has gone. A pad unplugged at full deflection — or a wireless
            // one whose battery dies — would otherwise walk the player forward
            // for the rest of the match with nobody touching anything.
            //
            // Precedent: clearAll() exists because a key held at the moment of
            // focus loss did exactly this.
            final InputAccumulator accumulator = unitAccumulator();

            accumulator.setTicDuration(1.0f / 60.0f);

            accumulator.setGamepadMovementAxes(1.0f, 1.0f);

            accumulator.setGamepadLookAxes(1.0f, 1.0f);

            accumulator.setGamepadActions(true, true, true);

            assertThat(accumulator.latch().isNeutral()).isFalse();

            accumulator.clearGamepad();

            assertThat(accumulator.gamepadForwardAxis()).isZero();

            assertThat(accumulator.gamepadStrafeAxis()).isZero();

            assertThat(accumulator.gamepadYawAxis()).isZero();

            assertThat(accumulator.gamepadPitchAxis()).isZero();

            // The sticky action flags are drained by the latch above, so the
            // very next snapshot is fully at rest.
            assertThat(accumulator.latch()).isEqualTo(InputState.NEUTRAL);
        }

        @Test
        @DisplayName("a disconnected pad does not turn the camera on any later tic")
        void shouldStopTurningForever()
        {
            final InputAccumulator accumulator = unitAccumulator();

            accumulator.setTicDuration(1.0f / 60.0f);

            accumulator.setGamepadLookAxes(1.0f, 0.0f);

            accumulator.latch();

            accumulator.clearGamepad();

            for (int tic = 0; tic < 240; tic++)
            {
                assertThat(accumulator.latch().yawDelta())
                    .as("tic %d after the pad went away", Integer.valueOf(tic))
                    .isZero();
            }
        }

        @Test
        @DisplayName("clearGamepad leaves the keyboard alone — the player still has a hand on it")
        void shouldNotClearTheKeyboardChannel()
        {
            // Partial on purpose. A player who unplugs a pad mid-match is
            // usually still holding a key, and clearing that would drop an input
            // they are making right now.
            final InputAccumulator accumulator = unitAccumulator();

            accumulator.setMovementKeys(true, false, false, false);

            accumulator.accumulateLook(25, 0);

            accumulator.setGamepadMovementAxes(0.0f, 1.0f);

            accumulator.clearGamepad();

            final InputState snapshot = accumulator.latch();

            assertThat(snapshot.forwardAxis()).as("W is still held").isEqualTo(1.0f);

            assertThat(snapshot.strafeAxis()).as("the stick is gone").isZero();

            assertThat(snapshot.yawDelta()).as("mouse pixels survive").isPositive();
        }

        @Test
        @DisplayName("clearAll takes the gamepad channel with it")
        void shouldClearTheGamepadOnClearAll()
        {
            // Focus loss is not a player asking to move, whatever device they
            // are holding.
            final InputAccumulator accumulator = unitAccumulator();

            accumulator.setGamepadMovementAxes(1.0f, 1.0f);

            accumulator.setGamepadLookAxes(1.0f, 1.0f);

            accumulator.setGamepadActions(true, true, true);

            accumulator.clearAll();

            assertThat(accumulator.latch()).isEqualTo(InputState.NEUTRAL);
        }

        @Test
        @DisplayName("reconnecting simply starts writing again — there is no state to reset")
        void shouldRecoverOnReconnect()
        {
            final InputAccumulator accumulator = unitAccumulator();

            accumulator.setGamepadMovementAxes(1.0f, 0.0f);

            accumulator.clearGamepad();

            accumulator.setGamepadMovementAxes(0.0f, 1.0f);

            assertThat(accumulator.latch().strafeAxis()).isEqualTo(1.0f);
        }
    }
}
