/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link MatchSummary} — the frozen end-of-match result.
 *
 * <p>The screen that draws this needs a window; the arithmetic behind it does
 * not, and the arithmetic has one genuine trap in it: a player who dies without
 * firing a shot. That is not a contrived case — standing still in the open is
 * exactly how a new player loses their first round — and it is a division by
 * zero on the accuracy line.</p>
 */
@DisplayName("MatchSummary")
class MatchSummaryTest
{
    /** Player feet and eye, at the origin looking down +z — as in {@code MatchTest}. */
    private static final float PLAYER_EYE_Y = PlayerController.EYE_HEIGHT_UNITS;

    /** How far down +z the single bot stands. Well inside the player's reach. */
    private static final float BOT_DISTANCE = 100.0f;

    // A match with one stationary bot that never fires within these tests, so a
    // round can be decided by the player's shots alone. The fire offset is
    // load-bearing for the same reason MatchTest records: a huge interval alone
    // still fires on tic 0, because tic 0 starts the first cycle.
    private static Match matchWithOneBot()
    {
        return new Match(new Bot[]
        {
            new Bot(Match.FIRST_BOT_ENTITY_ID, 0.0f, 0.0f, BOT_DISTANCE, BotPattern.SENTRY,
                0.0f, 60, 0),
        });
    }

    // The player shoots straight ahead along +z, from the origin.
    private static int shootAhead(final Match match)
    {
        return match.firePlayerShot(0.0f, PLAYER_EYE_Y, 0.0f, 0.0f, 0.0f, 1.0f);
    }

    // ...and behind them, which connects with nothing.
    private static int shootBehind(final Match match)
    {
        return match.firePlayerShot(0.0f, PLAYER_EYE_Y, 0.0f, 0.0f, 0.0f, -1.0f);
    }

    @Nested
    @DisplayName("accuracy")
    class Accuracy
    {
        @Test
        @DisplayName("is the hit rate as a whole percentage")
        void shouldReportHitRate()
        {
            assertThat(summaryWithShots(20, 13).accuracyPercent()).isEqualTo(65);
            assertThat(summaryWithShots(4, 1).accuracyPercent()).isEqualTo(25);
        }

        @Test
        @DisplayName("rounds to nearest rather than truncating")
        void shouldRoundToNearest()
        {
            // 2 of 3 is 66.67%, which reads as 67. Truncation would show 66 and
            // look like an off-by-one nobody could explain.
            assertThat(summaryWithShots(3, 2).accuracyPercent()).isEqualTo(67);
            assertThat(summaryWithShots(3, 1).accuracyPercent()).isEqualTo(33);
        }

        @Test
        @DisplayName("is zero when nothing was fired, not a division by zero")
        void shouldReportZeroForNoShots()
        {
            // The one case a real run produces: a player who dies without
            // pulling the trigger. Integer division would throw here.
            assertThat(summaryWithShots(0, 0).accuracyPercent()).isZero();
        }

        @Test
        @DisplayName("is 100 when every shot landed")
        void shouldReportPerfectAccuracy()
        {
            assertThat(summaryWithShots(9, 9).accuracyPercent()).isEqualTo(100);
        }

        // A summary that differs only in its shot counts.
        private MatchSummary summaryWithShots(final int fired, final int hits)
        {
            return new MatchSummary(MatchState.WON, 7, 0, 7, fired, hits, 20, 80);
        }
    }

    @Nested
    @DisplayName("the outcome")
    class Outcome
    {
        @Test
        @DisplayName("a win is a win and a loss is not")
        void shouldDistinguishWinFromLoss()
        {
            assertThat(new MatchSummary(MatchState.WON, 7, 1, 7, 21, 13, 44, 56).isWin())
                .isTrue();
            assertThat(new MatchSummary(MatchState.LOST, 3, 4, 7, 18, 9, 100, 0).isWin())
                .isFalse();
        }

        @Test
        @DisplayName("a match still in progress has no summary")
        void shouldRefuseAnUnfinishedMatch()
        {
            // The type is what makes "this round is over" unrepresentable as
            // anything else. A summary of a running match would be a set of
            // numbers still moving.
            assertThatThrownBy(() ->
                new MatchSummary(MatchState.IN_PROGRESS, 0, 0, 7, 0, 0, 0, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("in progress");
        }

        @Test
        @DisplayName("a null outcome is rejected")
        void shouldRefuseANullOutcome()
        {
            assertThatThrownBy(() -> new MatchSummary(null, 0, 0, 7, 0, 0, 0, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finalState");
        }
    }

    @Nested
    @DisplayName("figures that cannot be")
    class Rejections
    {
        @Test
        @DisplayName("more hits than shots is refused")
        void shouldRefuseMoreHitsThanShots()
        {
            assertThatThrownBy(() -> new MatchSummary(MatchState.WON, 7, 0, 7, 3, 5, 0, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hits");
        }

        @Test
        @DisplayName("negative counters are refused")
        void shouldRefuseNegativeCounters()
        {
            assertThatThrownBy(() -> new MatchSummary(MatchState.WON, -1, 0, 7, 0, 0, 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new MatchSummary(MatchState.WON, 0, 0, 7, -2, 0, 0, 100))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("negative remaining health is allowed, because the killing shot is not clamped")
        void shouldAllowNegativeHealth()
        {
            final MatchSummary summary =
                new MatchSummary(MatchState.LOST, 2, 3, 7, 10, 4, 102, -2);
            assertThat(summary.playerHealth()).isEqualTo(-2);
        }
    }

    @Nested
    @DisplayName("freezing a live match")
    class Freezing
    {
        @Test
        @DisplayName("copies a won round off the match")
        void shouldCopyAWonRound()
        {
            final Match match = matchWithOneBot();
            // Three shots at PLAYER_SHOT_DAMAGE each puts a MAX_HEALTH bot down.
            for (int shot = 0; shot < 3; shot++)
            {
                shootAhead(match);
            }
            assertThat(match.state()).isEqualTo(MatchState.WON);

            final MatchSummary summary = MatchSummary.of(match);
            assertThat(summary.outcome()).isEqualTo(MatchState.WON);
            assertThat(summary.isWin()).isTrue();
            assertThat(summary.botsKilled()).isEqualTo(1);
            assertThat(summary.botCount()).isEqualTo(1);
            assertThat(summary.shotsFired()).isEqualTo(3);
            assertThat(summary.shotsHit()).isEqualTo(3);
            assertThat(summary.accuracyPercent()).isEqualTo(100);
            assertThat(summary.playerHealth()).isEqualTo(Match.PLAYER_MAX_HEALTH);
            assertThat(summary.damageTaken()).isZero();
        }

        @Test
        @DisplayName("counts misses against accuracy")
        void shouldCountMisses()
        {
            final Match match = matchWithOneBot();
            shootBehind(match);
            for (int shot = 0; shot < 3; shot++)
            {
                shootAhead(match);
            }

            final MatchSummary summary = MatchSummary.of(match);
            assertThat(summary.shotsFired()).isEqualTo(4);
            assertThat(summary.shotsHit()).isEqualTo(3);
            assertThat(summary.accuracyPercent()).isEqualTo(75);
        }

        @Test
        @DisplayName("a room with nobody in it is won from the first check")
        void shouldFreezeAnEmptyRoom()
        {
            // Legal, and it is what a scene with no character art staged
            // produces. Match documents it, so the summary has to survive it.
            final MatchSummary summary = MatchSummary.of(new Match(new Bot[0]));
            assertThat(summary.isWin()).isTrue();
            assertThat(summary.botCount()).isZero();
            assertThat(summary.accuracyPercent()).isZero();
        }

        @Test
        @DisplayName("a running match cannot be frozen")
        void shouldRefuseARunningMatch()
        {
            assertThatThrownBy(() -> MatchSummary.of(matchWithOneBot()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("in progress");
        }

        @Test
        @DisplayName("a null match is rejected")
        void shouldRefuseANullMatch()
        {
            assertThatThrownBy(() -> MatchSummary.of(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("match");
        }
    }

    @Nested
    @DisplayName("toString")
    class Description
    {
        @Test
        @DisplayName("names the outcome and the figures behind it")
        void shouldDescribeItself()
        {
            final String text =
                new MatchSummary(MatchState.LOST, 3, 4, 7, 18, 9, 100, 0).toString();
            assertThat(text).contains("LOST").contains("3/7").contains("9/18")
                .contains("50%");
        }
    }
}
