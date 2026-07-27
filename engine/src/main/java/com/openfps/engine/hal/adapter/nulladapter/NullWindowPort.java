/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.nulladapter;

import com.openfps.engine.hal.port.I_FrameCallback;
import com.openfps.engine.hal.port.I_WindowPort;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Null implementation of I_WindowPort — no window, no graphics, no OS calls.
 *
 * {@link #isRealWindow()} returns false, which tells the engine to skip the
 * platform frame loop entirely and just join the game loop thread. So
 * {@link #runFrameLoop} is never reached in a normal headless run; it
 * returns immediately without invoking the callback, because there is no
 * surface for a callback to draw on and pretending otherwise would hand
 * tests a graphics context that does not exist.
 *
 * {@link #requestClose()} is honoured, which lets a headless test exercise
 * the window-close shutdown path with no display attached.
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class NullWindowPort implements I_WindowPort
{
    /** Close flag. Set from any thread, read by the pump. */
    private final AtomicBoolean closeRequested = new AtomicBoolean(false);

    @Override
    public void init()
    {
        closeRequested.set(false);
    }

    @Override
    public void create(final int width, final int height, final String title)
    {
        // no-op: nothing to create
    }

    @Override
    public void runFrameLoop(final I_FrameCallback callback)
    {
        // Returns immediately. There is no surface, so the callback is
        // deliberately NOT invoked — onSurfaceReady would be a lie and
        // onFrame would have nothing to draw to.
    }

    @Override
    public boolean isCloseRequested()
    {
        return closeRequested.get();
    }

    @Override
    public void requestClose()
    {
        closeRequested.set(true);
    }

    @Override
    public boolean isRealWindow()
    {
        return false;
    }

    @Override
    public void shutdown()
    {
        closeRequested.set(false);
    }
}
