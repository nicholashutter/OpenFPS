/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import java.util.HashMap;
import java.util.Map;

/**
 * A 5x7 block alphabet: text as a grid of squares rather than as glyphs.
 *
 * <p>The title on the welcome screen is drawn from this, one coloured square
 * per set cell. That is the whole idea — the game's art is Kenney's blocky
 * character and prototype kits, everything in the world is made of cubes, and a
 * title rendered in a smooth typeface looks like it wandered in from a
 * different program. Letters made of the same cubes as the walls belong.</p>
 *
 * <h2>Why not just scale up the built-in font</h2>
 *
 * <p>{@code MainMenuScreen} already carries libGDX's default {@link
 * com.badlogic.gdx.graphics.g2d.BitmapFont}, and scaling it is one line. It is
 * a 15-pixel bitmap face, so at the size a title wants — three or four hundred
 * pixels wide — it is a blurry mess of stretched texels. A block alphabet is
 * <i>sharper</i> the larger it gets, because each cell is a solid rectangle
 * rather than a magnified sample.</p>
 *
 * <h2>No libGDX here, on purpose</h2>
 *
 * <p>This class imports nothing from any toolkit. It answers "which cells are
 * set" and stops; drawing them is {@link BlockTitle}'s job. That split is what
 * makes the layout arithmetic — glyph advance, string width, the position of
 * the last column — testable in a headless JVM, which is where CI runs. A
 * measurement bug here would put the title off-centre or clip its final letter,
 * and neither is visible to anything but a human looking at a window.</p>
 *
 * <h2>Coordinates</h2>
 *
 * <p>Cells are reported as {@code (column, row)} with <b>row 0 at the top</b>,
 * because that is how the glyph table below reads on screen. A drawer working
 * in a y-up coordinate system flips it; doing that once at the drawing site is
 * clearer than authoring every letter upside down.</p>
 */
public final class BlockFont
{
    /** Cells across one glyph. */
    public static final int GLYPH_WIDTH = 5;

    /** Cells down one glyph. */
    public static final int GLYPH_HEIGHT = 7;

    /**
     * Blank cells between adjacent glyphs — <b>1</b>.
     *
     * <p>One rather than two: at 5 cells wide a two-cell gap makes a word read
     * as separate letters rather than as a word. The space character supplies
     * the wider gap where one is actually wanted.</p>
     */
    public static final int GLYPH_SPACING = 1;

    /** How many cells a glyph plus its trailing gap occupies. */
    public static final int GLYPH_ADVANCE = GLYPH_WIDTH + GLYPH_SPACING;

    /** Marks a set cell in the glyph table. */
    private static final char ON = '#';

    /**
     * The alphabet, seven rows of five per glyph, top row first.
     *
     * <p>Written as art rather than as bitmasks so a reader can see the letter.
     * The conversion to a bit-per-cell representation happens once, in the
     * static initialiser — authoring hexadecimal here would make a typo
     * invisible until someone rendered the word containing it.</p>
     */
    private static final String[][] GLYPH_ART =
    {
        {"A", ".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"},
        {"B", "####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."},
        {"C", ".####", "#....", "#....", "#....", "#....", "#....", ".####"},
        {"D", "####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."},
        {"E", "#####", "#....", "#....", "####.", "#....", "#....", "#####"},
        {"F", "#####", "#....", "#....", "####.", "#....", "#....", "#...."},
        {"G", ".###.", "#...#", "#....", "#..##", "#...#", "#...#", ".###."},
        {"H", "#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"},
        {"I", "#####", "..#..", "..#..", "..#..", "..#..", "..#..", "#####"},
        {"J", "..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##.."},
        {"K", "#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"},
        {"L", "#....", "#....", "#....", "#....", "#....", "#....", "#####"},
        {"M", "#...#", "##.##", "#.#.#", "#...#", "#...#", "#...#", "#...#"},
        {"N", "#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"},
        {"O", ".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."},
        {"P", "####.", "#...#", "#...#", "####.", "#....", "#....", "#...."},
        {"Q", ".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"},
        {"R", "####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"},
        {"S", ".####", "#....", "#....", ".###.", "....#", "....#", "####."},
        {"T", "#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."},
        {"U", "#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."},
        {"V", "#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."},
        {"W", "#...#", "#...#", "#...#", "#...#", "#.#.#", "##.##", "#...#"},
        {"X", "#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"},
        {"Y", "#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."},
        {"Z", "#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"},
        {"0", ".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."},
        {"1", "..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."},
        {"2", ".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"},
        {"3", "#####", "...#.", "..##.", "....#", "....#", "#...#", ".###."},
        {"4", "...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."},
        {"5", "#####", "#....", "####.", "....#", "....#", "#...#", ".###."},
        {"6", "..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."},
        {"7", "#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."},
        {"8", ".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."},
        {"9", ".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."},
        {"-", ".....", ".....", ".....", "#####", ".....", ".....", "....."},
        {"!", "..#..", "..#..", "..#..", "..#..", "..#..", ".....", "..#.."},
        {" ", ".....", ".....", ".....", ".....", ".....", ".....", "....."},
    };

    /** Character to its seven row bitmasks, bit 0 being the leftmost cell. */
    private static final Map<Character, int[]> GLYPHS = buildGlyphs();

    private BlockFont()
    {
        // glyph table holder
    }

    /**
     * Receives one set cell at a time.
     *
     * <p>A callback rather than a returned collection because the caller draws
     * as it goes: a title is a few hundred cells, and materialising a list of
     * coordinate pairs per frame would allocate for nothing.</p>
     */
    public interface BlockSink
    {
        /**
         * Called once per set cell.
         *
         * @param column cell column from the left of the whole string, from 0
         * @param row cell row, <b>0 at the top</b>
         * @param glyphIndex which character of the string this cell belongs to,
         *     from 0 — so a drawer can colour per letter without re-parsing
         */
        void block(int column, int row, int glyphIndex);
    }

    /**
     * Returns whether a character can be drawn.
     *
     * @param character the character to test; case is ignored
     * @return true if the alphabet has a glyph for it
     */
    public static boolean isSupported(final char character)
    {
        return GLYPHS.containsKey(Character.toUpperCase(character));
    }

    /**
     * Returns how many cells wide a string is, including the gaps between
     * glyphs but not after the last one.
     *
     * <p>Excluding the trailing gap is what makes centring come out right. With
     * it included, every string is one cell wider than it looks and sits half a
     * cell left of centre — small, consistent, and exactly the sort of thing
     * nobody finds by looking.</p>
     *
     * @param text the string to measure; must not be null
     * @return the width in cells, zero for an empty string
     * @throws IllegalArgumentException if {@code text} is null or holds an
     *     unsupported character
     */
    public static int widthInBlocks(final String text)
    {
        requireDrawable(text);
        if (text.isEmpty())
        {
            return 0;
        }
        return text.length() * GLYPH_ADVANCE - GLYPH_SPACING;
    }

    /**
     * Walks every set cell of a string.
     *
     * @param text the string to draw; must not be null, case is ignored
     * @param sink receives each set cell; must not be null
     * @throws IllegalArgumentException if either argument is null, or if the
     *     text holds a character the alphabet has no glyph for
     */
    public static void forEachBlock(final String text, final BlockSink sink)
    {
        requireDrawable(text);
        if (sink == null)
        {
            throw new IllegalArgumentException("sink must not be null");
        }
        for (int glyphIndex = 0; glyphIndex < text.length(); glyphIndex++)
        {
            final int[] rows = GLYPHS.get(Character.toUpperCase(text.charAt(glyphIndex)));
            final int originColumn = glyphIndex * GLYPH_ADVANCE;
            for (int row = 0; row < GLYPH_HEIGHT; row++)
            {
                final int mask = rows[row];
                for (int cell = 0; cell < GLYPH_WIDTH; cell++)
                {
                    if ((mask & (1 << cell)) != 0)
                    {
                        sink.block(originColumn + cell, row, glyphIndex);
                    }
                }
            }
        }
    }

    // Rejects a null string, and one holding a character with no glyph. Failing
    // loudly here rather than silently skipping the character means a typo in a
    // menu label is a startup exception rather than a word with a hole in it.
    private static void requireDrawable(final String text)
    {
        if (text == null)
        {
            throw new IllegalArgumentException("text must not be null");
        }
        for (int index = 0; index < text.length(); index++)
        {
            if (!isSupported(text.charAt(index)))
            {
                throw new IllegalArgumentException("no block glyph for '"
                    + text.charAt(index) + "' in \"" + text + "\"");
            }
        }
    }

    // Turns the art table into one bitmask per row. Runs once.
    private static Map<Character, int[]> buildGlyphs()
    {
        final Map<Character, int[]> table = new HashMap<>();
        for (final String[] entry : GLYPH_ART)
        {
            final int[] rows = new int[GLYPH_HEIGHT];
            for (int row = 0; row < GLYPH_HEIGHT; row++)
            {
                final String art = entry[row + 1];
                if (art.length() != GLYPH_WIDTH)
                {
                    throw new IllegalStateException("glyph '" + entry[0] + "' row " + row
                        + " is " + art.length() + " cells, expected " + GLYPH_WIDTH);
                }
                int mask = 0;
                for (int cell = 0; cell < GLYPH_WIDTH; cell++)
                {
                    if (art.charAt(cell) == ON)
                    {
                        mask = mask | (1 << cell);
                    }
                }
                rows[row] = mask;
            }
            table.put(entry[0].charAt(0), rows);
        }
        return table;
    }
}
