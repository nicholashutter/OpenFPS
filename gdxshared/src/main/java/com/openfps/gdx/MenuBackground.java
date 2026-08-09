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
 * The welcome screen's backdrop: a checkerboard of cubes with a bright band
 * drifting diagonally across it.
 *
 * <p>The checker is the game's own floor, seen flat. The band is what stops the
 * screen being a static texture — it travels on the diagonal, so it crosses the
 * whole field rather than sweeping one axis, and it is the only thing on the
 * menu that moves apart from the title's colour cycle.</p>
 *
 * <h2>Deliberately quiet</h2>
 *
 * <p>The band is a <b>partial</b> blend toward {@link MenuPalette#GRID_PULSE},
 * peaking well short of it, and it takes several seconds to cross. A menu
 * background that pulses hard competes with the buttons for attention and is
 * genuinely unpleasant to sit in front of while reading four labels. This is
 * meant to be noticed once and then ignored.</p>
 *
 * <h2>Cost</h2>
 *
 * <p>One {@code batch.draw} per cell of a grid sized to the window: about 900
 * quads at 1280x720 with a 40-pixel cell. That is nothing for a GPU and it
 * happens only in {@link UiState#MENU}, where the software rasterizer is not
 * running at all — the menu owns the whole window in that state, so there is no
 * frame being composited underneath and no budget being competed for.</p>
 *
 * <b>Threading:</b> constructed and drawn on the platform render thread only.
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class MenuBackground extends Actor
{
    /** Side of one grid cell in pixels. */
    public static final float CELL_PIXELS = 40.0f;

    /** How fast the bright band travels, in cells per second. */
    public static final float BAND_CELLS_PER_SECOND = 3.5f;

    /** How many cells the band spans from its centre to nothing. */
    public static final float BAND_HALF_WIDTH_CELLS = 5.0f;

    /**
     * How far toward {@link MenuPalette#GRID_PULSE} the band's centre reaches.
     *
     * <p>Not 1. At full strength the band is a bright stripe that pulls the eye
     * off the buttons every few seconds; at 0.55 it reads as a sheen.</p>
     */
    public static final float BAND_STRENGTH = 0.55f;

    /** How many cells the band's cycle covers before it repeats. */
    private static final float BAND_PERIOD_CELLS = 46.0f;

    /** The 1x1 white pixel every cell is drawn from. */
    private final TextureRegion pixel;

    /** Scratch colour, reused so drawing allocates nothing per frame. */
    private final Color scratch = new Color();

    /** Seconds since construction. MUTABLE: advanced by {@link #act}. */
    private float elapsedSeconds;

    /**
     * Creates the backdrop.
     *
     * @param whitePixel a 1x1 white region to tint per cell; must not be null
     */
    public MenuBackground(final TextureRegion whitePixel)
    {
        if (whitePixel == null)
        {
            throw new IllegalArgumentException("whitePixel must not be null");
        }

        this.pixel = whitePixel;
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
        final Color previous = batch.getColor();

        final int columns = (int) StrictMath.ceil(getWidth() / CELL_PIXELS);

        final int rows = (int) StrictMath.ceil(getHeight() / CELL_PIXELS);

        for (int column = 0; column < columns; column++)
        {
            for (int row = 0; row < rows; row++)
            {
                batch.setColor(cellColour(column, row));

                batch.draw(pixel, getX() + column * CELL_PIXELS, getY() + row * CELL_PIXELS,
                    CELL_PIXELS, CELL_PIXELS);
            }
        }

        batch.setColor(previous);
    }

    /**
     * Returns the colour of one grid cell right now.
     *
     * <p>Exposed so a headless test can assert the two things that would be
     * wrong in a way nobody notices: that the checker really alternates, and
     * that the band never leaves the palette it blends between.</p>
     *
     * @param column cell column from the left, from 0
     * @param row cell row from the bottom, from 0
     * @return the tint for that cell
     */
    public Color cellColour(final int column, final int row)
    {
        final boolean light = (column + row) % 2 == 0;

        final float across = gradientAt(column);

        if (light)
        {
            scratch.set(MenuPalette.GRID_LIGHT);

            scratch.lerp(MenuPalette.GRID_LIGHT_FAR, across);
        }
        else
        {
            scratch.set(MenuPalette.GRID_DARK);

            scratch.lerp(MenuPalette.GRID_DARK_FAR, across);
        }

        final float strength = bandStrengthAt(column, row);

        if (strength > 0.0f)
        {
            scratch.lerp(MenuPalette.GRID_PULSE, strength);
        }

        return scratch;
    }

    /**
     * Returns how far across the field a column is, 0 at the left edge and 1 at
     * the right.
     *
     * <p>Drives the grid's hue gradient. Clamped rather than allowed to run
     * past 1, because {@code Color.lerp} does not clamp and a factor above 1
     * extrapolates <i>past</i> the far colour into whatever lies beyond it —
     * which for a saturated violet is a channel above 1 and, once the GPU
     * clamps that, a different hue entirely. That would show up as a stripe of
     * the wrong colour down the right edge on any window whose width the layout
     * had not been told about.</p>
     *
     * @param column cell column from the left, from 0
     * @return the gradient factor in {@code [0, 1]}
     */
    public float gradientAt(final int column)
    {
        final float width = getWidth();

        if (width <= 0.0f)
        {
            // No bounds yet — before the first layout. Left edge is as good an
            // answer as any and is the one that never extrapolates.
            return 0.0f;
        }

        final float across = column * CELL_PIXELS / width;

        if (across <= 0.0f)
        {
            return 0.0f;
        }

        if (across >= 1.0f)
        {
            return 1.0f;
        }

        return across;
    }

    /**
     * Returns how strongly the drifting band covers one cell, 0 to
     * {@link #BAND_STRENGTH}.
     *
     * @param column cell column from the left, from 0
     * @param row cell row from the bottom, from 0
     * @return the blend factor toward {@link MenuPalette#GRID_PULSE}
     */
    public float bandStrengthAt(final int column, final int row)
    {
        // Diagonal: the band's coordinate is column + row, so it sweeps
        // corner to corner rather than down one axis.
        final float diagonal = column + row;

        final float head = elapsedSeconds * BAND_CELLS_PER_SECOND;

        float distance = (diagonal - head) % BAND_PERIOD_CELLS;

        if (distance < 0.0f)
        {
            distance = distance + BAND_PERIOD_CELLS;
        }

        // Measure from the nearer side of the wrap, so the band is continuous
        // across the seam instead of vanishing and reappearing.
        final float fromCentre = StrictMath.min(distance, BAND_PERIOD_CELLS - distance);

        if (fromCentre >= BAND_HALF_WIDTH_CELLS)
        {
            return 0.0f;
        }

        final float falloff = 1.0f - fromCentre / BAND_HALF_WIDTH_CELLS;

        return BAND_STRENGTH * falloff;
    }

    /** Returns seconds since construction, as accumulated by {@link #act}. */
    public float elapsedSeconds()
    {
        return elapsedSeconds;
    }
}
