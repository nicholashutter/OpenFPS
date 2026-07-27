/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

/**
 * R_ The rasterizer's inner loop: perspective-correct interpolation, depth
 * test, depth write, colour write.
 *
 * Render adapter — pure math over primitive arrays. It imports nothing outside
 * this package.
 *
 * <p><b>This class does not decide which triangles it draws, or where.</b> It
 * is handed one {@link Rasterizer} setup record and one tile rectangle and
 * fills the intersection of the two ({@code render/README.md} § 1). Coverage,
 * culling, the fill rule and tile assignment were all decided at setup; what is
 * left here is a sign test, a compare, a reciprocal and a texel.</p>
 *
 * <h2>Perspective correction</h2>
 *
 * <p>{@code 1/w}, {@code u/w} and {@code v/w} are linear in screen space;
 * {@code u} and {@code v} are not. {@link Rasterizer} therefore stores each as
 * a plane equation over the divided quantity, and this loop undoes the division
 * per pixel:</p>
 *
 * <pre>
 *   invW = wdx * x + (wdy * y + wc)
 *   w    = 1 / invW
 *   u    = (udx * x + (udy * y + uc)) * w
 * </pre>
 *
 * <p>{@code docs/ASSETS.md} § 2 measures the cost of that correction at 8% of
 * the inner loop. It is never worth trading away, and this class offers no
 * affine path.</p>
 *
 * <h2>Why the divide is per pixel and there is no segment path</h2>
 *
 * <p>README § 8 proposes dividing only at the ends of an 8- or 16-pixel segment
 * and interpolating linearly between. Measured, that buys nothing:
 * {@code docs/ASSETS.md} § 2 and § 11(c) record that the floating-point divider
 * is not the bottleneck — memory traffic and the bilinear load/ALU work are, and
 * bilinear filtering alone is 2.9x the entire rest of the loop. So this is the
 * reference per-pixel path and there is deliberately no second one to keep in
 * agreement with it. <b>Do not add the segment path without a measurement
 * showing it wins.</b></p>
 *
 * <h2>Mip selection is per segment</h2>
 *
 * <p>The level of detail <i>is</i> chosen per segment, every
 * {@link #MIP_SEGMENT_PIXELS} pixels, because its logarithm genuinely does not
 * belong in a per-pixel loop (README § 9, and {@link TextureSampler}'s own
 * note). The derivatives come from the plane equations analytically rather than
 * from differencing two sampled points:</p>
 *
 * <pre>
 *   du/dx = (udx - u * wdx) * w
 * </pre>
 *
 * <p>which is the quotient rule applied to {@code u = (u/w) / (1/w)}, and costs
 * two operations from values the loop already holds. Segments are aligned to
 * screen x, so a pixel selects the same level whichever tile or triangle
 * reaches it.</p>
 *
 * <h2>Depth</h2>
 *
 * <p>The buffer stores {@code 1/w}, cleared to {@link Framebuffer#DEPTH_CLEAR}
 * (zero, infinitely far), and <b>greater passes</b> (README § 8). The
 * interpolant is already being computed for the perspective correction, so
 * depth costs no extra setup and no extra interpolant. The test runs
 * <b>before</b> texture sampling: at the 2x overdraw
 * {@code docs/ASSETS.md} § 2 budgets, roughly half of all sampling would
 * otherwise be thrown away.</p>
 *
 * <p>Every coverage and depth comparison is written negated —
 * {@code if (!(e >= bias))} rather than {@code if (e < bias)} — so that a NaN
 * fails it. A NaN interpolant then discards the pixel instead of writing an
 * undefined colour.</p>
 *
 * <h2>Why the edge functions are evaluated, not accumulated</h2>
 *
 * <p>Each edge value is computed as {@code dx * x + rowConstant}, where the row
 * constant is computed once per scanline. Stepping {@code e += dx} instead would
 * save an operation and cost the fill rule its exactness: two triangles sharing
 * an edge arrive at a boundary pixel from different starting points, their
 * accumulated values drift apart, and the pixel is painted twice or not at all.
 * Evaluated this way the two are bitwise negations of one another, which is what
 * makes {@link Rasterizer}'s top-left bias decide the pixel rather than
 * rounding. The per-scanline work is two multiplies per plane; there is no
 * divide anywhere outside the perspective reciprocal.</p>
 *
 * <h2>Threading</h2>
 *
 * <p><b>Stateless, therefore thread-safe.</b> Every value the loop needs lives
 * in the record, the framebuffer or a local. One instance serves every tile of
 * every frame, which is also why it allocates nothing.</p>
 */
public final class SpanRenderer
{
    /**
     * Pixels between mip level re-selections. A power of two so the segment
     * boundary is a mask on the screen x coordinate, which keeps segments
     * aligned across triangles and tiles.
     */
    public static final int MIP_SEGMENT_PIXELS = 16;

    /** Attributes {@link ShadingMode#TEXTURED} needs: u and v. */
    public static final int TEXTURED_ATTRIBUTES = 2;

    /** Attributes {@link ShadingMode#VERTEX_COLOR} needs: red, green and blue. */
    public static final int VERTEX_COLOR_ATTRIBUTES = 3;

    /** Full-scale value of one 8-bit colour channel. */
    private static final float CHANNEL_MAX = 255.0f;

    /** Mask selecting the offset of a pixel within its mip segment. */
    private static final int MIP_SEGMENT_MASK = MIP_SEGMENT_PIXELS - 1;

    private final ShadingMode mode;
    private final int attributeCount;

    /**
     * Creates a span renderer for one shading mode.
     *
     * @param mode how a covered pixel gets its colour
     * @param attributeCount floats per vertex beyond position, matching the
     *     {@link Rasterizer} whose records this will consume
     * @throws IllegalArgumentException if the mode is null, or the attribute
     *     count is too small for it
     */
    public SpanRenderer(final ShadingMode mode, final int attributeCount)
    {
        if (mode == null)
        {
            throw new IllegalArgumentException("mode must not be null");
        }
        if (attributeCount < mode.requiredAttributes())
        {
            throw new IllegalArgumentException(mode + " needs at least "
                + mode.requiredAttributes() + " vertex attributes, got " + attributeCount);
        }
        this.mode = mode;
        this.attributeCount = attributeCount;
    }

    /**
     * Fills one triangle's coverage inside one tile.
     *
     * <p>The caller owns the tile exclusively, so every colour and depth write
     * below is uncontended and needs no atomic. Pixels outside the intersection
     * of the triangle's bounding box and the tile are never visited.</p>
     *
     * @param target the framebuffer; must be READY
     * @param records the {@link Rasterizer} record array
     * @param recordOffset offset of this triangle's record within it
     * @param material index into {@code textures}, or
     *     {@link Rasterizer#NO_MATERIAL} for an untextured triangle
     * @param flatColor packed RGBA8888 used by {@link ShadingMode#FLAT}, and by
     *     {@link ShadingMode#TEXTURED} when no texture is bound
     * @param textures textures indexed by material, or null
     * @param tileMinX inclusive left edge of the tile
     * @param tileMinY inclusive top edge of the tile
     * @param tileMaxX inclusive right edge of the tile
     * @param tileMaxY inclusive bottom edge of the tile
     */
    public void renderTriangle(final Framebuffer target, final float[] records,
        final int recordOffset, final int material, final int flatColor,
        final MipChain[] textures, final int tileMinX, final int tileMinY,
        final int tileMaxX, final int tileMaxY)
    {
        final int minX = Math.max(tileMinX, (int) records[recordOffset + Rasterizer.BOUND_MIN_X]);
        final int maxX = Math.min(tileMaxX, (int) records[recordOffset + Rasterizer.BOUND_MAX_X]);
        if (minX > maxX)
        {
            return;
        }
        final int minY = Math.max(tileMinY, (int) records[recordOffset + Rasterizer.BOUND_MIN_Y]);
        final int maxY = Math.min(tileMaxY, (int) records[recordOffset + Rasterizer.BOUND_MAX_Y]);
        if (minY > maxY)
        {
            return;
        }
        final MipChain texture = textureFor(material, textures);
        if (texture == null)
        {
            if (mode == ShadingMode.VERTEX_COLOR)
            {
                renderVertexColor(target, records, recordOffset, minX, minY, maxX, maxY);
                return;
            }
            renderFlat(target, records, recordOffset, flatColor, minX, minY, maxX, maxY);
            return;
        }
        renderTextured(target, records, recordOffset, texture, minX, minY, maxX, maxY);
    }

    /** Returns the shading mode this renderer was built for. */
    public ShadingMode mode()
    {
        return mode;
    }

    /** Returns the vertex attribute count this renderer expects. */
    public int attributeCount()
    {
        return attributeCount;
    }

    /** How a covered pixel gets its colour. */
    public enum ShadingMode
    {
        /** One packed colour for the whole triangle. Needs no attributes. */
        FLAT(0),

        /**
         * Perspective-correct interpolation of attributes 0, 1 and 2 as red,
         * green and blue, each on a 0 to 255 scale. This is the baked-lighting
         * path for untextured geometry.
         */
        VERTEX_COLOR(VERTEX_COLOR_ATTRIBUTES),

        /**
         * Perspective-correct interpolation of attributes 0 and 1 as u and v,
         * sampled through {@link TextureSampler} with per-segment mip
         * selection. Falls back to the flat colour when no texture is bound.
         */
        TEXTURED(TEXTURED_ATTRIBUTES);

        private final int requiredAttributes;

        ShadingMode(final int requiredAttributes)
        {
            this.requiredAttributes = requiredAttributes;
        }

        /** Returns the smallest vertex attribute count this mode can work with. */
        public int requiredAttributes()
        {
            return requiredAttributes;
        }
    }

    // ---- the three inner loops ----

    // Coverage plus depth, one constant colour. Also the fallback for a
    // textured triangle whose material resolves to nothing.
    private void renderFlat(final Framebuffer target, final float[] records, final int base,
        final int flatColor, final int minX, final int minY, final int maxX, final int maxY)
    {
        final int[] color = target.colorBuffer();
        final float[] depth = target.depthBuffer();
        final int stride = target.strideInPixels();

        final float e0dx = records[base + Rasterizer.EDGE0_DX];
        final float e1dx = records[base + Rasterizer.EDGE1_DX];
        final float e2dx = records[base + Rasterizer.EDGE2_DX];
        final float bias0 = records[base + Rasterizer.EDGE0_BIAS];
        final float bias1 = records[base + Rasterizer.EDGE1_BIAS];
        final float bias2 = records[base + Rasterizer.EDGE2_BIAS];
        final float wdx = records[base + Rasterizer.INV_W_DX];

        for (int py = minY; py <= maxY; py++)
        {
            final float row0 = rowConstant(records, base + Rasterizer.EDGE0_DY, py);
            final float row1 = rowConstant(records, base + Rasterizer.EDGE1_DY, py);
            final float row2 = rowConstant(records, base + Rasterizer.EDGE2_DY, py);
            final float rowW = rowConstant(records, base + Rasterizer.INV_W_DY, py);
            final int rowBase = py * stride;
            for (int px = minX; px <= maxX; px++)
            {
                if (!covered(e0dx, row0, bias0, e1dx, row1, bias1, e2dx, row2, bias2, px))
                {
                    continue;
                }
                final float invW = wdx * px + rowW;
                final int index = rowBase + px;
                if (!(invW > depth[index]))
                {
                    continue;
                }
                depth[index] = invW;
                color[index] = flatColor;
            }
        }
    }

    // Attributes 0, 1 and 2 interpolated perspective-correctly as R, G and B.
    private void renderVertexColor(final Framebuffer target, final float[] records,
        final int base, final int minX, final int minY, final int maxX, final int maxY)
    {
        final int[] color = target.colorBuffer();
        final float[] depth = target.depthBuffer();
        final int stride = target.strideInPixels();

        final float e0dx = records[base + Rasterizer.EDGE0_DX];
        final float e1dx = records[base + Rasterizer.EDGE1_DX];
        final float e2dx = records[base + Rasterizer.EDGE2_DX];
        final float bias0 = records[base + Rasterizer.EDGE0_BIAS];
        final float bias1 = records[base + Rasterizer.EDGE1_BIAS];
        final float bias2 = records[base + Rasterizer.EDGE2_BIAS];
        final float wdx = records[base + Rasterizer.INV_W_DX];
        final int redPlane = base + Rasterizer.attributePlaneOffset(0);
        final int greenPlane = base + Rasterizer.attributePlaneOffset(1);
        final int bluePlane = base + Rasterizer.attributePlaneOffset(2);

        for (int py = minY; py <= maxY; py++)
        {
            final float row0 = rowConstant(records, base + Rasterizer.EDGE0_DY, py);
            final float row1 = rowConstant(records, base + Rasterizer.EDGE1_DY, py);
            final float row2 = rowConstant(records, base + Rasterizer.EDGE2_DY, py);
            final float rowW = rowConstant(records, base + Rasterizer.INV_W_DY, py);
            final float rowR = rowConstant(records, redPlane + 1, py);
            final float rowG = rowConstant(records, greenPlane + 1, py);
            final float rowB = rowConstant(records, bluePlane + 1, py);
            final int rowBase = py * stride;
            for (int px = minX; px <= maxX; px++)
            {
                if (!covered(e0dx, row0, bias0, e1dx, row1, bias1, e2dx, row2, bias2, px))
                {
                    continue;
                }
                final float invW = wdx * px + rowW;
                final int index = rowBase + px;
                if (!(invW > depth[index]))
                {
                    continue;
                }
                final float w = 1.0f / invW;
                depth[index] = invW;
                color[index] = Rgba.pack(channel(records[redPlane] * px + rowR, w),
                    channel(records[greenPlane] * px + rowG, w),
                    channel(records[bluePlane] * px + rowB, w), (int) CHANNEL_MAX);
            }
        }
    }

    // Attributes 0 and 1 interpolated perspective-correctly as u and v, then
    // sampled. The mip level is resolved on the first covered pixel of a
    // segment, which is guaranteed to be inside the triangle and so to have a
    // positive 1/w.
    private void renderTextured(final Framebuffer target, final float[] records, final int base,
        final MipChain texture, final int minX, final int minY, final int maxX, final int maxY)
    {
        final int[] color = target.colorBuffer();
        final float[] depth = target.depthBuffer();
        final int stride = target.strideInPixels();

        final float e0dx = records[base + Rasterizer.EDGE0_DX];
        final float e1dx = records[base + Rasterizer.EDGE1_DX];
        final float e2dx = records[base + Rasterizer.EDGE2_DX];
        final float bias0 = records[base + Rasterizer.EDGE0_BIAS];
        final float bias1 = records[base + Rasterizer.EDGE1_BIAS];
        final float bias2 = records[base + Rasterizer.EDGE2_BIAS];
        final float wdx = records[base + Rasterizer.INV_W_DX];
        final float wdy = records[base + Rasterizer.INV_W_DY];
        final int uPlane = base + Rasterizer.attributePlaneOffset(0);
        final int vPlane = base + Rasterizer.attributePlaneOffset(1);

        for (int py = minY; py <= maxY; py++)
        {
            final float row0 = rowConstant(records, base + Rasterizer.EDGE0_DY, py);
            final float row1 = rowConstant(records, base + Rasterizer.EDGE1_DY, py);
            final float row2 = rowConstant(records, base + Rasterizer.EDGE2_DY, py);
            final float rowW = rowConstant(records, base + Rasterizer.INV_W_DY, py);
            final float rowU = rowConstant(records, uPlane + 1, py);
            final float rowV = rowConstant(records, vPlane + 1, py);
            final int rowBase = py * stride;
            // MUTABLE local — the mip level in force, re-resolved once per
            // segment. Negative means "not yet resolved on this row".
            int level = -1;
            for (int px = minX; px <= maxX; px++)
            {
                if (!covered(e0dx, row0, bias0, e1dx, row1, bias1, e2dx, row2, bias2, px))
                {
                    continue;
                }
                final float invW = wdx * px + rowW;
                final int index = rowBase + px;
                if (!(invW > depth[index]))
                {
                    continue;
                }
                final float w = 1.0f / invW;
                final float u = (records[uPlane] * px + rowU) * w;
                final float v = (records[vPlane] * px + rowV) * w;
                if (level < 0 || (px & MIP_SEGMENT_MASK) == 0)
                {
                    level = selectLevel(texture, u, v, w, records[uPlane],
                        records[uPlane + 1], records[vPlane], records[vPlane + 1], wdx, wdy);
                }
                depth[index] = invW;
                color[index] = TextureSampler.sampleLevel(texture, u, v, level);
            }
        }
    }

    // ---- shared inner-loop helpers ----

    // The part of a plane equation that is constant along a scanline. Passed
    // the offset of the plane's y coefficient, so the constant follows it.
    private static float rowConstant(final float[] records, final int dyOffset, final int py)
    {
        return records[dyOffset] * py + records[dyOffset + 1];
    }

    // All three edge functions in one negated test, so a NaN edge value rejects
    // the pixel. Each edge is evaluated rather than accumulated — see the class
    // Javadoc on why the fill rule depends on that.
    private static boolean covered(final float e0dx, final float row0, final float bias0,
        final float e1dx, final float row1, final float bias1,
        final float e2dx, final float row2, final float bias2, final int px)
    {
        if (!(e0dx * px + row0 >= bias0))
        {
            return false;
        }
        if (!(e1dx * px + row1 >= bias1))
        {
            return false;
        }
        return e2dx * px + row2 >= bias2;
    }

    // Analytic screen-space UV derivatives by the quotient rule, then the
    // OpenGL rho / lambda rule. One logarithm per segment, none per pixel.
    private static int selectLevel(final MipChain texture, final float u, final float v,
        final float w, final float udx, final float udy, final float vdx, final float vdy,
        final float wdx, final float wdy)
    {
        final float dudx = (udx - u * wdx) * w;
        final float dvdx = (vdx - v * wdx) * w;
        final float dudy = (udy - u * wdy) * w;
        final float dvdy = (vdy - v * wdy) * w;
        return TextureSampler.levelForLod(texture,
            TextureSampler.lodFromDerivatives(texture, dudx, dvdx, dudy, dvdy));
    }

    // Undoes the perspective divide on one colour channel and clamps it into
    // range. Rgba.pack masks rather than saturates, so the clamp has to be here.
    private static int channel(final float overW, final float w)
    {
        final float value = overW * w;
        if (!(value > 0.0f))
        {
            return 0;
        }
        if (value >= CHANNEL_MAX)
        {
            return (int) CHANNEL_MAX;
        }
        return (int) value;
    }

    private MipChain textureFor(final int material, final MipChain[] textures)
    {
        if (mode != ShadingMode.TEXTURED || textures == null || material < 0
            || material >= textures.length)
        {
            return null;
        }
        return textures[material];
    }

    /** Returns a debug rendering of this renderer's configuration. */
    @Override
    public String toString()
    {
        return "SpanRenderer{mode=" + mode + ", attributeCount=" + attributeCount + "}";
    }
}
