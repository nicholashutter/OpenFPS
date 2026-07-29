/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How big a frame the software rasterizer is asked to fill, given a surface.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p><b>A software rasterizer's cost is per pixel, and a display panel is not a
 * budget.</b> Desktop opens a 1280x720 window — 0.92 megapixels — and R_ fills
 * it in about 5.6 ms. Android took the panel's native 2400x1080, which is 2.59
 * megapixels, and the same frame took about <b>98 ms</b>: roughly 2.8x the
 * pixels for roughly 2.8x the time, measured on the {@code OpenFPS_API36}
 * emulator through {@link DebugOverlay}. That is ten rasterized frames a second
 * behind a 56 Hz present. Nothing is wrong with the rasterizer; it was being
 * asked to fill a framebuffer sized by a panel, and no amount of tuning inside
 * the span loop closes a gap of that shape.</p>
 *
 * <p>{@link FramebufferPresenter} already draws the finished frame as a
 * <b>fullscreen quad</b>, and a quad fills the viewport whatever the texture's
 * dimensions are. So the upscale is a GPU blit that costs nothing measurable,
 * and the only thing standing between the rasterizer and a frame budget was
 * that the render size and the surface size were the same number. This enum is
 * the seam that separates them.</p>
 *
 * <h2>A mode names the SHORT edge</h2>
 *
 * <p>{@link #P480} is a height of 480 in landscape, exactly as "480p" has meant
 * since broadcast television, and <b>not</b> a long edge, a pixel count or a
 * percentage. That choice is what makes the label mean something across
 * devices: a 16:9 phone and a 21:9 one both render 480 rows, and the wider
 * panel pays for the extra columns its own shape asks for rather than being
 * quietly squashed to a common pixel budget.</p>
 *
 * <h2>One factor for both edges — the aspect is not negotiable</h2>
 *
 * <p>The short edge lands on the mode exactly and the long edge is that same
 * ratio applied to the surface's own long edge. Two independently chosen edges
 * would stretch the image, and worse, would stretch it <i>invisibly</i>: the
 * camera's aspect ratio comes from {@code SoftwareRenderPort.surfaceWidth()}
 * and {@code surfaceHeight()} — the framebuffer's own numbers, see
 * {@code DemoGameplayPort.aimCamera} — so a distorted framebuffer produces a
 * consistently distorted <i>projection</i>, which reads as a slightly wrong
 * field of view rather than as a bug. There is no letterboxing either: the quad
 * covers the surface, and it is the frame's shape that is made to match, not
 * the frame that is made to fit inside a shape.</p>
 *
 * <p><b>"Exactly" means "to within one pixel of rounding on the long edge", and
 * it cannot mean more than that.</b> A framebuffer is a whole number of pixels.
 * The short edge is exact by construction; the long edge is the nearest whole
 * pixel to the true ratio, so the aspect error is under half a pixel. At the
 * size that prompted this work it is not even that far: 2400x1080 at
 * {@link #P480} is 1067x480, whose aspect differs from 20:9 by 0.03%.
 * {@code RenderModeTest} asserts the bound rather than arguing it.</p>
 *
 * <h2>A mode is a CEILING, never a target</h2>
 *
 * <p>A surface already shorter than the mode is rendered at its own size, and
 * that rule is load-bearing rather than defensive. Rasterizing 480 rows into a
 * 320-row window would cost <i>more</i> pixels than the window has and then
 * throw the extra away on the blit — slower and blurrier at once, which is the
 * worst of both. {@link #NATIVE} is the same rule with no ceiling at all, and
 * it returns the surface size <b>exactly</b>: not a factor of 1.0 applied and
 * rounded, but the surface's own two integers, so today's behaviour survives
 * bit for bit.</p>
 *
 * <h2>The default is 480p, everywhere</h2>
 *
 * <p>Including desktop, which is already fast at its native 720p. One default
 * across both platforms means the thing a player sees is the thing a developer
 * sees, and it means the scaling path is exercised by every desktop run rather
 * than only by the platform that cannot be debugged as easily. {@link #NATIVE}
 * is one press away on the settings screen for anyone who wants the pixels
 * back.</p>
 *
 * <h2>Not persisted, and that is a decision</h2>
 *
 * <p>The chosen mode survives the run and no longer, exactly as
 * {@link DebugSettings} does and for exactly the reason spelled out there:
 * persisting it means a field on {@code UserProfile}, a column and a migration
 * on the Room entity and DAO, matching changes in {@code SqliteUserProfilePort}
 * and {@code MemoryUserProfilePort}, and the validation and {@code withXxx}
 * copy that every field on that immutable class carries. That is a schema
 * migration across two databases. When a settings screen carries something a
 * player would be annoyed to re-enter — sensitivity, field of view, volume, all
 * of which {@code UserProfile} already stores — the migration becomes worth
 * doing and this rides along with it. Until then, saying so here is more honest
 * than leaving the reader wondering why their choice forgot.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public enum RenderMode
{
    /**
     * 480 rows — the default on every platform. About 0.51 MPix on a 20:9
     * phone, against 2.59 native.
     */
    P480("480P", 480),

    /**
     * 720 rows, labelled "High". The desktop window's own height, so a desktop
     * run in this mode rasterizes exactly what it always did.
     */
    P720("720P", 720),

    /**
     * The surface's own resolution, labelled "Super High". Today's behaviour,
     * kept reachable because a small window or a fast machine has no reason to
     * throw pixels away.
     *
     * <p>The literal zero is {@link #NO_CEILING}, spelled out rather than named
     * because an enum's constants are declared before anything else in its
     * body and a constructor argument may not reach forward past them.</p>
     */
    NATIVE("NATIVE", 0);

    /** The {@link #shortEdgePixels()} value meaning "no ceiling at all". */
    public static final int NO_CEILING = 0;

    /** The default on every platform. See the class Javadoc on why it is 480p. */
    public static final RenderMode DEFAULT = P480;

    /**
     * System property choosing the mode a run starts in.
     *
     * <p>Opt-in, on the {@link DebugSettings#OVERLAY_PROPERTY} pattern and for
     * exactly its reason: <b>a mode other than the default is otherwise
     * unreachable without a human.</b> Getting to it needs a mouse to press
     * Settings and then the control, so an automated capture could only ever
     * photograph the default — which would leave the two modes most in need of
     * a "does it flip? does it stretch?" screenshot as the two nothing can
     * photograph.</p>
     *
     * <p>It seeds the starting mode and nothing more. The settings screen still
     * owns the choice from the first press, so this is a starting position
     * rather than a lock.</p>
     *
     * <pre>
     *   gradlew :desktop:run "--args=--start-in-game" -Dopenfps.renderMode=native
     * </pre>
     */
    public static final String MODE_PROPERTY = "openfps.renderMode";

    private static final Logger LOG = LoggerFactory.getLogger(RenderMode.class);

    /** What the settings screen shows for this mode. */
    private final String label;

    /** The ceiling on the shorter surface edge in pixels, or {@link #NO_CEILING}. */
    private final int shortEdge;

    RenderMode(final String modeLabel, final int shortEdgeCeiling)
    {
        this.label = modeLabel;
        this.shortEdge = shortEdgeCeiling;
    }

    /**
     * Returns the mode {@link #MODE_PROPERTY} asks for, or {@link #DEFAULT}.
     *
     * <p>Matched against {@link #label()} case-insensitively, so the words a
     * player sees on the settings screen are the words that work on a command
     * line. An unrecognised value is warned about and ignored: a bad diagnostic
     * setting must not stop a window opening, which is the rule
     * {@code FramebufferPresenter.logIntervalSeconds} already follows.</p>
     *
     * @return the configured mode; never null
     */
    public static RenderMode configured()
    {
        final String wanted = System.getProperty(MODE_PROPERTY);
        if (wanted == null || wanted.isEmpty())
        {
            return DEFAULT;
        }
        final String tidied = wanted.trim().toUpperCase(Locale.ROOT);
        for (final RenderMode mode : values())
        {
            if (mode.label.equals(tidied))
            {
                return mode;
            }
        }
        LOG.warn("Ignoring -D{}={}: expected 480p, 720p or native", MODE_PROPERTY, wanted);
        return DEFAULT;
    }

    /** Returns the label the settings screen shows, such as {@code "480P"}. */
    public String label()
    {
        return label;
    }

    /**
     * Returns the ceiling this mode puts on the shorter surface edge.
     *
     * @return the ceiling in pixels, or {@link #NO_CEILING} for {@link #NATIVE}
     */
    public int shortEdgePixels()
    {
        return shortEdge;
    }

    /**
     * Returns the next mode in the settings screen's cycle.
     *
     * <p>Declaration order, wrapping — cheapest first, so pressing the button
     * always moves in one direction and the player can get back by going round
     * rather than by hunting for a way to reverse.</p>
     *
     * @return the mode after this one; never null
     */
    public RenderMode next()
    {
        final RenderMode[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /**
     * Returns the framebuffer width to use for a surface.
     *
     * @param surfaceWidth the surface width in pixels
     * @param surfaceHeight the surface height in pixels — needed even for the
     *     width, because the ceiling applies to whichever edge is shorter
     * @return the render width: the surface width when this mode does not scale
     *     that surface, and otherwise at least 1 and never larger
     */
    public int widthFor(final int surfaceWidth, final int surfaceHeight)
    {
        return edgeFor(surfaceWidth, surfaceHeight, surfaceWidth);
    }

    /**
     * Returns the framebuffer height to use for a surface.
     *
     * @param surfaceWidth the surface width in pixels
     * @param surfaceHeight the surface height in pixels
     * @return the render height: the surface height when this mode does not
     *     scale that surface, and otherwise at least 1 and never larger
     */
    public int heightFor(final int surfaceWidth, final int surfaceHeight)
    {
        return edgeFor(surfaceWidth, surfaceHeight, surfaceHeight);
    }

    /**
     * Returns whether this mode leaves a surface at its own size.
     *
     * <p>The presenter asks so it can keep {@code Nearest} filtering for a blit
     * that is exactly 1:1 — see
     * {@link FramebufferPresenter#UPSCALE_FILTER_PROPERTY}.</p>
     *
     * @param surfaceWidth the surface width in pixels
     * @param surfaceHeight the surface height in pixels
     * @return true if the render size is the surface size
     */
    public boolean isNativeFor(final int surfaceWidth, final int surfaceHeight)
    {
        return !scalesDown(surfaceWidth, surfaceHeight);
    }

    // Whether this mode actually shrinks a given surface. Everything else keys
    // off this one question, so "NATIVE", "no surface yet" and "the window is
    // already smaller than the mode" all take one path and cannot disagree.
    private boolean scalesDown(final int surfaceWidth, final int surfaceHeight)
    {
        if (shortEdge == NO_CEILING || surfaceWidth <= 0 || surfaceHeight <= 0)
        {
            return false;
        }
        return Math.min(surfaceWidth, surfaceHeight) > shortEdge;
    }

    // One edge of the render size.
    //
    // The short edge lands on the ceiling EXACTLY rather than through the
    // rounded factor, which is what keeps "480p" a promise rather than an
    // approximation, and what leaves the whole of the rounding error on the
    // long edge where it is worth less than half a pixel of aspect. A square
    // surface has two short edges and both take that exact path.
    private int edgeFor(final int surfaceWidth, final int surfaceHeight, final int wanted)
    {
        if (!scalesDown(surfaceWidth, surfaceHeight))
        {
            return wanted;
        }
        final int shorter = Math.min(surfaceWidth, surfaceHeight);
        if (wanted == shorter)
        {
            return shortEdge;
        }
        // The long edge, at the same ratio. Rounded to the nearest whole pixel
        // and floored at 1: an extreme aspect such as 4000x3 never reaches here
        // (its short edge is already under any ceiling), but a floor costs
        // nothing and a framebuffer with a zero dimension is an allocation of no
        // pixels that every downstream divide then trips over.
        final long scaled = Math.round((double) wanted * shortEdge / shorter);
        return (int) Math.max(1L, scaled);
    }
}
