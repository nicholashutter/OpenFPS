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

    /**
     * The heading's real width in cells, asked of the font rather than counted
     * by hand — the cap arithmetic inverts BlockTitle's, so a test that guessed
     * the number would stop testing the thing the moment the font changed.
     */
    private static int defeatBlocks()
    {
        return BlockFont.widthInBlocks(GameOverScreen.LOSS_TEXT);
    }

    /**
     * The fit rule that keeps the one way off this screen on the screen.
     *
     * <p>Laid out naively at a landscape phone's density the end-of-match block
     * is taller than the surface, and what falls off the bottom is the button —
     * on a screen that has already taken the input processor away from
     * everything else. The emulator showed DEFEAT, four tidy figures and a
     * sliver of yellow along the bottom edge, which is a dead end rather than a
     * blemish. Both halves of the correction are plain arithmetic and are
     * pinned here; the placement they feed still needs a window to judge.</p>
     */
    @Nested
    @DisplayName("the end-of-match fit rule")
    class FitRule
    {

        @Test
        @DisplayName("a roomy surface leaves every gap at its natural size")
        void shouldNotTightenAScreenThatAlreadyFits()
        {
            assertThat(GameOverScreen.gapFitFraction(500.0f, 300.0f)).isEqualTo(1.0f);
            assertThat(GameOverScreen.gapFitFraction(300.0f, 300.0f)).isEqualTo(1.0f);
        }

        @Test
        @DisplayName("a short surface shrinks the gaps proportionally rather than overflowing")
        void shouldShrinkGapsWhenTheBlockIsTooTall()
        {
            assertThat(GameOverScreen.gapFitFraction(150.0f, 300.0f)).isEqualTo(0.5f);
            assertThat(GameOverScreen.gapFitFraction(240.0f, 300.0f)).isEqualTo(0.8f);
        }

        @Test
        @DisplayName("a surface with nothing left to give collapses the gaps, never inverts them")
        void shouldClampToZeroRatherThanGoingNegative()
        {
            // A negative factor would push the button UP past the figures and
            // out of the top of the screen, which is the same dead end upside
            // down.
            assertThat(GameOverScreen.gapFitFraction(0.0f, 300.0f)).isEqualTo(0.0f);
            assertThat(GameOverScreen.gapFitFraction(-400.0f, 300.0f)).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("a layout wanting no gaps at all is left alone rather than divided by zero")
        void shouldReturnOneWhenThereAreNoGaps()
        {
            assertThat(GameOverScreen.gapFitFraction(-10.0f, 0.0f)).isEqualTo(1.0f);
        }

        @Test
        @DisplayName("a desktop window keeps the heading at its full width fraction")
        void shouldNotCapTheHeadingOnADesktopWindow()
        {
            // 1280x720: 52% of the width is a word well inside 22% of the
            // height, so the cap must not fire and change what already worked.
            assertThat(GameOverScreen.headingWidthFor(1280.0f, 720.0f, defeatBlocks()))
                .isEqualTo(1280.0f * 0.52f);
        }

        @Test
        @DisplayName("a landscape phone caps the heading by height instead of width")
        void shouldCapTheHeadingOnAWideShortSurface()
        {
            // 2400x1080 is the emulator. The heading must come back smaller than
            // the width alone would give it, or everything below inherits the
            // overflow.
            final float capped = GameOverScreen.headingWidthFor(2400.0f, 1080.0f, defeatBlocks());

            assertThat(capped).isLessThan(2400.0f * 0.52f);
            // A hundredth of a pixel of slack: the cap is set by inverting
            // BlockTitle's divide-by-cells and this multiplies it back, which
            // is not bit-exact in float. Anything that actually overflowed
            // would be out by whole pixels.
            assertThat(capped / defeatBlocks() * BlockFont.GLYPH_HEIGHT)
                .as("the resulting word must sit inside the height cap")
                .isLessThanOrEqualTo(1080.0f * 0.22f + 0.01f);
        }

        @Test
        @DisplayName("a heading with no blocks asks for the width rather than dividing by zero")
        void shouldFallBackWhenTheHeadingIsEmpty()
        {
            assertThat(GameOverScreen.headingWidthFor(1280.0f, 720.0f, 0))
                .isEqualTo(1280.0f * 0.52f);
        }
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
