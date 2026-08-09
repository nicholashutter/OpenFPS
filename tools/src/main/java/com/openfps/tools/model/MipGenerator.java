/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.model;

import com.openfps.engine.render.adapter.Rgba;

/**
 * Builds a complete mipmap pyramid from a level 0 image.
 *
 * Build-time only — this type is never on a runtime classpath.
 *
 * <h2>Why this runs offline</h2>
 *
 * {@code docs/ASSETS.md} § 5 makes mipmaps <strong>required</strong>, not
 * optional: unmipmapped minification is both aliased and slow, because it
 * destroys texture cache locality exactly when the texture is being sampled
 * sparsely. Generating the pyramid costs a few milliseconds once, at build
 * time, and saves it on every frame forever. That is the whole argument of
 * {@code docs/ASSETS.md} § 4 in miniature.
 *
 * <h2>The filter</h2>
 *
 * A 2x2 box filter, averaging each channel independently with round-half-up.
 * Level {@code k} measures {@code max(1, width >> k)} by
 * {@code max(1, height >> k)}, exactly as {@link com.openfps.engine.render.adapter.MipChain}
 * requires. When a dimension has already collapsed to 1 the two source
 * coordinates along that axis coincide, so the same code path handles
 * non-square textures without a special case.
 *
 * <h2>Two known approximations, recorded rather than hidden</h2>
 *
 * The average is taken on the stored 8-bit values, which are sRGB-encoded, not
 * linear. A gamma-correct downsample would decode to linear, average, and
 * re-encode; it produces slightly brighter mips on high-contrast texels. It is
 * not done here because the renderer does no lighting at all — vertex colour
 * is baked and multiplied directly — so there is no linear-light stage for the
 * correction to be consistent with. Alpha is averaged straight rather than
 * premultiplied, which is only visibly wrong at hard alpha edges over very
 * different colours; the budget is albedo-only and near-universally opaque.
 *
 * Source — Lance Williams, "Pyramidal Parametrics", SIGGRAPH '83,
 * Computer Graphics 17(3), pp. 1-11.
 */
public final class MipGenerator
{
    /** Rounding bias for a four-sample average: half of the divisor. */
    private static final int ROUND_FOUR = 2;

    /** Samples in one box filter footprint. */
    private static final int BOX_SAMPLES = 4;

    private MipGenerator()
    {
        // utility class
    }

    /**
     * Returns the number of mip levels a texture of the given size has,
     * counting level 0 and ending at 1x1.
     *
     * @param width level 0 width in texels, a power of two
     * @param height level 0 height in texels, a power of two
     * @return the level count, at least one
     */
    public static int levelCount(final int width, final int height)
    {
        return Integer.numberOfTrailingZeros(Math.max(width, height)) + 1;
    }

    /**
     * Generates the full pyramid, level 0 first, down to 1x1.
     *
     * <p>Level 0 is copied, not aliased, so the caller may reuse its array.</p>
     *
     * @param width level 0 width in texels; must be a power of two
     * @param height level 0 height in texels; must be a power of two
     * @param level0 level 0 texels, RGBA8888 packed, row-major,
     *     {@code width * height} entries
     * @return one array per level, level 0 first
     * @throws IllegalArgumentException if the dimensions are not powers of two
     *     or {@code level0} is not exactly {@code width * height} entries
     */
    public static int[][] generate(final int width, final int height, final int[] level0)
    {
        requirePowerOfTwo(width, "width");

        requirePowerOfTwo(height, "height");

        if (level0 == null || level0.length != width * height)
        {
            throw new IllegalArgumentException("level 0 of a " + width + "x" + height
                + " texture needs " + (width * height) + " texels, got " + describe(level0));
        }

        final int levels = levelCount(width, height);

        final int[][] pyramid = new int[levels][];

        pyramid[0] = new int[level0.length];

        System.arraycopy(level0, 0, pyramid[0], 0, level0.length);

        for (int level = 1; level < levels; level++)
        {
            final int sourceWidth = Math.max(1, width >> (level - 1));

            final int sourceHeight = Math.max(1, height >> (level - 1));

            pyramid[level] = downsample(pyramid[level - 1], sourceWidth, sourceHeight);
        }

        return pyramid;
    }

    /**
     * Halves an image with a 2x2 box filter.
     *
     * <p>Exposed so the filter itself can be tested against hand-computed
     * expectations without building a whole pyramid.</p>
     *
     * @param source source texels, RGBA8888 packed, row-major
     * @param sourceWidth source width in texels
     * @param sourceHeight source height in texels
     * @return the halved image, {@code max(1, w/2) * max(1, h/2)} texels
     */
    public static int[] downsample(final int[] source, final int sourceWidth,
        final int sourceHeight)
    {
        final int targetWidth = Math.max(1, sourceWidth >> 1);

        final int targetHeight = Math.max(1, sourceHeight >> 1);

        final int[] target = new int[targetWidth * targetHeight];

        for (int y = 0; y < targetHeight; y++)
        {
            // When the source axis has collapsed to 1 these coincide, and the
            // box filter degenerates to averaging the same texel twice.
            final int y0 = Math.min(y * 2, sourceHeight - 1);

            final int y1 = Math.min((y * 2) + 1, sourceHeight - 1);

            for (int x = 0; x < targetWidth; x++)
            {
                final int x0 = Math.min(x * 2, sourceWidth - 1);

                final int x1 = Math.min((x * 2) + 1, sourceWidth - 1);

                target[(y * targetWidth) + x] = average(
                    source[(y0 * sourceWidth) + x0],
                    source[(y0 * sourceWidth) + x1],
                    source[(y1 * sourceWidth) + x0],
                    source[(y1 * sourceWidth) + x1]);
            }
        }

        return target;
    }

    // Averages four packed texels per channel, rounding half up.
    private static int average(final int a, final int b, final int c, final int d)
    {
        final int red = (Rgba.red(a) + Rgba.red(b) + Rgba.red(c) + Rgba.red(d) + ROUND_FOUR)
            / BOX_SAMPLES;

        final int green = (Rgba.green(a) + Rgba.green(b) + Rgba.green(c) + Rgba.green(d)
            + ROUND_FOUR) / BOX_SAMPLES;

        final int blue = (Rgba.blue(a) + Rgba.blue(b) + Rgba.blue(c) + Rgba.blue(d) + ROUND_FOUR)
            / BOX_SAMPLES;

        final int alpha = (Rgba.alpha(a) + Rgba.alpha(b) + Rgba.alpha(c) + Rgba.alpha(d)
            + ROUND_FOUR) / BOX_SAMPLES;

        return Rgba.pack(red, green, blue, alpha);
    }

    // Rejects anything that is not a positive power of two, naming the value.
    private static void requirePowerOfTwo(final int value, final String name)
    {
        if (value <= 0 || (value & (value - 1)) != 0)
        {
            throw new IllegalArgumentException(
                "texture " + name + " must be a power of two, got " + value);
        }
    }

    // Describes an array length for an error message, tolerating null.
    private static String describe(final int[] array)
    {
        if (array == null)
        {
            return "null";
        }

        return Integer.toString(array.length);
    }
}
