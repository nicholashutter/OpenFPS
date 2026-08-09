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
 * Draws the on-screen control pad over the finished frame.
 *
 * <p>The counterpart to {@link TouchLayout}: that class decides where the
 * controls are, this one shows the player. They read the same numbers from the
 * same object, which is the point — a drawn button that is not where the touch
 * test thinks it is produces a control that visibly does nothing, and that is a
 * bug nobody can diagnose by looking. This class does not compute a single
 * position; it asks {@link TouchLayout#buttonCentreX(int)} and friends, and it
 * walks {@link TouchLayout#buttonRegions()} rather than naming the buttons a
 * second time.</p>
 *
 * <h2>Three shapes, all generated, none of them a file</h2>
 *
 * <p>A filled <b>disc</b>, a <b>ring</b>, and one <b>glyph</b> per button —
 * a crosshair, a chevron, a cross. All built in code at first resize on the
 * same principle as the desktop welcome screen and the window icon: no asset
 * file means no asset pipeline, no density variants, and nothing to go missing
 * from an APK. It also means the shapes are described where they are used, as
 * a handful of line segments, instead of in a drawing program nobody has.</p>
 *
 * <p>Every edge is antialiased by hand, one pixel wide, because a hard-edged
 * circle scaled to 92 dp on a 560 dpi screen is visibly jagged and libGDX's
 * linear filter alone does not fix it — the source is what is jagged.</p>
 *
 * <h2>Why a button is four sprites and not one</h2>
 *
 * <p>The old overlay was a single translucent white disc per control, and it
 * had the failure a HUD over a first-person game always has: over a bright wall
 * it disappeared. Each button is now, from the back:</p>
 *
 * <ol>
 *   <li>a <b>dark halo</b> slightly larger than the button, which is what makes
 *       it readable against a bright scene — the light parts of the button have
 *       something dark to sit on no matter what is behind them;</li>
 *   <li>a <b>tinted fill</b>, and the tint is the button's identity: warm for
 *       fire, green for jump, grey for leave, so a thumb finds the right one
 *       without reading it;</li>
 *   <li>a <b>near-white rim</b>, drawn at exactly the radius the finger is
 *       tested against, so the edge the player aims at is the edge that
 *       works;</li>
 *   <li>a <b>glyph</b>, which is what stops three coloured circles from being
 *       three coloured circles.</li>
 * </ol>
 *
 * <p>That is about fourteen quads a frame including the stick. It is not worth
 * an atlas: the texture switches cost four extra draw calls out of a frame that
 * has already rasterized the world in software.</p>
 *
 * <h2>Pressed state</h2>
 *
 * <p>A phone has no click and no key travel, so a press that changes nothing on
 * screen cannot be told from a miss — and the player's next move is to press
 * harder, which also does nothing. Every layer of a held button therefore
 * changes at once: it grows by {@link TouchLayout#PRESSED_SCALE}, the fill and
 * rim go opaque, and the glyph brightens. The growth is drawn only; the hit
 * radius never moves, or a held button would start stealing its neighbour from
 * a second finger.</p>
 *
 * <h2>Coordinates flip here, and only here</h2>
 *
 * <p>{@link TouchLayout} works in touch coordinates, y downward, because that
 * is what {@code InputProcessor} reports. {@link SpriteBatch} works in world
 * coordinates, y upward. The single conversion lives in {@link #drawSprite}, so
 * there is exactly one place to look when a control is drawn at the wrong end
 * of the screen.</p>
 *
 * <h2>Lifecycle</h2>
 *
 * <p>Every method runs on the GL thread. GPU resources are created in
 * {@link #resize} rather than the constructor, because there is no context
 * until the surface exists. The textures are <b>managed</b> — built from
 * Pixmaps libGDX retains — so they survive Android destroying and rebuilding
 * the EGL context without this class doing anything, the same property
 * {@code MainMenuFrameCallback} relies on.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class TouchOverlay
{
    /** Side of the generated disc and ring textures, in pixels. */
    public static final int DISC_TEXTURE_SIZE = 128;

    /** Side of a generated glyph texture, in pixels. */
    public static final int GLYPH_TEXTURE_SIZE = 96;

    /** Ring thickness as a fraction of its radius. About 4 dp on a real button. */
    private static final float RING_THICKNESS_FRACTION = 0.10f;

    /** Glyph stroke half-width as a fraction of the glyph texture's side. */
    private static final float GLYPH_STROKE_FRACTION = 0.05f;

    /** How much of a button's radius the glyph occupies. */
    private static final float GLYPH_RADIUS_FRACTION = 0.62f;

    /** How far the dark halo extends past the shape it sits behind. */
    private static final float HALO_SPREAD = 1.09f;

    /** Opacity of the dark halo. Enough to anchor a button on a white wall. */
    private static final float HALO_ALPHA = 0.38f;

    /** Opacity of an idle button's tinted fill. Low enough to see the world through. */
    private static final float FILL_ALPHA = 0.30f;

    /** Opacity of a held button's fill. */
    private static final float FILL_PRESSED_ALPHA = 0.68f;

    /** Opacity of an idle rim — the one part that must always be findable. */
    private static final float RIM_ALPHA = 0.66f;

    /** Opacity of a held rim. */
    private static final float RIM_PRESSED_ALPHA = 1.0f;

    /** Opacity of an idle glyph. */
    private static final float GLYPH_ALPHA = 0.72f;

    /** Opacity of a held glyph. */
    private static final float GLYPH_PRESSED_ALPHA = 1.0f;

    /** Opacity of the dished field inside the stick's ring. */
    private static final float WELL_ALPHA = 0.20f;

    /** Fire button tint — the warm one, so it reads as the trigger at a glance. */
    private static final Color FIRE_TINT = new Color(1.0f, 0.42f, 0.24f, 1.0f);

    /** Jump button tint. */
    private static final Color JUMP_TINT = new Color(0.36f, 0.82f, 0.44f, 1.0f);

    /** Leave button tint — muted, because it is the one nobody should hit by accident. */
    private static final Color LEAVE_TINT = new Color(0.72f, 0.74f, 0.82f, 1.0f);

    /** Movement stick tint. */
    private static final Color STICK_TINT = new Color(0.55f, 0.68f, 1.0f, 1.0f);

    /** Rim and glyph colour — very slightly cool, which reads as white on a warm scene. */
    private static final Color RIM_TINT = new Color(0.94f, 0.96f, 1.0f, 1.0f);

    /** The halo. Not pure black: a hard black edge looks like a hole in the frame. */
    private static final Color HALO_TINT = new Color(0.04f, 0.05f, 0.08f, 1.0f);

    /**
     * The fire glyph — a crosshair, drawn as four ticks and a centre dot.
     *
     * <p>Segment endpoints as {@code x0,y0,x1,y1} quadruples in 0..1 of the
     * glyph texture, <b>y downward</b>, which is the Pixmap's own row order and
     * also {@link TouchLayout}'s convention — one less place to flip. A
     * zero-length segment is a dot, which is how the centre is drawn.</p>
     */
    private static final float[] FIRE_GLYPH =
    {
        0.50f, 0.08f, 0.50f, 0.30f,
        0.50f, 0.70f, 0.50f, 0.92f,
        0.08f, 0.50f, 0.30f, 0.50f,
        0.70f, 0.50f, 0.92f, 0.50f,
        0.50f, 0.50f, 0.50f, 0.50f,
    };

    /** The jump glyph — a chevron leaving the ground it is drawn above. */
    private static final float[] JUMP_GLYPH =
    {
        0.22f, 0.54f, 0.50f, 0.24f,
        0.50f, 0.24f, 0.78f, 0.54f,
        0.26f, 0.80f, 0.74f, 0.80f,
    };

    /** The leave glyph — a cross, the one symbol no player has to be taught. */
    private static final float[] LEAVE_GLYPH =
    {
        0.28f, 0.28f, 0.72f, 0.72f,
        0.72f, 0.28f, 0.28f, 0.72f,
    };

    /** The shape of a region with nothing to say. Draws nothing at all. */
    private static final float[] NO_GLYPH = {};

    /** Geometry shared with the input port. Never null. */
    private final TouchLayout layout;

    /**
     * The buttons to draw, in the layout's own order.
     *
     * <p>Copied once rather than asked for per frame:
     * {@link TouchLayout#buttonRegions()} hands out a fresh array so a caller
     * cannot edit the scheme, and taking that copy sixty times a second would
     * be garbage for no reason.</p>
     */
    private final int[] buttons = TouchLayout.buttonRegions();

    /** Scratch colour, mutated per draw so no allocation happens per frame. */
    private final Color tint = new Color();

    /** Draws the sprites. MUTABLE: built on the first resize, released by dispose. */
    private SpriteBatch batch;

    /** The white disc. MUTABLE: built on the first resize, released by dispose. */
    private Texture disc;

    /** The white ring. MUTABLE: built on the first resize, released by dispose. */
    private Texture ring;

    /** One glyph per entry of {@link #buttons}. MUTABLE: built on the first resize. */
    private Texture[] glyphs;

    /** The Pixmaps the textures are managed from. MUTABLE: retained for context loss. */
    private Pixmap[] sources;

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
            buildTextures();
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
     * <p>The stick goes down first so that a thumb dragged all the way across
     * the screen slides <i>under</i> the buttons rather than over them. Which
     * is the truth of it — the buttons are what it would be pressing.</p>
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

        for (int index = 0; index < buttons.length; index++)
        {
            drawButton(index, input.isHeld(buttons[index]));
        }

        batch.end();
    }

    /** Releases the batch and every generated texture. */
    public void dispose()
    {
        disposeTextures();

        if (batch != null)
        {
            batch.dispose();

            batch = null;
        }
    }

    // Every generated shape, built once. The glyph table is walked in the same
    // order as the button table so index i draws button i — the two arrays are
    // parallel and nothing else keeps them so.
    private void buildTextures()
    {
        glyphs = new Texture[buttons.length];

        sources = new Pixmap[buttons.length + 2];

        sources[0] = buildDisc(DISC_TEXTURE_SIZE);

        sources[1] = buildRing(DISC_TEXTURE_SIZE, RING_THICKNESS_FRACTION);

        disc = managed(sources[0]);

        ring = managed(sources[1]);

        for (int index = 0; index < buttons.length; index++)
        {
            sources[index + 2] = buildGlyph(GLYPH_TEXTURE_SIZE, glyphShapeFor(buttons[index]));

            glyphs[index] = managed(sources[index + 2]);
        }
    }

    // A texture that survives an EGL context loss, filtered so it does not
    // stair-step when a 128 px disc is stretched over a 250 px button.
    private static Texture managed(final Pixmap pixels)
    {
        final Texture texture = new Texture(pixels);

        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);

        return texture;
    }

    // Textures and the Pixmaps they are managed from, all of which are native
    // memory libGDX will not collect for us.
    private void disposeTextures()
    {
        if (disc != null)
        {
            disc.dispose();

            disc = null;
        }

        if (ring != null)
        {
            ring.dispose();

            ring = null;
        }

        if (glyphs != null)
        {
            for (int index = 0; index < glyphs.length; index++)
            {
                if (glyphs[index] != null)
                {
                    glyphs[index].dispose();
                }
            }

            glyphs = null;
        }

        if (sources != null)
        {
            for (int index = 0; index < sources.length; index++)
            {
                if (sources[index] != null)
                {
                    sources[index].dispose();
                }
            }

            sources = null;
        }
    }

    // One button: halo, fill, rim, glyph. Indexed rather than named so the
    // glyph beside it comes from the same position in the parallel array.
    private void drawButton(final int index, final boolean pressed)
    {
        final int region = buttons[index];

        final float centreX = layout.buttonCentreX(region);

        final float centreY = layout.buttonCentreY(region);

        final float radius = layout.drawnRadius(region, pressed);

        drawSprite(disc, centreX, centreY, radius * HALO_SPREAD, HALO_TINT, HALO_ALPHA);

        drawSprite(disc, centreX, centreY, radius, tintFor(region), fillAlpha(pressed));

        drawSprite(ring, centreX, centreY, radius, RIM_TINT, rimAlpha(pressed));

        drawSprite(glyphs[index], centreX, centreY, radius * GLYPH_RADIUS_FRACTION,
            RIM_TINT, glyphAlpha(pressed));
    }

    // The stick, wherever it currently is. Unlike the buttons it is drawn even
    // when untouched — at its resting place, faintly — because the first thing
    // a player does is look for the controls, and the previous version of this
    // file showed them nothing until they had already guessed right.
    private void drawStick(final AndroidInputPort input)
    {
        final int pointer = input.stickPointer();

        if (pointer < 0)
        {
            final float homeX = layout.stickHomeX();

            final float homeY = layout.stickHomeY();

            drawStickAt(homeX, homeY, homeX, homeY, false);

            return;
        }

        // Held: the ring is at the anchor the thumb made, not at the resting
        // place, because that anchor is what deflection is measured from and a
        // ring drawn anywhere else would be a lie about the control.
        final float anchorX = input.anchorXOf(pointer);

        final float anchorY = input.anchorYOf(pointer);

        final float currentX = input.currentXOf(pointer);

        final float currentY = input.currentYOf(pointer);

        drawStickAt(anchorX, anchorY,
            layout.knobCentreX(anchorX, anchorY, currentX, currentY),
            layout.knobCentreY(anchorX, anchorY, currentX, currentY),
            true);
    }

    // A base ring at the travel limit with a dark well inside it, and a thumb
    // that can never leave the ring because TouchLayout clamps it to the rim.
    private void drawStickAt(final float baseX, final float baseY, final float knobX,
        final float knobY, final boolean held)
    {
        final float range = layout.stickRange();

        drawSprite(disc, baseX, baseY, range, HALO_TINT, WELL_ALPHA);

        // The base ring was the one control with nothing behind its outer edge.
        // The well darkens the inside of it; outside is whatever the room
        // happens to be, and against a lit grey floor a light blue ring at 0.66
        // is the faintest thing on the screen — which the black captures the
        // control pad was built against could not possibly have shown.
        //
        // A RING rather than the filled disc the buttons use. The buttons' halo
        // is a disc a little larger than the rim, which works because a button
        // is small; at the stick's range the same treatment is four times the
        // area and lays a dark plate over the corner of the world the player is
        // walking into. This shadows the rim from outside only, and the well
        // already backs it from inside, so the ring is bracketed either way.
        drawSprite(ring, baseX, baseY, range * HALO_SPREAD, HALO_TINT, HALO_ALPHA);

        drawSprite(ring, baseX, baseY, range, STICK_TINT, rimAlpha(held));

        final float knobRadius = layout.stickKnobRadius();

        drawSprite(disc, knobX, knobY, knobRadius * HALO_SPREAD, HALO_TINT, HALO_ALPHA);

        drawSprite(disc, knobX, knobY, knobRadius, STICK_TINT, fillAlpha(held));

        drawSprite(ring, knobX, knobY, knobRadius, RIM_TINT, rimAlpha(held));
    }

    // One sprite, converting from touch coordinates (y down) to world
    // coordinates (y up). The ONLY place that conversion happens.
    private void drawSprite(final Texture texture, final float centreX, final float touchCentreY,
        final float radius, final Color colour, final float alpha)
    {
        tint.set(colour.r, colour.g, colour.b, alpha);

        batch.setColor(tint);

        final float worldCentreY = layout.height() - touchCentreY;

        batch.draw(texture, centreX - radius, worldCentreY - radius,
            radius * 2.0f, radius * 2.0f);
    }

    // The button's identity colour. Anything unknown draws in the stick's blue
    // rather than in nothing, so a region added to the layout and forgotten
    // here appears on screen instead of silently not.
    private static Color tintFor(final int region)
    {
        switch (region)
        {
            case TouchLayout.REGION_FIRE:
                return FIRE_TINT;
            case TouchLayout.REGION_JUMP:
                return JUMP_TINT;
            case TouchLayout.REGION_LEAVE:
                return LEAVE_TINT;
            default:
                return STICK_TINT;
        }
    }

    /**
     * Returns the line segments that draw a region's glyph.
     *
     * <p>Package-visible so a plain-JVM test can assert that every button the
     * layout offers has a symbol on it — the one thing about this file that can
     * be checked without a GL context, and the one that breaks silently when a
     * fourth button is added.</p>
     *
     * @param region one of {@link TouchLayout}'s region constants
     * @return {@code x0,y0,x1,y1} quadruples in 0..1 of the glyph texture, y
     *     downward; empty for a region with no symbol
     */
    static float[] glyphShapeFor(final int region)
    {
        switch (region)
        {
            case TouchLayout.REGION_FIRE:
                return FIRE_GLYPH;
            case TouchLayout.REGION_JUMP:
                return JUMP_GLYPH;
            case TouchLayout.REGION_LEAVE:
                return LEAVE_GLYPH;
            default:
                return NO_GLYPH;
        }
    }

    private static float fillAlpha(final boolean held)
    {
        if (held)
        {
            return FILL_PRESSED_ALPHA;
        }

        return FILL_ALPHA;
    }

    private static float rimAlpha(final boolean held)
    {
        if (held)
        {
            return RIM_PRESSED_ALPHA;
        }

        return RIM_ALPHA;
    }

    private static float glyphAlpha(final boolean held)
    {
        if (held)
        {
            return GLYPH_PRESSED_ALPHA;
        }

        return GLYPH_ALPHA;
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
        final Pixmap pixmap = blank(size);

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

    /**
     * Builds a white ring — the button rim and the stick's base.
     *
     * <p>An annulus rather than an outlined disc, because the fill underneath
     * it is translucent and a rim drawn as "a disc with a smaller disc on top"
     * would show the fill through its own outline.</p>
     *
     * @param size the texture's side in pixels; must be positive and even
     * @param thicknessFraction ring thickness as a fraction of the radius
     * @return the pixmap; the caller owns it and must dispose it
     */
    static Pixmap buildRing(final int size, final float thicknessFraction)
    {
        final Pixmap pixmap = blank(size);

        final float centre = size - 1;

        final float radius = size;

        // At least four doubled units — two real pixels — of thickness, or the
        // two one-pixel antialiasing ramps meet and cancel each other into a
        // ring that fades out entirely at small sizes.
        final float inner = radius - Math.max(4.0f, thicknessFraction * radius);

        for (int y = 0; y < size; y++)
        {
            for (int x = 0; x < size; x++)
            {
                final float dx = (2 * x) - centre;

                final float dy = (2 * y) - centre;

                final float distance = (float) Math.sqrt((dx * dx) + (dy * dy));

                pixmap.drawPixel(x, y, ringPixel(distance, radius, inner));
            }
        }

        return pixmap;
    }

    /**
     * Builds a glyph by stamping round-capped strokes along line segments.
     *
     * <p>Distance field rather than {@code Pixmap.drawLine}: that draws
     * one-pixel hard-edged Bresenham lines, which is neither thick enough to
     * see on a button nor smooth enough to bear being scaled up three times.
     * Measuring each pixel's distance to the nearest segment gives thickness,
     * antialiasing and round caps and joins from the same arithmetic.</p>
     *
     * @param size the texture's side in pixels; must be positive
     * @param segments {@code x0,y0,x1,y1} quadruples in 0..1, y downward; a
     *     zero-length segment draws a dot
     * @return the pixmap; the caller owns it and must dispose it
     */
    static Pixmap buildGlyph(final int size, final float[] segments)
    {
        final Pixmap pixmap = blank(size);

        final float halfStroke = GLYPH_STROKE_FRACTION * size;

        for (int y = 0; y < size; y++)
        {
            for (int x = 0; x < size; x++)
            {
                // Pixel centres, not corners: sampling at the corner shifts the
                // whole glyph half a pixel up and left of the disc behind it.
                final float distance = nearestSegment(x + 0.5f, y + 0.5f, segments, size);

                pixmap.drawPixel(x, y, strokePixel(distance, halfStroke));
            }
        }

        return pixmap;
    }

    // A transparent RGBA canvas with blending off, so every drawPixel below
    // REPLACES rather than composites — these write alpha deliberately and
    // blending would let the transparent background eat it.
    private static Pixmap blank(final int size)
    {
        final Pixmap pixmap = new Pixmap(size, size, Pixmap.Format.RGBA8888);

        pixmap.setBlending(Pixmap.Blending.None);

        return pixmap;
    }

    // Distance from a point to the closest of the segments, in pixels.
    private static float nearestSegment(final float pointX, final float pointY,
        final float[] segments, final int size)
    {
        // MUTABLE local: the running minimum. Starts past any distance a
        // square of this size can produce.
        float nearest = size * 2.0f;

        for (int index = 0; index + 3 < segments.length; index += 4)
        {
            final float distance = distanceToSegment(pointX, pointY,
                segments[index] * size, segments[index + 1] * size,
                segments[index + 2] * size, segments[index + 3] * size);

            if (distance < nearest)
            {
                nearest = distance;
            }
        }

        return nearest;
    }

    // Point-to-segment distance: project onto the segment, clamp the projection
    // to its ends — that clamp is what makes the caps round instead of the
    // stroke running off to infinity — and measure to the clamped point.
    private static float distanceToSegment(final float pointX, final float pointY,
        final float startX, final float startY, final float endX, final float endY)
    {
        final float runX = endX - startX;

        final float runY = endY - startY;

        final float lengthSquared = (runX * runX) + (runY * runY);

        if (lengthSquared <= 0.0f)
        {
            // A zero-length segment is a dot, and the projection below would be
            // a division by zero.
            return length(pointX - startX, pointY - startY);
        }

        final float raw = (((pointX - startX) * runX) + ((pointY - startY) * runY))
            / lengthSquared;

        final float along = clampUnitInterval(raw);

        return length(pointX - (startX + (along * runX)), pointY - (startY + (along * runY)));
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

        return whiteWithAlpha(coverage);
    }

    // One pixel of the ring: the narrower of the two edges' coverage, so a ring
    // thin enough for both ramps to overlap thins out rather than doubling up.
    private static int ringPixel(final float distance, final float radius, final float inner)
    {
        if (distance >= radius || distance <= inner)
        {
            return 0;
        }

        final float outward = (radius - distance) / 2.0f;

        final float inward = (distance - inner) / 2.0f;

        return whiteWithAlpha(Math.min(outward, inward));
    }

    // One pixel of a glyph stroke, with the same one-pixel ramp at its edge.
    private static int strokePixel(final float distance, final float halfStroke)
    {
        return whiteWithAlpha(halfStroke + 0.5f - distance);
    }

    // White at a coverage clamped into 0..1. Fully transparent pixels are
    // written as 0 rather than white-with-zero-alpha: linear filtering
    // interpolates the colour channels too, and a transparent WHITE neighbour
    // is invisible while a transparent BLACK one darkens every edge it touches.
    private static int whiteWithAlpha(final float coverage)
    {
        final float clamped = clampUnitInterval(coverage);

        if (clamped <= 0.0f)
        {
            return 0;
        }

        return 0xFFFFFF00 | (Math.round(clamped * 255.0f) & 0xFF);
    }

    // Folds a value into 0..1.
    private static float clampUnitInterval(final float value)
    {
        if (value > 1.0f)
        {
            return 1.0f;
        }

        if (value < 0.0f)
        {
            return 0.0f;
        }

        return value;
    }

    private static float length(final float dx, final float dy)
    {
        return (float) Math.sqrt((dx * dx) + (dy * dy));
    }
}
