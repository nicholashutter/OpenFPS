/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

/**
 * Bilinear texture sampling, mip level selection and texel fetch for the
 * software rasterizer.
 *
 * Render adapter — must not import from core engine packages.
 *
 * <h2>Position in the pipeline</h2>
 *
 * The span renderer walks a scanline, produces a finished {@code (u, v)} pair
 * per pixel and an LOD per span, and calls
 * {@link #sample(MipChain, float, float, float)}. This class does not compute
 * UVs and knows nothing about perspective correction; it turns a coordinate
 * into a colour. It does own the derivative-to-LOD relationship, via
 * {@link #lodFromDerivatives}, which the span renderer calls once per span.
 *
 * <h2>Allocation</h2>
 *
 * <strong>Nothing here allocates.</strong> Every method is static, takes
 * primitives, and returns a primitive. A sample returns a packed
 * {@code RGBA8888} {@code int}, never an object: at several million samples a
 * frame, returning a colour object would dominate the frame time in GC alone.
 *
 * <h2>Addressing: repeat, not clamp</h2>
 *
 * Out-of-range coordinates <strong>repeat</strong>. This is the decision, and
 * it applies to the whole bilinear footprint, not just the sample point: a
 * sample at {@code u} just below 1.0 blends the last column with the
 * <em>first</em> column, because with repeat addressing those texels are
 * neighbours. Rationale: level surfaces tile, tiling is the common case, and
 * with power-of-two dimensions repeat costs a single AND while clamp costs a
 * compare-and-branch pair per axis. The visible consequence is on atlases —
 * but a bilinear footprint straddling a sub-image boundary bleeds the
 * neighbouring sub-image under clamp addressing too, so clamping would not
 * have fixed atlases. Atlas sub-images need a guard border from the converter
 * regardless; that is an asset-pipeline problem, not a sampler one.
 *
 * <h2>The half-texel offset</h2>
 *
 * Texel {@code i} covers {@code [i/w, (i+1)/w)} and its <em>centre</em> sits at
 * {@code (i + 0.5)/w}. Bilinear weights are defined between texel centres, so
 * the sample point is converted with {@code x = u * w - 0.5} before the
 * integer and fractional parts are taken. Omitting the {@code -0.5} shifts
 * every texture by half a texel in each axis — subtle enough to ship, ugly
 * once seen. The sharpest test of it is that sampling exactly at a texel
 * centre must return that texel bit-for-bit, with no blending; see
 * {@code TextureSamplerTest}.
 *
 * <h2>Filter arithmetic</h2>
 *
 * The blend runs entirely in integers on packed components. Weights are
 * quantised to 8 fractional bits and applied in two stages (horizontal, then
 * vertical), each with round-to-nearest. Two components are lerped per
 * multiply using the {@code 0x00FF00FF} lane trick, so a bilinear sample costs
 * six packed multiply-add pairs instead of twelve scalar ones, and never
 * touches a float. The scalar form of the identical arithmetic lives in the
 * test as an oracle and the two are asserted equal across an exhaustive sweep
 * of weights and inputs — the packed form is a rearrangement of the scalar
 * expression, not an approximation of it.
 *
 * Source — Lance Williams, "Pyramidal Parametrics", SIGGRAPH '83, Computer
 * Graphics 17(3), pp. 1-11. Mipmapping, and the level-of-detail selection
 * reproduced in {@link #lodFromDerivatives}.
 *
 * Source — Paul S. Heckbert, "Survey of Texture Mapping", IEEE Computer
 * Graphics and Applications 6(11), November 1986, pp. 56-67. Bilinear
 * reconstruction, texel-centre placement, and the magnification versus
 * minification filter distinction.
 */
public final class TextureSampler
{
    /** Fractional bits in a filter weight. */
    private static final int WEIGHT_SHIFT = 8;

    /** A filter weight of 1.0 in 0.8 fixed point. Weights live in {@code [0, 256]}. */
    private static final int WEIGHT_ONE = 1 << WEIGHT_SHIFT;

    /**
     * Two lanes of a packed RGBA8888 texel: bits 0-7 and bits 16-23.
     * Shifting the texel right by 8 and masking again yields the other two.
     */
    private static final int LANE_MASK = 0x00FF00FF;

    /** Round-to-nearest bias for both lanes at once — 128 in each 16-bit lane. */
    private static final int LANE_ROUND = 0x00800080;

    /** Distance in bits between the two lanes of a packed pair. */
    private static final int LANE_SHIFT = 8;

    /** Mask isolating one 8-bit component. */
    private static final int BYTE_MASK = 0xFF;

    /** Bit position of the red component in RGBA8888. */
    private static final int RED_SHIFT = 24;

    /** Bit position of the green component in RGBA8888. */
    private static final int GREEN_SHIFT = 16;

    /** Bit position of the blue component in RGBA8888. */
    private static final int BLUE_SHIFT = 8;

    /** Half the reciprocal of ln 2 — converts ln(rho squared) straight to log2(rho). */
    private static final double HALF_INV_LN2 = 0.5 / Math.log(2.0);

    /** Round-to-nearest bias for a float about to be truncated by a cast. */
    private static final float ROUND_HALF = 0.5f;

    /** Half a texel, in texel units. The bilinear centre correction. */
    private static final float HALF_TEXEL = 0.5f;

    private TextureSampler()
    {
        // utility class
    }

    /**
     * Bilinearly samples the mip level nearest the requested LOD.
     *
     * <p>This is the per-pixel entry point. The LOD is resolved to a discrete
     * level by {@link #levelForLod} — there is no trilinear blend between
     * levels, so a sample reads exactly four texels.</p>
     *
     * @param texture the mip chain to read
     * @param u horizontal coordinate, 1.0 being one full texture width; repeats
     * @param v vertical coordinate, 1.0 being one full texture height; repeats
     * @param lod level of detail; clamped to the levels the chain actually has
     * @return the filtered texel, packed RGBA8888
     */
    public static int sample(final MipChain texture, final float u, final float v,
        final float lod)
    {
        return sampleLevel(texture, u, v, levelForLod(texture, lod));
    }

    /**
     * Bilinearly samples one specific mip level.
     *
     * <p>Sampling exactly at a texel centre — {@code u = (i + 0.5) / width} —
     * returns texel {@code i} unchanged, because both fractional weights are
     * then zero. That property is the half-texel offset and the weight
     * rounding working together, and it is what keeps a texture drawn at 1:1
     * scale sharp rather than uniformly blurred.</p>
     *
     * @param texture the mip chain to read
     * @param u horizontal coordinate, 1.0 being one full texture width; repeats
     * @param v vertical coordinate, 1.0 being one full texture height; repeats
     * @param level level index in {@code [0, texture.levelCount())}
     * @return the filtered texel, packed RGBA8888
     */
    public static int sampleLevel(final MipChain texture, final float u, final float v,
        final int level)
    {
        // Texel-centre space: the integer part names the lower-left texel of
        // the 2x2 footprint, the fraction is the blend weight toward the next.
        final float x = u * texture.width(level) - HALF_TEXEL;
        final float y = v * texture.height(level) - HALF_TEXEL;

        final int x0 = floorToInt(x);
        final int y0 = floorToInt(y);

        final int weightX = quantiseWeight(x - x0);
        final int weightY = quantiseWeight(y - y0);

        final int c00 = texture.texel(level, x0, y0);
        final int c10 = texture.texel(level, x0 + 1, y0);
        final int c01 = texture.texel(level, x0, y0 + 1);
        final int c11 = texture.texel(level, x0 + 1, y0 + 1);

        return blend(c00, c10, c01, c11, weightX, weightY);
    }

    /**
     * Blends a 2x2 texel footprint with the given 0.8 fixed-point weights.
     *
     * <p>Exposed because it is the arithmetic the test oracle checks against,
     * and because a span renderer that has already fetched its footprint can
     * call it directly. Weights outside {@code [0, 256]} are not valid and are
     * not checked — this method sits inside the inner loop.</p>
     *
     * @param c00 texel at (x0, y0), packed RGBA8888
     * @param c10 texel at (x0 + 1, y0)
     * @param c01 texel at (x0, y0 + 1)
     * @param c11 texel at (x0 + 1, y0 + 1)
     * @param weightX horizontal weight toward the {@code x0 + 1} column, 0..256
     * @param weightY vertical weight toward the {@code y0 + 1} row, 0..256
     * @return the blended texel, packed RGBA8888
     */
    public static int blend(final int c00, final int c10, final int c01, final int c11,
        final int weightX, final int weightY)
    {
        final int inverseX = WEIGHT_ONE - weightX;
        final int inverseY = WEIGHT_ONE - weightY;

        // Horizontal pass. Each 16-bit lane holds at most
        // 255 * 256 + 128 = 65408, so neighbouring lanes never carry into one
        // another and the unsigned shift keeps the high lane's sign bit honest.
        final int topLow = lerpLanes(c00 & LANE_MASK, c10 & LANE_MASK, weightX, inverseX);
        final int topHigh = lerpLanes((c00 >>> LANE_SHIFT) & LANE_MASK,
            (c10 >>> LANE_SHIFT) & LANE_MASK, weightX, inverseX);
        final int bottomLow = lerpLanes(c01 & LANE_MASK, c11 & LANE_MASK, weightX, inverseX);
        final int bottomHigh = lerpLanes((c01 >>> LANE_SHIFT) & LANE_MASK,
            (c11 >>> LANE_SHIFT) & LANE_MASK, weightX, inverseX);

        // Vertical pass over the two horizontal results, already lane-packed.
        final int low = lerpLanes(topLow, bottomLow, weightY, inverseY);
        final int high = lerpLanes(topHigh, bottomHigh, weightY, inverseY);

        return low | (high << LANE_SHIFT);
    }

    /**
     * Selects the level of detail from screen-space UV derivatives.
     *
     * <p>Williams' rule: scale the derivatives into texels, take the longer of
     * the two screen-axis footprints, and take its base-2 logarithm. One
     * screen pixel covering {@code 2^n} texels selects level {@code n}, which
     * is exactly the level whose texels are one screen pixel across.</p>
     *
     * <p>Magnification — a footprint of one texel or less — returns 0. The
     * result is clamped to the top of the chain, so a caller may pass it
     * straight to {@link #sample} without checking. This is a per-span cost,
     * not a per-pixel one: the logarithm is the only transcendental in the
     * texturing path and it must stay out of the inner loop.</p>
     *
     * @param texture the mip chain the LOD will be used against
     * @param dudx change in u per pixel step in screen x
     * @param dvdx change in v per pixel step in screen x
     * @param dudy change in u per pixel step in screen y
     * @param dvdy change in v per pixel step in screen y
     * @return the level of detail, in {@code [0, levelCount() - 1]}
     */
    public static float lodFromDerivatives(final MipChain texture, final float dudx,
        final float dvdx, final float dudy, final float dvdy)
    {
        final int width = texture.width(0);
        final int height = texture.height(0);

        final float texelsPerXu = dudx * width;
        final float texelsPerXv = dvdx * height;
        final float texelsPerYu = dudy * width;
        final float texelsPerYv = dvdy * height;

        final float lengthSqX = texelsPerXu * texelsPerXu + texelsPerXv * texelsPerXv;
        final float lengthSqY = texelsPerYu * texelsPerYu + texelsPerYv * texelsPerYv;

        // MUTABLE local — the longer of the two screen-axis footprints, squared.
        float rhoSq = lengthSqX;
        if (lengthSqY > rhoSq)
        {
            rhoSq = lengthSqY;
        }

        // A footprint of one texel or less is magnification: level 0, and no
        // logarithm. NaN derivatives fail this comparison and fall through to
        // the clamp below, which returns 0 for a NaN lambda.
        if (rhoSq <= 1.0f)
        {
            return 0.0f;
        }

        // lambda = log2(rho) = log2(rhoSq) / 2, folded into one constant.
        final float lambda = (float) (Math.log(rhoSq) * HALF_INV_LN2);
        return clampLod(texture, lambda);
    }

    /**
     * Resolves a continuous LOD to the nearest available mip level.
     *
     * <p>Rounds to nearest with ties going up, then clamps to
     * {@code [0, levelCount() - 1]}. Both ends matter: an LOD below zero is a
     * magnified surface and must read level 0, and an LOD past the end of a
     * partial chain must read the smallest level present rather than index off
     * the end. A NaN LOD resolves to level 0.</p>
     *
     * @param texture the mip chain being sampled
     * @param lod continuous level of detail, any value
     * @return a valid level index for {@code texture}
     */
    public static int levelForLod(final MipChain texture, final float lod)
    {
        final int topLevel = texture.levelCount() - 1;
        // Also catches NaN: every comparison against NaN is false, so the
        // negated form sends it here rather than into the cast.
        if (!(lod > 0.0f))
        {
            return 0;
        }
        final int level = (int) (lod + ROUND_HALF);
        if (level > topLevel)
        {
            return topLevel;
        }
        return level;
    }

    /**
     * Returns the red component of a packed RGBA8888 texel, 0..255.
     *
     * @param texel packed texel
     */
    public static int red(final int texel)
    {
        return (texel >>> RED_SHIFT) & BYTE_MASK;
    }

    /**
     * Returns the green component of a packed RGBA8888 texel, 0..255.
     *
     * @param texel packed texel
     */
    public static int green(final int texel)
    {
        return (texel >>> GREEN_SHIFT) & BYTE_MASK;
    }

    /**
     * Returns the blue component of a packed RGBA8888 texel, 0..255.
     *
     * @param texel packed texel
     */
    public static int blue(final int texel)
    {
        return (texel >>> BLUE_SHIFT) & BYTE_MASK;
    }

    /**
     * Returns the alpha component of a packed RGBA8888 texel, 0..255.
     *
     * @param texel packed texel
     */
    public static int alpha(final int texel)
    {
        return texel & BYTE_MASK;
    }

    /**
     * Packs four components into an RGBA8888 texel.
     * Each component is masked to 8 bits; out-of-range inputs are truncated,
     * not clamped.
     *
     * @param red red component, 0..255
     * @param green green component, 0..255
     * @param blue blue component, 0..255
     * @param alpha alpha component, 0..255
     * @return the packed texel
     */
    public static int pack(final int red, final int green, final int blue, final int alpha)
    {
        return ((red & BYTE_MASK) << RED_SHIFT)
            | ((green & BYTE_MASK) << GREEN_SHIFT)
            | ((blue & BYTE_MASK) << BLUE_SHIFT)
            | (alpha & BYTE_MASK);
    }

    // Lerps two lane-packed component pairs. Both inputs must already be
    // masked to LANE_MASK; the result is masked back to it.
    private static int lerpLanes(final int a, final int b, final int weight, final int inverse)
    {
        return ((a * inverse + b * weight + LANE_ROUND) >>> WEIGHT_SHIFT) & LANE_MASK;
    }

    // Quantises a [0, 1) fraction to a 0.8 fixed-point weight in [0, 256].
    private static int quantiseWeight(final float fraction)
    {
        return (int) (fraction * WEIGHT_ONE + ROUND_HALF);
    }

    // Floor toward negative infinity without touching double. A plain cast
    // truncates toward zero, which is wrong for the negative UVs that repeat
    // addressing has to handle.
    private static int floorToInt(final float value)
    {
        final int truncated = (int) value;
        if (value < truncated)
        {
            return truncated - 1;
        }
        return truncated;
    }

    // Clamps a continuous LOD to the levels the chain actually has.
    private static float clampLod(final MipChain texture, final float lambda)
    {
        if (!(lambda > 0.0f))
        {
            return 0.0f;
        }
        final float topLevel = texture.levelCount() - 1;
        if (lambda > topLevel)
        {
            return topLevel;
        }
        return lambda;
    }
}
