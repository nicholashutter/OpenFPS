/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import com.openfps.engine.hal.port.I_FrameCallback;

/**
 * Fans one platform frame out to two callbacks, in order.
 *
 * The window port takes a single {@link I_FrameCallback}, but Android needs
 * two things per frame: the engine's own callback (which watches for the
 * game loop ending) and the menu's (which draws). Rather than teach either
 * about the other, or widen the port to take a list, this composes them.
 *
 * <b>Order is load-bearing.</b> {@code first} runs before {@code second} for
 * every method, so the engine callback is given the frame before anything
 * draws — and on the way down, {@code onSurfaceLost} still runs first, which
 * means the engine hears about the surface going away before the menu
 * releases the GL resources it was drawing with. If that ever needs to
 * reverse, it should reverse deliberately and with a comment, not by
 * swapping the constructor arguments at a call site.
 *
 * Exceptions are not caught. A callback that throws mid-frame is a bug worth
 * seeing immediately, and swallowing it here would leave the other callback
 * in an undefined state anyway.
 *
 * Platform adapter — must not import from core engine packages beyond the
 * HAL port it implements.
 */
public final class CompositeFrameCallback implements I_FrameCallback
{
    /** Receives every event first. */
    private final I_FrameCallback first;

    /** Receives every event second. */
    private final I_FrameCallback second;

    /**
     * Creates a composite over two callbacks.
     *
     * @param first receives every event first; must not be null
     * @param second receives every event second; must not be null
     */
    public CompositeFrameCallback(final I_FrameCallback first, final I_FrameCallback second)
    {
        if (first == null)
        {
            throw new IllegalArgumentException("first must not be null");
        }

        if (second == null)
        {
            throw new IllegalArgumentException("second must not be null");
        }

        this.first = first;

        this.second = second;
    }

    @Override
    public void onSurfaceReady(final int width, final int height)
    {
        first.onSurfaceReady(width, height);

        second.onSurfaceReady(width, height);
    }

    @Override
    public void onFrame(final float deltaSeconds)
    {
        first.onFrame(deltaSeconds);

        second.onFrame(deltaSeconds);
    }

    @Override
    public void onResize(final int width, final int height)
    {
        first.onResize(width, height);

        second.onResize(width, height);
    }

    @Override
    public void onPause()
    {
        first.onPause();

        second.onPause();
    }

    @Override
    public void onResume()
    {
        first.onResume();

        second.onResume();
    }

    @Override
    public void onSurfaceLost()
    {
        first.onSurfaceLost();

        second.onSurfaceLost();
    }
}
