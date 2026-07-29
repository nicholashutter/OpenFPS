/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import java.util.Arrays;

/**
 * R_ The aiming reticle: a black-outlined cross with a centre gap, white
 * normally and <b>red while an enemy is under the point of aim</b>, drawn into
 * the colour buffer after every other pass.
 *
 * Platform adapter — must not import from core engine packages.
 *
 * <b>This class draws and nothing else.</b> It owns no state, allocates
 * nothing, and reads no pixel it is about to write.
 * {@link #draw(Framebuffer, boolean)} is a pure function of the framebuffer's
 * dimensions and one boolean: the same geometry and the same flag always
 * produce the same bytes, and drawing twice is indistinguishable from drawing
 * once (see <i>Idempotence</i> below).
 *
 * <h2>Two states, and hue is what carries the difference</h2>
 *
 * <p>The reticle is a state display now, not only a sight. The caller passes
 * whether the entity-id buffer holds a tagged entity at the centre pixel —
 * {@code SoftwareRenderPort} samples it once per frame — and the core is
 * {@link #CORE_COLOR} (white) or {@link #ENEMY_CORE_COLOR} (red) accordingly.
 * Everything else about the figure is identical between the two: same arms,
 * same gap, same black border, same pixels. Only the fill changes, which is
 * what makes the change read as a <i>state</i> rather than as a different
 * reticle.</p>
 *
 * <p><b>Sampling the id buffer for this is legitimate, and doing it for
 * hitscan would not be.</b> The reticle is cosmetic: nothing reads it back,
 * nothing simulates from it, and a peer that renders at another resolution and
 * disagrees about the centre pixel has a different-coloured cross and an
 * identical simulation. Hit detection is resolved from geometry precisely
 * because it must <i>not</i> depend on resolution or worker count — see
 * {@code DemoGameplayPort.fireIfRequested} and {@code Framebuffer}'s note on
 * the id buffer. That boundary is the whole reason this parameter is a boolean
 * the caller computed rather than a {@code Framebuffer} read done here.</p>
 *
 * <h2>Why white by default, measured against the real scene</h2>
 *
 * <p>The colours are not a taste call. They were chosen against rendered
 * 1280x720 frames of the demo room, sampled per pixel. What those frames
 * actually contain, as sRGB and Rec.709 relative luminance:</p>
 *
 * <pre>
 *   pale side wall      #A9B0D3   lum 177
 *   ceiling             #8D93B1   lum 148
 *   near checker floor  #7E839B   lum 132
 *   dark doorway        #181C26   lum  28
 *   weapon body, orange #DF5C4A   lum 119   (viewmodel spans  55..211)
 *   crate decal, amber  #FFC457   lum 201   &lt;- brightest room surface
 *   character bodies                        (span 0..255, see below)
 * </pre>
 *
 * <ul>
 *   <li><b>{@link #CORE_COLOR} is pure white, {@code #FFFFFF}, luminance
 *       255.</b> It is above <i>every</i> surface in the room, including the
 *       amber decal that is the brightest of them, so it carries the contrast
 *       against the dark end of the range.</li>
 *   <li><b>{@link #OUTLINE_COLOR} is pure black, {@code #000000}, luminance
 *       0.</b> It carries the contrast against the bright end — 177 against
 *       the pale walls, 201 against the amber decal, 132 against the floor.</li>
 * </ul>
 *
 * <p><b>The pair cannot both be defeated, and that is the point.</b> The room's
 * whole luminance range (28 to 201) is strictly inside the span between the two
 * reticle colours (0 to 255), so any background whatsoever is far from at least
 * one of them: a background bright enough to swallow the white core is by
 * construction miles from the black outline, and one dark enough to swallow the
 * outline is miles from the core. That bracket is <b>wider than the green core
 * this replaced</b> managed (0 to 182), which is the one respect in which the
 * new default is strictly better rather than merely different.</p>
 *
 * <p><b>White was previously rejected here, and the objection was real.</b> The
 * argument against it was that it is achromatic in an achromatic scene and that
 * the weapon's own highlights are already near-white, so it reads as part of the
 * picture. Green kept most of white's luminance and added a hue the room does
 * not contain a single pixel of. All of that is still true — and it is the price
 * now being paid on purpose, because <b>hue has been promoted to the state
 * channel</b> and the neutral state is the one that has to give it up. Two
 * measurements soften the cost: the weapon viewmodel spans luminance 55 to 211
 * and so never actually reaches white, and it sits in the bottom right of the
 * frame while the reticle is at the centre — they do not overlap.</p>
 *
 * <h2>Why the enemy colour is red, and why red alone would not be enough</h2>
 *
 * <p><b>Red is chosen for meaning, not for contrast, and this Javadoc is not
 * going to pretend otherwise.</b> {@code #FF0000} has a Rec.709 luminance of
 * <b>54</b> — red is intrinsically dark, and no fully saturated red is
 * brighter. Sampled over the demo's character bodies in a real frame, those
 * bodies span luminance 0 to 255 and between 20% and 50% of their pixels are
 * already red-dominant. So a red core, on its own, over the thing that makes it
 * red, is <i>not</i> reliably legible. Three things pay for that:</p>
 *
 * <ul>
 *   <li><b>The black outline, which is why it is not optional.</b> Every core
 *       pixel is within {@link #OUTLINE_THICKNESS_DIVISOR} of a luminance-0
 *       border — 2 px each side of a 7 px core at 720p — so the figure is read
 *       from its banded silhouette rather than from its fill, in either
 *       state.</li>
 *   <li><b>The transition, which is the actual signal.</b> White to red on the
 *       same pixels is a drop of 201 luminance levels together with a jump from
 *       zero saturation to full. What a player perceives is the change, and
 *       that is the largest change this figure can make without moving.</li>
 *   <li><b>{@code OutlinePass} paints the same body in this same red, keyed with
 *       this same black</b>, on the same frame and off the same sample. It used
 *       to paint it cyan, and this Javadoc used to argue that a complementary
 *       hue was the point — that the two marks were 180 degrees apart and so
 *       could not be confused. That was backwards: they are not two marks to
 *       tell apart, they are one fact about one body, and giving the fact two
 *       hues taught a player that hue means nothing in particular. What pays for
 *       the red core now is <b>redundancy of position</b>: the same instant that
 *       the reticle changes at the centre of the screen, the body under it gains
 *       a red-and-black wireframe. A player who loses the core against a red
 *       shirt has not lost the signal, because the signal is also drawn round
 *       the shirt.</li>
 * </ul>
 *
 * <p><b>White to red also survives colour blindness, and green to red would
 * not.</b> Roughly 8% of men cannot separate red from green; a green-to-red
 * reticle would have signalled entirely in the one channel they do not have.
 * White to red is a luminance change first and a hue change second, so the
 * state is still visible with no colour discrimination at all. That is the
 * strongest single reason the neutral colour is not simply left green.</p>
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
     * The reticle's core colour with nothing under the point of aim: pure
     * white, {@code #FFFFFF}, luminance 255 — above every surface in the demo
     * room. See the class Javadoc for the measurement that chose it, and for
     * the objection to white that is being paid on purpose.
     */
    public static final int CORE_COLOR = Rgba.pack(255, 255, 255, 255);

    /**
     * The reticle's core colour while an enemy is under the point of aim: pure
     * red, {@code #FF0000}, luminance 54.
     *
     * <p>Dark, and knowingly so — see the class Javadoc. It is chosen for what
     * it means rather than for how far it is from the body behind it, and the
     * black outline plus the 201-level drop from {@link #CORE_COLOR} are what
     * make it legible.</p>
     */
    public static final int ENEMY_CORE_COLOR = Rgba.pack(255, 0, 0, 255);

    /**
     * The colour of the border drawn around the core: pure black, luminance 0.
     * It supplies the contrast against pale walls, which the white core cannot,
     * and against the character bodies, which the red core cannot.
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
     * Frame height divided by this is the thickness of an arm's coloured core:
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
     * Draws the reticle in its neutral state — a white core.
     *
     * <p>Exactly {@code draw(target, false)}. It exists because "nothing is
     * under the point of aim" is the overwhelmingly common case and because the
     * preview tools and the geometry tests have no notion of an enemy at all.
     * </p>
     *
     * @param target the framebuffer to draw into; must be
     *     {@link Framebuffer.State#READY}
     * @throws IllegalArgumentException if the framebuffer is null
     * @throws IllegalStateException if the framebuffer has no buffers
     */
    public static void draw(final Framebuffer target)
    {
        draw(target, false);
    }

    /**
     * Returns the core colour for one of the two states.
     *
     * <p>Public so a caller — or a test asserting that the two states really do
     * differ perceptibly — can name the colour without restating the constant
     * and letting the two copies drift.</p>
     *
     * @param enemyUnderAim whether a tagged entity is under the point of aim
     * @return {@link #ENEMY_CORE_COLOR} if it is, {@link #CORE_COLOR} if not
     */
    public static int coreColor(final boolean enemyUnderAim)
    {
        if (enemyUnderAim)
        {
            return ENEMY_CORE_COLOR;
        }
        return CORE_COLOR;
    }

    /**
     * Draws the reticle into the framebuffer's colour buffer, in whichever of
     * its two states the caller asks for.
     *
     * <p>Call once per frame, after the world pass, the outline pass and the
     * viewmodel pass — the reticle is the topmost thing on screen and is not
     * depth-tested, because it is not in the world.</p>
     *
     * <p>Allocates nothing, reads no pixel, and is idempotent: calling it
     * twice on one frame with the same flag is indistinguishable from calling
     * it once.</p>
     *
     * @param target the framebuffer to draw into; must be
     *     {@link Framebuffer.State#READY}
     * @param enemyUnderAim true when the entity-id buffer holds a tagged entity
     *     at the point of aim, which turns the core red. The caller samples
     *     that, not this method — see the class Javadoc on why the id buffer is
     *     fair game for a cosmetic mark and forbidden for a hitscan
     * @throws IllegalArgumentException if the framebuffer is null
     * @throws IllegalStateException if the framebuffer has no buffers
     */
    public static void draw(final Framebuffer target, final boolean enemyUnderAim)
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
        drawCross(color, width, height, stride, core, gap, gap + arm,
            coreColor(enemyUnderAim));
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
     * Returns the thickness of an arm's coloured core in pixels.
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
