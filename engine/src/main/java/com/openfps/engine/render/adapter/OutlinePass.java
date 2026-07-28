/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import com.openfps.engine.core.pool.I_ParallelJob;
import com.openfps.engine.core.pool.I_ThreadPoolPort;

/**
 * R_ Draws a solid silhouette around every tagged entity, from the per-pixel
 * entity-id buffer.
 *
 * Render adapter — it reads {@link Framebuffer#entityIdBuffer()} and writes
 * {@link Framebuffer#colorBuffer()}, and imports nothing outside this package
 * but the worker pool.
 *
 * <h2>Where it runs, and why exactly there</h2>
 *
 * <p><b>After the world pass, before the viewmodel pass and its depth
 * clear.</b> The id buffer is complete only once the world pass has finished,
 * and the held weapon must draw <i>over</i> outlines rather than under them —
 * a player standing behind the barrel should not have an outline painted
 * across it.</p>
 *
 * <h2>The edge test</h2>
 *
 * <p>A pixel is an outline pixel when its own id is not
 * {@link Scene#UNTAGGED} and some pixel within
 * {@link #OUTLINE_THICKNESS_PIXELS} of it along the four axes carries a
 * <b>different</b> id.</p>
 *
 * <p><b>Different, not merely "zero versus non-zero".</b> That distinction is
 * the whole point. Two players standing in a line overlap in screen space, and
 * a test for "is my neighbour untagged" finds no edge between them: they merge
 * into one blob and read as one player. Comparing ids finds the boundary
 * between id 7 and id 9 exactly as readily as the boundary between id 7 and
 * the wall behind it.</p>
 *
 * <p>The converse also falls out, and is why {@link Scene} lets two instances
 * share an id: an entity assembled from several models is one id throughout,
 * so no seam is drawn through its own joints.</p>
 *
 * <p>Neighbours off the edge of the screen count as <b>not</b> different. An
 * entity walking half out of frame would otherwise get a bright line drawn
 * down the border of the window, which reads as a rendering fault rather than
 * as a player.</p>
 *
 * <h2>Solid colour, no depth test, no blending</h2>
 *
 * <p>Outline pixels are overwritten outright. There is no falloff and no
 * alpha: the point of the feature is that a player is unmistakable, and a
 * blended outline over a light grey wall is exactly the case where it stops
 * being so. Depth is not consulted either — an outline is a UI affordance
 * drawn on top of the frame, not geometry in it.</p>
 *
 * <h2>Threading — parallel over tiles, and safe by construction</h2>
 *
 * <p>Dispatched over the same tile grid as the raster pass, through
 * {@link I_ThreadPoolPort#submitParallel}. The edge test reads a neighbourhood
 * that crosses tile boundaries, so a worker genuinely does read pixels another
 * worker owns — and that is safe here for a reason that must not be eroded:
 *
 * <ul>
 *   <li><b>The two buffers are disjoint.</b> This pass <i>reads</i> the id
 *       buffer and <i>writes</i> the colour buffer. Nothing in it writes an id,
 *       so no worker can observe a neighbour's half-finished state.</li>
 *   <li><b>The id buffer is complete before the pass starts.</b> The raster
 *       pass's {@code submitParallel} has already joined, which publishes every
 *       worker's writes.</li>
 *   <li><b>Colour writes stay inside the worker's own tile.</b> Exclusive tile
 *       ownership is unchanged, so no atomic is needed and none is used.</li>
 * </ul>
 *
 * <p><b>Do not fuse this pass into the raster pass.</b> Doing so would have a
 * worker read id pixels a neighbouring worker is still writing, and the output
 * would depend on the worker count — which is precisely the invariant
 * {@code SoftwareRenderPortTest} and {@code RasterizerTest} pin at every worker
 * count from one to eight.</p>
 *
 * <p>Given those, each output pixel is a pure function of the frozen id
 * buffer, so the frame is bit-identical at any worker count. Nothing about the
 * result depends on which worker claimed which tile.</p>
 *
 * <h2>Cost</h2>
 *
 * <p>The pass reads every visible pixel's id once. That is unavoidable for a
 * screen-space silhouette and it is why {@link SoftwareRenderPort} does not
 * run the pass at all unless {@link Scene#hasTaggedEntities()} — a scene with
 * no players in it never pays the scan, the dispatch barrier, or the id clear
 * that feeds it. Only the pixels that <i>are</i> tagged pay for the
 * neighbourhood test, four reads per pixel of thickness.</p>
 *
 * <h2>Allocation</h2>
 *
 * <p>None per frame. The tile job is a field, the frame's buffers are hoisted
 * into fields once per call, and the inner loop holds only primitives.</p>
 */
public final class OutlinePass
{
    /**
     * How far the outline reaches inward from a silhouette edge, in pixels, at
     * 720p.
     *
     * <p>Three is thick enough to survive a light grey wall and thin enough
     * not to swallow a distant player whole. It is a pixel count rather than a
     * fraction of the frame height because the thing it has to beat — the
     * one-pixel steps of an aliased silhouette — is also measured in
     * pixels.</p>
     */
    public static final int OUTLINE_THICKNESS_PIXELS = 3;

    /**
     * The outline colour, packed RGBA8888: fully saturated cyan.
     *
     * <p>Chosen against the demo's actual palette rather than in the abstract.
     * The room is light grey, the doorways are dark, and the weapon is orange;
     * cyan is orange's complement, so it separates from the one thing on
     * screen that is most likely to sit behind it, and it has the contrast
     * against both light and dark that a mid-tone would not. Two channels at
     * full and one at zero is a corner of the colour cube, which the Kenney
     * atlases — desaturated, warm, low-poly flats — do not reach.</p>
     *
     * <p>Magenta was the other candidate and was rejected: it is the
     * established "missing texture" colour, and a renderer that paints a
     * player the same colour it paints a broken asset has made its own bug
     * reports harder to read.</p>
     */
    public static final int OUTLINE_COLOR = Rgba.pack(0, 255, 255, 255);

    /** The tile pass, one index per tile. Held once; never allocated per frame. */
    private final I_ParallelJob tileJob = this::runTile;

    private final int thickness;
    private final int outlineColor;

    /** The frame's target. MUTABLE: rebound by {@link #draw}. */
    private volatile Framebuffer framebuffer;

    /** The frame's id buffer, read-only to this pass. MUTABLE: rebound per frame. */
    private volatile int[] ids;

    /** The frame's colour buffer. MUTABLE: rebound per frame. */
    private volatile int[] color;

    /** Cached framebuffer geometry. MUTABLE: refreshed per frame. */
    private volatile int width;

    /** Cached framebuffer geometry. MUTABLE: refreshed per frame. */
    private volatile int height;

    /** Cached framebuffer geometry. MUTABLE: refreshed per frame. */
    private volatile int strideInPixels;

    /**
     * Creates an outline pass with the default thickness and colour.
     */
    public OutlinePass()
    {
        this(OUTLINE_THICKNESS_PIXELS, OUTLINE_COLOR);
    }

    /**
     * Creates an outline pass with an explicit thickness and colour.
     *
     * <p>Exists so a test can assert that the thickness is honoured and
     * symmetric without depending on the shipped default, and so a higher
     * resolution can be given a thicker line without editing a constant.</p>
     *
     * @param outlineThickness how far the outline reaches inward from an edge,
     *     in pixels; must be positive
     * @param rgba packed {@code 0xRRGGBBAA} outline colour
     * @throws IllegalArgumentException if the thickness is not positive
     */
    public OutlinePass(final int outlineThickness, final int rgba)
    {
        if (outlineThickness <= 0)
        {
            throw new IllegalArgumentException("outline thickness must be > 0, got "
                + outlineThickness);
        }
        this.thickness = outlineThickness;
        this.outlineColor = rgba;
    }

    /**
     * Paints an outline into the colour buffer wherever the id buffer changes
     * value.
     *
     * <p>Call after the world pass has finished and before the viewmodel pass
     * clears depth — see the class Javadoc for why that position is not
     * negotiable.</p>
     *
     * @param target the framebuffer, whose id buffer must already be complete;
     *     must be READY
     * @param pool the worker pool, or null to run every tile on the calling
     *     thread — the serial reference the parallel result is compared against
     * @throws IllegalArgumentException if the framebuffer is null
     * @throws IllegalStateException if the framebuffer has no buffers
     */
    public void draw(final Framebuffer target, final I_ThreadPoolPort pool)
    {
        if (target == null)
        {
            throw new IllegalArgumentException("framebuffer must not be null");
        }
        if (target.state() != Framebuffer.State.READY)
        {
            throw new IllegalStateException("Framebuffer is " + target.state() + ", not READY");
        }
        this.framebuffer = target;
        this.ids = target.entityIdBuffer();
        this.color = target.colorBuffer();
        this.width = target.width();
        this.height = target.height();
        this.strideInPixels = target.strideInPixels();

        final int tiles = target.tileCount();
        if (pool == null)
        {
            for (int tile = 0; tile < tiles; tile++)
            {
                runTile(tile);
            }
            return;
        }
        pool.submitParallel(tileJob, tiles);
    }

    /** Returns how far this pass reaches inward from a silhouette edge, in pixels. */
    public int thickness()
    {
        return thickness;
    }

    /** Returns the packed RGBA8888 colour this pass paints. */
    public int outlineColor()
    {
        return outlineColor;
    }

    /** Returns a debug rendering of this pass's configuration. */
    @Override
    public String toString()
    {
        return "OutlinePass{thickness=" + thickness + ", color=0x"
            + Integer.toHexString(outlineColor) + "}";
    }

    // ---- the tile pass ----

    // One tile. Reads ids anywhere on screen; writes colour only inside this
    // tile. See the class Javadoc for why that combination needs no
    // synchronisation and produces the same frame at any worker count.
    private void runTile(final int tile)
    {
        final Framebuffer target = framebuffer;
        final int[] idBuffer = ids;
        final int[] colorBuffer = color;
        final int stride = strideInPixels;
        final int minX = target.tileMinX(tile);
        final int minY = target.tileMinY(tile);
        final int maxX = target.tileMaxX(tile);
        final int maxY = target.tileMaxY(tile);
        final int paint = outlineColor;

        for (int py = minY; py <= maxY; py++)
        {
            final int rowBase = py * stride;
            for (int px = minX; px <= maxX; px++)
            {
                final int id = idBuffer[rowBase + px];
                if (id == Scene.UNTAGGED)
                {
                    continue;
                }
                if (onSilhouette(idBuffer, px, py, id))
                {
                    colorBuffer[rowBase + px] = paint;
                }
            }
        }
    }

    // Whether any pixel within `thickness` along the four axes carries a
    // different id. Only tagged pixels reach here, so the untagged majority of
    // the frame costs one compare and nothing else.
    private boolean onSilhouette(final int[] idBuffer, final int px, final int py,
        final int id)
    {
        for (int step = 1; step <= thickness; step++)
        {
            if (differs(idBuffer, px - step, py, id) || differs(idBuffer, px + step, py, id))
            {
                return true;
            }
            if (differs(idBuffer, px, py - step, id) || differs(idBuffer, px, py + step, id))
            {
                return true;
            }
        }
        return false;
    }

    // Whether one neighbour carries a different id. Off-screen is NOT
    // different: a player half out of frame must not draw a line down the
    // window border. The padded stride columns are outside [0, width) and so
    // are never sampled, which matters because nothing ever writes them.
    private boolean differs(final int[] idBuffer, final int x, final int y, final int id)
    {
        if (x < 0 || x >= width || y < 0 || y >= height)
        {
            return false;
        }
        return idBuffer[y * strideInPixels + x] != id;
    }
}
