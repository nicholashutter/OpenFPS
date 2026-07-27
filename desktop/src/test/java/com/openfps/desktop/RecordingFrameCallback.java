/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import com.openfps.engine.hal.port.I_FrameCallback;

/**
 * Test double for {@link I_FrameCallback} that records what it was told.
 *
 * Lets the desktop tests assert on lifecycle forwarding without a GL
 * context. Counters are plain {@code int}s because every call arrives on
 * one thread in these tests.
 */
final class RecordingFrameCallback implements I_FrameCallback
{
    /** Surface-ready calls. MUTABLE: incremented per callback. */
    private int surfaceReadyCount;

    /** Frame calls. MUTABLE: incremented per callback. */
    private int frameCount;

    /** Resize calls. MUTABLE: incremented per callback. */
    private int resizeCount;

    /** Pause calls. MUTABLE: incremented per callback. */
    private int pauseCount;

    /** Resume calls. MUTABLE: incremented per callback. */
    private int resumeCount;

    /** Surface-lost calls. MUTABLE: incremented per callback. */
    private int surfaceLostCount;

    @Override
    public void onSurfaceReady(final int width, final int height)
    {
        surfaceReadyCount++;
    }

    @Override
    public void onFrame(final float deltaSeconds)
    {
        frameCount++;
    }

    @Override
    public void onResize(final int width, final int height)
    {
        resizeCount++;
    }

    @Override
    public void onPause()
    {
        pauseCount++;
    }

    @Override
    public void onResume()
    {
        resumeCount++;
    }

    @Override
    public void onSurfaceLost()
    {
        surfaceLostCount++;
    }

    /** Returns how many times the surface was reported ready. */
    int surfaceReadyCount()
    {
        return surfaceReadyCount;
    }

    /** Returns how many frames were forwarded. */
    int frameCount()
    {
        return frameCount;
    }

    /** Returns how many resizes were forwarded. */
    int resizeCount()
    {
        return resizeCount;
    }

    /** Returns how many pauses were forwarded. */
    int pauseCount()
    {
        return pauseCount;
    }

    /** Returns how many resumes were forwarded. */
    int resumeCount()
    {
        return resumeCount;
    }

    /** Returns how many surface-lost notifications were forwarded. */
    int surfaceLostCount()
    {
        return surfaceLostCount;
    }
}
