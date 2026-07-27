/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.nulladapter;

import com.openfps.engine.hal.port.I_WindowPort;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Null implementation of I_WindowPort — no window, no graphics, no OS calls.
 *
 * {@link #isRealWindow()} returns false, which tells the engine to skip
 * the main-thread pump loop entirely and just join the game loop thread.
 * So {@link #pumpEvents()} and {@link #present()} are never called in a
 * normal headless run; they are no-ops purely so tests can drive them.
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
    public void pumpEvents()
    {
        // no-op: no OS event queue to drain
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
    public void present()
    {
        // no-op: no surface to swap
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
