/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

/**
 * Decides whether a whole instance can be skipped before a single vertex of it
 * is transformed.
 *
 * Render adapter — pure math over primitives, exactly like {@link Camera}. It
 * imports nothing, allocates nothing, and knows about neither instances nor
 * scenes: it is handed one packed transform and one model-space box.
 *
 * <h2>What this saves, and why it exists at all</h2>
 *
 * <p>{@link Rasterizer#setupAndBin} already discards a triangle whose screen
 * bounding box misses the viewport — that is what makes the four side frustum
 * planes free there. But <b>by then the triangle has already been transformed
 * and clipped</b>, and in a room of a few hundred instances the camera looks at
 * a fraction of them. That per-triangle work is the resolution-independent floor
 * on frame time: halving the pixel count does not touch it. This class moves the
 * same decision up to the instance, where it is paid <b>once per instance</b>
 * instead of once per triangle, and where answering "no" costs the pipeline
 * nothing at all.</p>
 *
 * <h2>The test, and why it is exactly conservative</h2>
 *
 * <p>{@link Camera#transformToClip} is <b>affine</b> in model coordinates: each
 * of {@code x_clip}, {@code y_clip}, {@code w_clip} is a linear function of
 * {@code (x, y, z)} plus a constant. Three consequences follow, and together
 * they are the whole correctness argument:</p>
 *
 * <ol>
 *   <li>The model's axis-aligned box <b>contains</b> every vertex of the model,
 *       by construction.</li>
 *   <li>Each frustum plane's inside-test is a linear inequality in clip space —
 *       {@code x + w >= 0} for the left plane, and so on — so composing it with
 *       the affine transform gives a linear inequality in <i>model</i> space.
 *       A plane in model space, in other words, tested against a box in model
 *       space: no corner is ever transformed.</li>
 *   <li>A linear function over a box attains its extremes at a corner, so
 *       {@code centre +/- radius} brackets it exactly. If the maximum is still
 *       negative, <b>every point of the box</b> is outside that plane, and
 *       therefore every vertex of the model is.</li>
 * </ol>
 *
 * <p>The test is one-sided on purpose. It answers "certainly invisible" or
 * "don't know", never "certainly visible": an instance that straddles a plane,
 * or that lies outside two planes without lying outside either one on its own,
 * is <b>kept</b> and drawn exactly as before. Being wrong in that direction
 * costs a few transforms. Being wrong in the other direction is a hole in the
 * world.</p>
 *
 * <p><b>This is why the change is bit-identical rather than merely close.</b>
 * A culled instance's triangles would each have failed
 * {@code Rasterizer.writeBounds} — the screen box misses the viewport — or the
 * near clip, so they would have contributed to no pixel, no depth value and no
 * entity id. Removing them removes nothing from the image.</p>
 *
 * <h2>Five planes, not six</h2>
 *
 * <p>Near, left, right, bottom, top. There is <b>no far plane</b>, because this
 * pipeline has none: {@link Camera} computes no {@code z_clip} and the depth
 * buffer stores {@code 1/w}, so distance alone never removes anything. Adding a
 * far plane here would cull geometry the renderer would otherwise have
 * drawn.</p>
 *
 * <h2>Cost</h2>
 *
 * <p>Five planes, each four coefficients formed by one add or subtract of two
 * transform rows, then six multiplies and five adds against the box. Under a
 * hundred floating-point operations for an instance whose cheapest triangle
 * costs thirty-six on its own.</p>
 */
public final class InstanceCull
{
    /**
     * Floats in a model-space bounding box: min x, y, z then max x, y, z.
     * Matches the order {@link ModelFormat} reports its bounds in.
     */
    public static final int BOX_FLOATS = 6;

    /** Frustum planes this class tests: near, left, right, bottom, top. */
    public static final int PLANE_COUNT = 5;

    // Row offsets into a packed transform, matching Camera's layout.
    private static final int ROW_X = 0;
    private static final int ROW_Y = 4;
    private static final int ROW_W = 8;

    /** Floats in one packed transform row: three coefficients and a constant. */
    private static final int ROW_FLOATS = 4;

    private InstanceCull()
    {
        // math holder
    }

    /**
     * Returns whether a model-space box is entirely outside the view frustum.
     *
     * <p>Conservative in one direction only: a false answer means "may be
     * visible", never "is visible". See the class Javadoc for why that asymmetry
     * is what makes culling safe.</p>
     *
     * @param transform the instance's packed model-to-clip transform, in
     *     {@link Camera}'s layout — three rows of four, x then y then w
     * @param transformOffset index of the transform's first float
     * @param near the near plane distance, in view-space z units; the same value
     *     {@link TriangleClipper} clips against
     * @param box the model-space bounding box, {@link #BOX_FLOATS} floats: min
     *     x, y, z then max x, y, z
     * @param boxOffset index of the box's first float
     * @return true if no point of the box can produce a pixel, so the whole
     *     instance may be skipped
     */
    public static boolean isOutsideFrustum(final float[] transform, final int transformOffset,
        final float near, final float[] box, final int boxOffset)
    {
        final int x = transformOffset + ROW_X;
        final int y = transformOffset + ROW_Y;
        final int w = transformOffset + ROW_W;

        // Near, and it is the plane that pays for this class in the demo room:
        // most of a room is behind the camera, and behind the camera the clipper
        // would emit nothing after transforming every vertex to find that out.
        // The constant is shifted by -near because the inside test is w > near,
        // not w > 0.
        if (outsideShifted(transform, w, -near, box, boxOffset))
        {
            return true;
        }
        // Left and right: x + w >= 0 and w - x >= 0. Bottom and top: the same
        // with the y row. The projection scales are already folded into the
        // rows by Camera, so the field of view needs no separate term.
        if (outsideSum(transform, x, w, box, boxOffset))
        {
            return true;
        }
        if (outsideDifference(transform, w, x, box, boxOffset))
        {
            return true;
        }
        if (outsideSum(transform, y, w, box, boxOffset))
        {
            return true;
        }
        return outsideDifference(transform, w, y, box, boxOffset);
    }

    // One row used as a plane, with its constant term shifted. The near plane
    // and nothing else.
    private static boolean outsideShifted(final float[] transform, final int row,
        final float shift, final float[] box, final int boxOffset)
    {
        return outside(transform[row], transform[row + 1], transform[row + 2],
            transform[row + 3] + shift, box, boxOffset);
    }

    // The sum of two rows as a plane: the left and bottom frustum planes.
    private static boolean outsideSum(final float[] transform, final int first,
        final int second, final float[] box, final int boxOffset)
    {
        return outside(transform[first] + transform[second],
            transform[first + 1] + transform[second + 1],
            transform[first + 2] + transform[second + 2],
            transform[first + 3] + transform[second + 3], box, boxOffset);
    }

    // The difference of two rows as a plane: the right and top frustum planes.
    private static boolean outsideDifference(final float[] transform, final int first,
        final int second, final float[] box, final int boxOffset)
    {
        return outside(transform[first] - transform[second],
            transform[first + 1] - transform[second + 1],
            transform[first + 2] - transform[second + 2],
            transform[first + 3] - transform[second + 3], box, boxOffset);
    }

    // Whether every point of the box makes the plane function negative.
    //
    // The centre and half-extent are recomputed per plane rather than hoisted
    // into the caller: it is six subtractions repeated five times against a
    // saving of nothing measurable, and hoisting them would mean threading ten
    // floats through every helper above. The box is read from an array the
    // caller owns, so the JIT hoists the loads anyway.
    //
    // Half-extents are taken as magnitudes so that a degenerate box whose max is
    // below its min — which ModelFormat can produce only for a model with no
    // vertices at all — inflates the test rather than shrinking it. Shrinking it
    // is the one direction that could cull something visible.
    private static boolean outside(final float a, final float b, final float c, final float d,
        final float[] box, final int at)
    {
        final float centreX = (box[at] + box[at + 3]) * 0.5f;
        final float centreY = (box[at + 1] + box[at + 4]) * 0.5f;
        final float centreZ = (box[at + 2] + box[at + 5]) * 0.5f;
        final float extentX = Math.abs(box[at + 3] - box[at]) * 0.5f;
        final float extentY = Math.abs(box[at + 4] - box[at + 1]) * 0.5f;
        final float extentZ = Math.abs(box[at + 5] - box[at + 2]) * 0.5f;

        final float centre = a * centreX + b * centreY + c * centreZ + d;
        final float radius = Math.abs(a) * extentX + Math.abs(b) * extentY
            + Math.abs(c) * extentZ;
        return centre + radius < 0.0f;
    }

    /**
     * Returns the number of floats one packed transform row occupies.
     *
     * <p>Exposed so a test can assert this class and {@link Camera} agree about
     * the layout rather than each hard-coding four.</p>
     *
     * @return floats per row
     */
    public static int rowFloats()
    {
        return ROW_FLOATS;
    }
}
