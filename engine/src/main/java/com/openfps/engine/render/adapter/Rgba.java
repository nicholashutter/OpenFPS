/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

/**
 * The single definition of the renderer's packed colour format.
 *
 * <b>Format: {@code 0xRRGGBBAA}</b> — red in the most significant byte, alpha
 * in the least. This is RGBA8888, the format the colour buffer, the texture
 * texels and the presentation upload all share, so that nothing in the
 * per-pixel path ever swizzles. {@code docs/ASSETS.md} § 4 records why RGBA
 * won: the colour buffer's format is pinned from outside by the presentation
 * path (libGDX {@code Pixmap.Format.RGBA8888}), while the texture's format is
 * ours to choose, so the free side moved.
 *
 * <b>Why this class exists.</b> {@link Framebuffer} and {@link TextureSampler}
 * were written in parallel and each grew its own identical copy of these five
 * operations. They agreed — same shifts, same masking — but two definitions of
 * one format is exactly the duplication {@code AGENTS.md} rule 1 forbids, and
 * the failure mode if they ever drifted would be a channel swap visible only
 * as slightly-wrong colour. This is the canonical set; new code uses it.
 *
 * <b>Masking, not clamping.</b> Components are masked to 8 bits, so an
 * out-of-range input truncates rather than saturating. Callers that need
 * saturation must clamp before packing. This matches what the packed-lane
 * blend in {@link TextureSampler} relies on and is cheaper in the inner loop,
 * where inputs are already known to be in range.
 *
 * Stateless and non-instantiable — every method is a pure function of its
 * arguments and allocates nothing.
 */
public final class Rgba
{
    /** Bit position of the red channel in a packed {@code 0xRRGGBBAA} value. */
    public static final int RED_SHIFT = 24;

    /** Bit position of the green channel. */
    public static final int GREEN_SHIFT = 16;

    /** Bit position of the blue channel. */
    public static final int BLUE_SHIFT = 8;

    /** Bit position of the alpha channel. Zero — alpha is the low byte. */
    public static final int ALPHA_SHIFT = 0;

    /** Mask selecting one 8-bit channel. */
    public static final int CHANNEL_MASK = 0xFF;

    /** Opaque black, {@code 0x000000FF}. */
    public static final int OPAQUE_BLACK = CHANNEL_MASK;

    // Non-instantiable: this is a function table, not an object.
    private Rgba()
    {
        throw new AssertionError("Rgba is not instantiable");
    }

    /**
     * Composites a source colour over a destination one — the source-over
     * operator, {@code out = src * a + dst * (1 - a)} per channel.
     *
     * <h2>Integer, not float, and that is a correctness decision</h2>
     *
     * <p>This runs once per covered translucent pixel in the hottest loop in
     * the engine, but speed is the lesser reason. Two peers rendering the same
     * tic must agree on the bytes — the render path is already held to that
     * standard, which is why the pooled frame is asserted bit-identical to the
     * serial one at every worker count. Integer arithmetic is exact and
     * reproducible on every JVM; a float multiply-add here would be neither
     * guaranteed to round the same way nor cheaper.</p>
     *
     * <p>The division by 255 is the rounded form
     * {@code (x + 127 + ((x + 127) >> 8)) >> 8}, which equals
     * {@code round(x / 255.0)} for every {@code x} a channel product can
     * produce. The naive {@code x >> 8} divides by 256 instead and loses a step
     * of brightness, so an {@code alpha} of 255 would not reproduce the source
     * exactly. That round trip is asserted rather than assumed.</p>
     *
     * <p><b>Alpha out is the destination's.</b> The colour buffer is opaque and
     * must stay opaque — it is presented to a window, not composited again — so
     * carrying the source's alpha through would quietly make the frame
     * translucent.</p>
     *
     * @param src the incoming colour, packed {@code 0xRRGGBBAA}; its own alpha
     *     channel is ignored in favour of {@code coverage}
     * @param dst the colour already in the buffer, packed {@code 0xRRGGBBAA}
     * @param coverage coverage of the source, 0-255; 0 leaves {@code dst}
     *     untouched and 255 reproduces {@code src} exactly
     * @return the composited colour, carrying {@code dst}'s alpha channel
     */
    public static int srcOver(final int src, final int dst, final int coverage)
    {
        final int a = coverage & CHANNEL_MASK;

        final int inverse = CHANNEL_MASK - a;

        return pack(
            div255(red(src) * a + red(dst) * inverse),
            div255(green(src) * a + green(dst) * inverse),
            div255(blue(src) * a + blue(dst) * inverse),
            alpha(dst));
    }

    // Rounded division by 255, exact across the whole range a channel product
    // can occupy. See srcOver for why this is not a shift.
    private static int div255(final int value)
    {
        final int biased = value + 127;

        return (biased + (biased >> 8)) >> 8;
    }

    /**
     * Packs four channels into one {@code 0xRRGGBBAA} value.
     *
     * Each channel is masked to 8 bits; out-of-range inputs truncate rather
     * than clamp.
     *
     * @param red red channel, 0-255
     * @param green green channel, 0-255
     * @param blue blue channel, 0-255
     * @param alpha alpha channel, 0-255
     * @return the packed colour
     */
    public static int pack(final int red, final int green, final int blue, final int alpha)
    {
        return ((red & CHANNEL_MASK) << RED_SHIFT)
            | ((green & CHANNEL_MASK) << GREEN_SHIFT)
            | ((blue & CHANNEL_MASK) << BLUE_SHIFT)
            | (alpha & CHANNEL_MASK);
    }

    /**
     * Extracts the red channel.
     *
     * @param rgba packed {@code 0xRRGGBBAA} colour
     * @return red, 0-255
     */
    public static int red(final int rgba)
    {
        return (rgba >>> RED_SHIFT) & CHANNEL_MASK;
    }

    /**
     * Extracts the green channel.
     *
     * @param rgba packed {@code 0xRRGGBBAA} colour
     * @return green, 0-255
     */
    public static int green(final int rgba)
    {
        return (rgba >>> GREEN_SHIFT) & CHANNEL_MASK;
    }

    /**
     * Extracts the blue channel.
     *
     * @param rgba packed {@code 0xRRGGBBAA} colour
     * @return blue, 0-255
     */
    public static int blue(final int rgba)
    {
        return (rgba >>> BLUE_SHIFT) & CHANNEL_MASK;
    }

    /**
     * Extracts the alpha channel.
     *
     * @param rgba packed {@code 0xRRGGBBAA} colour
     * @return alpha, 0-255
     */
    public static int alpha(final int rgba)
    {
        return rgba & CHANNEL_MASK;
    }
}
