/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import java.util.Random;

/**
 * Shared helpers for the {@link Rasterizer} and {@link SpanRenderer} tests.
 *
 * Tests are far easier to read in screen coordinates than in clip space, so
 * everything here builds clip-space vertices from a screen position and a
 * {@code w}, inverting the viewport transform in {@code render/README.md} § 4.
 * The round trip is not bit-exact and does not need to be: what the fill-rule
 * test depends on is that two triangles sharing a vertex are given the
 * <em>same</em> clip-space floats, which they are.
 */
final class RasterFixtures
{
    /** Empty attribute block, for position-only geometry. */
    static final float[] NO_ATTRIBUTES = new float[0];

    private RasterFixtures()
    {
        throw new AssertionError("RasterFixtures is not instantiable");
    }

    /** Inverts the viewport transform's x half. */
    static float clipX(final float screenX, final float w, final int width)
    {
        return (2.0f * screenX / width - 1.0f) * w;
    }

    /** Inverts the viewport transform's y half, flip included. */
    static float clipY(final float screenY, final float w, final int height)
    {
        return (1.0f - 2.0f * screenY / height) * w;
    }

    /** Writes one clip-space vertex at {@code offset}. */
    static void vertex(final float[] out, final int offset, final int width, final int height,
        final float screenX, final float screenY, final float w, final float[] attributes)
    {
        out[offset] = clipX(screenX, w, width);

        out[offset + 1] = clipY(screenY, w, height);

        out[offset + 2] = w;

        System.arraycopy(attributes, 0, out, offset + TriangleClipper.POSITION_FLOATS,
            attributes.length);
    }

    /**
     * Writes one triangle, three vertices at a shared {@code w}, into a stream
     * whose vertices carry {@code attributes[i].length} attributes each.
     *
     * @param out the vertex stream
     * @param triangle index of the triangle to write
     * @param width framebuffer width
     * @param height framebuffer height
     * @param screen six floats: x0, y0, x1, y1, x2, y2
     * @param w the shared clip-space w
     * @param attributes three attribute blocks, one per vertex
     */
    static void triangle(final float[] out, final int triangle, final int width,
        final int height, final float[] screen, final float w, final float[][] attributes)
    {
        final int stride = TriangleClipper.POSITION_FLOATS + attributes[0].length;

        final int base = triangle * TriangleClipper.TRIANGLE_VERTICES * stride;

        for (int v = 0; v < TriangleClipper.TRIANGLE_VERTICES; v++)
        {
            vertex(out, base + v * stride, width, height, screen[v * 2], screen[v * 2 + 1], w,
                attributes[v]);
        }
    }

    /** Builds a framebuffer of the given size and tile size, cleared. */
    static Framebuffer framebuffer(final int width, final int height, final int tileSize)
    {
        final Framebuffer target = new Framebuffer(tileSize);

        target.init(width, height);

        target.clear();

        return target;
    }

    /**
     * Builds a deterministic pseudo-random scene of position-only triangles.
     *
     * Positions deliberately overrun the viewport on every side so that
     * clamping, off-screen rejection and partial tile coverage all get
     * exercised, and {@code w} spans an order of magnitude so the depth test has
     * something to resolve.
     *
     * @param seed random seed
     * @param triangles how many to build
     * @param width framebuffer width
     * @param height framebuffer height
     * @param colors filled with one packed RGBA8888 colour per triangle
     * @return the clip-space vertex stream
     */
    static float[] randomScene(final long seed, final int triangles, final int width,
        final int height, final int[] colors)
    {
        final Random random = new Random(seed);

        final int stride = TriangleClipper.POSITION_FLOATS;

        final float[] out = new float[triangles * TriangleClipper.TRIANGLE_VERTICES * stride];

        final float[] screen = new float[6];

        final float[][] attributes = {NO_ATTRIBUTES, NO_ATTRIBUTES, NO_ATTRIBUTES};

        for (int t = 0; t < triangles; t++)
        {
            for (int k = 0; k < 3; k++)
            {
                screen[k * 2] = random.nextFloat() * (width + 40.0f) - 20.0f;

                screen[k * 2 + 1] = random.nextFloat() * (height + 40.0f) - 20.0f;
            }

            final float w = 0.5f + random.nextFloat() * 9.5f;

            triangle(out, t, width, height, screen, w, attributes);

            colors[t] = Rgba.pack(random.nextInt(256), random.nextInt(256),
                random.nextInt(256), 255);
        }

        return out;
    }

    /** Collects the indices of every colour-buffer pixel differing from the clear value. */
    static boolean[] paintedMask(final Framebuffer target, final int clearColor)
    {
        final boolean[] painted = new boolean[target.width() * target.height()];

        for (int y = 0; y < target.height(); y++)
        {
            for (int x = 0; x < target.width(); x++)
            {
                painted[y * target.width() + x] = target.pixel(x, y) != clearColor;
            }
        }

        return painted;
    }
}
