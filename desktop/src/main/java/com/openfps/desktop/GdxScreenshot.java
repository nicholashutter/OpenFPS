/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import java.util.Locale;
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
 *   -Dopenfps.screenshotCount=8                   how many frames, default 1
 *   -Dopenfps.screenshotExit=true                 close the window after
 * </pre>
 *
 * <h2>Why {@code screenshotCount} exists</h2>
 *
 * <p><b>Because one frame cannot show motion, and this project has twice shipped
 * an effect that was present in a still and invisible in play.</b> A tracer lives
 * eight tics and a puff of smoke thirty-six; whether either is <i>perceptible</i>
 * is a question about a sequence — how many pixels it covers on each of a run of
 * consecutive frames — and the only way to answer it was to launch the game once
 * per frame. That does not work: each launch is a separate process, so frame 300
 * of one run and frame 301 of the next are not adjacent tics and may not even be
 * close. Six "consecutive" captures taken that way showed a bolt in one and
 * nothing in the other five, which says nothing whatsoever about the effect.</p>
 *
 * <p>A count captures a genuine run from one process. The files are numbered
 * {@code frame-0001.png}, {@code frame-0002.png} and so on, inserted before the
 * extension so they sort in capture order; a count of one keeps the exact name it
 * was given, so every existing caller is unaffected.</p>
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

    /** System property naming how many consecutive frames to capture. */
    public static final String COUNT_PROPERTY = "openfps.screenshotCount";

    /** System property asking the window to close once the capture is written. */
    public static final String EXIT_PROPERTY = "openfps.screenshotExit";

    /** Frame captured when {@link #FRAME_PROPERTY} is not set. */
    public static final int DEFAULT_FRAME = 60;

    /** Frames captured when {@link #COUNT_PROPERTY} is not set — one. */
    public static final int DEFAULT_COUNT = 1;

    private static final Logger LOG = LoggerFactory.getLogger(GdxScreenshot.class);

    /** Digits in the index a burst appends, so the files sort in capture order. */
    private static final int INDEX_DIGITS = 4;

    /** Where to write, or null when capture is disabled. */
    private final String path;

    /** Which frame to capture first. */
    private final int targetFrame;

    /** How many consecutive frames to capture, at least one. */
    private final int captureCount;

    /** Whether to end the run once the last capture is written. */
    private final boolean exitAfter;

    /** Frames seen so far. MUTABLE: incremented once per platform frame. */
    private int frames;

    /** Captures written so far. MUTABLE. */
    private int written;

    /** Creates a capture configured from the system properties above. */
    public GdxScreenshot()
    {
        this(System.getProperty(PATH_PROPERTY),
            Integer.getInteger(FRAME_PROPERTY, DEFAULT_FRAME),
            Integer.getInteger(COUNT_PROPERTY, DEFAULT_COUNT),
            Boolean.parseBoolean(System.getProperty(EXIT_PROPERTY, "false")));
    }

    /**
     * Creates a single-frame capture with explicit settings.
     *
     * @param file where to write the PNG, or null to disable capture
     * @param frame which platform frame to capture, counting from one
     * @param exit whether to ask the window to close once written
     */
    public GdxScreenshot(final String file, final int frame, final boolean exit)
    {
        this(file, frame, DEFAULT_COUNT, exit);
    }

    /**
     * Creates a capture of one or more consecutive frames.
     *
     * @param file where to write the PNG, or null to disable capture
     * @param frame which platform frame to capture first, counting from one
     * @param count how many consecutive frames to capture; anything below one is
     *     treated as one rather than rejected, because a diagnostic switch must
     *     not be able to fail a run
     * @param exit whether to ask the window to close once the last is written
     */
    public GdxScreenshot(final String file, final int frame, final int count,
        final boolean exit)
    {
        this.path = file;

        this.targetFrame = frame;

        this.captureCount = Math.max(1, count);

        this.exitAfter = exit;
    }

    /**
     * Returns the file name one capture of a burst is written to.
     *
     * <p>A single capture keeps the name it was given, so nothing that already
     * asks for one frame sees a different file appear. A burst inserts a
     * zero-padded index <b>before the extension</b> — {@code frame-0003.png}
     * rather than {@code frame.png-0003} — so the results are still PNGs to
     * everything that opens them and still sort in capture order.</p>
     *
     * @param file the requested path
     * @param count how many frames the burst holds
     * @param index which capture this is, counting from one
     * @return the path to write
     */
    public static String fileFor(final String file, final int count, final int index)
    {
        if (count <= 1)
        {
            return file;
        }

        final String suffix = String.format(Locale.ROOT, "-%0" + INDEX_DIGITS + "d", index);

        final int dot = file.lastIndexOf('.');

        final int separator = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));

        if (dot <= separator)
        {
            // No extension at all, or a dot that belongs to a directory name.
            return file + suffix;
        }

        return file.substring(0, dot) + suffix + file.substring(dot);
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
        if (!isEnabled() || written >= captureCount)
        {
            return;
        }

        frames++;

        if (frames < targetFrame)
        {
            return;
        }

        written++;

        write(fileFor(path, captureCount, written));

        if (exitAfter && written >= captureCount)
        {
            Gdx.app.exit();
        }
    }

    /** Returns how many captures have been written so far. */
    public int capturesWritten()
    {
        return written;
    }

    // Reads the default framebuffer back and writes it. Any failure is logged
    // rather than thrown: a diagnostic must not be able to kill a run.
    private static void write(final String file)
    {
        try
        {
            final Pixmap shot = Pixmap.createFromFrameBuffer(0, 0,
                Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());

            try
            {
                PixmapIO.writePNG(new FileHandle(file), shot,
                    Deflater.DEFAULT_COMPRESSION, true);

                LOG.info("Wrote window capture: {} ({}x{})", file, shot.getWidth(),
                    shot.getHeight());
            }
            finally
            {
                shot.dispose();
            }
        }
        catch (final RuntimeException e)
        {
            LOG.error("Window capture failed: {}", file, e);
        }
    }
}
