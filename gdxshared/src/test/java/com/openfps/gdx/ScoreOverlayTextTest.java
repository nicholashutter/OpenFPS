/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openfps.engine.gameplay.MatchStatus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the text {@link ScoreOverlay} puts on the glass.
 *
 * <p><b>What a player is TOLD, not that a draw call happened.</b> That distinction
 * has cost this project real bugs — an effect once shipped "working" while being
 * ten colour levels from its background, because the test asserted it was drawn
 * rather than that it could be seen. The strings are the part of the overlay a
 * plain JVM can reach, and they are the part that carries the meaning.</p>
 *
 * <p>What is NOT covered, and cannot be without a display: whether the panel is
 * legible over a bright wall, and whether the band lands where the eye is. Those
 * need {@code gradlew :desktop:run} and a screenshot, and they were checked that
 * way — the notice was three sizes too large the first time, which no assertion
 * here would ever have caught.</p>
 */
@DisplayName("ScoreOverlay text")
class ScoreOverlayTextTest
{
    /** The demo's tic rate. */
    private static final int TICS_PER_SECOND = 60;

    /** A player three kills in, twice dead, on their last legs. */
    private static MatchStatus underPressure()
    {
        return new MatchStatus(3, 7, 4, 2, 20, false, 0);
    }

    /** A player on the floor with a second and a half of the respawn left. */
    private static MatchStatus down()
    {
        return new MatchStatus(3, 7, 4, 3, 0, true, 90);
    }

    @Nested
    @DisplayName("the score panel")
    class Score
    {
        @Test
        @DisplayName("names the kills against the roster, the deaths, and the health")
        void shouldReportTheScore()
        {
            final String[] lines = ScoreOverlay.scoreText(underPressure());

            assertThat(lines).hasSize(4);

            assertThat(lines[0]).contains("KILLS").contains("3/7");

            assertThat(lines[1]).contains("DEATHS").contains("2");

            assertThat(lines[2]).contains("HEALTH").contains("20");

            assertThat(lines[3]).contains("STREAK");
        }

        @Test
        @DisplayName("never shows a negative health, because a corpse is not owed arithmetic")
        void shouldClampHealthAtZero()
        {
            // The killing shot is not clamped in Match — a 20-damage hit on 10
            // health leaves -10 — and the negative belongs in the simulation. It
            // does not belong on a HUD, where it reads as a rendering fault.
            final MatchStatus overkilled = new MatchStatus(3, 7, 4, 3, -10, true, 60);

            assertThat(ScoreOverlay.scoreText(overkilled)[2])
                .contains("HEALTH").contains("0").doesNotContain("-");
        }

        @Test
        @DisplayName("every character it produces can actually be drawn")
        void shouldOnlyUseDrawableCharacters()
        {
            // BlockFont REJECTS a character it has no glyph for rather than
            // skipping it, so an unsupported one is a thrown exception on the
            // frame the figure first appears — mid-match, in the middle of a
            // fight. That is the failure mode requireDrawable was written for and
            // it has already happened once, to a decimal point.
            for (final String line : ScoreOverlay.scoreText(underPressure()))
            {
                for (final char character : line.toCharArray())
                {
                    assertThat(BlockFont.isSupported(character))
                        .as("BlockFont cannot draw '%s' in \"%s\"", character, line)
                        .isTrue();
                }
            }
        }

        @Test
        @DisplayName("a null status is rejected rather than drawn as blanks")
        void shouldRejectANullStatus()
        {
            assertThatThrownBy(() -> ScoreOverlay.scoreText(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
        }
    }

    @Nested
    @DisplayName("the death notice")
    class DeathNotice
    {
        @Test
        @DisplayName("counts DOWN, which is what distinguishes it from a hung game")
        void shouldCountDown()
        {
            // The whole point of the notice showing a number. A static "you died"
            // is indistinguishable from a frozen frame, and a player whose camera
            // has just stopped responding has every reason to assume the worst.
            final String far = ScoreOverlay.countdownText(
                new MatchStatus(0, 7, 7, 1, 0, true, 120), TICS_PER_SECOND);

            final String near = ScoreOverlay.countdownText(
                new MatchStatus(0, 7, 7, 1, 0, true, 30), TICS_PER_SECOND);

            assertThat(far).contains("2");

            assertThat(near).contains("1");

            assertThat(far).isNotEqualTo(near);
        }

        @Test
        @DisplayName("says what will happen, not just what did")
        void shouldNameTheRespawn()
        {
            // "ELIMINATED" alone tells the player they have lost control and
            // nothing about getting it back. The second line is the half that
            // makes the first one bearable.
            assertThat(ScoreOverlay.countdownText(down(), TICS_PER_SECOND))
                .containsIgnoringCase("RESPAWN");
        }

        @Test
        @DisplayName("every character it produces can actually be drawn")
        void shouldOnlyUseDrawableCharacters()
        {
            final String line = ScoreOverlay.countdownText(down(), TICS_PER_SECOND);

            for (final char character : line.toCharArray())
            {
                assertThat(BlockFont.isSupported(character))
                    .as("BlockFont cannot draw '%s' in \"%s\"", character, line)
                    .isTrue();
            }
        }

        @Test
        @DisplayName("a null status is rejected rather than drawn as blanks")
        void shouldRejectANullStatus()
        {
            assertThatThrownBy(() -> ScoreOverlay.countdownText(null, TICS_PER_SECOND))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
        }
    }

    @Nested
    @DisplayName("the super-blaster plaque")
    class SuperBlaster
    {
        @Test
        @DisplayName("the streak line counts toward the reward, so it does not arrive unexplained")
        void shouldShowProgressTowardTheReward()
        {
            // The cheapest line on the glass and the one that teaches the rule. A
            // gun that changes on the third kill with nothing counting up to it is
            // a mystery rather than a reward.
            assertThat(ScoreOverlay.streakText(streakOf(0)))
                .contains("STREAK").contains("0/3");

            assertThat(ScoreOverlay.streakText(streakOf(2)))
                .contains("STREAK").contains("2/3");
        }

        @Test
        @DisplayName("the streak line names the reward while it is live, rather than reading 0/3")
        void shouldNameTheRewardWhileLive()
        {
            // The award spends the streak that earned it, so the count really is
            // zero for the whole four seconds. A zero beside a plaque announcing
            // double damage is not a progress indicator, it is a contradiction.
            final String line = ScoreOverlay.streakText(superFor(240));

            assertThat(line).containsIgnoringCase("SUPER").contains("2");

            assertThat(line).doesNotContain("0/3");
        }

        @Test
        @DisplayName("counts DOWN, so the badge is not one the game forgot to take away")
        void shouldCountDown()
        {
            final String fresh = ScoreOverlay.superCountdownText(superFor(240), TICS_PER_SECOND);

            final String nearlyGone =
                ScoreOverlay.superCountdownText(superFor(30), TICS_PER_SECOND);

            assertThat(fresh).contains("4");

            assertThat(nearlyGone).contains("1");

            assertThat(fresh).isNotEqualTo(nearlyGone);
        }

        @Test
        @DisplayName("says what the reward DOES, not merely that there is one")
        void shouldNameTheEffect()
        {
            // "SUPER BLASTER" alone is a name. The number is the mechanic, and the
            // player has four seconds to decide what to spend it on.
            assertThat(ScoreOverlay.superCountdownText(superFor(240), TICS_PER_SECOND))
                .contains("X2").containsIgnoringCase("DAMAGE");
        }

        @Test
        @DisplayName("every character either line produces can actually be drawn")
        void shouldOnlyUseDrawableCharacters()
        {
            // BlockFont throws on a character it has no glyph for, so an
            // unsupported one is an exception on the frame the reward first
            // appears — which is mid-fight, which is the worst moment available.
            assertDrawable(ScoreOverlay.superCountdownText(superFor(240), TICS_PER_SECOND));

            assertDrawable(ScoreOverlay.streakText(superFor(240)));

            assertDrawable(ScoreOverlay.streakText(streakOf(2)));
        }

        @Test
        @DisplayName("the plaque is narrow enough that it cannot reach the score panel")
        void shouldNotCollideWithTheScorePanel()
        {
            // The two are the only things this class draws at the same time, and
            // the plaque is a centred box precisely so they cannot meet. Measured
            // in cells against a 1280 px window rather than eyeballed: half the
            // plaque plus the panel's own width has to leave the corner alone.
            final int plaqueCells = Math.max(BlockFont.widthInBlocks("SUPER BLASTER"),
                BlockFont.widthInBlocks(
                    ScoreOverlay.superCountdownText(superFor(240), TICS_PER_SECOND)));

            final float plaqueWidth = plaqueCells * ScoreOverlay.SUPER_CELL_PIXELS
                + ScoreOverlay.PADDING_PIXELS * 4.0f;

            final float panelWidth = BlockFont.widthInBlocks("HEALTH 100")
                * ScoreOverlay.CELL_PIXELS + ScoreOverlay.PADDING_PIXELS * 2.0f;

            final float surfaceWidth = 1280.0f;

            assertThat(surfaceWidth * 0.5f + plaqueWidth * 0.5f)
                .as("the plaque reaches into the score panel at %.0f px wide", plaqueWidth)
                .isLessThan(surfaceWidth - ScoreOverlay.MARGIN_PIXELS - panelWidth);
        }

        @Test
        @DisplayName("a null status is rejected rather than drawn as blanks")
        void shouldRejectANullStatus()
        {
            assertThatThrownBy(() -> ScoreOverlay.superCountdownText(null, TICS_PER_SECOND))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");

            assertThatThrownBy(() -> ScoreOverlay.streakText(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status");
        }

        // A player part-way to the reward.
        private static MatchStatus streakOf(final int streak)
        {
            return new MatchStatus(streak, 7, 7 - streak, 0, 100, false, 0, streak, 0);
        }

        // A player with the reward live and a given number of tics left.
        private static MatchStatus superFor(final int superTics)
        {
            return new MatchStatus(3, 7, 4, 0, 100, false, 0, 0, superTics);
        }

        // Every character of a line has a glyph.
        private static void assertDrawable(final String line)
        {
            for (final char character : line.toCharArray())
            {
                assertThat(BlockFont.isSupported(character))
                    .as("BlockFont cannot draw '%s' in \"%s\"", character, line)
                    .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("the low-health threshold")
    class LowHealth
    {
        @Test
        @DisplayName("triggers with more than one bot hit left, so the warning is useful")
        void shouldWarnBeforeTheLastHit()
        {
            // A warning that appears on the hit that kills you is not a warning.
            // The threshold has to leave room for at least one more shot to land,
            // which means it must exceed the damage one shot does.
            assertThat(ScoreOverlay.LOW_HEALTH)
                .as("the health line turns red only when a single hit would finish it")
                .isGreaterThan(20);
        }
    }
}
