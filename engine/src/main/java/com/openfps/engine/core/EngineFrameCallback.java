/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core;

import com.openfps.engine.hal.port.I_FrameCallback;
import com.openfps.engine.hal.port.I_WindowPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The engine's side of the platform frame loop.
 *
 * The platform owns the loop and calls in here; this class decides what the
 * engine does per frame. In Phase 1.5 that is almost nothing — the window
 * adapter clears and swaps, and this only watches for the game loop dying
 * so the window can close with it. Phase 5 replaces {@link #onFrame} with a
 * framebuffer upload.
 *
 * <b>This does not advance the simulation.</b> {@link GameLoop} does that on
 * its own thread at a fixed 30/60/120 Hz with absolute deadlines, because
 * lockstep determinism requires two peers at the same tic to be at the same
 * point in the simulation. Platform frame rate is whatever the display and
 * the OS decide — vsync, a 120 Hz phone panel, a throttled background tab —
 * and letting it drive a tic would make the simulation machine-dependent.
 *
 * <b>Threading:</b> every method runs on the platform's render thread,
 * which is not the game loop thread. Fields read here must be safe to
 * publish across threads; {@code Thread.isAlive()} and the window port's
 * close flag both are.
 */
final class EngineFrameCallback implements I_FrameCallback
{
    private static final Logger LOG = LoggerFactory.getLogger(EngineFrameCallback.class);

    /** The simulation thread. Watched so the window can follow it down. */
    private final Thread loopThread;

    /** The window, so a dead game loop can end the platform loop. */
    private final I_WindowPort window;

    EngineFrameCallback(final Thread loopThread, final I_WindowPort window)
    {
        this.loopThread = loopThread;

        this.window = window;
    }

    @Override
    public void onSurfaceReady(final int width, final int height)
    {
        LOG.info("Surface ready: {}x{}", width, height);
    }

    @Override
    public void onFrame(final float deltaSeconds)
    {
        // The game loop can finish on its own — maxTics reached, or a
        // ShutdownEvent. When it does, the window has nothing left to
        // present, so ask the platform to end its loop. Without this a
        // bounded run would leave a dead window on screen.
        if (!loopThread.isAlive())
        {
            window.requestClose();
        }
    }

    @Override
    public void onResize(final int width, final int height)
    {
        LOG.info("Surface resized: {}x{}", width, height);
    }

    @Override
    public void onPause()
    {
        // On Android this is the last guaranteed callback before the
        // process may be killed. Phase 1.4 persistence has nothing
        // frame-local to flush yet, so this only records the transition.
        LOG.info("Platform paused");
    }

    @Override
    public void onResume()
    {
        LOG.info("Platform resumed");
    }

    @Override
    public void onSurfaceLost()
    {
        // Android can destroy and later recreate the graphics context while
        // the process lives on, so this is not necessarily terminal.
        LOG.info("Surface lost");
    }
}
