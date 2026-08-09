/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;

/**
 * A chunky menu button: a bright face sitting on a darker base, which drops onto
 * it when pressed.
 *
 * <p>The look every blocky game UI uses, and it is one idea: draw the button
 * twice, the shade at the bottom and the face above it, and move the face down
 * when the button is held. Nothing is shaded, nothing is rounded, and the
 * "press" is a real two-pixel displacement rather than a colour change — so the
 * button reads as a physical key being pushed, which is the whole point.</p>
 *
 * <h2>Why not a Scene2D TextButton</h2>
 *
 * <p>{@code TextButton} with a tinted 1x1 drawable gives a flat rectangle that
 * changes colour on hover, and getting the raised face out of it means a nine-
 * patch, which means an atlas, which means an asset file. The menu deliberately
 * has <b>zero external asset dependencies</b> — see {@link MainMenuScreen} — so
 * a fifty-line actor that draws two rectangles is the cheaper answer as well as
 * the better-looking one.</p>
 *
 * <p>The click handling is still Scene2D's: {@link ClickListener} already knows
 * about press, release-outside, hover and touch, and reimplementing that is
 * exactly the kind of thing that works until someone drags off a button.</p>
 *
 * <b>Threading:</b> constructed and drawn on the platform render thread only.
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class BlockButton extends Actor
{
    /**
     * How tall the base is under the face, in pixels — the depth of the key.
     *
     * <p>Six: enough that the press is unmistakable at a glance, small enough
     * that the face still dominates. This is subtracted from the actor's height,
     * so a button's overall footprint is what the layout asked for rather than
     * that plus a bevel.</p>
     */
    public static final float DEPTH_PIXELS = 6.0f;

    /** How far the face lifts when the pointer is over it. */
    public static final float HOVER_LIFT_PIXELS = 2.0f;

    /**
     * What the button says.
     *
     * <p>MUTABLE: replaced by {@link #setLabel}, on the render thread only. A
     * button whose text is part of its state — "DEBUG OVERLAY  ON" — has to be
     * able to say the other thing, and relabelling one actor is cheaper and
     * clearer than swapping between two that differ only in a word.</p>
     */
    private String label;

    /** The bright top surface. */
    private final Color faceColor;

    /** The darker base beneath it. */
    private final Color shadeColor;

    /** The 1x1 white pixel both rectangles are drawn from. */
    private final TextureRegion pixel;

    /** The font the label is drawn in. */
    private final BitmapFont font;

    /** How far the label is magnified over the font's native size. */
    private final float labelScale;

    /** Reused label measurement, so drawing allocates nothing per frame. */
    private final GlyphLayout layout = new GlyphLayout();

    /** Scene2D's press and hover tracking. */
    private final ClickListener clicks;

    /**
     * Creates a button.
     *
     * @param text what the button says; must not be null
     * @param face the bright top surface; must not be null
     * @param shade the darker base; must not be null
     * @param whitePixel a 1x1 white region to tint; must not be null
     * @param labelFont the font for the label; must not be null
     * @param fontScale how far to magnify the label over the font's native
     *     size; must be positive
     * @param action run once per activation; must not be null
     */
    public BlockButton(final String text, final Color face, final Color shade,
        final TextureRegion whitePixel, final BitmapFont labelFont, final float fontScale,
        final Runnable action)
    {
        if (text == null || face == null || shade == null || whitePixel == null
            || labelFont == null || action == null)
        {
            throw new IllegalArgumentException("BlockButton takes no null arguments");
        }

        if (!(fontScale > 0.0f))
        {
            throw new IllegalArgumentException(
                "fontScale must be positive and a number, got " + fontScale);
        }

        this.labelScale = fontScale;

        this.label = text;

        this.faceColor = face;

        this.shadeColor = shade;

        this.pixel = whitePixel;

        this.font = labelFont;

        this.clicks = new ClickListener()
        {
            @Override
            public void clicked(final InputEvent event, final float x, final float y)
            {
                action.run();
            }
        };

        addListener(clicks);
    }

    /** Returns the button's label. Never null. */
    public String label()
    {
        return label;
    }

    /**
     * Changes what the button says.
     *
     * <p>The label is re-measured on the next draw, so nothing else has to be
     * told. Sizing is not: the actor keeps the bounds the layout gave it, which
     * is what stops a toggle from resizing itself as its text changes length.</p>
     *
     * @param text the new label; must not be null
     * @throws IllegalArgumentException if {@code text} is null
     */
    public void setLabel(final String text)
    {
        if (text == null)
        {
            throw new IllegalArgumentException("text must not be null");
        }

        this.label = text;
    }

    /** Returns true while the pointer is held down on this button. */
    public boolean isPressed()
    {
        return clicks.isPressed();
    }

    /** Returns true while the pointer is over this button. */
    public boolean isHovered()
    {
        return clicks.isOver();
    }

    /**
     * Returns how far the face sits above the base right now, in pixels.
     *
     * <p>Exposed because it is the whole of the button's behaviour and the one
     * part a headless test can reach — the drawing needs a GL context, the
     * decision does not.</p>
     *
     * @return {@link #DEPTH_PIXELS} at rest, more when hovered, zero when
     *     pressed flat
     */
    public float faceOffset()
    {
        if (isPressed())
        {
            // Flat onto the base. The face occupies the space the shade was
            // showing, which is what makes a press look like a press rather
            // than like the button shrinking.
            return 0.0f;
        }

        if (isHovered())
        {
            return DEPTH_PIXELS + HOVER_LIFT_PIXELS;
        }

        return DEPTH_PIXELS;
    }

    @Override
    public void draw(final Batch batch, final float parentAlpha)
    {
        final Color previous = batch.getColor();

        final float width = getWidth();

        final float faceHeight = getHeight() - DEPTH_PIXELS;

        final float lift = faceOffset();

        // The base is drawn full height so there is always something solid under
        // a lifted face; otherwise hovering opens a gap onto the background.
        batch.setColor(shadeColor);

        batch.draw(pixel, getX(), getY(), width, getHeight());

        batch.setColor(faceColor);

        batch.draw(pixel, getX(), getY() + lift, width, faceHeight);

        drawLabel(batch, width, faceHeight, lift);

        batch.setColor(previous);
    }

    // Centres the label on the face at this button's own scale.
    //
    // The scale is set on the shared BitmapFont and put back afterwards, rather
    // than each button owning a font of its own. One font is one texture; four
    // would be four copies of the same glyph atlas for four different
    // magnifications of it. The save/restore is what keeps the sharing safe —
    // without it, the last button drawn would leave its scale on the font and
    // the tagline and footer would silently render at button size.
    private void drawLabel(final Batch batch, final float width, final float faceHeight,
        final float lift)
    {
        final float scaleX = font.getData().scaleX;

        final float scaleY = font.getData().scaleY;

        font.getData().setScale(labelScale);

        try
        {
            layout.setText(font, label);

            font.setColor(MenuPalette.BUTTON_LABEL);

            font.draw(batch, layout,
                getX() + (width - layout.width) * 0.5f,
                getY() + lift + (faceHeight + layout.height) * 0.5f);
        }
        finally
        {
            font.getData().setScale(scaleX, scaleY);
        }
    }
}
