/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import java.util.zip.Deflater;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;

import com.openfps.gdx.FramebufferPresenter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the finished window back off the GPU and writes it as a PNG.
 *
 * <b>Why this exists, and why it is not just a convenience.</b>
 * {@code PixmapByteOrderTest} proves the Java half of the colour round trip
 * that {@code docs/ASSETS.md} § 4 demands — a {@code 0xRRGGBBAA} int arrives in
 * a {@code Pixmap} as the bytes R, G, B, A. It explicitly cannot prove the
 * other half, because no headless test has a GL context: whether
 * {@code glTexImage2D} then accepts those bytes unchanged, and whether the
 * quad lands the right way up. This class closes both gaps by reading the real
 * default framebuffer back and writing an image a human can look at.
 *
 * <p>Opt-in and off by default, driven by system properties so that a normal
 * run pays nothing:</p>
 *
 * <pre>
 *   -Dopenfps.screenshot=C:\some\path\frame.png   where to write
 *   -Dopenfps.screenshotFrame=60                  which frame, default 60
 *   -Dopenfps.screenshotExit=true                 close the window after
 * </pre>
 *
 * <p>{@code glReadPixels} returns rows bottom-up, which is the opposite of
 * PNG's order, so the write is asked to flip. That flip is a property of
 * {@code glReadPixels} alone and says nothing about
 * {@link FramebufferPresenter}'s orientation — if the presenter were upside
 * down, this file would show it.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class GdxScreenshot
{
    /** System property naming the file to write. Absent disables capture entirely. */
    public static final String PATH_PROPERTY = "openfps.screenshot";

    /** System property naming the frame index to capture. */
    public static final String FRAME_PROPERTY = "openfps.screenshotFrame";

    /** System property asking the window to close once the capture is written. */
    public static final String EXIT_PROPERTY = "openfps.screenshotExit";

    /** Frame captured when {@link #FRAME_PROPERTY} is not set. */
    public static final int DEFAULT_FRAME = 60;

    private static final Logger LOG = LoggerFactory.getLogger(GdxScreenshot.class);

    /** Where to write, or null when capture is disabled. */
    private final String path;

    /** Which frame to capture. */
    private final int targetFrame;

    /** Whether to end the run once the capture is written. */
    private final boolean exitAfter;

    /** Frames seen so far. MUTABLE: incremented once per platform frame. */
    private int frames;

    /** Whether the capture has already been written. MUTABLE. */
    private boolean captured;

    /** Creates a capture configured from the system properties above. */
    public GdxScreenshot()
    {
        this(System.getProperty(PATH_PROPERTY),
            Integer.getInteger(FRAME_PROPERTY, DEFAULT_FRAME),
            Boolean.parseBoolean(System.getProperty(EXIT_PROPERTY, "false")));
    }

    /**
     * Creates a capture with explicit settings.
     *
     * @param file where to write the PNG, or null to disable capture
     * @param frame which platform frame to capture, counting from one
     * @param exit whether to ask the window to close once written
     */
    public GdxScreenshot(final String file, final int frame, final boolean exit)
    {
        this.path = file;
        this.targetFrame = frame;
        this.exitAfter = exit;
    }

    /** Returns true if a capture was requested at all. */
    public boolean isEnabled()
    {
        return path != null && !path.isEmpty();
    }

    /**
     * Counts one platform frame and writes the capture if this is the one.
     *
     * Call at the very end of {@code render()}, after everything that should
     * appear in the image has been drawn and before the backend swaps.
     */
    public void afterFrame()
    {
        if (!isEnabled() || captured)
        {
            return;
        }
        frames++;
        if (frames < targetFrame)
        {
            return;
        }
        captured = true;
        write();
        if (exitAfter)
        {
            Gdx.app.exit();
        }
    }

    // Reads the default framebuffer back and writes it. Any failure is logged
    // rather than thrown: a diagnostic must not be able to kill a run.
    private void write()
    {
        try
        {
            final Pixmap shot = Pixmap.createFromFrameBuffer(0, 0,
                Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
            try
            {
                PixmapIO.writePNG(new FileHandle(path), shot,
                    Deflater.DEFAULT_COMPRESSION, true);
                LOG.info("Wrote window capture: {} ({}x{})", path, shot.getWidth(),
                    shot.getHeight());
            }
            finally
            {
                shot.dispose();
            }
        }
        catch (final RuntimeException e)
        {
            LOG.error("Window capture failed: {}", path, e);
        }
    }
}
