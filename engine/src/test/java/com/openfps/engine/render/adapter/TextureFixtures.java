/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import java.util.Arrays;

/**
 * Builds mip chains for the sampler tests.
 *
 * <p>This is test scaffolding, not production code. In the shipping engine
 * mip chains arrive fully generated from the build-time asset converter
 * ({@code docs/ASSETS.md} section 5); nothing at runtime builds one. The box
 * filter here exists only so a test can hand the sampler a plausible pyramid.</p>
 *
 * <p>Everything is deterministic. The "random" texels come from a fixed-seed
 * linear congruential generator so a failure reproduces exactly.</p>
 */
final class TextureFixtures
{
    /** Multiplier of the Numerical Recipes LCG — deterministic filler, not cryptography. */
    private static final long LCG_MULTIPLIER = 1664525L;

    /** Increment of the same LCG. */
    private static final long LCG_INCREMENT = 1013904223L;

    /** Modulus of the same LCG. */
    private static final long LCG_MODULUS = 1L << 32;

    private TextureFixtures()
    {
        // test utility
    }

    /** Packs a texel, mirroring the sampler's RGBA8888 layout. */
    static int rgba(final int red, final int green, final int blue, final int alpha)
    {
        return TextureSampler.pack(red, green, blue, alpha);
    }

    /** Builds a chain from explicit level arrays, level 0 first. */
    static MipChain chain(final int width, final int height, final int[][] levels)
    {
        return new MipChain(width, height, levels);
    }

    /** Builds a single-level chain from one array of texels. */
    static MipChain single(final int width, final int height, final int[] texels)
    {
        return new MipChain(width, height, new int[][] {texels});
    }

    /** Builds a chain of the requested depth in which every texel is one colour. */
    static MipChain solid(final int width, final int height, final int levelCount,
        final int colour)
    {
        final int[][] levels = new int[levelCount][];

        for (int level = 0; level < levelCount; level++)
        {
            final int levelWidth = Math.max(1, width >> level);

            final int levelHeight = Math.max(1, height >> level);

            levels[level] = new int[levelWidth * levelHeight];

            Arrays.fill(levels[level], colour);
        }

        return new MipChain(width, height, levels);
    }

    /**
     * Builds a level in which every texel carries its own coordinates, so a
     * misplaced fetch names the texel it wrongly read.
     * Red is x, green is y, blue and alpha are fixed markers.
     */
    static int[] coordinateTexels(final int width, final int height)
    {
        final int[] texels = new int[width * height];

        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                texels[y * width + x] = rgba(x, y, 0x40, 0x80);
            }
        }

        return texels;
    }

    /** Builds a deterministic pseudo-random level. */
    static int[] pseudoRandomTexels(final int width, final int height, final long seed)
    {
        final int[] texels = new int[width * height];

        // MUTABLE local — LCG state.
        long state = seed;

        for (int i = 0; i < texels.length; i++)
        {
            state = (state * LCG_MULTIPLIER + LCG_INCREMENT) % LCG_MODULUS;

            texels[i] = (int) state;
        }

        return texels;
    }

    /**
     * Box-filters a base level down to a full pyramid ending at 1x1.
     * Test scaffolding only — the engine never builds a chain at runtime.
     */
    static MipChain pyramid(final int width, final int height, final int[] base)
    {
        final int levelCount = log2(Math.max(width, height)) + 1;

        final int[][] levels = new int[levelCount][];

        levels[0] = base.clone();

        for (int level = 1; level < levelCount; level++)
        {
            levels[level] = halve(levels[level - 1],
                Math.max(1, width >> (level - 1)), Math.max(1, height >> (level - 1)));
        }

        return new MipChain(width, height, levels);
    }

    // Averages each 2x2 block, or each 1x2 / 2x1 pair when an axis is already 1.
    private static int[] halve(final int[] src, final int srcWidth, final int srcHeight)
    {
        final int dstWidth = Math.max(1, srcWidth >> 1);

        final int dstHeight = Math.max(1, srcHeight >> 1);

        final int[] dst = new int[dstWidth * dstHeight];

        for (int y = 0; y < dstHeight; y++)
        {
            for (int x = 0; x < dstWidth; x++)
            {
                final int x0 = Math.min(srcWidth - 1, x * 2);

                final int x1 = Math.min(srcWidth - 1, x * 2 + 1);

                final int y0 = Math.min(srcHeight - 1, y * 2);

                final int y1 = Math.min(srcHeight - 1, y * 2 + 1);

                dst[y * dstWidth + x] = average(
                    src[y0 * srcWidth + x0], src[y0 * srcWidth + x1],
                    src[y1 * srcWidth + x0], src[y1 * srcWidth + x1]);
            }
        }

        return dst;
    }

    // Component-wise mean of four packed texels.
    private static int average(final int a, final int b, final int c, final int d)
    {
        final int red = (TextureSampler.red(a) + TextureSampler.red(b)
            + TextureSampler.red(c) + TextureSampler.red(d)) / 4;

        final int green = (TextureSampler.green(a) + TextureSampler.green(b)
            + TextureSampler.green(c) + TextureSampler.green(d)) / 4;

        final int blue = (TextureSampler.blue(a) + TextureSampler.blue(b)
            + TextureSampler.blue(c) + TextureSampler.blue(d)) / 4;

        final int alpha = (TextureSampler.alpha(a) + TextureSampler.alpha(b)
            + TextureSampler.alpha(c) + TextureSampler.alpha(d)) / 4;

        return rgba(red, green, blue, alpha);
    }

    // Exact base-2 logarithm of a positive power of two.
    private static int log2(final int powerOfTwo)
    {
        return Integer.numberOfTrailingZeros(powerOfTwo);
    }
}
