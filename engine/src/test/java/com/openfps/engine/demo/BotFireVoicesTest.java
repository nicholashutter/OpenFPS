/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.openfps.engine.audio.synth.CarbineSound;
import com.openfps.engine.gameplay.BotSkill;
import com.openfps.engine.gameplay.Match;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the cap on how much of the room's fire is heard.
 *
 * <p>The sound cannot be heard from here, so this asserts the two things that
 * would make it wrong: that the cap actually caps, and that it does not cap so
 * hard that the room goes quiet. Both numbers are derived rather than chosen — see
 * {@link BotFireVoices#MIN_INTERVAL_TICS} — so the tests are written against the
 * derivations, which is what makes them catch a change to
 * {@link CarbineSound#DURATION_MS} or to {@link BotSkill#DUMB}.</p>
 */
@DisplayName("BotFireVoices")
final class BotFireVoicesTest
{
    /** The demo's default simulation rate. */
    private static final int TICS_PER_SECOND = 60;

    @Nested
    @DisplayName("the cap")
    final class Cap
    {
        @Test
        @DisplayName("a whole room firing on one tic makes ONE noise")
        void oneVoicePerTic()
        {
            // Seven copies of one generated buffer started on the same tic are not
            // seven shots. They are the same waveform added to itself — identical
            // samples, perfectly in phase — so the mix is 7x the amplitude of one
            // and clips. There is no second shot in there to hear.
            final BotFireVoices voices = new BotFireVoices();

            // MUTABLE local — how many of the volley were let through.
            int played = 0;

            for (int shot = 0; shot < Match.DEFAULT_BOT_COUNT; shot++)
            {
                if (voices.allow(100))
                {
                    played++;
                }
            }

            assertThat(played).isEqualTo(BotFireVoices.MAX_VOICES_PER_TIC);

            assertThat(voices.suppressedCount())
                .isEqualTo(Match.DEFAULT_BOT_COUNT - BotFireVoices.MAX_VOICES_PER_TIC);
        }

        @Test
        @DisplayName("holds the minimum interval, so sixty tics of fire is ten sounds")
        void holdsTheInterval()
        {
            // Every tic asked, which is a room firing far harder than DUMB can. The
            // answer is the rate ceiling and nothing else.
            final BotFireVoices voices = new BotFireVoices();

            for (int tic = 0; tic < TICS_PER_SECOND; tic++)
            {
                voices.allow(tic);
            }

            assertThat(voices.allowedCount())
                .isEqualTo(TICS_PER_SECOND / BotFireVoices.MIN_INTERVAL_TICS);
        }

        @Test
        @DisplayName("permits exactly two overlapping voices, which is what PEAK is solved for")
        void permitsTwoConcurrentVoices()
        {
            // The interval is derived from the sound's own length: 120 ms is 7.2
            // tics at 60 Hz, so a six-tic minimum admits ceil(7.2 / 6) = 2. Two at
            // PEAK 0.45 reach 0.90, inside full scale. Three would clip and one
            // would make the room sound like a single opponent.
            assertThat(BotFireVoices.maxConcurrentVoices(TICS_PER_SECOND)).isEqualTo(2);

            assertThat(CarbineSound.PEAK * 2.0).isLessThan(1.0);

            assertThat(CarbineSound.PEAK * 3.0)
                .as("three voices would fit, so the interval is looser than it needs to be")
                .isGreaterThan(1.0);
        }
    }

    @Nested
    @DisplayName("not capping too hard")
    final class StillAudible
    {
        @Test
        @DisplayName("never closes on the rate the room actually fires at")
        void doesNotThrottleTheOrdinaryCase()
        {
            // A limiter that shaped the AVERAGE would be changing the demo's
            // balance from the audio layer. DUMB produces a shot every 18 tics
            // across the seven of them, so the gate must be well inside that.
            // 2026-08: pinned to 7 (the demo's count) rather than the new
            // Match.DEFAULT_BOT_COUNT = 32. The map mode's 30-bot room
            // produces a shot every ~2 tics; the audio cap is a per-voice
            // gate, not a per-room one, so this assertion is about the
            // demo, where the 7-of-them rate is the meaningful number.
            final int roomInterval =
                BotSkill.DUMB.meanShotIntervalTics() / 7;

            assertThat(BotFireVoices.MIN_INTERVAL_TICS)
                .as("the cap is tighter than the room's own rate of fire")
                .isLessThan(roomInterval);
        }

        @Test
        @DisplayName("lets the room be heard more often than the player's own weapon")
        void theRoomMayBeBusierThanThePlayer()
        {
            // The room is busier than the player, because it is seven of them. A cap
            // at or below the player's own five a second would make return fire
            // sound like one more gun rather than like a room.
            assertThat(BotFireVoices.MIN_INTERVAL_TICS)
                .isLessThan(DemoGameplayPort.FIRE_INTERVAL_TICS);
        }

        @Test
        @DisplayName("the very first shot of a round is heard")
        void theFirstShotIsNeverSilent()
        {
            // The interval test is a subtraction, and a "never" sentinel left inside
            // it overflows on the first shot, wraps negative and reads as "not ready"
            // — and since the field is only assigned after the test passes, nothing
            // ever plays. That is the bug DemoGameplayPort.lastFireTic records, which
            // silenced the player's weapon for whole runs on every platform.
            assertThat(new BotFireVoices().allow(0)).isTrue();

            assertThat(new BotFireVoices().allow(Integer.MIN_VALUE + 1)).isTrue();

            assertThat(new BotFireVoices().allow(-5000)).isTrue();
        }

        @Test
        @DisplayName("a rematch starts with a clear gate")
        void clearForgetsTheLastRound()
        {
            final BotFireVoices voices = new BotFireVoices();

            voices.allow(500);

            voices.clear();

            assertThat(voices.lastPlayedTic()).isEqualTo(BotFireVoices.NEVER);

            assertThat(voices.allow(501))
                .as("the first bot to shoot in the new round was silent")
                .isTrue();

            assertThat(voices.suppressedCount()).isZero();
        }
    }

    @Nested
    @DisplayName("determinism")
    final class Deterministic
    {
        @Test
        @DisplayName("the same tic sequence gives the same gate decisions")
        void isAFunctionOfTheTicIndexAlone()
        {
            // No wall clock is consulted anywhere, for the reason BotRng gives: a
            // gate that opened on different tics on two peers would be a divergence
            // in the one layer nobody would look in.
            final BotFireVoices first = new BotFireVoices();

            final BotFireVoices second = new BotFireVoices();

            final StringBuilder one = new StringBuilder();

            final StringBuilder two = new StringBuilder();

            for (int tic = 0; tic < 200; tic++)
            {
                one.append(first.allow(tic));

                two.append(second.allow(tic));
            }

            assertThat(one.toString()).isEqualTo(two.toString());
        }

        @Test
        @DisplayName("a repeated tic index does not open the gate twice")
        void aRepeatedTicIsStillOneTic()
        {
            // Match is driven by an index rather than by an increment, so a caller
            // is entitled to hand the same tic twice.
            final BotFireVoices voices = new BotFireVoices();

            assertThat(voices.allow(10)).isTrue();

            assertThat(voices.allow(10)).isFalse();
        }
    }
}
