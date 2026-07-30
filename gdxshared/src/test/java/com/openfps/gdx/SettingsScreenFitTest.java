/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether the settings screen leaves the player a way off it.
 *
 * <p>The layout as a whole needs a GL context and a pair of eyes. The <b>fit
 * rule</b> needs neither: it is arithmetic over a surface size and a set of
 * measured heights, and it is the part whose failure mode is a screen with no
 * BACK button on it. So it is expressed as static methods on
 * {@link SettingsScreen} and asserted here, exactly as
 * {@link GameOverScreen}'s equivalent is.</p>
 *
 * <p><b>The numbers in these tests came off a device, not out of a design.</b>
 * The OpenFPS_API36 emulator, 2400x1080 at 2.625x, showed the SETTINGS heading,
 * both group names, TARGET OUTLINE and RENDER laid out correctly, DEBUG OVERLAY
 * sliced off by the bottom edge and no BACK button anywhere — on the one screen
 * that owns the input processor and had un-caught the back key, so the only exit
 * was killing the app. Every case below is that panel or a variation on it.</p>
 */
@DisplayName("the settings screen fits the surface it is given")
class SettingsScreenFitTest
{
    /** The emulator panel the defect was found on, in pixels. */
    private static final float PHONE_WIDTH = 2400.0f;

    /** The short edge of that panel — the one that ran out. */
    private static final float PHONE_HEIGHT = 1080.0f;

    /** That panel's density, which every fixed metric is multiplied by. */
    private static final float PHONE_DENSITY = 2.625f;

    /**
     * How many cells the real heading occupies, asked of {@link BlockFont} rather
     * than written down — so renaming the screen cannot leave this suite asserting
     * the fit of a word that is no longer on it.
     */
    private static final int HEADING_BLOCKS =
        BlockFont.widthInBlocks(SettingsScreen.TITLE_TEXT);

    /**
     * The four buttons, the two group headings and the bottom margin at phone
     * scale — measured on the device that failed, and the reason it failed.
     *
     * <p>Four 62 px buttons at 2.625x is 651 px on its own; the group labels and
     * the margin bring it to roughly 875 px of content that has to stay
     * reachable, on a surface 1080 px tall whose heading already starts 108 px
     * down.</p>
     */
    private static final float PHONE_REACHABLE_CONTENT = 875.0f;

    /** What the three hints and their gaps wanted on the same panel. */
    private static final float PHONE_HINTS = 266.0f;

    @Nested
    @DisplayName("the heading yields first, because it is decoration")
    class Heading
    {
        @Test
        @DisplayName("takes its natural width when the controls leave room for it")
        void shouldNotShrinkAHeadingThatFits()
        {
            // A desktop window: 1.0 scale, so the content is a fraction of the
            // surface and nothing has to give. The heading must come back at the
            // width it asked for, or every window that was already correct would
            // get tighter for the sake of a phone.
            final float wanted = 1280.0f * 0.38f;

            assertThat(SettingsScreen.headingWidthFor(1280.0f, 720.0f, HEADING_BLOCKS,
                SettingsScreen.headingHeightBudget(720.0f, 340.0f)))
                .as("a roomy window is left alone")
                .isEqualTo(wanted);
        }

        @Test
        @DisplayName("is capped by height on a wide short panel, not just by width")
        void shouldCapByHeightOnAPhone()
        {
            // BlockTitle sizes its cells from its WIDTH, so 38% of a 2400 px panel
            // is a heading far taller than a 1080 px surface can spare. The cap has
            // to be expressed as a width by inverting that arithmetic, which is the
            // whole reason this method exists rather than a second constant.
            final float budget =
                SettingsScreen.headingHeightBudget(PHONE_HEIGHT, PHONE_REACHABLE_CONTENT);
            final float width = SettingsScreen.headingWidthFor(PHONE_WIDTH, PHONE_HEIGHT,
                HEADING_BLOCKS, budget);

            assertThat(width)
                .as("narrower than the 38% it would have taken")
                .isLessThan(PHONE_WIDTH * 0.38f);
            assertThat(width / HEADING_BLOCKS * BlockFont.GLYPH_HEIGHT)
                .as("and the height that width implies is inside the budget")
                .isLessThanOrEqualTo(budget + 0.01f);
        }

        @Test
        @DisplayName("gets nothing rather than a negative budget when nothing is left")
        void shouldFloorTheBudgetAtZero()
        {
            // A heading of zero height is ugly. A BACK key below the bottom edge is
            // a dead end. Only one of those two is recoverable, so the budget floors
            // at zero and never goes negative.
            assertThat(SettingsScreen.headingHeightBudget(1080.0f, 5000.0f))
                .isEqualTo(0.0f);
        }
    }

    @Nested
    @DisplayName("then the hints go, because a sentence is not a control")
    class Hints
    {
        @Test
        @DisplayName("are drawn when there is room for them")
        void shouldKeepHintsOnARoomySurface()
        {
            assertThat(SettingsScreen.hintsFit(400.0f, 120.0f))
                .as("120 px of hints into 400 px of space")
                .isTrue();
        }

        @Test
        @DisplayName("are dropped on the phone panel that had no BACK button")
        void shouldDropHintsOnThePhoneThatFailed()
        {
            // THE regression. On this panel the heading has already yielded as far
            // as it can and there is still nothing spare, so three explanatory
            // sentences cannot be afforded — and a player who cannot read why the
            // render mode matters has lost strictly less than one who cannot leave
            // the screen.
            final float budget =
                SettingsScreen.headingHeightBudget(PHONE_HEIGHT, PHONE_REACHABLE_CONTENT);
            final float headingHeight = Math.min(
                SettingsScreen.headingWidthFor(PHONE_WIDTH, PHONE_HEIGHT, HEADING_BLOCKS,
                    budget) / HEADING_BLOCKS * BlockFont.GLYPH_HEIGHT,
                budget);
            final float controlsTop =
                PHONE_HEIGHT * 0.90f - headingHeight;

            assertThat(SettingsScreen.hintsFit(controlsTop - PHONE_REACHABLE_CONTENT,
                PHONE_HINTS))
                .as("266 px of hints do not fit a panel with nothing spare")
                .isFalse();
        }

        @Test
        @DisplayName("are dropped rather than overflowed when the space is negative")
        void shouldDropHintsWhenAlreadyOverflowing()
        {
            // The case the old code got wrong by never asking: it shrank the gaps,
            // found the total still did not fit, and carried on placing anyway.
            assertThat(SettingsScreen.hintsFit(-300.0f, 266.0f)).isFalse();
        }

        @Test
        @DisplayName("cost nothing to fit when there are none to draw")
        void shouldTreatNoHintsAsFitting()
        {
            // Guards the degenerate case rather than dividing by it: a screen with
            // no hints at all always "fits" them, even on a surface with no room.
            assertThat(SettingsScreen.hintsFit(-1.0f, 0.0f)).isTrue();
        }
    }

    @Nested
    @DisplayName("what the rule guarantees, stated as the property that matters")
    class ReachableBackButton
    {
        @Test
        @DisplayName("all four buttons and the bottom margin fit under the capped heading")
        void shouldLeaveEveryButtonOnScreen()
        {
            // The property, rather than any one of the three corrections: once the
            // heading has been capped against the reachable content, what is left
            // below it is at least that content. That is exactly the claim "there is
            // a BACK button on the screen", and it is the claim the device disproved.
            final float budget =
                SettingsScreen.headingHeightBudget(PHONE_HEIGHT, PHONE_REACHABLE_CONTENT);
            final float headingHeight = Math.min(
                SettingsScreen.headingWidthFor(PHONE_WIDTH, PHONE_HEIGHT, HEADING_BLOCKS,
                    budget) / HEADING_BLOCKS * BlockFont.GLYPH_HEIGHT,
                budget);

            assertThat(PHONE_HEIGHT * 0.90f - headingHeight)
                .as("room below the heading covers every button and the margin")
                .isGreaterThanOrEqualTo(PHONE_REACHABLE_CONTENT);
        }

        @Test
        @DisplayName("holds on a shorter panel too, not just the one that failed")
        void shouldHoldOnAnEvenShorterPanel()
        {
            // 2340x1080 is not the only landscape phone, and the rule must not be
            // tuned to the single AVD it was found on.
            final float height = 900.0f;
            final float budget =
                SettingsScreen.headingHeightBudget(height, PHONE_REACHABLE_CONTENT);
            final float headingHeight = Math.min(
                SettingsScreen.headingWidthFor(2160.0f, height, HEADING_BLOCKS, budget)
                    / HEADING_BLOCKS * BlockFont.GLYPH_HEIGHT,
                budget);

            assertThat(height * 0.90f - headingHeight)
                .isGreaterThanOrEqualTo(Math.min(PHONE_REACHABLE_CONTENT, height * 0.90f));
        }
    }

    @Nested
    @DisplayName("the density the defect needed to appear")
    class ScaleSanity
    {
        @Test
        @DisplayName("four buttons at phone density really do overflow a 1080 px panel")
        void shouldShowWhyTheRuleIsNeeded()
        {
            // Not a test of production code so much as a guard on the premise: if
            // someone shrinks the buttons or drops a control, this figure changes and
            // the reader should be told the numbers above are stale rather than
            // discovering it from a screenshot.
            final float buttons = 62.0f * PHONE_DENSITY * 4.0f;
            final float hints = 14.0f * PHONE_DENSITY * 3.0f;

            assertThat(buttons)
                .as("four pointer-sized buttons alone")
                .isGreaterThan(600.0f);
            assertThat(buttons + hints + 24.0f * PHONE_DENSITY)
                .as("with the hint gaps and the margin, over half the panel again")
                .isGreaterThan(PHONE_HEIGHT * 0.65f);
        }
    }
}
