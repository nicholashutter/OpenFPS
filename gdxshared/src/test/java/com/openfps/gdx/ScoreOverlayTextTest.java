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

            assertThat(lines).hasSize(3);
            assertThat(lines[0]).contains("KILLS").contains("3/7");
            assertThat(lines[1]).contains("DEATHS").contains("2");
            assertThat(lines[2]).contains("HEALTH").contains("20");
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
