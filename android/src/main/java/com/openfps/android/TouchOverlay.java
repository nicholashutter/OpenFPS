/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

/**
 * Draws the on-screen controls over the finished frame.
 *
 * <p>The counterpart to {@link TouchLayout}: that class decides where the
 * controls are, this one shows the player. They read the same numbers from the
 * same object, which is the point — a drawn button that is not where the touch
 * test thinks it is produces a control that visibly does nothing, and that is a
 * bug nobody can diagnose by looking.</p>
 *
 * <h2>One texture, and it is a circle</h2>
 *
 * <p>Every control is the same 128 px white disc, tinted and scaled. Generated
 * in code rather than shipped as a PNG, on the same principle as the desktop
 * welcome screen: no asset file means no asset pipeline, no density variants,
 * and nothing to go missing from an APK. A disc rather than the menu's 1x1
 * white pixel because these are round and a square would have to be masked
 * somehow anyway.</p>
 *
 * <p>The edge is antialiased by hand, one pixel wide, because a hard-edged
 * circle scaled to 92 dp on a 560 dpi screen is visibly jagged and libGDX's
 * linear filter alone does not fix it — the source is what is jagged.</p>
 *
 * <h2>Coordinates flip here, and only here</h2>
 *
 * <p>{@link TouchLayout} works in touch coordinates, y downward, because that
 * is what {@code InputProcessor} reports. {@link SpriteBatch} works in world
 * coordinates, y upward. The single conversion lives in {@link #drawDisc}, so
 * there is exactly one place to look when a control is drawn at the wrong end
 * of the screen.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Every method runs on the GL thread. GPU resources are created in
 * {@link #resize} rather than the constructor, because there is no context
 * until the surface exists. The texture is <b>managed</b> — built from a Pixmap
 * libGDX retains — so it survives Android destroying and rebuilding the EGL
 * context without this class doing anything, the same property
 * {@code MainMenuFrameCallback} relies on.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class TouchOverlay
{
    /** Side of the generated disc texture, in pixels. */
    public static final int DISC_TEXTURE_SIZE = 128;

    /** Opacity of an idle control. Low enough to see the world through. */
    public static final float IDLE_ALPHA = 0.28f;

    /** Opacity of a control under a finger. */
    public static final float PRESSED_ALPHA = 0.60f;

    /** Opacity of the movement stick's outer ring. */
    public static final float STICK_BASE_ALPHA = 0.22f;

    /** How much bigger the stick's base ring is than its knob. */
    public static final float STICK_BASE_SCALE = 1.0f;

    /** The knob's radius as a fraction of the stick's full travel. */
    public static final float KNOB_RADIUS_FRACTION = 0.42f;

    /** Fire button tint — the warm one, so it reads as the trigger at a glance. */
    private static final Color FIRE_TINT = new Color(1.0f, 0.42f, 0.24f, 1.0f);

    /** Jump button tint. */
    private static final Color JUMP_TINT = new Color(0.36f, 0.82f, 0.44f, 1.0f);

    /** Leave button tint — muted, because it is the one nobody should hit by accident. */
    private static final Color LEAVE_TINT = new Color(0.72f, 0.74f, 0.82f, 1.0f);

    /** Movement stick tint. */
    private static final Color STICK_TINT = new Color(0.55f, 0.68f, 1.0f, 1.0f);

    /** Geometry shared with the input port. Never null. */
    private final TouchLayout layout;

    /** Scratch colour, mutated per draw so no allocation happens per frame. */
    private final Color tint = new Color();

    /** Draws the discs. MUTABLE: built on the first resize, released by dispose. */
    private SpriteBatch batch;

    /** The white disc. MUTABLE: built on the first resize, released by dispose. */
    private Texture disc;

    /** The Pixmap the texture is managed from. MUTABLE: retained for context loss. */
    private Pixmap discPixels;

    /**
     * Creates an overlay over one control layout.
     *
     * @param touchLayout the geometry to draw; must not be null
     * @throws IllegalArgumentException if {@code touchLayout} is null
     */
    public TouchOverlay(final TouchLayout touchLayout)
    {
        if (touchLayout == null)
        {
            throw new IllegalArgumentException("touchLayout must not be null");
        }
        this.layout = touchLayout;
    }

    /**
     * Builds the GPU resources if needed and sizes the projection.
     *
     * @param width surface width in pixels
     * @param height surface height in pixels
     */
    public void resize(final int width, final int height)
    {
        if (width <= 0 || height <= 0)
        {
            return;
        }
        if (batch == null)
        {
            batch = new SpriteBatch();
        }
        if (disc == null)
        {
            discPixels = buildDisc(DISC_TEXTURE_SIZE);
            disc = new Texture(discPixels);
            disc.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        }
        batch.getProjectionMatrix().setToOrtho2D(0.0f, 0.0f, width, height);
    }

    /**
     * Draws every control, showing which are held.
     *
     * <p>A no-op before the first {@link #resize}. Blending is on and left on:
     * unlike the world quad these are translucent, which is what lets the
     * player see the room through their own thumb rest.</p>
     *
     * @param input the port that knows which controls are held and where the
     *     stick is; must not be null
     */
    public void render(final AndroidInputPort input)
    {
        if (batch == null || disc == null || layout.width() <= 0.0f)
        {
            return;
        }
        batch.begin();
        drawStick(input);
        drawDisc(layout.fireCentreX(), layout.fireCentreY(), layout.fireRadius(),
            FIRE_TINT, alphaFor(input.isFireHeld()));
        drawDisc(layout.jumpCentreX(), layout.jumpCentreY(), layout.jumpRadius(),
            JUMP_TINT, alphaFor(input.isJumpHeld()));
        drawDisc(layout.leaveCentreX(), layout.leaveCentreY(), layout.leaveRadius(),
            LEAVE_TINT, IDLE_ALPHA);
        batch.end();
    }

    /** Releases the batch and the disc texture. */
    public void dispose()
    {
        if (disc != null)
        {
            disc.dispose();
            disc = null;
        }
        if (discPixels != null)
        {
            discPixels.dispose();
            discPixels = null;
        }
        if (batch != null)
        {
            batch.dispose();
            batch = null;
        }
    }

    // The floating stick: a faint ring where the thumb landed, and a knob
    // showing how far it has been pushed. Nothing is drawn when no thumb is
    // down — a stick painted at a fixed spot the player is not touching is
    // clutter over the one part of the screen they are trying to walk through.
    private void drawStick(final AndroidInputPort input)
    {
        final int pointer = input.stickPointer();
        if (pointer < 0)
        {
            return;
        }
        final float centreX = input.anchorXOf(pointer);
        final float centreY = input.anchorYOf(pointer);
        final float range = layout.stickRange();
        drawDisc(centreX, centreY, range * STICK_BASE_SCALE, STICK_TINT, STICK_BASE_ALPHA);

        final float knobX = centreX + layout.knobOffset(centreX, input.currentXOf(pointer),
            centreY, input.currentYOf(pointer));
        final float knobY = centreY + layout.knobOffset(centreY, input.currentYOf(pointer),
            centreX, input.currentXOf(pointer));
        drawDisc(knobX, knobY, range * KNOB_RADIUS_FRACTION, STICK_TINT, PRESSED_ALPHA);
    }

    // One disc, converting from touch coordinates (y down) to world
    // coordinates (y up). The ONLY place that conversion happens.
    private void drawDisc(final float centreX, final float touchCentreY, final float radius,
        final Color colour, final float alpha)
    {
        tint.set(colour.r, colour.g, colour.b, alpha);
        batch.setColor(tint);
        final float worldCentreY = layout.height() - touchCentreY;
        batch.draw(disc, centreX - radius, worldCentreY - radius, radius * 2.0f, radius * 2.0f);
    }

    private static float alphaFor(final boolean held)
    {
        if (held)
        {
            return PRESSED_ALPHA;
        }
        return IDLE_ALPHA;
    }

    /**
     * Builds a white disc with a one-pixel soft edge.
     *
     * <p>Package-visible so a test can assert the shape — that the centre is
     * opaque, the corners are clear, and the edge is neither — without a GL
     * context. {@code Pixmap} needs libGDX's natives but not a context, which
     * is the same seam {@code PixmapByteOrderTest} uses on desktop.</p>
     *
     * @param size the texture's side in pixels; must be positive and even
     * @return the pixmap; the caller owns it and must dispose it
     */
    static Pixmap buildDisc(final int size)
    {
        final Pixmap pixmap = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        pixmap.setBlending(Pixmap.Blending.None);
        // Doubled coordinates, for the reason WindowIcon documents at length:
        // an even-sized image has no centre pixel, so measuring from size / 2
        // biases the disc half a pixel and leaves it visibly off-centre at
        // small sizes. Working in units of half a pixel removes the question.
        final float centre = size - 1;
        final float radius = size;
        for (int y = 0; y < size; y++)
        {
            for (int x = 0; x < size; x++)
            {
                final float dx = (2 * x) - centre;
                final float dy = (2 * y) - centre;
                final float distance = (float) Math.sqrt((dx * dx) + (dy * dy));
                pixmap.drawPixel(x, y, discPixel(distance, radius));
            }
        }
        return pixmap;
    }

    // One pixel of the disc: opaque inside, clear outside, and a two-unit —
    // one real pixel — ramp between so the rim is not a staircase.
    private static int discPixel(final float distance, final float radius)
    {
        if (distance >= radius)
        {
            return 0;
        }
        final float edge = radius - 2.0f;
        if (distance <= edge)
        {
            return 0xFFFFFFFF;
        }
        final float coverage = (radius - distance) / 2.0f;
        final int alpha = Math.round(coverage * 255.0f);
        return 0xFFFFFF00 | (alpha & 0xFF);
    }
}
