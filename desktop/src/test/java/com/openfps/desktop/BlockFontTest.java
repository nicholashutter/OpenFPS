/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BlockFont}.
 *
 * <p>The drawing needs a GL context and a human; the <b>measurement</b> does
 * not, and the measurement is where the bugs are. A width that is one cell too
 * large puts every centred string half a cell off; a glyph authored a column
 * short shifts every letter after it. Neither is visible except by looking at a
 * window, and both are exactly arithmetic.</p>
 */
@DisplayName("BlockFont")
class BlockFontTest
{
    // Collects every cell a string sets, as "column,row" strings.
    private static List<String> cellsOf(final String text)
    {
        final List<String> cells = new ArrayList<>();
        BlockFont.forEachBlock(text, (column, row, glyph) -> cells.add(column + "," + row));
        return cells;
    }

    // The highest column index any cell of a string occupies.
    private static int rightmostColumn(final String text)
    {
        final int[] rightmost = { -1 };
        BlockFont.forEachBlock(text, (column, row, glyph) ->
        {
            if (column > rightmost[0])
            {
                rightmost[0] = column;
            }
        });
        return rightmost[0];
    }

    @Nested
    @DisplayName("the alphabet")
    class Alphabet
    {
        @Test
        @DisplayName("covers every letter and digit")
        void shouldSupportEveryLetterAndDigit()
        {
            for (char letter = 'A'; letter <= 'Z'; letter++)
            {
                assertThat(BlockFont.isSupported(letter))
                    .as("no glyph for '%s'", letter).isTrue();
            }
            for (char digit = '0'; digit <= '9'; digit++)
            {
                assertThat(BlockFont.isSupported(digit))
                    .as("no glyph for '%s'", digit).isTrue();
            }
        }

        @Test
        @DisplayName("accepts lower case by folding it")
        void shouldFoldLowerCaseWhenLookingUpAGlyph()
        {
            assertThat(BlockFont.isSupported('a')).isTrue();
            assertThat(cellsOf("a")).isEqualTo(cellsOf("A"));
        }

        @Test
        @DisplayName("every glyph in the title fits the declared 5x7 box")
        void shouldKeepEveryGlyphInsideItsBox()
        {
            // The table is authored as art, so a typo is a row of the wrong
            // length. buildGlyphs throws on that at class-load; this asserts the
            // consequence — no cell escapes its glyph.
            for (char letter = 'A'; letter <= 'Z'; letter++)
            {
                final String single = String.valueOf(letter);
                BlockFont.forEachBlock(single, (column, row, glyph) ->
                {
                    assertThat(column).isBetween(0, BlockFont.GLYPH_WIDTH - 1);
                    assertThat(row).isBetween(0, BlockFont.GLYPH_HEIGHT - 1);
                });
            }
        }

        @Test
        @DisplayName("no letter is blank")
        void shouldSetAtLeastOneCellForEveryLetter()
        {
            // A glyph authored as all dots draws nothing and would be invisible
            // in a title until someone spelled a word with it.
            for (char letter = 'A'; letter <= 'Z'; letter++)
            {
                assertThat(cellsOf(String.valueOf(letter)))
                    .as("'%s' draws nothing", letter)
                    .isNotEmpty();
            }
        }

        @Test
        @DisplayName("space draws nothing, which is what makes it a space")
        void shouldDrawNothingForASpace()
        {
            assertThat(cellsOf(" ")).isEmpty();
            assertThat(BlockFont.widthInBlocks(" ")).isEqualTo(BlockFont.GLYPH_WIDTH);
        }
    }

    @Nested
    @DisplayName("measurement")
    class Measurement
    {
        @Test
        @DisplayName("one glyph is exactly GLYPH_WIDTH wide, with no trailing gap")
        void shouldNotCountATrailingGapWhenMeasuringOneGlyph()
        {
            // The trailing gap is the classic off-by-one here. Counted, every
            // string is a cell wider than it looks and sits half a cell left of
            // centre — small, consistent, and invisible without a ruler.
            assertThat(BlockFont.widthInBlocks("A")).isEqualTo(BlockFont.GLYPH_WIDTH);
        }

        @Test
        @DisplayName("each further glyph adds a width and one gap")
        void shouldAddAnAdvancePerExtraGlyph()
        {
            assertThat(BlockFont.widthInBlocks("AB"))
                .isEqualTo(BlockFont.GLYPH_WIDTH * 2 + BlockFont.GLYPH_SPACING);
            assertThat(BlockFont.widthInBlocks("ABC"))
                .isEqualTo(BlockFont.GLYPH_WIDTH * 3 + BlockFont.GLYPH_SPACING * 2);
        }

        @Test
        @DisplayName("an empty string is zero wide")
        void shouldMeasureAnEmptyStringAsZero()
        {
            assertThat(BlockFont.widthInBlocks("")).isZero();
            assertThat(cellsOf("")).isEmpty();
        }

        @Test
        @DisplayName("the measured width really is where the last cell can reach")
        void shouldMatchTheRightmostDrawnCellWhenMeasuring()
        {
            // The assertion that ties measurement to drawing. A width that does
            // not bound the cells clips the last letter or leaves a gap, and the
            // two are computed in different methods.
            final String word = "OPENFPS";
            assertThat(rightmostColumn(word))
                .isEqualTo(BlockFont.widthInBlocks(word) - 1);
        }

        @Test
        @DisplayName("the title fits its measured width")
        void shouldBoundTheTitleWhenMeasuringIt()
        {
            final String title = MainMenuScreen.TITLE_TEXT;
            final int width = BlockFont.widthInBlocks(title);

            BlockFont.forEachBlock(title, (column, row, glyph) ->
                assertThat(column).isLessThan(width));
        }
    }

    @Nested
    @DisplayName("layout")
    class Layout
    {
        @Test
        @DisplayName("glyphs are laid out left to right with no overlap")
        void shouldAdvanceEachGlyphPastTheLastWhenLayingOut()
        {
            // Every cell of glyph n must sit in n's own slot. Overlapping slots
            // would draw letters through each other.
            BlockFont.forEachBlock("OPENFPS", (column, row, glyph) ->
            {
                final int slotStart = glyph * BlockFont.GLYPH_ADVANCE;
                assertThat(column)
                    .as("glyph %d put a cell at column %d, outside its slot", glyph, column)
                    .isBetween(slotStart, slotStart + BlockFont.GLYPH_WIDTH - 1);
            });
        }

        @Test
        @DisplayName("reports the glyph index each cell belongs to, in order")
        void shouldReportTheGlyphIndexWhenWalkingCells()
        {
            // The title colours per letter, so the index has to be right or the
            // cycle travels through the word in the wrong shape.
            final List<Integer> indices = new ArrayList<>();
            BlockFont.forEachBlock("ABC", (column, row, glyph) -> indices.add(glyph));

            assertThat(indices).isNotEmpty();
            assertThat(indices).allSatisfy(index -> assertThat(index).isBetween(0, 2));
            assertThat(indices.get(0)).isZero();
            assertThat(indices.get(indices.size() - 1)).isEqualTo(2);
        }

        @Test
        @DisplayName("row 0 is the top, so a T has its bar on row 0")
        void shouldReportRowZeroAsTheTopWhenWalkingCells()
        {
            // The convention drawing depends on. If it were bottom-up every
            // letter would render upside down, which is obvious in a window and
            // invisible in a unit test that does not say so.
            final List<String> cells = cellsOf("T");

            assertThat(cells).contains("0,0", "1,0", "2,0", "3,0", "4,0");
            assertThat(cells).contains("2,6");
            assertThat(cells).doesNotContain("0,6");
        }
    }

    @Nested
    @DisplayName("refusing what it cannot draw")
    class Refusal
    {
        @Test
        @DisplayName("throws on a character with no glyph rather than skipping it")
        void shouldThrowOnAnUnsupportedCharacterWhenMeasuring()
        {
            // Loudly, because the alternative is a menu label with a hole in it
            // that nobody notices until it ships.
            assertThatThrownBy(() -> BlockFont.widthInBlocks("HELLO?"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("?");
            assertThatThrownBy(() -> BlockFont.forEachBlock("A@B", (c, r, g) -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects null text and a null sink")
        void shouldRejectNullsWhenWalkingCells()
        {
            assertThatThrownBy(() -> BlockFont.widthInBlocks(null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> BlockFont.forEachBlock("A", null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
