/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Actor;

/**
 * The welcome screen's title: a word built out of coloured cubes, drifting
 * through {@link MenuPalette#TITLE_CYCLE}.
 *
 * <p>One actor, not one per cell. A seven-letter title is around two hundred
 * set cells and each is a single {@code batch.draw} of the shared white pixel —
 * cheap, and cheaper than two hundred Scene2D actors each with its own
 * transform, hit box and layout pass, none of which a title needs.</p>
 *
 * <h2>The gap between cells is the point</h2>
 *
 * <p>Each cell is drawn slightly smaller than its slot, so the blocks read as
 * separate cubes stacked into a letter rather than as a solid shape. Without
 * the gap this is just a very low-resolution bitmap font; with it, the title is
 * made of the same thing the level is.</p>
 *
 * <h2>Colour drifts along the word, not with time alone</h2>
 *
 * <p>The palette index is {@code letter + elapsed}, so the colours travel
 * <i>through</i> the word rather than the whole title changing at once. A
 * simultaneous change reads as a flash; a travelling one reads as motion, and
 * it costs the same. {@link MenuPalette} explains why the cycle is slow.</p>
 *
 * <b>Threading:</b> constructed and drawn on the platform render thread only.
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class BlockTitle extends Actor
{
    /**
     * Fraction of a cell left empty on each side, so cubes read as cubes.
     *
     * <p>0.12 rather than something larger: at a quarter the letters start to
     * come apart at a glance, and the eye has to reassemble them.</p>
     */
    public static final float CELL_GAP_FRACTION = 0.12f;

    /**
     * How far the drop shadow sits below and right of a block, as a fraction of
     * a cell.
     *
     * <p>The title sits on a patterned background, and a flat word on a
     * patterned field loses its edges wherever the two happen to be close in
     * value. The shadow is what keeps the letters legible over every part of
     * the drifting grid rather than most of it.</p>
     */
    public static final float SHADOW_OFFSET_FRACTION = 0.22f;

    /** Palette entries advanced per second. A full six-colour pass takes 4 s. */
    public static final float CYCLE_PER_SECOND = 1.5f;

    /** The word, uppercase, in {@link BlockFont}'s alphabet. */
    private final String text;

    /**
     * The one colour every letter takes, or null to cycle the palette.
     *
     * <p>Non-null is for a heading whose colour <b>means</b> something —
     * {@code GameOverScreen} draws VICTORY green and DEFEAT red, and a rainbow
     * would be actively misleading there: the drift would carry the word through
     * the other outcome's colour twice a pass. The welcome screen's title has no
     * such meaning to carry, so it cycles.</p>
     */
    private final Color fixedColour;

    /** The 1x1 white pixel every block is drawn from. */
    private final TextureRegion pixel;

    /** Width of the word in cells, cached — it never changes. */
    private final int widthInBlocks;

    /** Seconds since construction. MUTABLE: advanced by {@link #act}. */
    private float elapsedSeconds;

    /** Scratch colour, reused so drawing allocates nothing per frame. */
    private final Color scratch = new Color();

    /**
     * Creates a title that drifts through {@link MenuPalette#TITLE_CYCLE}.
     *
     * @param word the text to draw; must not be null and must contain only
     *     characters {@link BlockFont} has glyphs for
     * @param whitePixel a 1x1 white region to tint per block; must not be null
     */
    public BlockTitle(final String word, final TextureRegion whitePixel)
    {
        this(word, whitePixel, null);
    }

    /**
     * Creates a title, optionally pinned to one colour.
     *
     * @param word the text to draw; must not be null and must contain only
     *     characters {@link BlockFont} has glyphs for
     * @param whitePixel a 1x1 white region to tint per block; must not be null
     * @param colour the single colour every letter takes, or null to drift
     *     through the palette. Copied rather than held, so a caller handing over
     *     a shared palette constant cannot have it mutated underneath them by
     *     this actor's scratch colour
     */
    public BlockTitle(final String word, final TextureRegion whitePixel, final Color colour)
    {
        if (whitePixel == null)
        {
            throw new IllegalArgumentException("whitePixel must not be null");
        }

        // Measuring here rather than at draw time is what makes an unsupported
        // character a construction failure instead of a word with a hole in it
        // sixty times a second.
        this.widthInBlocks = BlockFont.widthInBlocks(word);

        this.text = word;

        this.pixel = whitePixel;

        if (colour == null)
        {
            this.fixedColour = null;
        }
        else
        {
            this.fixedColour = new Color(colour);
        }
    }

    /** Returns the word this title draws. */
    public String text()
    {
        return text;
    }

    /** Returns the word's width in {@link BlockFont} cells. */
    public int widthInBlocks()
    {
        return widthInBlocks;
    }

    /**
     * Returns the cell size that fits this title into a given width.
     *
     * @param availableWidth the width to fit into, in pixels
     * @return the size of one cell in pixels, never negative
     */
    public float cellSizeFor(final float availableWidth)
    {
        if (widthInBlocks <= 0)
        {
            return 0.0f;
        }

        return availableWidth / widthInBlocks;
    }

    @Override
    public void act(final float deltaSeconds)
    {
        super.act(deltaSeconds);

        this.elapsedSeconds = elapsedSeconds + deltaSeconds;
    }

    @Override
    public void draw(final Batch batch, final float parentAlpha)
    {
        final float cell = getWidth() / widthInBlocks;

        final float inset = cell * CELL_GAP_FRACTION;

        final float block = cell - inset * 2.0f;

        final float shadow = cell * SHADOW_OFFSET_FRACTION;

        final float left = getX();

        final float top = getY() + getHeight();

        final Color previous = batch.getColor();

        // Every shadow first, then every face. Two passes rather than
        // shadow-then-face per cell, because otherwise a block's own shadow is
        // drawn over the face of the block below and left of it, and the word
        // acquires a dark seam through the middle of every letter.
        BlockFont.forEachBlock(text, (column, row, glyph) ->
        {
            batch.setColor(MenuPalette.TITLE_SHADOW);

            batch.draw(pixel, left + column * cell + inset + shadow,
                top - (row + 1) * cell + inset - shadow, block, block);
        });

        BlockFont.forEachBlock(text, (column, row, glyph) ->
        {
            batch.setColor(colourFor(glyph));

            batch.draw(pixel, left + column * cell + inset,
                top - (row + 1) * cell + inset, block, block);
        });

        batch.setColor(previous);
    }

    /**
     * Returns the colour one letter carries right now.
     *
     * <p>Exposed so a test can assert the cycle advances and stays inside the
     * palette without a GL context — the drawing cannot be checked headlessly,
     * but the choice can.</p>
     *
     * @param glyphIndex which letter, from 0
     * @return the palette colour for that letter at the current time, or the
     *     fixed colour this title was pinned to
     */
    public Color colourFor(final int glyphIndex)
    {
        if (fixedColour != null)
        {
            return fixedColour;
        }

        final int cycle = MenuPalette.TITLE_CYCLE.length;

        // floorMod, written out: the index must stay in range for a negative
        // glyph index as readily as a positive one, and % keeps the dividend's
        // sign.
        int slot = (glyphIndex + (int) (elapsedSeconds * CYCLE_PER_SECOND)) % cycle;

        if (slot < 0)
        {
            slot = slot + cycle;
        }

        scratch.set(MenuPalette.TITLE_CYCLE[slot]);

        return scratch;
    }

    /** Returns seconds since construction, as accumulated by {@link #act}. */
    public float elapsedSeconds()
    {
        return elapsedSeconds;
    }
}
