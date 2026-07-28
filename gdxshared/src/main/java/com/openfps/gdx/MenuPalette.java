/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import com.badlogic.gdx.graphics.Color;

/**
 * The welcome screen's colours: bright, flat, and saturated, in the spirit of
 * the Kenney kits the game is built from without copying any of them.
 *
 * <h2>Flat, not shaded</h2>
 *
 * <p>Every colour here is used as a solid fill. No gradients, no transparency
 * over the background, no glow. That is what makes the screen read as blocks
 * rather than as a picture of blocks, and it is also what the software
 * rasterizer behind it does — the world is flat-shaded texels, so a menu full
 * of gradients would look like a different program bolted on.</p>
 *
 * <p><b>Each face colour has a shade</b>, and the shade is not computed from the
 * face by multiplying. Uniform darkening pushes saturated colours toward grey
 * and the bevel stops reading as the same material in shadow; each pair here
 * keeps its hue and drops value, which is what a real shadow does.</p>
 *
 * <h2>The one rule that is not taste</h2>
 *
 * <p>{@link #TITLE_CYCLE} is walked slowly — a full pass takes several
 * seconds — and no two adjacent entries are far apart in luminance. Fast,
 * high-contrast colour cycling is a genuine accessibility hazard, and it also
 * looks cheap. A menu should feel alive, not flash.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class MenuPalette
{
    /** The deep background the block grid sits on. */
    public static final Color BACKDROP = new Color(0.07f, 0.09f, 0.15f, 1f);

    /** The background grid's darker square, at the left of the field. */
    public static final Color GRID_DARK = new Color(0.10f, 0.13f, 0.24f, 1f);

    /** The background grid's lighter square, at the left of the field. */
    public static final Color GRID_LIGHT = new Color(0.14f, 0.18f, 0.31f, 1f);

    /**
     * The darker square at the right of the field.
     *
     * <p>The grid is not one colour. A flat navy field under a rainbow title
     * reads as a bright thing pasted onto a dull one; a slow gradient across the
     * whole width gives the background somewhere to go, without anything on it
     * moving fast enough to compete with the buttons.</p>
     *
     * <p>The pair drifts toward violet rather than toward a second hue chosen
     * for contrast — a gradient between two distant hues passes through
     * whatever is between them, and grey-green in the middle of a menu looks
     * like a bug.</p>
     */
    public static final Color GRID_DARK_FAR = new Color(0.17f, 0.11f, 0.26f, 1f);

    /** The lighter square at the right of the field. See {@link #GRID_DARK_FAR}. */
    public static final Color GRID_LIGHT_FAR = new Color(0.23f, 0.15f, 0.35f, 1f);

    /** The drifting highlight that crosses the background grid. */
    public static final Color GRID_PULSE = new Color(0.22f, 0.30f, 0.52f, 1f);

    /** Menu label text, on a coloured button face. */
    public static final Color BUTTON_LABEL = new Color(1f, 1f, 1f, 1f);

    /** Footer and hint text, quieter than a label. */
    public static final Color HINT = new Color(0.62f, 0.68f, 0.82f, 1f);

    /** Face of the primary action. */
    public static final Color PLAY_FACE = new Color(0.35f, 0.78f, 0.32f, 1f);

    /** Shade under the primary action. */
    public static final Color PLAY_SHADE = new Color(0.20f, 0.50f, 0.19f, 1f);

    /** Face of the multiplayer action. */
    public static final Color NET_FACE = new Color(0.26f, 0.60f, 0.90f, 1f);

    /** Shade under the multiplayer action. */
    public static final Color NET_SHADE = new Color(0.14f, 0.37f, 0.60f, 1f);

    /** Face of a neutral action. */
    public static final Color NEUTRAL_FACE = new Color(0.98f, 0.72f, 0.20f, 1f);

    /** Shade under a neutral action. */
    public static final Color NEUTRAL_SHADE = new Color(0.70f, 0.47f, 0.09f, 1f);

    /** Face of the quit action. */
    public static final Color QUIT_FACE = new Color(0.90f, 0.34f, 0.34f, 1f);

    /** Shade under the quit action. */
    public static final Color QUIT_SHADE = new Color(0.60f, 0.18f, 0.18f, 1f);

    /**
     * The colours the title cycles through, one per letter, drifting sideways.
     *
     * <p>Six entries, so a seven-letter title never shows the same colour twice
     * running — a repeat reads as two letters merging.</p>
     */
    public static final Color[] TITLE_CYCLE =
    {
        new Color(0.98f, 0.42f, 0.36f, 1f),
        new Color(0.98f, 0.72f, 0.24f, 1f),
        new Color(0.55f, 0.84f, 0.34f, 1f),
        new Color(0.30f, 0.80f, 0.72f, 1f),
        new Color(0.36f, 0.60f, 0.95f, 1f),
        new Color(0.76f, 0.48f, 0.92f, 1f),
    };

    /** The shadow cast under every title block, so letters read against the grid. */
    public static final Color TITLE_SHADOW = new Color(0.03f, 0.04f, 0.08f, 1f);

    private MenuPalette()
    {
        // colour table holder
    }
}
