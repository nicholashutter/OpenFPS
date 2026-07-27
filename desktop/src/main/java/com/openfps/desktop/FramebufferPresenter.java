/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import java.nio.ByteBuffer;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

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
 * cancel: no explicit flip belongs here. Adding one is the bug, not the fix.</p>
 *
 * <h2>Lifecycle and threading</h2>
 *
 * <p>Every method runs on the LWJGL3 render thread, which owns the GL context.
 * GPU resources are created in {@link #resize} rather than in the constructor,
 * because there is no context until the surface exists — the same reason
 * {@code I_FrameCallback.onSurfaceReady} exists at all.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class FramebufferPresenter
{
    private static final Logger LOG = LoggerFactory.getLogger(FramebufferPresenter.class);

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

    /** De-padded frame, exactly {@code width * height}. MUTABLE: rebuilt on resize. */
    private int[] scratch;

    /** Surface width in pixels. MUTABLE: set on resize. */
    private int width;

    /** Surface height in pixels. MUTABLE: set on resize. */
    private int height;

    /**
     * Creates a presenter for one renderer.
     *
     * @param port the software renderer to present; must not be null
     */
    public FramebufferPresenter(final SoftwareRenderPort port)
    {
        if (port == null)
        {
            throw new IllegalArgumentException("port must not be null");
        }
        this.renderPort = port;
    }

    /**
     * Sizes the renderer's framebuffer and this presenter's GPU resources to
     * the surface.
     *
     * Call from {@code onSurfaceReady} and {@code onResize}. The renderer is
     * resized here rather than from the engine's own frame callback so the two
     * can never disagree about the frame's dimensions: one call site, one size.
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
        renderPort.resize(newWidth, newHeight);
        if (batch == null)
        {
            batch = new SpriteBatch();
            // The world quad is opaque and covers every pixel, so blending is
            // pure cost. It is also a hazard: the colour buffer's alpha is
            // whatever R_ wrote, and a frame is not a sprite.
            batch.disableBlending();
        }
        if (newWidth != width || newHeight != height)
        {
            releaseSurface();
            width = newWidth;
            height = newHeight;
            scratch = new int[width * height];
            pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);
            texture = new Texture(pixmap);
            // Nearest, because the quad is drawn at exactly 1:1. Any filtering
            // here would blur a software-rendered image for nothing.
            texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            LOG.info("Presenter surface: {}x{}", width, height);
        }
        batch.getProjectionMatrix().setToOrtho2D(0.0f, 0.0f, newWidth, newHeight);
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
            return false;
        }
        final ByteBuffer pixels = pixmap.getPixels();
        pixels.clear();
        // One bulk copy of RGBA8888 ints into a big-endian byte buffer — the
        // path PixmapByteOrderTest asserts byte for byte.
        pixels.asIntBuffer().put(scratch, 0, width * height);
        texture.draw(pixmap, 0, 0);

        batch.begin();
        batch.draw(texture, 0.0f, 0.0f, width, height);
        batch.end();
        return true;
    }

    /** Releases the batch and every surface-sized GPU resource. */
    public void dispose()
    {
        releaseSurface();
        if (batch != null)
        {
            batch.dispose();
            batch = null;
        }
    }

    /** Returns the surface width this presenter is sized for. */
    public int width()
    {
        return width;
    }

    /** Returns the surface height this presenter is sized for. */
    public int height()
    {
        return height;
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
