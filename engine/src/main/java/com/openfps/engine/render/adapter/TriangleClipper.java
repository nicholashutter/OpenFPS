/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

/**
 * Near-plane clipping of triangles in homogeneous clip space.
 *
 * Render adapter — pure math over primitives. It imports nothing from the core
 * engine, and nothing from outside {@code java.lang}.
 *
 * <p>This class clips and re-triangulates and <b>stops</b>. It does not perform
 * the perspective divide and it does not apply the viewport transform; both
 * belong to the rasterizer ({@code render/README.md} § 1 and § 4). Its input is
 * the clip-space output of {@link Camera}.</p>
 *
 * <h2>Why clip, and why only here</h2>
 *
 * <p>The perspective divide is {@code 1 / w_clip}. A vertex behind the eye has
 * {@code w} at or below zero, and dividing by it does not produce an obviously broken
 * result — it produces geometry mirrored through the origin, or an infinity,
 * which looks plausible per-vertex and rasterizes catastrophically. There is no
 * fixing it up afterwards, so a triangle crossing the near plane <b>must</b> be
 * geometrically clipped <em>before</em> the divide.</p>
 *
 * <p>The other five frustum planes need no geometric clip: once in screen space,
 * left/right/top/bottom rejection is the intersection of the triangle's bounding
 * box with the screen rectangle, which the rasterizer's tile binning does
 * anyway. And there is no far plane at all, because {@code 1/w} depth converges
 * toward zero for distant geometry rather than overflowing. So: <b>one clip
 * plane, applied in homogeneous space.</b> ({@code render/README.md} § 6.)</p>
 *
 * <h2>Why linear interpolation is correct here</h2>
 *
 * <p>Clip-space position and every vertex attribute are affine functions of the
 * parameter along an edge. The divide is what destroys that linearity. Clipping
 * <em>before</em> the divide is therefore what lets one {@code lerp} at one
 * parameter {@code t} handle position, UV and baked colour identically. That is
 * the whole reason the operation lives in homogeneous space, and it is the point
 * of Blinn and Newell. A clipper that interpolates position correctly and
 * attributes at some other parameter produces geometry that looks right with
 * textures that swim near the near plane.</p>
 *
 * <h2>Vertex layout</h2>
 *
 * <p>Vertices are flat {@code float[]}, never objects. One vertex is
 * {@link #stride()} consecutive floats:</p>
 * <pre>
 *   [0] x_clip
 *   [1] y_clip
 *   [2] w_clip
 *   [3 .. 3 + attributeCount)  attributes, interpolated linearly alongside
 * </pre>
 *
 * <p>There is no {@code z_clip} — {@link Camera} does not compute one. The
 * attribute block is opaque to this class: two floats for UV, five for UV plus
 * a baked RGB, zero for position-only geometry. Everything in the vertex,
 * position included, is interpolated by the same loop at the same {@code t},
 * which is precisely the property that makes attribute drift impossible here.</p>
 *
 * <h2>Boundary convention</h2>
 *
 * <p>A vertex is inside iff {@code w > near}, strictly, as
 * {@code render/README.md} § 6 specifies. A vertex lying exactly <em>on</em> the
 * near plane is therefore outside. That is the conservative choice and it is
 * safe, but note the consequence: a triangle with one vertex exactly on the
 * plane and two in front clips to a four-vertex polygon in which the
 * intersections coincide with the on-plane vertex, so the fan emits one correct
 * triangle plus one with zero area. Zero-area triangles are rejected by the
 * rasterizer's {@code area2 == 0} test, which it must perform regardless, so no
 * de-duplication is done here — it would cost comparisons on every triangle to
 * save work on a measure-zero case.</p>
 *
 * <h2>Allocation</h2>
 *
 * <p><b>Nothing is allocated after construction.</b> Clipping a triangle against
 * a single plane yields at most {@link #MAX_CLIPPED_VERTICES} vertices, so one
 * fixed scratch polygon sized at construction is sufficient forever, and results
 * go to a caller-supplied buffer. At the 50k-100k triangles per frame ceiling of
 * {@code docs/ASSETS.md} § 2, a per-triangle vertex object or result array would
 * dominate the frame on its own.</p>
 *
 * <p><b>Not thread-safe.</b> The scratch polygon is instance state. Give each
 * render worker its own instance; they are cheap and share nothing.</p>
 *
 * <h2>Sources</h2>
 * <ul>
 *   <li>Sutherland and Hodgman, "Reentrant Polygon Clipping", CACM 17(1), 1974
 *       — https://dl.acm.org/doi/10.1145/360767.360802</li>
 *   <li>Blinn and Newell, "Clipping using homogeneous coordinates",
 *       SIGGRAPH '78 — https://dl.acm.org/doi/10.1145/800248.807398</li>
 * </ul>
 */
public final class TriangleClipper
{
    /** Floats of clip-space position per vertex: x, y and w. There is no z. */
    public static final int POSITION_FLOATS = 3;

    /** Index of {@code x_clip} within a vertex. */
    public static final int OFFSET_X = 0;

    /** Index of {@code y_clip} within a vertex. */
    public static final int OFFSET_Y = 1;

    /** Index of {@code w_clip} within a vertex. */
    public static final int OFFSET_W = 2;

    /** Vertices in a triangle. */
    public static final int TRIANGLE_VERTICES = 3;

    /**
     * Most vertices a single-plane Sutherland-Hodgman clip of a triangle can
     * produce. One plane can cut at most two edges, adding at most one vertex
     * net, so a triangle becomes at most a quad.
     */
    public static final int MAX_CLIPPED_VERTICES = 4;

    /** Most triangles one input triangle can produce: the quad, fanned. */
    public static final int MAX_OUTPUT_TRIANGLES = MAX_CLIPPED_VERTICES - 2;

    private final int attributeCount;
    private final int stride;

    /**
     * Clipped-polygon scratch, {@link #MAX_CLIPPED_VERTICES} vertices wide.
     * MUTABLE: overwritten in place on every {@link #clipTriangle} call. This
     * is the buffer that makes the per-triangle path allocation-free.
     */
    private final float[] polygon;

    /**
     * Creates a clipper for vertices carrying a fixed number of attributes.
     *
     * @param attributeCount floats per vertex beyond the three position floats
     *     — for example 2 for UV, 5 for UV plus a baked RGB, 0 for
     *     position-only geometry. Must not be negative.
     * @throws IllegalArgumentException if {@code attributeCount} is negative
     */
    public TriangleClipper(final int attributeCount)
    {
        if (attributeCount < 0)
        {
            throw new IllegalArgumentException(
                "attributeCount must not be negative, got " + attributeCount);
        }
        this.attributeCount = attributeCount;
        this.stride = POSITION_FLOATS + attributeCount;
        this.polygon = new float[MAX_CLIPPED_VERTICES * stride];
    }

    /**
     * Clips one triangle against the near plane and re-triangulates the result.
     *
     * Reads {@code 3 * stride()} floats from {@code in} starting at
     * {@code inOffset} and writes {@code 3 * stride()} floats per output
     * triangle to {@code out} starting at {@code outOffset}. The output buffer
     * must have room for {@link #maxOutputFloats()} floats; sizing it that way
     * once and reusing it is the intended pattern.
     *
     * <p>Winding is preserved, so the rasterizer's backface test is unaffected
     * by whether a triangle was clipped.</p>
     *
     * <p>Every emitted vertex satisfies {@code w >= near}, and {@code near} is
     * positive by {@link Camera}'s construction, so the rasterizer's
     * {@code 1 / w} is always finite. Vertices produced by an edge intersection
     * get {@code w} set to exactly {@code near} rather than to the lerped value,
     * which is the same number algebraically but free of the rounding the
     * division introduces.</p>
     *
     * @param near the near plane distance, from {@link Camera#near()}; must be
     *     positive
     * @param in the source buffer holding three consecutive clip-space vertices
     * @param inOffset index of the first float of the first source vertex
     * @param out the destination buffer
     * @param outOffset index of the first float to write
     * @return the number of triangles written: 0 if the triangle is entirely
     *     behind the near plane, 1 if it survives whole or clips to a triangle,
     *     2 if it clips to a quad
     */
    public int clipTriangle(final float near, final float[] in, final int inOffset,
        final float[] out, final int outOffset)
    {
        assert near > 0.0f : "near must be positive";

        final int s = stride;
        final float w0 = in[inOffset + OFFSET_W];
        final float w1 = in[inOffset + s + OFFSET_W];
        final float w2 = in[inOffset + 2 * s + OFFSET_W];

        // Fast path: wholly in front. This is the overwhelming majority of
        // triangles in any frame, so it is one early-out and a bulk copy.
        if (w0 > near && w1 > near && w2 > near)
        {
            System.arraycopy(in, inOffset, out, outOffset, TRIANGLE_VERTICES * s);
            return 1;
        }

        // Trivial reject: no vertex is inside. Written as negated comparisons
        // rather than `<=` so that a NaN w counts as outside — every comparison
        // against NaN is false, so a NaN vertex is never treated as visible and
        // an all-NaN triangle is rejected here rather than reaching the lerp.
        if (!(w0 > near) && !(w1 > near) && !(w2 > near))
        {
            return 0;
        }

        final int vertexCount = clipAgainstNear(near, in, inOffset);
        if (vertexCount < TRIANGLE_VERTICES)
        {
            return 0;
        }
        return fanTriangulate(vertexCount, out, outOffset);
    }

    // Sutherland-Hodgman against the single half-space w > near, writing the
    // surviving polygon into the scratch buffer. Returns its vertex count.
    private int clipAgainstNear(final float near, final float[] in, final int inOffset)
    {
        final int s = stride;
        // MUTABLE local — the emit cursor into the scratch polygon.
        int count = 0;
        for (int edge = 0; edge < TRIANGLE_VERTICES; edge++)
        {
            // MUTABLE local — index of the edge's second endpoint, wrapping.
            int nextIndex = edge + 1;
            if (nextIndex == TRIANGLE_VERTICES)
            {
                nextIndex = 0;
            }
            final int aOffset = inOffset + edge * s;
            final int bOffset = inOffset + nextIndex * s;
            final float aw = in[aOffset + OFFSET_W];
            final float bw = in[bOffset + OFFSET_W];
            final boolean aInside = aw > near;
            final boolean bInside = bw > near;

            if (aInside != bInside)
            {
                // The edge crosses the plane. Exactly one endpoint is strictly
                // greater than near and the other is not, so bw - aw is
                // strictly non-zero and this division cannot blow up.
                final float t = (near - aw) / (bw - aw);
                lerpVertex(in, aOffset, bOffset, t, count * s);
                polygon[count * s + OFFSET_W] = near;
                count++;
            }
            if (bInside)
            {
                System.arraycopy(in, bOffset, polygon, count * s, s);
                count++;
            }
        }
        assert count <= MAX_CLIPPED_VERTICES : "one plane cannot produce more than a quad";
        return count;
    }

    // Interpolates every float of the vertex — position and attributes alike —
    // at the same parameter t, into the scratch polygon.
    private void lerpVertex(final float[] in, final int aOffset, final int bOffset,
        final float t, final int destOffset)
    {
        for (int k = 0; k < stride; k++)
        {
            final float a = in[aOffset + k];
            polygon[destOffset + k] = a + t * (in[bOffset + k] - a);
        }
    }

    // Emits (0, 1, 2) and, for a quad, (0, 2, 3). Fanning from vertex 0 is
    // valid because a convex polygon clipped from a triangle stays convex.
    private int fanTriangulate(final int vertexCount, final float[] out, final int outOffset)
    {
        final int s = stride;
        System.arraycopy(polygon, 0, out, outOffset, TRIANGLE_VERTICES * s);
        if (vertexCount == TRIANGLE_VERTICES)
        {
            return 1;
        }
        final int second = outOffset + TRIANGLE_VERTICES * s;
        System.arraycopy(polygon, 0, out, second, s);
        System.arraycopy(polygon, 2 * s, out, second + s, s);
        System.arraycopy(polygon, 3 * s, out, second + 2 * s, s);
        return 2;
    }

    /** Returns the number of attribute floats carried per vertex. */
    public int attributeCount()
    {
        return attributeCount;
    }

    /** Returns the floats per vertex: {@link #POSITION_FLOATS} plus attributes. */
    public int stride()
    {
        return stride;
    }

    /**
     * Returns the size a {@link #clipTriangle} output buffer must have.
     *
     * @return floats needed to hold {@link #MAX_OUTPUT_TRIANGLES} triangles
     */
    public int maxOutputFloats()
    {
        return MAX_OUTPUT_TRIANGLES * TRIANGLE_VERTICES * stride;
    }

    /** Returns a debug rendering of the clipper's vertex layout. */
    @Override
    public String toString()
    {
        return "TriangleClipper{attributeCount=" + attributeCount + ", stride=" + stride + "}";
    }
}
