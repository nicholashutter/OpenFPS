/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MatchStatus}.
 *
 * <p>The interesting part is the <b>countdown</b>. Everything else here is a
 * field copied across a thread boundary; the seconds arithmetic is the only rule
 * in the class, and getting it wrong produces a notice that says "0" for a whole
 * second before anything happens — which reads as a game that has stopped, which
 * is the exact impression the notice exists to prevent.</p>
 */
@DisplayName("MatchStatus")
class MatchStatusTest
{
    /** The demo's tic rate. */
    private static final int TICS_PER_SECOND = 60;

    /** A route period; irrelevant to a sentry, but the constructor needs one. */
    private static final int PERIOD = 60;

    // A match whose one bot never misses, so a death can be arranged in a known
    // number of tics rather than waited for.
    private static Match matchWithOneMarksman()
    {
        final Bot sentry = new Bot(Match.FIRST_BOT_ENTITY_ID, 0.0f, 0.0f, 200.0f,
            BotPattern.SENTRY, 0.0f, PERIOD, 0);
        return new Match(new Bot[] {sentry}, new BotRng(), BotSkill.MARKSMAN,
            Match.UNLIMITED_DEATHS);
    }

    @Nested
    @DisplayName("snapshotting a running match")
    class Snapshot
    {
        @Test
        @DisplayName("copies a match that is still in progress, which no summary may")
        void shouldSnapshotARunningMatch()
        {
            // The whole reason this type exists beside MatchSummary: that one
            // refuses a match still in progress, and this is the case that needs
            // serving. Sixty times a second, on the other thread.
            final Match match = matchWithOneMarksman();

            final MatchStatus status = MatchStatus.of(match, 0);

            assertThat(status.botCount()).isEqualTo(1);
            assertThat(status.botsAlive()).isEqualTo(1);
            assertThat(status.botsKilled()).isZero();
            assertThat(status.playerDeaths()).isZero();
            assertThat(status.playerHealth()).isEqualTo(Match.PLAYER_MAX_HEALTH);
            assertThat(status.isPlayerDown()).isFalse();
            assertThat(status.respawnTicsRemaining()).isZero();
        }

        @Test
        @DisplayName("is a copy, so a later tic cannot move a figure already on screen")
        void shouldNotFollowTheMatchAfterBeingTaken()
        {
            // The thread boundary, stated as behaviour. A frame that held a
            // reference could show a health bar from one tic beside a kill count
            // from another.
            final Match match = matchWithOneMarksman();
            final MatchStatus taken = MatchStatus.of(match, 0);

            match.firePlayerShot(0.0f, PlayerController.EYE_HEIGHT_UNITS, 0.0f,
                0.0f, 0.0f, 1.0f);
            match.firePlayerShot(0.0f, PlayerController.EYE_HEIGHT_UNITS, 0.0f,
                0.0f, 0.0f, 1.0f);
            match.firePlayerShot(0.0f, PlayerController.EYE_HEIGHT_UNITS, 0.0f,
                0.0f, 0.0f, 1.0f);
            assertThat(match.botsKilled()).isEqualTo(1);

            assertThat(taken.botsKilled()).isZero();
            assertThat(taken.botsAlive()).isEqualTo(1);
        }

        @Test
        @DisplayName("reports the player as down, with tics left to run")
        void shouldReportADownPlayer()
        {
            final Match match = matchWithOneMarksman();
            final int hitsToKill = Match.PLAYER_MAX_HEALTH / Match.BOT_SHOT_DAMAGE;
            for (int tic = 0; tic < hitsToKill; tic++)
            {
                match.tick(tic, 0.0f, 0.0f, 0.0f);
            }
            final int deathTic = hitsToKill - 1;

            final MatchStatus status = MatchStatus.of(match, deathTic);

            assertThat(status.isPlayerDown()).isTrue();
            assertThat(status.playerHealth()).isZero();
            assertThat(status.playerDeaths()).isEqualTo(1);
            assertThat(status.respawnTicsRemaining()).isEqualTo(Match.RESPAWN_DELAY_TICS);
        }

        @Test
        @DisplayName("a null match is rejected rather than snapshotted as blanks")
        void shouldRejectANullMatch()
        {
            assertThatThrownBy(() -> MatchStatus.of(null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("match");
        }
    }

    @Nested
    @DisplayName("the respawn countdown")
    class Countdown
    {
        @Test
        @DisplayName("rounds UP, so it never shows a zero the player has to wait through")
        void shouldRoundSecondsUp()
        {
            // The rule, and the only arithmetic in this class. Truncation would
            // show "0" for the last full second of every respawn, which reads as a
            // hang — precisely the impression the countdown exists to dispel.
            assertThat(status(1).respawnSecondsRemaining(TICS_PER_SECOND)).isEqualTo(1);
            assertThat(status(59).respawnSecondsRemaining(TICS_PER_SECOND)).isEqualTo(1);
            assertThat(status(60).respawnSecondsRemaining(TICS_PER_SECOND)).isEqualTo(1);
            assertThat(status(61).respawnSecondsRemaining(TICS_PER_SECOND)).isEqualTo(2);
            assertThat(status(120).respawnSecondsRemaining(TICS_PER_SECOND)).isEqualTo(2);
        }

        @Test
        @DisplayName("is zero when the player is on their feet")
        void shouldBeZeroWhenNotDown()
        {
            assertThat(status(0).respawnSecondsRemaining(TICS_PER_SECOND)).isZero();
        }

        @Test
        @DisplayName("a non-positive tic rate answers zero rather than dividing by it")
        void shouldNotDivideByAZeroRate()
        {
            // Reachable: the rate is attached separately from the supplier, and a
            // window whose status arrived before its rate did would divide by zero
            // on its first frame.
            assertThat(status(90).respawnSecondsRemaining(0)).isZero();
            assertThat(status(90).respawnSecondsRemaining(-1)).isZero();
        }

        // A status that is down with a given number of tics left.
        private static MatchStatus status(final int ticsRemaining)
        {
            return new MatchStatus(0, 7, 7, 1, 0, ticsRemaining > 0, ticsRemaining);
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        @DisplayName("rejects negative counters")
        void shouldRejectNegativeCounters()
        {
            assertThatThrownBy(() -> new MatchStatus(-1, 7, 7, 0, 100, false, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new MatchStatus(0, 7, 7, -1, 100, false, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new MatchStatus(0, 7, 7, 0, 100, false, -1))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects more bots alive than the round started with")
        void shouldRejectAnImpossibleRoster()
        {
            assertThatThrownBy(() -> new MatchStatus(0, 7, 8, 0, 100, false, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more bots alive");
        }

        @Test
        @DisplayName("accepts a negative health, because the killing shot is not clamped")
        void shouldAcceptNegativeHealth()
        {
            assertThat(new MatchStatus(0, 7, 7, 1, -20, true, 60).playerHealth())
                .isEqualTo(-20);
        }
    }

    @Nested
    @DisplayName("description")
    class Description
    {
        @Test
        @DisplayName("toString names the score, for logs and assertion messages")
        void shouldDescribeItself()
        {
            final String text = new MatchStatus(3, 7, 4, 2, 40, false, 0).toString();

            assertThat(text).contains("3/7").contains("deaths=2").contains("hp=40");
        }
    }
}
