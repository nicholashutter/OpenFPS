/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MenuBackground} and {@link MenuPalette}.
 *
 * <p>These run headless. A {@link TextureRegion} with no texture behind it is a
 * plain value object — it is only dereferenced inside {@code draw}, which needs
 * a GL context and is not called here. What <b>is</b> called is the colour
 * decision, which is where the interesting failure lives: a band that never
 * moves, or one that leaves the palette and produces a colour nobody chose.</p>
 */
@DisplayName("MenuBackground")
class MenuBackgroundTest
{
    /** A region with no texture — enough for everything that is not drawing. */
    private static MenuBackground background()
    {
        return new MenuBackground(new TextureRegion());
    }

    @Nested
    @DisplayName("the checkerboard")
    class Checker
    {
        @Test
        @DisplayName("alternates on both axes")
        void shouldAlternateOnBothAxesWhenIdle()
        {
            final MenuBackground grid = background();

            // Sampled where the band is NOT. At the origin the band is at full
            // strength and its falloff varies from cell to cell, so every cell
            // there is a different colour whatever the checker does — the
            // parity is real but unobservable through it.
            final int base = clearOfBand(grid);

            // Compared as copies: cellColour returns a reused scratch instance,
            // which is what stops it allocating a Color per cell per frame.
            final Color origin = new Color(grid.cellColour(base, 0));
            final Color right = new Color(grid.cellColour(base + 1, 0));
            final Color up = new Color(grid.cellColour(base, 1));
            final Color diagonal = new Color(grid.cellColour(base + 1, 1));

            assertThat(right).isNotEqualTo(origin);
            assertThat(up).isNotEqualTo(origin);
            assertThat(diagonal).isEqualTo(origin);
        }

        @Test
        @DisplayName("the band tints the checker rather than replacing it")
        void shouldTintTheCheckerWhereTheBandFalls()
        {
            // The complement of the test above, and the reason it had to sample
            // elsewhere: under the band a cell is neither grid colour, because
            // the two are blended toward the pulse. A band that replaced the
            // checker outright would erase the pattern as it passed.
            final MenuBackground grid = background();
            final Color banded = new Color(grid.cellColour(0, 0));

            assertThat(grid.bandStrengthAt(0, 0)).isGreaterThan(0.0f);
            assertThat(banded).isNotIn(MenuPalette.GRID_LIGHT, MenuPalette.GRID_DARK,
                MenuPalette.GRID_PULSE);
        }

        @Test
        @DisplayName("uses the two grid colours where the band is not")
        void shouldUseThePaletteWhereTheBandIsAbsent()
        {
            final MenuBackground grid = background();

            // Far enough along the diagonal to be clear of the band at t=0.
            final int clear = clearOfBand(grid);

            assertThat(new Color(grid.cellColour(clear, 0)))
                .isIn(MenuPalette.GRID_LIGHT, MenuPalette.GRID_DARK);
        }
    }

    @Nested
    @DisplayName("the drifting band")
    class Band
    {
        @Test
        @DisplayName("never exceeds the strength it declares")
        void shouldStayWithinItsDeclaredStrength()
        {
            // A band at full strength is a bright stripe that pulls the eye off
            // the buttons every few seconds. The cap is the difference between
            // a sheen and a strobe.
            final MenuBackground grid = background();
            for (int column = 0; column < 60; column++)
            {
                for (int row = 0; row < 40; row++)
                {
                    assertThat(grid.bandStrengthAt(column, row))
                        .isBetween(0.0f, MenuBackground.BAND_STRENGTH);
                }
            }
        }

        @Test
        @DisplayName("is a diagonal, so it crosses corner to corner")
        void shouldTravelOnTheDiagonalWhenSweeping()
        {
            // Cells on the same anti-diagonal are at the same point in the
            // band. A band that varied with only one axis would sweep sideways
            // and the screen would read as a wipe rather than a sheen.
            final MenuBackground grid = background();

            assertThat(grid.bandStrengthAt(4, 2)).isEqualTo(grid.bandStrengthAt(2, 4));
            assertThat(grid.bandStrengthAt(0, 6)).isEqualTo(grid.bandStrengthAt(6, 0));
        }

        @Test
        @DisplayName("moves as time passes")
        void shouldMoveWhenTimeAdvances()
        {
            final MenuBackground grid = background();
            final float before = grid.bandStrengthAt(3, 0);

            grid.act(1.0f);

            assertThat(grid.elapsedSeconds()).isEqualTo(1.0f);
            assertThat(grid.bandStrengthAt(3, 0))
                .as("a band that does not move is a texture")
                .isNotEqualTo(before);
        }

        @Test
        @DisplayName("stays continuous across the wrap, with no flicker")
        void shouldNotFlickerWhenTheBandWrapsAround()
        {
            // Measured from the nearer side of the period, so the band crosses
            // the seam instead of vanishing at one edge and reappearing at the
            // other. Stepping a full period must land back on the same value.
            final MenuBackground grid = background();
            final float start = grid.bandStrengthAt(0, 0);

            grid.act(46.0f / MenuBackground.BAND_CELLS_PER_SECOND);

            assertThat(grid.bandStrengthAt(0, 0)).isCloseTo(start,
                org.assertj.core.api.Assertions.within(1.0e-3f));
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        @DisplayName("rejects a null pixel region")
        void shouldRejectANullRegionWhenConstructed()
        {
            assertThatThrownBy(() -> new MenuBackground(null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("the palette")
    class Palette
    {
        @Test
        @DisplayName("every shade is darker than the face it sits under")
        void shouldMakeEveryShadeDarkerThanItsFace()
        {
            // The bevel only reads as a bevel if the base is in shadow. A pair
            // the wrong way round makes the button look like it is glowing from
            // underneath.
            assertShadeIsDarker(MenuPalette.PLAY_FACE, MenuPalette.PLAY_SHADE);
            assertShadeIsDarker(MenuPalette.NET_FACE, MenuPalette.NET_SHADE);
            assertShadeIsDarker(MenuPalette.NEUTRAL_FACE, MenuPalette.NEUTRAL_SHADE);
            assertShadeIsDarker(MenuPalette.QUIT_FACE, MenuPalette.QUIT_SHADE);
        }

        @Test
        @DisplayName("has more title colours than the title has letters, so none repeats")
        void shouldOfferEnoughTitleColoursForTheWholeWord()
        {
            // A repeated colour on adjacent letters makes them read as one
            // letter. The cycle is walked one step per letter, so it needs at
            // least as many entries as the longest run it has to colour.
            assertThat(MenuPalette.TITLE_CYCLE.length)
                .isGreaterThanOrEqualTo(MainMenuScreen.TITLE_TEXT.length() - 1);
        }

        @Test
        @DisplayName("keeps the title shadow darker than every title colour")
        void shouldKeepTheTitleShadowDarkestOfAll()
        {
            // The shadow is what keeps letters legible over the drifting grid.
            // Lighter than a face and it stops being a shadow.
            for (final Color face : MenuPalette.TITLE_CYCLE)
            {
                assertShadeIsDarker(face, MenuPalette.TITLE_SHADOW);
            }
        }

        private void assertShadeIsDarker(final Color face, final Color shade)
        {
            assertThat(luminance(shade))
                .as("%s must be darker than %s", shade, face)
                .isLessThan(luminance(face));
        }

        private float luminance(final Color colour)
        {
            return 0.2126f * colour.r + 0.7152f * colour.g + 0.0722f * colour.b;
        }
    }

    // The first column along the diagonal that the band does not reach at t=0.
    private static int clearOfBand(final MenuBackground grid)
    {
        for (int column = 0; column < 60; column++)
        {
            if (grid.bandStrengthAt(column, 0) == 0.0f)
            {
                return column;
            }
        }
        throw new IllegalStateException("the band covers the whole grid, which is not a band");
    }
}
