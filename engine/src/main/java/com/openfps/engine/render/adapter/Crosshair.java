/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import java.util.Arrays;

/**
 * R_ The aiming reticle: a black-outlined green cross with a centre gap, drawn
 * into the colour buffer after every other pass.
 *
 * Platform adapter — must not import from core engine packages.
 *
 * <b>This class draws and nothing else.</b> It owns no state, allocates
 * nothing, and reads no pixel it is about to write. {@link #draw(Framebuffer)}
 * is a pure function of the framebuffer's dimensions: the same buffer geometry
 * always produces the same bytes, and drawing twice is indistinguishable from
 * drawing once (see <i>Idempotence</i> below).
 *
 * <h2>Why a green core inside a black outline — measured against the real scene</h2>
 *
 * <p>The colours are not a taste call. They were chosen against a rendered
 * 1280x720 frame of the demo room ({@code tools:demoPreview}, shot
 * {@code 01-down-room}), sampled per pixel. What that frame actually
 * contains, as sRGB and Rec.709 relative luminance:</p>
 *
 * <pre>
 *   pale side wall      #A1A9CA   lum 170
 *   far wall            #959CBB   lum 157
 *   ceiling / near floor#8D93B1   lum 148
 *   far checker floor   #7E839B   lum 132
 *   dark doorway        #181C26   lum  28   &lt;- darkest pixel in the frame
 *   weapon body, orange #DF5C4A   lum 119
 *   crate decal, amber  #FFC457   lum 201   &lt;- brightest pixel in the frame
 * </pre>
 *
 * <p><b>The scene occupies luminance 28 to 201, and it is chromatically almost
 * empty.</b> Every surface is either blue-grey (hue about 230 degrees, barely
 * saturated) or orange-to-amber (hue 8 to 38 degrees). Of 230,400 pixels
 * sampled across that frame, the number in which green is the dominant channel
 * is <b>zero</b>. Green is a hue the demo scene does not use at all.</p>
 *
 * <p>That gives two independent axes to exploit, and the design uses one for
 * each element:</p>
 *
 * <ul>
 *   <li><b>{@link #CORE_COLOR} is pure green, {@code #00FF00}, luminance
 *       182.</b> It sits <i>above</i> every scene surface except the amber
 *       decal, so it carries the contrast against the dark end of the range —
 *       182 against the doorway's 28, and 63 against the orange weapon body,
 *       from which it is additionally 112 degrees away in hue with both
 *       colours fully saturated, so they cannot be read as the same thing.</li>
 *   <li><b>{@link #OUTLINE_COLOR} is pure black, {@code #000000}, luminance
 *       0.</b> It carries the contrast against the bright end — 170 against
 *       the pale walls, 201 against the amber decal, 148 against the floor.</li>
 * </ul>
 *
 * <p><b>The pair cannot both be defeated, and that is the point.</b> The
 * scene's whole luminance range (28 to 201, a span of 173) is narrower than
 * the span between the two reticle colours (0 to 182). Any background
 * whatsoever is therefore far from at least one of them: a background bright
 * enough to swallow the green core is by construction miles from the black
 * outline, and one dark enough to swallow the outline is miles from the core.
 * A single-colour reticle has no such guarantee, which is exactly why plain
 * white vanishes on the walls and plain black vanishes in the doorway.</p>
 *
 * <p><b>Why green rather than white, cyan or magenta.</b> White would have the
 * best luminance contrast of all, but it is achromatic in an achromatic scene
 * and the weapon's own highlights are already near-white — it reads as part of
 * the picture. Green keeps almost all of white's luminance (182 of 255) and
 * <i>adds</i> a saturation and hue separation that white cannot have. Cyan and
 * blue are the scene's own hue family and would sit inside the blue-grey cast.
 * Magenta's luminance is 105, in the middle of the scene's range, so it has
 * poor luminance contrast against the floor and almost none against the
 * weapon. Amber and red are ruled out by rule: they are the weapon's colours,
 * and the reticle must never be confused with the gun.</p>
 *
 * <h2>Why there is a centre gap, and no centre dot</h2>
 *
 * <p>{@link #CENTRE_GAP_DIVISOR} leaves the exact point of aim unpainted. That
 * is the standard FPS answer and it is the right one here: the pixel the
 * player is trying to put on a target is the one pixel the reticle must not
 * cover, and at this size a solid cross would hide a distant target entirely.
 * The four arms converge on the gap from four directions, which localises the
 * centre to within a pixel without occluding it.</p>
 *
 * <p><b>There is deliberately no dot in the gap.</b> A dot would re-cover the
 * pixel the gap exists to reveal, and it would put a second, competing centre
 * inside the first. The gap is either worth having or it is not; half of it is
 * worse than either.</p>
 *
 * <h2>Size — every dimension is a fixed fraction of frame HEIGHT</h2>
 *
 * <p>Height, not width and not a pixel count. A reticle scaled from a pixel
 * count becomes a dot at 1440p and swamps a small window; scaled from width it
 * changes size when the window's aspect ratio changes, which is wrong because
 * the vertical field of view is what is held fixed
 * ({@code SoftwareRenderPort.DEFAULT_FOV_Y}). Fractions of height are
 * fractions of a constant angular field, so the reticle subtends the same
 * angle at every resolution and aspect.</p>
 *
 * <pre>
 *   fraction of H          divisor   at 720p   at 1440p
 *   arm length      H/9        9       80 px    160 px
 *   centre gap      H/60      60       12 px     24 px
 *   core thickness  H/100    100        7 px     14 px
 *   outline         H/300    300        2 px      4 px
 *
 *   banded arm width = core + 2 x outline    11 px     22 px
 *   tip to tip = 2 x (gap + arm + outline)  188 px    376 px
 *                                          = 26.1% of frame height
 * </pre>
 *
 * <p>A cross spanning a quarter of the screen with an eleven-pixel-wide banded
 * arm is not subtle, and it is not meant to be — "very big and visible and
 * noticeable" was the requirement, so the ratios are set loud rather than
 * tasteful. They were set by looking at a rendered frame, not by taste: the
 * first attempt at {@code H/12} arms and a 9-pixel band read clearly but not
 * boldly against the demo room, and was widened.</p>
 *
 * <p>Each figure has a floor of one pixel (the gap, of {@code outline + 1}) so
 * that a very small framebuffer still gets a well-formed miniature rather than
 * a figure with a zero-thickness part. The ratios keep the arms clear of the
 * gap by a factor of over two and a half at every size: the gap's half-extent
 * less the outline is {@code H/75}, against a half-thickness of {@code H/200}
 * for the outlined perpendicular bar.</p>
 *
 * <h2>Exact centring on even dimensions — the real decision</h2>
 *
 * <p>A 1280x720 frame has <b>no centre pixel</b>. Its geometric centre is the
 * corner between columns 639 and 640, not a pixel. Rounding to one of them
 * puts the reticle half a pixel off and, worse, makes it asymmetric — one arm
 * a pixel longer than the other, which is visible on a high-contrast figure
 * this size.</p>
 *
 * <p><b>So the reticle is centred on the geometric centre, not on a pixel, and
 * every span's thickness is nudged up by one pixel when its parity does not
 * match the parity of the axis it sits in.</b> A span of {@code s} pixels in
 * an axis of {@code n} leaves margins of {@code (n - s) / 2} on both sides,
 * and that is a whole number of pixels on each side exactly when {@code s} and
 * {@code n} share a parity. {@link #matchParity(int, int)} enforces it, so a
 * 5-pixel core arm becomes 6 pixels thick in an even-height frame and stays 5
 * in an odd-height one.</p>
 *
 * <p>The consequence is stronger than "centred within a pixel" and is what the
 * tests assert: <b>the drawn figure is bit-identical to its own mirror image
 * about both axes</b>, for every frame size, because every span
 * {@code [lo, lo + s)} with {@code lo = (n - s) / 2} and {@code s} of the same
 * parity as {@code n} maps onto itself under {@code x -> n - 1 - x}. The
 * horizontal arm's thickness follows the frame HEIGHT's parity and the
 * vertical arm's follows the WIDTH's, because a bar's thickness lies along the
 * axis perpendicular to its length.</p>
 *
 * <p>The same arithmetic survives clipping: {@code n - matchParity(n, s)} is
 * always even, so the halving is exact even when it is negative, and clamping
 * a symmetric span to the frame leaves it symmetric.</p>
 *
 * <h2>Clipping</h2>
 *
 * <p>Every rectangle is clamped to the visible {@code width x height}
 * rectangle and skipped when it clips to nothing, so a framebuffer smaller
 * than the reticle draws a correctly cropped fragment of it rather than
 * throwing. A 64x64 buffer, a 4x400 sliver and a 1x1 buffer are all legal
 * inputs. Nothing is ever written into the stride padding
 * ({@link Framebuffer#strideInPixels()}), which belongs to no tile.</p>
 *
 * <h2>Cost, allocation and idempotence</h2>
 *
 * <p>Eight rectangle fills — four arms of the fattened outline figure, then
 * four of the core figure over the top. Drawing the outline as a grown copy of
 * the same shape rather than as a per-pixel border is what keeps the inner
 * loop free of branches: it is {@link Arrays#fill(int[], int, int, int)} per
 * row, an intrinsic, with the only conditionals being the eight rectangle
 * clips. About 5,800 stores covering 3,600 pixels at 720p — 0.39% of the
 * frame — and no allocation of any kind.</p>
 *
 * <p><b>Idempotent, by construction.</b> Every write is an unconditional store
 * of a constant and no destination pixel is ever read, so there is no blend to
 * accumulate. Calling {@link #draw(Framebuffer)} twice on one frame produces
 * exactly the bytes one call produces. This is asserted rather than assumed,
 * because it is what makes the call safe to make from a frame path that might
 * legitimately run it more than once.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>Stateless, so it is safe to call from any thread — but it writes across
 * tile boundaries and therefore must <b>not</b> run concurrently with the tile
 * raster pass, whose lock-free correctness rests on one worker owning each
 * tile outright ({@code render/README.md} section 7). It is a serial pass at
 * the end of a frame, after the viewmodel.</p>
 */
public final class Crosshair
{
    /**
     * The reticle's core colour: pure green, {@code #00FF00}, luminance 182.
     * A hue the demo scene does not contain a single pixel of. See the class
     * Javadoc for the measurement that chose it.
     */
    public static final int CORE_COLOR = Rgba.pack(0, 255, 0, 255);

    /**
     * The colour of the border drawn around the core: pure black, luminance 0.
     * It supplies the contrast against pale walls, which the green core alone
     * cannot.
     */
    public static final int OUTLINE_COLOR = Rgba.pack(0, 0, 0, 255);

    /**
     * Frame height divided by this is the length of one arm, measured from the
     * inner edge of the gap outwards: 80 px at 720p, 160 px at 1440p.
     */
    public static final int ARM_LENGTH_DIVISOR = 9;

    /**
     * Frame height divided by this is the half-width of the unpainted centre
     * gap: 12 px at 720p, so a 24-pixel-square hole at the point of aim.
     */
    public static final int CENTRE_GAP_DIVISOR = 60;

    /**
     * Frame height divided by this is the thickness of an arm's green core:
     * 7 px at 720p, 14 px at 1440p.
     */
    public static final int CORE_THICKNESS_DIVISOR = 100;

    /**
     * Frame height divided by this is the thickness of the black border on
     * each side of the core: 2 px at 720p, 4 px at 1440p, making a banded arm
     * 11 px wide at 720p.
     */
    public static final int OUTLINE_THICKNESS_DIVISOR = 300;

    /**
     * Smallest value any derived size may take. A part of the figure that
     * rounded down to zero would not be a smaller reticle, it would be a
     * broken one.
     */
    private static final int MIN_SIZE = 1;

    // Non-instantiable: this is one drawing routine, not an object.
    private Crosshair()
    {
        throw new AssertionError("Crosshair is not instantiable");
    }

    /**
     * Draws the reticle into the framebuffer's colour buffer.
     *
     * <p>Call once per frame, after the world pass, the outline pass and the
     * viewmodel pass — the reticle is the topmost thing on screen and is not
     * depth-tested, because it is not in the world.</p>
     *
     * <p>Allocates nothing, reads no pixel, and is idempotent: calling it
     * twice on one frame is indistinguishable from calling it once.</p>
     *
     * @param target the framebuffer to draw into; must be
     *     {@link Framebuffer.State#READY}
     * @throws IllegalArgumentException if the framebuffer is null
     * @throws IllegalStateException if the framebuffer has no buffers
     */
    public static void draw(final Framebuffer target)
    {
        if (target == null)
        {
            throw new IllegalArgumentException("target must not be null");
        }
        if (target.state() != Framebuffer.State.READY)
        {
            throw new IllegalStateException("draw() called from state " + target.state()
                + " — only valid from READY");
        }

        final int width = target.width();
        final int height = target.height();
        final int stride = target.strideInPixels();
        final int[] color = target.colorBuffer();

        final int core = coreThickness(height);
        final int outline = outlineThickness(height);
        final int gap = centreGap(height);
        final int arm = armLength(height);

        // The border is the SAME figure grown by `outline` pixels in every
        // direction — thicker by one outline on each side, one outline longer
        // at the tips, one outline shorter at the gap — then the core is drawn
        // over the top of it. Two passes of one routine, rather than a
        // per-pixel neighbourhood test, which is what keeps the inner loop a
        // branch-free Arrays.fill and gets the corners and the arm tips right
        // for free.
        drawCross(color, width, height, stride, core + 2 * outline, gap - outline,
            gap + arm + outline, OUTLINE_COLOR);
        drawCross(color, width, height, stride, core, gap, gap + arm, CORE_COLOR);
    }

    // ---- derived geometry ----

    /**
     * Returns the length of one arm in pixels, {@code frameHeight /
     * ARM_LENGTH_DIVISOR}, floored at one.
     *
     * <p>Public so a caller — or a test asserting that the reticle really does
     * scale with resolution — can compute the figure without restating the
     * ratio and letting the two copies drift.</p>
     *
     * @param frameHeight the frame height in pixels
     * @return arm length in pixels, at least one
     */
    public static int armLength(final int frameHeight)
    {
        return Math.max(MIN_SIZE, frameHeight / ARM_LENGTH_DIVISOR);
    }

    /**
     * Returns the half-width of the unpainted centre gap in pixels.
     *
     * <p>Floored at {@code outlineThickness(frameHeight) + 1} rather than at
     * one, because the border eats one outline thickness off the inner end of
     * each arm; a smaller floor would let the border close the gap the whole
     * design depends on.</p>
     *
     * @param frameHeight the frame height in pixels
     * @return gap half-width in pixels; the full gap is twice this
     */
    public static int centreGap(final int frameHeight)
    {
        return Math.max(outlineThickness(frameHeight) + MIN_SIZE,
            frameHeight / CENTRE_GAP_DIVISOR);
    }

    /**
     * Returns the thickness of an arm's green core in pixels.
     *
     * <p>This is the requested thickness, before the parity adjustment that
     * {@link #draw(Framebuffer)} applies to centre the arm exactly — the drawn
     * band is this or one pixel more, depending on the parity of the axis it
     * lies across.</p>
     *
     * @param frameHeight the frame height in pixels
     * @return core thickness in pixels, at least one
     */
    public static int coreThickness(final int frameHeight)
    {
        return Math.max(MIN_SIZE, frameHeight / CORE_THICKNESS_DIVISOR);
    }

    /**
     * Returns the thickness of the black border on one side of the core.
     *
     * @param frameHeight the frame height in pixels
     * @return outline thickness in pixels, at least one
     */
    public static int outlineThickness(final int frameHeight)
    {
        return Math.max(MIN_SIZE, frameHeight / OUTLINE_THICKNESS_DIVISOR);
    }

    /**
     * Returns the reticle's full tip-to-tip extent in pixels, border included.
     *
     * <p>Reported for its own sake: it is the number that says whether the
     * thing is actually big, and it is what a test comparing two resolutions
     * compares.</p>
     *
     * @param frameHeight the frame height in pixels
     * @return the span from one arm tip to the opposite one
     */
    public static int totalSpan(final int frameHeight)
    {
        return 2 * (centreGap(frameHeight) + armLength(frameHeight)
            + outlineThickness(frameHeight));
    }

    // ---- drawing ----

    // One cross: four arms, drawn as four rectangles.
    //
    // `innerHalf` and `outerHalf` are half-extents measured from the frame's
    // geometric centre — an arm covers [innerHalf, outerHalf) along its own
    // axis and is `thickness` pixels across the other one. Expressing it as
    // two nested centred spans rather than as an origin plus offsets is what
    // makes the mirror symmetry exact: each span is centred independently by
    // the same parity rule, so both arms of a pair are the same length by
    // construction rather than by arithmetic that has to be got right twice.
    private static void drawCross(final int[] color, final int width, final int height,
        final int stride, final int thickness, final int innerHalf, final int outerHalf,
        final int rgba)
    {
        // Horizontal pair. Its thickness lies along Y, so it takes the
        // HEIGHT's parity; its length lies along X and takes the WIDTH's.
        final int barTop = centredLow(height, thickness);
        final int barBottom = barTop + matchParity(height, thickness);
        final int leftTip = centredLow(width, 2 * outerHalf);
        final int rightTip = leftTip + matchParity(width, 2 * outerHalf);
        final int gapLeft = centredLow(width, 2 * innerHalf);
        final int gapRight = gapLeft + matchParity(width, 2 * innerHalf);
        fillRect(color, width, height, stride, leftTip, barTop, gapLeft, barBottom, rgba);
        fillRect(color, width, height, stride, gapRight, barTop, rightTip, barBottom, rgba);

        // Vertical pair, with the two axes exchanged.
        final int barLeft = centredLow(width, thickness);
        final int barRight = barLeft + matchParity(width, thickness);
        final int topTip = centredLow(height, 2 * outerHalf);
        final int bottomTip = topTip + matchParity(height, 2 * outerHalf);
        final int gapTop = centredLow(height, 2 * innerHalf);
        final int gapBottom = gapTop + matchParity(height, 2 * innerHalf);
        fillRect(color, width, height, stride, barLeft, topTip, barRight, gapTop, rgba);
        fillRect(color, width, height, stride, barLeft, gapBottom, barRight, bottomTip, rgba);
    }

    // Inclusive-exclusive rectangle fill, clipped to the visible rectangle.
    //
    // Arrays.fill per row rather than a per-pixel loop: it is a JIT intrinsic,
    // and it puts every bounds decision outside the inner loop, which is what
    // "no per-pixel branching" means here. Indexed by stride, never by width —
    // the padding columns belong to no tile and nothing may write them.
    private static void fillRect(final int[] color, final int width, final int height,
        final int stride, final int x0, final int y0, final int x1, final int y1,
        final int rgba)
    {
        final int lowX = Math.max(0, x0);
        final int highX = Math.min(width, x1);
        final int lowY = Math.max(0, y0);
        final int highY = Math.min(height, y1);
        if (lowX >= highX || lowY >= highY)
        {
            return;
        }
        for (int y = lowY; y < highY; y++)
        {
            final int row = y * stride;
            Arrays.fill(color, row + lowX, row + highX, rgba);
        }
    }

    // The first coordinate of a `size`-pixel span centred in `extent`.
    //
    // May be negative when the span is larger than the frame; fillRect clips
    // it. The subtraction is guaranteed even by matchParity, so the halving is
    // exact in that case too — Java's division truncates toward zero, and an
    // off-by-one there would break the symmetry precisely on the clipped
    // frames the clipping exists to support.
    private static int centredLow(final int extent, final int size)
    {
        return (extent - matchParity(extent, size)) / 2;
    }

    // `size`, raised by one if its parity differs from `extent`'s.
    //
    // This is the whole of the even-dimension answer. A span is exactly
    // centred only when the leftover margin splits into two equal whole
    // pixels, which needs (extent - size) to be even. See the class Javadoc.
    private static int matchParity(final int extent, final int size)
    {
        return size + ((extent ^ size) & 1);
    }
}
