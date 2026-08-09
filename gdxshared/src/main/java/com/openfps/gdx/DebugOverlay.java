/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import java.util.Locale;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;

/**
 * The corner frame counter: how fast the window is running, and how long the
 * software renderer took.
 *
 * <h2>Two numbers, because they are two different questions</h2>
 *
 * <p><b>FRAME</b> is the platform's frame time — how long GLFW or the
 * GLSurfaceView took between calls, which with vsync on is the display's
 * cadence and is what the player actually experiences. <b>RENDER</b> is
 * {@code SoftwareRenderPort.lastFrameNanos()}: how long the rasterizer spent
 * producing a frame, on its own threads.</p>
 *
 * <p>They diverge, and the gap is the diagnosis — the same reasoning
 * {@link FramebufferPresenter}'s three-rate log is built on. RENDER well under
 * FRAME means the renderer is idle and vsync is the limiter, which is healthy.
 * RENDER at or above FRAME means the rasterizer is the limiter. A single
 * combined figure could not tell those apart, and the difference is the whole
 * reason anyone turns a counter on.</p>
 *
 * <h2>And a third: what RENDER is the cost OF</h2>
 *
 * <p><b>RES</b> is the framebuffer the rasterizer is filling, which since
 * {@link RenderMode} exists is no longer the surface. Without it the RENDER
 * figure is uninterpretable — 12 ms is excellent at 1067x480 and remarkable at
 * 2400x1080 — and worse, there would be no way to confirm from the screen that
 * a mode change took effect at all. It is the line that makes the setting
 * verifiable rather than merely present.</p>
 *
 * <h2>Drawn from the same white pixel as everything else</h2>
 *
 * <p>{@link BlockFont} cells, exactly like {@link BlockTitle} — no font file, no
 * atlas, no asset. That is not only consistency with the menu: a bitmap font
 * scaled to a legible size over a game view is a blurry smear, and a counter you
 * have to squint at is a counter you stop trusting. Block cells are solid
 * rectangles and stay sharp at any size.</p>
 *
 * <h2>The strings are rebuilt only when they change</h2>
 *
 * <p>{@code String.format} allocates, and this draws every frame while it is on.
 * The displayed figures are rounded to a whole frame per second and a tenth of a
 * millisecond, so at a steady 60 fps they are the same text for hundreds of
 * consecutive frames. Caching on the rounded value means a stable frame rate
 * allocates nothing at all, and a wildly varying one allocates three small
 * strings — which is the case where you have bigger problems than the counter's
 * garbage.</p>
 *
 * <p><b>Threading:</b> constructed and drawn on the platform render thread only,
 * after the GL context exists.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class DebugOverlay
{
    /** Size of one {@link BlockFont} cell, in pixels. */
    public static final float CELL_PIXELS = 4.0f;

    /** Fraction of a cell left empty, so the digits read as blocks. */
    public static final float CELL_GAP_FRACTION = 0.15f;

    /** Gap between one text line and the next, in cells. */
    public static final float LINE_SPACING_CELLS = 2.0f;

    /** How far the panel sits from the top-left corner, in pixels. */
    public static final float MARGIN_PIXELS = 14.0f;

    /** Padding between the panel edge and the text, in pixels. */
    public static final float PADDING_PIXELS = 10.0f;

    /** How many lines the panel shows. */
    private static final int LINE_COUNT = 4;

    /** The backdrop the text sits on, so it reads over any part of the world. */
    private static final Color PANEL = new Color(0.03f, 0.04f, 0.08f, 0.72f);

    /** Colour of the frames-per-second line — the headline figure. */
    private static final Color FPS_COLOUR = new Color(0.55f, 0.84f, 0.34f, 1.0f);

    /** Colour of the two timing lines, quieter than the headline. */
    private static final Color TIMING_COLOUR = new Color(0.62f, 0.68f, 0.82f, 1.0f);

    /** Sentinel meaning "no value has been formatted yet". */
    private static final int NO_READING = Integer.MIN_VALUE;

    /** Tenths, for rounding a float to one decimal place. */
    private static final float TENTHS = 10.0f;

    /** How fast the window is going round. */
    private final FpsMeter platform = new FpsMeter();

    /** How long the software renderer is taking. */
    private final FpsMeter renderer = new FpsMeter();

    /** Draws the panel and every cell. MUTABLE: built on first use, freed by dispose. */
    private SpriteBatch batch;

    /** The 1x1 white texture. MUTABLE: built on first use, freed by dispose. */
    private Texture white;

    /** The region every rectangle is drawn from. MUTABLE: built with the texture. */
    private TextureRegion pixel;

    /** The three lines, already formatted. MUTABLE: rebuilt when a figure moves. */
    private final String[] lines = new String[LINE_COUNT];

    /** Last whole frames per second rendered into {@link #lines}. MUTABLE. */
    private int shownFps = NO_READING;

    /** Last platform frame time in tenths of a millisecond. MUTABLE. */
    private int shownFrameTenths = NO_READING;

    /** Last renderer frame time in tenths of a millisecond. MUTABLE. */
    private int shownRenderTenths = NO_READING;

    /** The framebuffer width most recently reported. MUTABLE: set per render. */
    private int renderWidth;

    /** The framebuffer height most recently reported. MUTABLE: set per render. */
    private int renderHeight;

    /** The width {@link #lines} was last built for. MUTABLE. */
    private int shownRenderWidth = NO_READING;

    /** The height {@link #lines} was last built for. MUTABLE. */
    private int shownRenderHeight = NO_READING;

    /** Creates an overlay. Builds no GL resource until it is first drawn. */
    public DebugOverlay()
    {
        // GPU resources are deferred to the first render() for the reason
        // FramebufferPresenter defers its: there is no GL context until the
        // surface exists, and an overlay that is never switched on should cost
        // nothing at all.
    }

    /** Returns the meter tracking the platform's frame time. Never null. */
    public FpsMeter platformMeter()
    {
        return platform;
    }

    /** Returns the meter tracking the software renderer's frame time. Never null. */
    public FpsMeter rendererMeter()
    {
        return renderer;
    }

    /**
     * Folds one frame's timings into the averages.
     *
     * <p>Separate from {@link #render} so the counter keeps a continuous history
     * while it is switched off — otherwise switching it on would show a meter
     * climbing out of nothing for the first fifth of a second, which reads as a
     * stall that is not there.</p>
     *
     * @param platformFrameNanos how long the platform frame took, in
     *     nanoseconds; zero or less is discarded by {@link FpsMeter}
     * @param rendererFrameNanos the software renderer's last frame, in
     *     nanoseconds — {@code SoftwareRenderPort.lastFrameNanos()}. Zero before
     *     the renderer has finished a frame, and discarded on those frames
     */
    public void sample(final long platformFrameNanos, final long rendererFrameNanos)
    {
        platform.sample(platformFrameNanos);

        renderer.sample(rendererFrameNanos);
    }

    /**
     * Draws the counter in the top-left corner.
     *
     * <p>A no-op until the meters have a reading, so the first frame of a run
     * does not flash a panel full of zeroes.</p>
     *
     * @param surfaceWidth the surface width in pixels; must be positive to draw
     * @param surfaceHeight the surface height in pixels; must be positive to
     *     draw. The panel is anchored to the top, so this is what places it
     * @param frameWidth the framebuffer width the rasterizer is filling —
     *     {@code FramebufferPresenter.renderWidth()}. Zero or less falls back to
     *     the surface width, which is the truth when there is no presenter to
     *     have scaled anything
     * @param frameHeight the framebuffer height, on the same terms
     */
    public void render(final int surfaceWidth, final int surfaceHeight,
        final int frameWidth, final int frameHeight)
    {
        if (surfaceWidth <= 0 || surfaceHeight <= 0 || !platform.hasReading())
        {
            return;
        }

        this.renderWidth = positiveOr(frameWidth, surfaceWidth);

        this.renderHeight = positiveOr(frameHeight, surfaceHeight);

        ensureResources();

        refreshLines();

        final float cellGap = CELL_PIXELS * CELL_GAP_FRACTION;

        final float lineHeight = (BlockFont.GLYPH_HEIGHT + LINE_SPACING_CELLS) * CELL_PIXELS;

        final float textWidth = widestLinePixels();

        final float panelWidth = textWidth + PADDING_PIXELS * 2.0f;

        final float panelHeight = lineHeight * LINE_COUNT - LINE_SPACING_CELLS * CELL_PIXELS
            + PADDING_PIXELS * 2.0f;

        final float panelTop = surfaceHeight - MARGIN_PIXELS;

        batch.getProjectionMatrix().setToOrtho2D(0.0f, 0.0f, surfaceWidth, surfaceHeight);

        batch.begin();

        batch.setColor(PANEL);

        batch.draw(pixel, MARGIN_PIXELS, panelTop - panelHeight, panelWidth, panelHeight);

        float lineTop = panelTop - PADDING_PIXELS;

        for (int index = 0; index < LINE_COUNT; index++)
        {
            batch.setColor(colourFor(index));

            drawLine(lines[index], MARGIN_PIXELS + PADDING_PIXELS, lineTop, cellGap);

            lineTop = lineTop - lineHeight;
        }

        batch.end();
    }

    // A reported figure, or the fallback when nothing usable was reported.
    private static int positiveOr(final int reported, final int fallback)
    {
        if (reported > 0)
        {
            return reported;
        }

        return fallback;
    }

    // The headline figure is picked out; the rest are quieter.
    private static Color colourFor(final int lineIndex)
    {
        if (lineIndex == 0)
        {
            return FPS_COLOUR;
        }

        return TIMING_COLOUR;
    }

    // Draws one string of block cells, its top-left cell at (left, top).
    //
    // The lambda body is a single call to a named method rather than the draw
    // itself: STYLE.md § 6.2 caps lambdas at one operation, and the cell maths
    // needs four captured values.
    private void drawLine(final String text, final float left, final float top,
        final float gap)
    {
        final float size = CELL_PIXELS - gap;

        BlockFont.forEachBlock(text, (column, row, glyph) ->
            batch.draw(pixel, left + column * CELL_PIXELS,
                top - (row + 1) * CELL_PIXELS, size, size));
    }

    // The width of the longest line, in pixels, so the panel fits the text.
    private float widestLinePixels()
    {
        int widest = 0;

        for (int index = 0; index < LINE_COUNT; index++)
        {
            final int cells = BlockFont.widthInBlocks(lines[index]);

            if (cells > widest)
            {
                widest = cells;
            }
        }

        return widest * CELL_PIXELS;
    }

    // Re-formats only the lines whose displayed figure has actually moved. See
    // the class Javadoc on why this is worth a few fields.
    private void refreshLines()
    {
        final int fps = Math.round(platform.fps());

        if (fps != shownFps)
        {
            shownFps = fps;

            lines[0] = "FPS " + fps;
        }

        final int frameTenths = Math.round(platform.frameMillis() * TENTHS);

        if (frameTenths != shownFrameTenths)
        {
            shownFrameTenths = frameTenths;

            lines[1] = "FRAME " + oneDecimal(platform.frameMillis()) + " MS";
        }

        final int renderTenths = Math.round(renderer.frameMillis() * TENTHS);

        if (renderTenths != shownRenderTenths)
        {
            shownRenderTenths = renderTenths;

            lines[2] = "RENDER " + oneDecimal(renderer.frameMillis()) + " MS";
        }

        if (renderWidth != shownRenderWidth || renderHeight != shownRenderHeight)
        {
            shownRenderWidth = renderWidth;

            shownRenderHeight = renderHeight;

            // Changes only when the mode does, so this allocates once a run in
            // practice — the same caching rule as the three lines above, applied
            // to a figure that happens to be even more stable than they are.
            lines[3] = "RES " + renderWidth + "X" + renderHeight;
        }
    }

    // One decimal place, locale-independent — a comma separator would hit
    // BlockFont's unsupported-character check and take the window down.
    private static String oneDecimal(final float value)
    {
        return String.format(Locale.ROOT, "%.1f", Float.valueOf(value));
    }

    // Builds the batch and the white pixel, once, on the render thread.
    private void ensureResources()
    {
        if (batch != null)
        {
            return;
        }

        batch = new SpriteBatch();

        final Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);

        pixmap.setColor(Color.WHITE);

        pixmap.fill();

        white = new Texture(pixmap);

        pixmap.dispose();

        pixel = new TextureRegion(white);
    }

    /** Releases the batch and texture. Safe to call when nothing was ever built. */
    public void dispose()
    {
        if (batch != null)
        {
            batch.dispose();

            batch = null;
        }

        if (white != null)
        {
            white.dispose();

            white = null;
        }

        pixel = null;
    }

    /** Returns a debug rendering of the counter's current reading. */
    @Override
    public String toString()
    {
        return "DebugOverlay{platform=" + platform + ", renderer=" + renderer + "}";
    }
}
