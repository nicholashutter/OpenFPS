/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import com.openfps.engine.gameplay.MatchState;
import com.openfps.engine.gameplay.MatchSummary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the words on the two new screens.
 *
 * <p>{@link GameOverScreen} and {@link SettingsScreen} are layout, and layout
 * needs a window to judge — a GL context, and a human looking at it. What does
 * not need either is <b>what the player is told</b>, and that is what is pinned
 * here: that a loss never renders as VICTORY, that the heading colour carries
 * the result rather than contradicting it, and that the settings toggle reports
 * its state rather than its action.</p>
 *
 * <p>The heading is also checked against {@link BlockFont}'s alphabet, which is
 * not pedantry: that class <b>rejects</b> a character it has no glyph for rather
 * than skipping it, so a heading with an unsupported letter would take the
 * window down at the exact moment a player finished their first match.</p>
 */
@DisplayName("end-of-match and settings text")
class EndOfMatchTextTest
{
    /** A cleared room. */
    private static MatchSummary won()
    {
        return new MatchSummary(MatchState.WON, 7, 7, 21, 13, 44, 56);
    }

    /** A dead player. */
    private static MatchSummary lost()
    {
        return new MatchSummary(MatchState.LOST, 3, 7, 18, 9, 100, 0);
    }

    @Nested
    @DisplayName("the end-of-match heading")
    class Heading
    {
        @Test
        @DisplayName("says VICTORY for a win and DEFEAT for a loss")
        void shouldNameTheOutcome()
        {
            assertThat(GameOverScreen.headingText(won())).isEqualTo(GameOverScreen.WIN_TEXT);
            assertThat(GameOverScreen.headingText(lost())).isEqualTo(GameOverScreen.LOSS_TEXT);
        }

        @Test
        @DisplayName("carries the menu's own colour language")
        void shouldColourTheOutcome()
        {
            // Green is the primary action's face and red is Quit's, so the
            // result reads in the colours the player already learned on the
            // menu rather than in a third scheme invented for this screen.
            assertThat(GameOverScreen.headingColour(won())).isEqualTo(MenuPalette.PLAY_FACE);
            assertThat(GameOverScreen.headingColour(lost())).isEqualTo(MenuPalette.QUIT_FACE);
        }

        @Test
        @DisplayName("is drawable in the block alphabet")
        void shouldBeDrawableAsBlocks()
        {
            // BlockFont throws on a character it has no glyph for. A heading
            // that failed this would take the window down at the moment a
            // player finished their first match.
            assertThat(BlockFont.widthInBlocks(GameOverScreen.WIN_TEXT)).isPositive();
            assertThat(BlockFont.widthInBlocks(GameOverScreen.LOSS_TEXT)).isPositive();
            assertThat(BlockFont.widthInBlocks(SettingsScreen.TITLE_TEXT)).isPositive();
        }
    }

    @Nested
    @DisplayName("the summary lines")
    class Summary
    {
        @Test
        @DisplayName("report the kills against the roster")
        void shouldReportKills()
        {
            assertThat(GameOverScreen.summaryText(won())[0])
                .contains("KILLS").contains("7 of 7");
            assertThat(GameOverScreen.summaryText(lost())[0]).contains("3 of 7");
        }

        @Test
        @DisplayName("report accuracy as a percentage, with the raw counts behind it")
        void shouldReportAccuracy()
        {
            // Both, because neither alone is enough: the percentage is what a
            // player compares between rounds, and the counts are what makes a
            // 100% round of one shot readable as the fluke it is.
            final String line = GameOverScreen.summaryText(won())[1];
            assertThat(line).contains("ACCURACY").contains("62%")
                .contains("13 of 21");
        }

        @Test
        @DisplayName("report damage taken and the health left")
        void shouldReportHealth()
        {
            final String[] lines = GameOverScreen.summaryText(won());
            assertThat(lines[2]).contains("DAMAGE TAKEN").contains("44");
            assertThat(lines[3]).contains("HEALTH LEFT").contains("56");
        }

        @Test
        @DisplayName("never show negative health, because a corpse is not owed arithmetic")
        void shouldClampHealthAtZero()
        {
            // Match does not clamp the killing shot — playerHealth can finish
            // below zero — and "HEALTH LEFT -2" is a detail of the damage model
            // leaking onto a screen where it means nothing.
            final MatchSummary overkilled =
                new MatchSummary(MatchState.LOST, 2, 7, 10, 4, 102, -2);
            assertThat(GameOverScreen.summaryText(overkilled)[3])
                .contains("HEALTH LEFT").contains("0").doesNotContain("-2");
        }

        @Test
        @DisplayName("survive a player who died without firing")
        void shouldSurviveAPlayerWhoNeverFired()
        {
            // Standing still in the open is how a new player loses their first
            // round, so this is a real path rather than a contrived one.
            final MatchSummary silent =
                new MatchSummary(MatchState.LOST, 0, 7, 0, 0, 100, 0);
            assertThat(GameOverScreen.summaryText(silent)[1])
                .contains("0%").contains("0 of 0");
        }

        @Test
        @DisplayName("a null result is rejected rather than drawn as blanks")
        void shouldRejectANullResult()
        {
            assertThatThrownBy(() -> GameOverScreen.summaryText(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("result");
        }
    }

    @Nested
    @DisplayName("the settings toggle label")
    class ToggleLabel
    {
        @Test
        @DisplayName("names the setting and its current state")
        void shouldReportTheState()
        {
            final DebugSettings settings = new DebugSettings();
            assertThat(SettingsScreen.debugButtonLabel(settings))
                .startsWith(SettingsScreen.DEBUG_LABEL).endsWith("OFF");

            settings.toggleOverlay();
            assertThat(SettingsScreen.debugButtonLabel(settings)).endsWith("ON");
        }

        @Test
        @DisplayName("a null setting is rejected")
        void shouldRejectNullSettings()
        {
            assertThatThrownBy(() -> SettingsScreen.debugButtonLabel(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("debugSettings");
        }
    }
}
