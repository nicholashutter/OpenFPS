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
 * Tests for {@link FpsMeter} — the arithmetic behind the frame counter.
 *
 * <p><b>All of this is headless and all of it is real.</b> The drawing needs a
 * GL context and a human; the smoothing does not, and the smoothing is where
 * the mistakes live. Two of them are covered here specifically because they
 * produce a counter that <i>looks</i> plausible and is wrong: averaging the
 * reciprocals instead of the durations, which flatters a stuttering machine, and
 * seeding the average at zero, which makes every run open with a counter
 * climbing out of a hole it invented.</p>
 */
@DisplayName("FpsMeter")
class FpsMeterTest
{
    /** One frame at exactly 60 Hz, in nanoseconds. */
    private static final long SIXTY_HZ_NANOS = 16_666_667L;

    /** Nanoseconds in a millisecond. */
    private static final long MILLI = 1_000_000L;

    /** Floating-point slack for a millisecond figure. */
    private static final float MILLI_EPSILON = 0.001f;

    // Feeds one duration in repeatedly, which is how a steady frame rate looks.
    private static FpsMeter settledAt(final long frameNanos, final int frames)
    {
        final FpsMeter meter = new FpsMeter();

        for (int index = 0; index < frames; index++)
        {
            meter.sample(frameNanos);
        }

        return meter;
    }

    @Nested
    @DisplayName("a fresh meter")
    class Fresh
    {
        @Test
        @DisplayName("reports nothing before it has been given a frame")
        void shouldReportNothingBeforeAnySample()
        {
            final FpsMeter meter = new FpsMeter();

            assertThat(meter.hasReading()).isFalse();

            assertThat(meter.samples()).isZero();

            assertThat(meter.frameMillis()).isZero();

            assertThat(meter.fps()).isZero();
        }

        @Test
        @DisplayName("takes its first sample whole rather than easing toward it")
        void shouldSeedFromTheFirstSample()
        {
            // The alternative — starting the average at zero — makes the
            // counter climb toward the truth over the first second of every run
            // and every unpause, which reads as a stall that is not there.
            final FpsMeter meter = new FpsMeter();

            meter.sample(20L * MILLI);

            assertThat(meter.frameMillis()).isEqualTo(20.0f, within(MILLI_EPSILON));

            assertThat(meter.fps()).isEqualTo(50.0f, within(0.01f));

            assertThat(meter.samples()).isEqualTo(1L);
        }

        @Test
        @DisplayName("defaults to the documented smoothing weight")
        void shouldDefaultItsSmoothing()
        {
            assertThat(new FpsMeter().smoothing()).isEqualTo(FpsMeter.DEFAULT_SMOOTHING);
        }
    }

    @Nested
    @DisplayName("a steady frame rate")
    class Steady
    {
        @Test
        @DisplayName("settles on the frame time it is fed")
        void shouldSettleOnAConstantFrameTime()
        {
            final FpsMeter meter = settledAt(SIXTY_HZ_NANOS, 200);

            assertThat(meter.frameMillis()).isEqualTo(16.667f, within(0.01f));
        }

        @Test
        @DisplayName("reports 60 fps for a 16.7 ms frame")
        void shouldReportSixtyForASixtyHertzFrame()
        {
            final FpsMeter meter = settledAt(SIXTY_HZ_NANOS, 200);

            assertThat(meter.fps()).isEqualTo(60.0f, within(0.05f));
        }

        @Test
        @DisplayName("counts every sample it accepted")
        void shouldCountItsSamples()
        {
            assertThat(settledAt(SIXTY_HZ_NANOS, 37).samples()).isEqualTo(37L);
        }
    }

    @Nested
    @DisplayName("the smoothing itself")
    class Smoothing
    {
        @Test
        @DisplayName("moves the average by exactly its weight on the second sample")
        void shouldApplyTheWeightExactly()
        {
            // The arithmetic written out: seeded at 10, fed 20, weight 0.25,
            // so the average moves a quarter of the 10 ms gap to 12.5.
            final FpsMeter meter = new FpsMeter(0.25f);

            meter.sample(10L * MILLI);

            meter.sample(20L * MILLI);

            assertThat(meter.frameMillis()).isEqualTo(12.5f, within(MILLI_EPSILON));
        }

        @Test
        @DisplayName("a weight of 1 disables smoothing and reports the last frame")
        void shouldFollowExactlyAtWeightOne()
        {
            final FpsMeter meter = new FpsMeter(1.0f);

            meter.sample(10L * MILLI);

            meter.sample(40L * MILLI);

            assertThat(meter.frameMillis()).isEqualTo(40.0f, within(MILLI_EPSILON));
        }

        @Test
        @DisplayName("one slow frame barely moves the reading")
        void shouldAbsorbASingleSpike()
        {
            // The whole reason the counter is smoothed. A raw reciprocal would
            // show 5 fps for one frame and 60 again the next, which is a
            // flicker rather than a measurement.
            final FpsMeter meter = settledAt(SIXTY_HZ_NANOS, 200);

            final float before = meter.frameMillis();

            meter.sample(200L * MILLI);

            assertThat(meter.frameMillis()).isLessThan(before + 20.0f);

            assertThat(meter.fps()).isGreaterThan(25.0f);
        }

        @Test
        @DisplayName("a sustained slowdown is followed within a fraction of a second")
        void shouldFollowASustainedChange()
        {
            // The other half of the trade: absorbing a spike must not mean
            // ignoring a real collapse. Ten frames is the documented time
            // constant, so thirty gets most of the way there.
            final FpsMeter meter = settledAt(SIXTY_HZ_NANOS, 200);

            for (int index = 0; index < 30; index++)
            {
                meter.sample(33L * MILLI);
            }

            assertThat(meter.fps()).isBetween(30.0f, 36.0f);
        }
    }

    @Nested
    @DisplayName("averaging durations, not rates")
    class AverageOfDurations
    {
        @Test
        @DisplayName("a stall drags the reported rate down rather than being diluted")
        void shouldNotFlatterAStutteringMachine()
        {
            // The distinction this class's Javadoc turns on. A 210 ms stall
            // after a run of 10 ms frames is twenty times the duration, and
            // averaging DURATIONS lets it weigh twenty times as much — the
            // reading collapses, which is the honest report of a machine that
            // just spent a fifth of a second not drawing.
            //
            // Averaging the RECIPROCALS instead would let it weigh a twentieth
            // as much: the stall's contribution is one sample at 4.8 fps
            // against a settled 100, so the same event would barely move the
            // display. That is the version that flatters a stuttering machine,
            // and it is the easy mistake to make here.
            final FpsMeter meter = new FpsMeter(0.5f);

            for (int index = 0; index < 19; index++)
            {
                meter.sample(10L * MILLI);
            }

            meter.sample(210L * MILLI);

            assertThat(meter.fps()).isLessThan(20.0f);
        }
    }

    @Nested
    @DisplayName("samples that are not measurements")
    class Rejections
    {
        @Test
        @DisplayName("a zero duration is discarded rather than averaged in")
        void shouldIgnoreZeroDurations()
        {
            // SoftwareRenderPort.lastFrameNanos() is zero until it has finished
            // a frame. Folded in as "a frame that took no time" it would spike
            // the reported rate to something impossible.
            final FpsMeter meter = new FpsMeter();

            meter.sample(0L);

            assertThat(meter.hasReading()).isFalse();

            assertThat(meter.samples()).isZero();
        }

        @Test
        @DisplayName("a negative duration is discarded")
        void shouldIgnoreNegativeDurations()
        {
            final FpsMeter meter = settledAt(SIXTY_HZ_NANOS, 50);

            final float before = meter.frameMillis();

            meter.sample(-1L);

            assertThat(meter.frameMillis()).isEqualTo(before);

            assertThat(meter.samples()).isEqualTo(50L);
        }

        @Test
        @DisplayName("a smoothing weight outside (0, 1] is refused")
        void shouldRefuseAnUnusableWeight()
        {
            assertThatThrownBy(() -> new FpsMeter(0.0f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleWeight");

            assertThatThrownBy(() -> new FpsMeter(1.5f))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new FpsMeter(-0.2f))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new FpsMeter(Float.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("reset")
    class Reset
    {
        @Test
        @DisplayName("puts the meter back to knowing nothing")
        void shouldForgetEverything()
        {
            final FpsMeter meter = settledAt(SIXTY_HZ_NANOS, 100);

            meter.reset();

            assertThat(meter.hasReading()).isFalse();

            assertThat(meter.frameMillis()).isZero();

            assertThat(meter.fps()).isZero();

            assertThat(meter.samples()).isZero();
        }

        @Test
        @DisplayName("makes the next sample the seed again, not a smoothed step")
        void shouldReseedAfterReset()
        {
            final FpsMeter meter = settledAt(SIXTY_HZ_NANOS, 100);

            meter.reset();

            meter.sample(40L * MILLI);

            assertThat(meter.frameMillis()).isEqualTo(40.0f, within(MILLI_EPSILON));
        }
    }
}
