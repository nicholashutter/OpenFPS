/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import java.nio.ByteBuffer;
import java.util.Locale;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.TimeUtils;

import com.openfps.engine.render.adapter.SoftwareRenderPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Uploads the software rasterizer's finished frame and draws it fullscreen.
 *
 * <b>This is the whole of the platform's side of {@code render/README.md}
 * § 12.</b> R_ produces a finished {@code int[]} and stops; this class turns it
 * into a texture and a quad. Nothing here reaches back into the renderer beyond
 * one copy call, and nothing in {@code :engine} learns that a GL context
 * exists.
 *
 * <pre>
 *   R_ (worker threads)              this class (LWJGL3 render thread)
 *   ───────────────────              ────────────────────────────────
 *   render into Framebuffer
 *         │
 *         └── copyColorInto ──►      Pixmap (RGBA8888)
 *                                    texture.draw  → glTexSubImage2D
 *                                    batch.draw    → fullscreen quad
 * </pre>
 *
 * <h2>The three things that were never proven until this class ran</h2>
 *
 * <p><b>De-padding.</b> {@link SoftwareRenderPort#copyColorInto} is
 * {@link com.openfps.engine.render.adapter.Framebuffer#copyColorTo}, which
 * copies the visible {@code width x height} rectangle out of a buffer whose row
 * stride is padded to a multiple of 16 pixels (§ 7, false sharing). Uploading
 * {@code colorBuffer()} raw instead would shear the image progressively down
 * the screen, because stride is not width. The scratch array here is
 * {@code width * height} exactly, so the mistake is not expressible.</p>
 *
 * <p><b>Byte order.</b> {@code PixmapByteOrderTest} proved the Java half — a
 * {@code 0xRRGGBBAA} int written through {@code getPixels().asIntBuffer()}
 * lands as the bytes R, G, B, A, because libGDX hands out a big-endian buffer.
 * What it could not prove without a context is that {@code glTexImage2D} then
 * accepts those bytes as {@code GL_RGBA / GL_UNSIGNED_BYTE} unchanged. It does;
 * {@link GdxScreenshot} closes that gap by reading the finished window back and
 * writing a PNG.</p>
 *
 * <p><b>Orientation.</b> The viewport transform in
 * {@code Rasterizer.screenY} flips y once, so framebuffer row 0 is the
 * <i>top</i> of the image. {@code Pixmap} row 0 uploads as GL texture row
 * {@code t = 0}, and {@code SpriteBatch.draw(Texture, x, y, w, h)} maps
 * {@code t = 0} to the <b>top</b> of the drawn rectangle — its bottom-left
 * vertex carries {@code v = 1}. Two conventions, one flip each, and they
 * cancel: no explicit flip belongs here. Adding one is the bug, not the fix.
 * <b>None of the three depends on the texture's size</b>, which is what let the
 * render resolution be decoupled from the surface without disturbing any of
 * them — see below.</p>
 *
 * <h2>The render size is NOT the surface size</h2>
 *
 * <p>The quad above is a <b>fullscreen</b> quad: it covers the viewport
 * whatever the texture's dimensions are, so a smaller framebuffer is upscaled
 * by the GPU for free. {@link RenderMode} decides how much smaller, the render
 * port is sized to <i>that</i> rather than to the surface, and the phone stops
 * rasterizing 2.59 megapixels per frame in software. Three numbers therefore
 * live here rather than two:</p>
 *
 * <ul>
 *   <li>{@link #width()} and {@link #height()} — the surface. The quad, the
 *       ortho projection and every other thing drawn over the world use
 *       these, which is why the UI, the touch pad and the debug counter stay
 *       crisp at native resolution while the world does not.</li>
 *   <li>{@link #renderWidth()} and {@link #renderHeight()} — the framebuffer.
 *       The render port, the scratch array, the Pixmap and the Texture are all
 *       this size, and so is the camera's aspect ratio, because
 *       {@code DemoGameplayPort.aimCamera} derives it from
 *       {@code SoftwareRenderPort.surfaceWidth()/surfaceHeight()} — the
 *       framebuffer's own numbers, which is exactly the property that keeps
 *       the projection honest without a second place to keep in step.</li>
 * </ul>
 *
 * <p><b>Filtering.</b> A 1:1 blit uses {@code Nearest}: filtering an image that
 * lands on whole texels can only blur it. Anything else uses
 * {@link #UPSCALE_FILTER_PROPERTY}'s filter, which defaults to {@code Linear}
 * because the ratio between 1067 and 2400 is not a whole number — with
 * {@code Nearest} some source pixels are duplicated twice and their neighbours
 * three times, and the seam between the two crawls across the screen as the
 * camera turns. <b>This is GPU filtering of the finished blit and has nothing
 * to do with the bilinear TEXTURE sampling {@code docs/ASSETS.md} measures at
 * 2.9x inside the rasterizer.</b> That one is per rasterized pixel on the CPU
 * and is a real cost; this one is a sampler state on a single quad and is
 * free. Confusing the two would be an expensive mistake in either
 * direction.</p>
 *
 * <h2>Changing mode mid-session</h2>
 *
 * <p>{@link RenderSettings} is held here and the presenter listens to itself,
 * so the settings screen's button re-sizes a live framebuffer between frames.
 * That is safe on both sides:</p>
 *
 * <ul>
 *   <li><b>Against the renderer</b> — {@code SoftwareRenderPort.resize} takes
 *       the same {@code frameLock} that serialises {@code renderFrame} and
 *       {@code setScene}, so it cannot land in the middle of a frame, and it
 *       clears the published-frame flag so no frame captured at the old size is
 *       ever handed to a texture sized for the new one.</li>
 *   <li><b>Against this class</b> — the button callback and {@link #present}
 *       both run on the platform's render thread. Scene2D input is pumped
 *       before {@code render()}, so a mode change is complete before the next
 *       upload begins, and the scratch array can never be smaller than the
 *       frame being copied into it.</li>
 * </ul>
 *
 * <p>The one visible effect is that the first frame or two after a change
 * present nothing — {@code copyColorInto} returns false until the renderer
 * publishes at the new size — and the caller clears instead. That is the same
 * behaviour a window resize has always had.</p>
 *
 * <h2>Lifecycle and threading</h2>
 *
 * <p>Every method runs on the LWJGL3 render thread, which owns the GL context.
 * GPU resources are created in {@link #resize} rather than in the constructor,
 * because there is no context until the surface exists — the same reason
 * {@code I_FrameCallback.onSurfaceReady} exists at all.</p>
 *
 * <h2>The frame-rate log</h2>
 *
 * <p>Opt-in and off by default, on the {@link GdxScreenshot} pattern and for
 * the same reason: <b>the windowed frame rate cannot be measured anywhere
 * else.</b> A headless tool measures how long the rasterizer takes; only the
 * window can say how many of those frames actually reach a display, which is a
 * different number whenever presentation, coalescing or lock contention is the
 * limiter — and all three have been.</p>
 *
 * <pre>
 *   -Dopenfps.fpsLog=2    log every 2 seconds; absent or 0 disables it
 * </pre>
 *
 * <p>Three rates, because they diverge and the difference is the diagnosis:
 * <i>platform</i> is how often GLFW called {@code render()}, <i>presented</i>
 * is how many of those uploaded a frame, and <i>rendered</i> is how many frames
 * R_ finished. Presented well below platform means the renderer is not keeping
 * up; rendered well above presented means frames are being drawn that nobody
 * ever sees.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class FramebufferPresenter
{
    /**
     * System property enabling the frame-rate log. Its value is the interval in
     * whole seconds; absent, empty, unparseable or non-positive disables it.
     */
    public static final String FPS_LOG_PROPERTY = "openfps.fpsLog";

    /**
     * System property choosing the GPU filter for a scaled blit.
     *
     * <pre>
     *   -Dopenfps.renderFilter=nearest   crisp and blocky
     *   -Dopenfps.renderFilter=linear    smooth and slightly soft (the default)
     * </pre>
     *
     * <p>Ignored when the render size equals the surface size, where
     * {@code Nearest} is the only right answer — see the class Javadoc, which
     * also explains why this is not the bilinear sampling {@code docs/ASSETS.md}
     * costs at 2.9x.</p>
     */
    public static final String UPSCALE_FILTER_PROPERTY = "openfps.renderFilter";

    /** The {@link #UPSCALE_FILTER_PROPERTY} value asking for a crisp blit. */
    public static final String FILTER_NEAREST = "nearest";

    private static final Logger LOG = LoggerFactory.getLogger(FramebufferPresenter.class);

    /** Nanoseconds in a second. */
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /** Nanoseconds in a millisecond, for the last-frame figure. */
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    /** The renderer whose finished frames this presents. */
    private final SoftwareRenderPort renderPort;

    /**
     * Draws the fullscreen quad.
     * MUTABLE: built on the first {@link #resize}, released by {@link #dispose}.
     */
    private SpriteBatch batch;

    /** Staging image the texture is uploaded from. MUTABLE: rebuilt on resize. */
    private Pixmap pixmap;

    /** The GPU texture. MUTABLE: rebuilt on resize. */
    private Texture texture;

    /** De-padded frame, exactly {@code renderWidth * renderHeight}. MUTABLE: rebuilt on resize. */
    private int[] scratch;

    /** Surface width in pixels — what the quad covers. MUTABLE: set on resize. */
    private int width;

    /** Surface height in pixels — what the quad covers. MUTABLE: set on resize. */
    private int height;

    /** Framebuffer width in pixels — what R_ fills. MUTABLE: set on resize. */
    private int renderWidth;

    /** Framebuffer height in pixels — what R_ fills. MUTABLE: set on resize. */
    private int renderHeight;

    /**
     * The render mode in force, and the thing the settings screen cycles.
     *
     * <p>Held here rather than by the launcher because this is the only object
     * that can act on it — see {@link RenderSettings}. This class attaches its
     * own observer in the constructor.</p>
     */
    private final RenderSettings renderSettings;

    /** The filter a scaled blit uses. {@code Nearest} at 1:1 regardless. */
    private final Texture.TextureFilter upscaleFilter;

    /** Log interval in nanoseconds, or zero when the frame-rate log is off. */
    private final long fpsIntervalNanos;

    /** When the current sampling window opened. MUTABLE: reset per window. */
    private long windowStartNanos;

    /** Platform frames in the current window. MUTABLE. */
    private int windowPlatformFrames;

    /** Frames actually uploaded in the current window. MUTABLE. */
    private int windowPresentedFrames;

    /** The renderer's frame count when the window opened. MUTABLE. */
    private long windowRenderedAtStart;

    /**
     * Creates a presenter for one renderer, with the frame-rate log configured
     * from {@link #FPS_LOG_PROPERTY}.
     *
     * @param port the software renderer to present; must not be null
     */
    public FramebufferPresenter(final SoftwareRenderPort port)
    {
        this(port, RenderMode.configured());
    }

    /**
     * Creates a presenter starting in a given render mode.
     *
     * <p>Both launchers use the one-argument form, which takes
     * {@link RenderMode#configured()}. This one exists so a platform that knows
     * better about its own surface can say so at the composition root rather
     * than by reaching in later, and so a test can pin a mode without touching a
     * system property.</p>
     *
     * @param port the software renderer to present; must not be null
     * @param initialMode the render mode to start in; must not be null
     */
    public FramebufferPresenter(final SoftwareRenderPort port, final RenderMode initialMode)
    {
        this(port, logIntervalSeconds(), initialMode);
    }

    /**
     * Creates a presenter with an explicit frame-rate log interval.
     *
     * @param port the software renderer to present; must not be null
     * @param logIntervalSeconds seconds between frame-rate log lines; zero or
     *     less disables the log entirely
     */
    public FramebufferPresenter(final SoftwareRenderPort port, final int logIntervalSeconds)
    {
        this(port, logIntervalSeconds, RenderMode.configured());
    }

    /**
     * Creates a presenter with everything spelled out.
     *
     * @param port the software renderer to present; must not be null
     * @param logIntervalSeconds seconds between frame-rate log lines; zero or
     *     less disables the log entirely
     * @param initialMode the render mode to start in; must not be null
     * @throws IllegalArgumentException if the port or the mode is null
     */
    public FramebufferPresenter(final SoftwareRenderPort port, final int logIntervalSeconds,
        final RenderMode initialMode)
    {
        if (port == null)
        {
            throw new IllegalArgumentException("port must not be null");
        }
        this.renderPort = port;
        this.fpsIntervalNanos = Math.max(0L, (long) logIntervalSeconds * NANOS_PER_SECOND);
        this.upscaleFilter = configuredFilter();
        this.renderSettings = new RenderSettings(initialMode);
        // Listening to our own switch rather than exposing a setter is what
        // keeps the settings screen from needing a presenter: it cycles a small
        // object, and the object tells whoever can act on it. Attaching does not
        // fire, so the mode above is applied by the first resize and not twice.
        this.renderSettings.onChange(this::applyMode);
    }

    // The configured filter for a scaled blit, defaulting to Linear. Anything
    // unrecognised is Linear too: a misspelled diagnostic flag must not decide
    // how the game looks, and Linear is the safe end of that mistake.
    private static Texture.TextureFilter configuredFilter()
    {
        final String configured = System.getProperty(UPSCALE_FILTER_PROPERTY);
        if (configured != null && FILTER_NEAREST.equalsIgnoreCase(configured.trim()))
        {
            return Texture.TextureFilter.Nearest;
        }
        return Texture.TextureFilter.Linear;
    }

    // The configured interval, or zero for anything absent or unusable. A bad
    // diagnostic setting must not stop a window opening.
    private static int logIntervalSeconds()
    {
        final String configured = System.getProperty(FPS_LOG_PROPERTY);
        if (configured == null || configured.isEmpty())
        {
            return 0;
        }
        try
        {
            return Integer.parseInt(configured.trim());
        }
        catch (final NumberFormatException e)
        {
            LOG.warn("Ignoring -D{}={}: not a whole number of seconds",
                FPS_LOG_PROPERTY, configured);
            return 0;
        }
    }

    /**
     * Sizes the renderer's framebuffer and this presenter's GPU resources for
     * a surface.
     *
     * Call from {@code onSurfaceReady} and {@code onResize}. The renderer is
     * resized here rather than from the engine's own frame callback so the two
     * can never disagree about the frame's dimensions: one call site, one size.
     * <b>That size is the render size, not the surface size</b> — see the class
     * Javadoc.
     *
     * @param newWidth surface width in pixels
     * @param newHeight surface height in pixels
     */
    public void resize(final int newWidth, final int newHeight)
    {
        if (newWidth <= 0 || newHeight <= 0)
        {
            return;
        }
        width = newWidth;
        height = newHeight;
        sizeForSurface();
    }

    // Applies a mode change to the surface already in force. The observer
    // RenderSettings fires, and a no-op before the first resize: a mode is
    // meaningless without a surface to apply it to, and the constructor's mode
    // is picked up by that first resize anyway.
    private void applyMode(final RenderMode changed)
    {
        if (width <= 0 || height <= 0)
        {
            return;
        }
        LOG.info("Render mode: {}", changed.label());
        sizeForSurface();
    }

    // Sizes everything from the current surface and the current mode. The only
    // place either number turns into an allocation.
    //
    // The render port is resized BEFORE the scratch array is replaced, and the
    // order is not arbitrary: resize() drops the published frame and republishes
    // presentPixels at the new size, and copyColorInto rejects a destination
    // smaller than that. Both happen on this thread with no present() in
    // between, so the pair is atomic as far as anything can observe.
    private void sizeForSurface()
    {
        final RenderMode mode = renderSettings.mode();
        final int wantWidth = mode.widthFor(width, height);
        final int wantHeight = mode.heightFor(width, height);
        renderPort.resize(wantWidth, wantHeight);
        if (batch == null)
        {
            batch = new SpriteBatch();
            // The world quad is opaque and covers every pixel, so blending is
            // pure cost. It is also a hazard: the colour buffer's alpha is
            // whatever R_ wrote, and a frame is not a sprite.
            batch.disableBlending();
        }
        if (wantWidth != renderWidth || wantHeight != renderHeight)
        {
            releaseSurface();
            renderWidth = wantWidth;
            renderHeight = wantHeight;
            scratch = new int[renderWidth * renderHeight];
            pixmap = new Pixmap(renderWidth, renderHeight, Pixmap.Format.RGBA8888);
            texture = new Texture(pixmap);
            final Texture.TextureFilter filter = filterFor(mode);
            texture.setFilter(filter, filter);
            LOG.info("Presenter surface {}x{}, render {}x{} ({}), blit filter {}",
                Integer.valueOf(width), Integer.valueOf(height),
                Integer.valueOf(renderWidth), Integer.valueOf(renderHeight),
                mode.label(), filter);
        }
        // The projection is the SURFACE, always. It is what makes the quad
        // fullscreen whatever the texture measures, and it is why nothing else
        // drawn over the world had to change.
        batch.getProjectionMatrix().setToOrtho2D(0.0f, 0.0f, width, height);
    }

    // Nearest for a 1:1 blit, the configured filter otherwise. See the class
    // Javadoc: at 1:1 any filtering can only blur an image that already lands on
    // whole texels, and at a fractional ratio Nearest crawls.
    private Texture.TextureFilter filterFor(final RenderMode mode)
    {
        if (mode.isNativeFor(width, height))
        {
            return Texture.TextureFilter.Nearest;
        }
        return upscaleFilter;
    }

    /**
     * Returns the switch the settings screen cycles to change render mode.
     *
     * <p>Never null, and live from construction — see {@link RenderSettings} for
     * why this class owns it rather than the launcher.</p>
     *
     * @return this presenter's render settings
     */
    public RenderSettings renderSettings()
    {
        return renderSettings;
    }

    /**
     * Copies, uploads and draws the latest finished frame.
     *
     * A no-op before the first {@link #resize}, or while the renderer has no
     * frame to give — a windowed run reaches its first platform frame before
     * the game loop has published a single {@code RenderFrameEvent}.
     *
     * @return true if a frame was drawn
     */
    public boolean present()
    {
        // framesRendered guards the window's first few frames: the framebuffer
        // exists from onSurfaceReady but is all zeros until R_ first clears it,
        // and RGBA8888 zero is transparent black, not black. Presenting that
        // would blend a fully transparent quad over an unclear GL buffer, which
        // reads as "the renderer is broken" when it has simply not run yet.
        if (texture == null || renderPort.framesRendered() == 0L
            || !renderPort.copyColorInto(scratch))
        {
            sampleFrameRate(false);
            return false;
        }
        final ByteBuffer pixels = pixmap.getPixels();
        pixels.clear();
        // One bulk copy of RGBA8888 ints into a big-endian byte buffer — the
        // path PixmapByteOrderTest asserts byte for byte.
        pixels.asIntBuffer().put(scratch, 0, renderWidth * renderHeight);
        texture.draw(pixmap, 0, 0);

        batch.begin();
        // A renderWidth x renderHeight texture drawn over the whole SURFACE.
        // The GPU does the upscale, and the orientation argument in the class
        // Javadoc is untouched by it: draw() maps t = 0 to the top of the
        // rectangle whatever the two sizes are.
        batch.draw(texture, 0.0f, 0.0f, width, height);
        batch.end();
        sampleFrameRate(true);
        return true;
    }

    // Counts one platform frame and emits a line once per interval. A no-op
    // unless the log was asked for, so a normal run pays two field reads.
    private void sampleFrameRate(final boolean presented)
    {
        if (fpsIntervalNanos == 0L)
        {
            return;
        }
        final long now = TimeUtils.nanoTime();
        windowPlatformFrames++;
        if (presented)
        {
            windowPresentedFrames++;
        }
        if (windowStartNanos == 0L)
        {
            openWindow(now);
            return;
        }
        final long elapsed = now - windowStartNanos;
        if (elapsed < fpsIntervalNanos)
        {
            return;
        }
        final double seconds = (double) elapsed / (double) NANOS_PER_SECOND;
        // Both sizes, because the last-frame figure is the cost of the RENDER
        // size while the frame rate is a property of the surface being
        // presented — reading one against the other is the whole point.
        LOG.info("windowed {}x{} (render {}x{}): {} platform fps, {} presented fps,"
            + " {} rendered fps, last frame {} ms, {} parallel passes",
            width, height, renderWidth, renderHeight,
            rate(windowPlatformFrames, seconds),
            rate(windowPresentedFrames, seconds),
            rate(renderPort.framesRendered() - windowRenderedAtStart, seconds),
            String.format(Locale.ROOT, "%.2f", renderPort.lastFrameNanos() / NANOS_PER_MILLI),
            renderPort.lastFrameParallelPasses());
        openWindow(now);
    }

    // Starts a fresh sampling window at the given instant.
    private void openWindow(final long now)
    {
        this.windowStartNanos = now;
        this.windowPlatformFrames = 0;
        this.windowPresentedFrames = 0;
        this.windowRenderedAtStart = renderPort.framesRendered();
    }

    private static String rate(final long frames, final double seconds)
    {
        return String.format(Locale.ROOT, "%.1f", frames / seconds);
    }

    /** Releases the batch and every surface-sized GPU resource. */
    public void dispose()
    {
        releaseSurface();
        // Forget the size along with the resources. Android disposes on
        // onSurfaceLost and can hand the SAME dimensions back on the next
        // onSurfaceReady, and a remembered size would make that resize a no-op
        // that never rebuilds the texture it just freed — leaving present() to
        // return false for the rest of the run.
        renderWidth = 0;
        renderHeight = 0;
        if (batch != null)
        {
            batch.dispose();
            batch = null;
        }
    }

    /**
     * Returns how long the renderer's most recent frame took, in nanoseconds.
     *
     * <p>Straight through to {@code SoftwareRenderPort.lastFrameNanos()}. It is
     * forwarded here rather than read from the port directly by callers because
     * this class is already the one thing on the platform side that holds a
     * renderer — a second holder would be a second place to keep in step with
     * "is there a renderer at all", which is the question the null presenter
     * answers everywhere else.</p>
     *
     * @return the renderer's last frame duration, or 0 before it has finished
     *     one — which {@link FpsMeter} discards rather than averaging in
     */
    public long lastRenderNanos()
    {
        return renderPort.lastFrameNanos();
    }

    /**
     * Returns the framebuffer width the rasterizer is filling.
     *
     * <p>Equal to {@link #width()} in {@link RenderMode#NATIVE}, and smaller in
     * every other mode. This is the number the RENDER milliseconds are the cost
     * of, which is why {@link DebugOverlay} shows the two together.</p>
     *
     * @return the render width, or zero before the first {@link #resize}
     */
    public int renderWidth()
    {
        return renderWidth;
    }

    /**
     * Returns the framebuffer height the rasterizer is filling.
     *
     * @return the render height, or zero before the first {@link #resize}
     */
    public int renderHeight()
    {
        return renderHeight;
    }

    // Drops the texture and pixmap. Both are native allocations; letting a
    // resize leak them would leak once per window drag event.
    private void releaseSurface()
    {
        if (texture != null)
        {
            texture.dispose();
            texture = null;
        }
        if (pixmap != null)
        {
            pixmap.dispose();
            pixmap = null;
        }
    }
}
