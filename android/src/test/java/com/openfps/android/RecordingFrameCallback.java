/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import com.openfps.engine.hal.port.I_FrameCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * Test double for {@link I_FrameCallback} that records what it was told.
 *
 * Hand-written rather than mocked — the same choice the desktop module made
 * for its callback of the same name, and the reason these tests need no
 * mocking dependency. One addition over the desktop version: several
 * instances can share one event log, which is how
 * {@link CompositeFrameCallback}'s ordering guarantee is asserted without
 * either callback knowing about the other.
 *
 * Counters and the log are plain, non-atomic fields because every call
 * arrives on one thread in these tests.
 */
final class RecordingFrameCallback implements I_FrameCallback
{
    /** Prefix written into the shared log, identifying this instance. */
    private final String name;

    /** Ordered event log; may be shared with other recorders. */
    private final List<String> events;

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

    /** Width from the most recent surface-ready or resize. MUTABLE. */
    private int lastWidth;

    /** Height from the most recent surface-ready or resize. MUTABLE. */
    private int lastHeight;

    /** Delta from the most recent frame. MUTABLE. */
    private float lastDeltaSeconds;

    /** Creates a recorder with its own private event log. */
    RecordingFrameCallback()
    {
        this("callback", new ArrayList<>());
    }

    /**
     * Creates a recorder writing into a log it may share with others.
     *
     * @param name prefix identifying this instance in the log
     * @param events the log to append to; may already hold entries
     */
    RecordingFrameCallback(final String name, final List<String> events)
    {
        this.name = name;

        this.events = events;
    }

    @Override
    public void onSurfaceReady(final int width, final int height)
    {
        surfaceReadyCount++;

        lastWidth = width;

        lastHeight = height;

        events.add(name + ":onSurfaceReady");
    }

    @Override
    public void onFrame(final float deltaSeconds)
    {
        frameCount++;

        lastDeltaSeconds = deltaSeconds;

        events.add(name + ":onFrame");
    }

    @Override
    public void onResize(final int width, final int height)
    {
        resizeCount++;

        lastWidth = width;

        lastHeight = height;

        events.add(name + ":onResize");
    }

    @Override
    public void onPause()
    {
        pauseCount++;

        events.add(name + ":onPause");
    }

    @Override
    public void onResume()
    {
        resumeCount++;

        events.add(name + ":onResume");
    }

    @Override
    public void onSurfaceLost()
    {
        surfaceLostCount++;

        events.add(name + ":onSurfaceLost");
    }

    /**
     * Returns how many times the surface was reported ready.
     *
     * @return the count
     */
    int surfaceReadyCount()
    {
        return surfaceReadyCount;
    }

    /**
     * Returns how many frames were forwarded.
     *
     * @return the count
     */
    int frameCount()
    {
        return frameCount;
    }

    /**
     * Returns how many resizes were forwarded.
     *
     * @return the count
     */
    int resizeCount()
    {
        return resizeCount;
    }

    /**
     * Returns how many pauses were forwarded.
     *
     * @return the count
     */
    int pauseCount()
    {
        return pauseCount;
    }

    /**
     * Returns how many resumes were forwarded.
     *
     * @return the count
     */
    int resumeCount()
    {
        return resumeCount;
    }

    /**
     * Returns how many surface-lost notifications were forwarded.
     *
     * @return the count
     */
    int surfaceLostCount()
    {
        return surfaceLostCount;
    }

    /**
     * Returns the width from the most recent surface-ready or resize.
     *
     * @return the width, or zero if neither has happened
     */
    int lastWidth()
    {
        return lastWidth;
    }

    /**
     * Returns the height from the most recent surface-ready or resize.
     *
     * @return the height, or zero if neither has happened
     */
    int lastHeight()
    {
        return lastHeight;
    }

    /**
     * Returns the delta from the most recent frame.
     *
     * @return the delta in seconds, or zero if no frame has arrived
     */
    float lastDeltaSeconds()
    {
        return lastDeltaSeconds;
    }

    /**
     * Returns the ordered event log this recorder writes to.
     *
     * @return the live log; shared when the sharing constructor was used
     */
    List<String> events()
    {
        return events;
    }
}
