/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import com.openfps.engine.core.pool.I_ParallelJob;
import com.openfps.engine.core.pool.I_ThreadPoolPort;

/**
 * R_ Draws a wireframe edge highlight over <b>one</b> tagged entity — the one
 * the player is aiming at — from the per-pixel entity-id and depth buffers.
 *
 * Render adapter — it reads {@link Framebuffer#entityIdBuffer()} and
 * {@link Framebuffer#depthBuffer()} and writes
 * {@link Framebuffer#colorBuffer()}, and imports nothing outside this package
 * but the worker pool.
 *
 * <h2>One entity, not all of them — the second half of the same bug report</h2>
 *
 * <p>This pass used to mark every tagged entity in the frame, and the shape of
 * the mark was fixed before its <i>scope</i> was. Both were the same complaint:
 * a permanent mark on an opponent reads as a status the opponent is in.
 * Thinning the band to a hairline stopped it reading as damage; it did not stop
 * seven simultaneous hairlines reading as seven simultaneous somethings, in a
 * room where nothing had happened to any of them.</p>
 *
 * <p>So the mark now means what a mark on exactly one body can mean:
 * <b>this is the one you are pointing at</b>. {@code SoftwareRenderPort}
 * samples the id buffer at the point of aim once per frame and passes the id
 * here; every other entity is skipped by the same {@code continue} that skips
 * the background. A highlight that appears and disappears as the player sweeps
 * across the room is unmistakably about the player's aim rather than about the
 * body, which is the distinction the earlier versions could not make.</p>
 *
 * <p>It also costs less than it did: the neighbourhood test now runs for the
 * pixels of one body rather than of all seven, and a frame with nothing under
 * the crosshair is not dispatched at all.</p>
 *
 * <p>{@link #draw(Framebuffer, I_ThreadPoolPort)} still marks everything
 * tagged. That overload is not production — it is the reference the
 * single-subject path is compared against, and it is what the tests that are
 * about <i>edge finding</i> rather than about <i>scope</i> use, so those tests
 * do not have to name a subject to assert that a seam appears between two
 * touching bodies.</p>
 *
 * <h2>A wireframe, not a glow — and that distinction is a bug report</h2>
 *
 * <p>This pass used to paint a {@link #OUTLINE_THICKNESS_PIXELS}-deep band of
 * solid colour round each entity's silhouette, and it was switched off by
 * default because of what a player said about it: a permanent saturated halo
 * round an opponent reads as <b>damage</b>. Every shooter uses a filled
 * highlight to mean "this one is hurt", so a filled highlight that means "this
 * one exists" is not a neutral affordance — it is a lie the renderer tells
 * sixty times a second.</p>
 *
 * <p>So the shape of the mark changed rather than the mark going away. It is
 * now a <b>hairline</b>, one pixel deep, and it is drawn on <b>interior edges
 * as well as the silhouette</b>: wherever the entity's own surface turns away
 * from itself — the seam between an arm and a torso, the step from a head to
 * the shoulders behind it. A line drawing over a body reads as a wireframe
 * overlay, which is a statement about geometry. A filled band round a body
 * reads as a status effect. Same colour, same cost, opposite meaning.</p>
 *
 * <h2>The two edge tests</h2>
 *
 * <p><b>Silhouette</b>, from the id buffer: a pixel is on one when some pixel
 * within {@link #thickness()} of it along the four axes carries a
 * <i>different</i> id.</p>
 *
 * <p><b>Crease</b>, from the depth buffer: a pixel is on one when an immediate
 * neighbour carrying the <i>same</i> id sits at a depth differing by more than
 * {@link #CREASE_DEPTH_RATIO} of the nearer of the two. Relative rather than
 * absolute because the buffer holds {@code 1/w} — an absolute threshold would
 * find creases everywhere on a close body and nowhere on a distant one, which
 * is precisely backwards.</p>
 *
 * <p>The crease test is one step, never {@code thickness} steps. A crease is
 * interior, so widening it fills the body in rather than tracing it, which is
 * the solid glow this pass exists to stop being.</p>
 *
 * <h2>Where it runs, and why exactly there</h2>
 *
 * <p><b>After the world pass, before the viewmodel pass and its depth
 * clear.</b> The id buffer is complete only once the world pass has finished,
 * and the held weapon must draw <i>over</i> outlines rather than under them —
 * a player standing behind the barrel should not have an outline painted
 * across it.</p>
 *
 * <h2>Comparing ids, not merely testing for one</h2>
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
 * <h2>Solid colour, no depth <i>test</i>, no blending</h2>
 *
 * <p>Edge pixels are overwritten outright. There is no falloff and no alpha:
 * the line is one pixel wide, and a one-pixel line that is also half
 * transparent is a line nobody can see over a light grey wall. The depth
 * buffer is <b>read</b> to find creases but is never <b>tested</b> against —
 * the mark is a UI affordance drawn on top of the frame, not geometry in
 * it.</p>
 *
 * <h2>Threading — parallel over tiles, and safe by construction</h2>
 *
 * <p>Dispatched over the same tile grid as the raster pass, through
 * {@link I_ThreadPoolPort#submitParallel}. The edge test reads a neighbourhood
 * that crosses tile boundaries, so a worker genuinely does read pixels another
 * worker owns — and that is safe here for a reason that must not be eroded:
 *
 * <ul>
 *   <li><b>The buffers are disjoint.</b> This pass <i>reads</i> the id and
 *       depth buffers and <i>writes</i> the colour buffer. Nothing in it writes
 *       an id or a depth, so no worker can observe a neighbour's half-finished
 *       state.</li>
 *   <li><b>Both read buffers are complete before the pass starts.</b> The
 *       raster pass's {@code submitParallel} has already joined, which
 *       publishes every worker's writes.</li>
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
     * <p><b>One, and the change from three is the whole of the fix for "the
     * highlighting reads as damage".</b> Three pixels of saturated colour round
     * a body a hundred pixels tall is a band with area — the eye reads area as
     * fill, and fill on an enemy means a status effect. One pixel has no area
     * to read; it is a line, and a line round a shape is a drawing of the
     * shape. Nothing else about the mark changed.</p>
     *
     * <p>It is a pixel count rather than a fraction of the frame height because
     * the thing it has to beat — the one-pixel steps of an aliased silhouette —
     * is also measured in pixels. A much higher resolution would want two; that
     * is what the explicit constructor is for.</p>
     */
    public static final int OUTLINE_THICKNESS_PIXELS = 1;

    /**
     * How far apart two same-entity depths must be, as a fraction of the nearer
     * one, before the pixel between them is an interior crease — 3%.
     *
     * <p><b>Relative, because the buffer holds {@code 1/w}.</b> An absolute
     * threshold is meaningless against a reciprocal: the same physical step in
     * world units produces a huge difference in {@code 1/w} up close and a
     * vanishing one far away, so a fixed number would scribble over a body at
     * arm's length and find nothing at all across the room. A ratio is scale
     * free and gives the same line on the same geometry at any distance.</p>
     *
     * <p>Three percent is well above the per-pixel gradient across a flat
     * surface even at a grazing angle, and well below the step from a limb to
     * the torso behind it — which is the seam this exists to draw on the blocky
     * character art the demo stages.</p>
     */
    public static final float CREASE_DEPTH_RATIO = 0.03f;

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

    /**
     * Subject meaning "every tagged entity", the behaviour this pass used to
     * have unconditionally.
     *
     * <p>{@link Integer#MIN_VALUE} rather than {@link Scene#UNTAGGED}, and the
     * difference is load-bearing: {@code UNTAGGED} is the id the buffer holds
     * where there is <i>nothing</i>, so a subject of {@code UNTAGGED} has to
     * mean "mark nothing at all" — which is exactly what a frame with the
     * crosshair on a wall asks for. Overloading the two onto one value would
     * make an empty sky outline the whole room.</p>
     */
    public static final int EVERY_TAGGED_ENTITY = Integer.MIN_VALUE;

    /** The tile pass, one index per tile. Held once; never allocated per frame. */
    private final I_ParallelJob tileJob = this::runTile;

    private final int thickness;
    private final int outlineColor;

    /** The frame's target. MUTABLE: rebound by {@link #draw}. */
    private volatile Framebuffer framebuffer;

    /** The frame's id buffer, read-only to this pass. MUTABLE: rebound per frame. */
    private volatile int[] ids;

    /** The frame's depth buffer, read-only to this pass. MUTABLE: rebound per frame. */
    private volatile float[] depths;

    /** The frame's colour buffer. MUTABLE: rebound per frame. */
    private volatile int[] color;

    /** Cached framebuffer geometry. MUTABLE: refreshed per frame. */
    private volatile int width;

    /** Cached framebuffer geometry. MUTABLE: refreshed per frame. */
    private volatile int height;

    /** Cached framebuffer geometry. MUTABLE: refreshed per frame. */
    private volatile int strideInPixels;

    /**
     * Which entity this frame marks, or {@link #EVERY_TAGGED_ENTITY}.
     * MUTABLE: rebound by {@link #draw} before any tile job reads it, on the
     * thread that then dispatches them.
     */
    private volatile int subject = EVERY_TAGGED_ENTITY;

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
     * Paints an outline around every tagged entity in the frame.
     *
     * <p>The reference behaviour, and no longer what the game does — see the
     * class Javadoc. It is exactly
     * {@code draw(target, pool, EVERY_TAGGED_ENTITY)}.</p>
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
        draw(target, pool, EVERY_TAGGED_ENTITY);
    }

    /**
     * Paints an outline into the colour buffer wherever one entity's pixels
     * meet something that is not that entity, or fold away from themselves.
     *
     * <p>Call after the world pass has finished and before the viewmodel pass
     * clears depth — see the class Javadoc for why that position is not
     * negotiable.</p>
     *
     * <p>A subject of {@link Scene#UNTAGGED} paints nothing, which is the
     * honest answer when the player is aiming at a wall; the caller may skip
     * the call entirely in that case and usually does, because the scan is the
     * cost rather than the paint.</p>
     *
     * @param target the framebuffer, whose id buffer must already be complete;
     *     must be READY
     * @param pool the worker pool, or null to run every tile on the calling
     *     thread — the serial reference the parallel result is compared against
     * @param subjectEntityId the one entity to mark, or
     *     {@link #EVERY_TAGGED_ENTITY} for all of them
     * @throws IllegalArgumentException if the framebuffer is null
     * @throws IllegalStateException if the framebuffer has no buffers
     */
    public void draw(final Framebuffer target, final I_ThreadPoolPort pool,
        final int subjectEntityId)
    {
        if (target == null)
        {
            throw new IllegalArgumentException("framebuffer must not be null");
        }
        if (target.state() != Framebuffer.State.READY)
        {
            throw new IllegalStateException("Framebuffer is " + target.state() + ", not READY");
        }
        // After the validation, so a refused call leaves the pass exactly as it
        // was rather than half-rebound to a frame it never drew.
        this.subject = subjectEntityId;
        this.framebuffer = target;
        this.ids = target.entityIdBuffer();
        this.depths = target.depthBuffer();
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

    /**
     * Returns the entity the last {@link #draw} marked, or
     * {@link #EVERY_TAGGED_ENTITY}.
     *
     * @return the subject of the most recent pass
     */
    public int subjectEntityId()
    {
        return subject;
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
        final float[] depthBuffer = depths;
        final int[] colorBuffer = color;
        final int stride = strideInPixels;
        final int minX = target.tileMinX(tile);
        final int minY = target.tileMinY(tile);
        final int maxX = target.tileMaxX(tile);
        final int maxY = target.tileMaxY(tile);
        final int paint = outlineColor;
        final int marked = subject;
        // Hoisted, so the per-pixel test is one integer compare in both modes
        // rather than a compare against a sentinel plus a compare against an id.
        final boolean everything = marked == EVERY_TAGGED_ENTITY;

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
                if (!everything && id != marked)
                {
                    // Someone else's body. It still counts as "different" to
                    // the subject's own edge test below, so the seam between
                    // two touching opponents is still found — it is simply
                    // drawn on the subject's side of the boundary only.
                    continue;
                }
                if (onEdge(idBuffer, depthBuffer, px, py, id, depthBuffer[rowBase + px]))
                {
                    colorBuffer[rowBase + px] = paint;
                }
            }
        }
    }

    // Whether this tagged pixel is on either kind of edge. Only tagged pixels
    // reach here, so the untagged majority of the frame costs one compare and
    // nothing else.
    //
    // Silhouette first because it is the cheaper of the two — integer compares
    // against a buffer already in cache — and because on a body of any size it
    // is the test that answers true for the ring the eye actually follows.
    private boolean onEdge(final int[] idBuffer, final float[] depthBuffer, final int px,
        final int py, final int id, final float depth)
    {
        return onSilhouette(idBuffer, px, py, id)
            || onCrease(idBuffer, depthBuffer, px, py, id, depth);
    }

    // Whether any pixel within `thickness` along the four axes carries a
    // different id.
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

    // Whether an immediate neighbour of the SAME entity sits far enough away in
    // depth to be a different surface of it.
    //
    // One step in each direction, never `thickness` steps — see the class
    // Javadoc. A crease is interior, and a wide interior line is a fill.
    private boolean onCrease(final int[] idBuffer, final float[] depthBuffer, final int px,
        final int py, final int id, final float depth)
    {
        return stepsInDepth(idBuffer, depthBuffer, px - 1, py, id, depth)
            || stepsInDepth(idBuffer, depthBuffer, px + 1, py, id, depth)
            || stepsInDepth(idBuffer, depthBuffer, px, py - 1, id, depth)
            || stepsInDepth(idBuffer, depthBuffer, px, py + 1, id, depth);
    }

    // Whether one neighbour belongs to the same entity but to a different
    // surface of it.
    //
    // A neighbour with a DIFFERENT id is not a crease — it is a silhouette, and
    // the other test already owns it. Saying so explicitly keeps the two marks
    // from double-counting, and keeps a background pixel's cleared depth of
    // Framebuffer.DEPTH_CLEAR out of the comparison entirely.
    private boolean stepsInDepth(final int[] idBuffer, final float[] depthBuffer, final int x,
        final int y, final int id, final float depth)
    {
        if (x < 0 || x >= width || y < 0 || y >= height)
        {
            return false;
        }
        final int at = y * strideInPixels + x;
        if (idBuffer[at] != id)
        {
            return false;
        }
        final float neighbour = depthBuffer[at];
        // Greater 1/w is nearer, so the larger of the two is the near surface —
        // and the near one is the right denominator: it is the surface the
        // crease belongs to, and using the far one would make the same physical
        // step read as a bigger fraction the further away it got.
        return Math.abs(depth - neighbour)
            > CREASE_DEPTH_RATIO * Math.max(depth, neighbour);
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
