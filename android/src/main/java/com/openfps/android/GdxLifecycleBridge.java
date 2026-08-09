/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import android.util.Log;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;

import com.openfps.engine.hal.port.I_FrameCallback;

/**
 * Translates libGDX's {@code ApplicationListener} into the engine's
 * {@link I_FrameCallback}.
 *
 * Platform adapter — must not import from core engine packages.
 *
 * The two interfaces line up almost one-to-one, which is not a coincidence:
 * {@code I_FrameCallback} was designed against this lifecycle so the Android
 * side would need a translation and not an emulation.
 *
 * <pre>
 *   create()   -&gt; onSurfaceReady(w, h)
 *   resize()   -&gt; onResize(w, h)      (first, redundant call suppressed)
 *   render()   -&gt; onFrame(deltaSeconds)
 *   pause()    -&gt; onPause()
 *   resume()   -&gt; onResume()
 *   dispose()  -&gt; onSurfaceLost()
 * </pre>
 *
 * <b>The first resize is swallowed.</b> libGDX calls {@code create()} and
 * then immediately {@code resize()} with the identical dimensions. Forwarding
 * both would have the engine rebuild its viewport twice at startup and, worse,
 * would make {@code onResize} unreliable as a genuine "the surface changed"
 * signal. The dimensions from {@code create()} are recorded and a resize that
 * does not actually change them is dropped.
 *
 * <b>GL context loss.</b> libGDX calls {@code create()} exactly once per
 * Activity. When Android destroys and later recreates the EGL context while
 * the process survives, the sequence is {@code pause()} then {@code resume()}
 * — never a second {@code create()}. So {@code onSurfaceReady} fires once and
 * anything it allocates must survive that gap on its own; see
 * {@link MainMenuFrameCallback} for how the menu does it.
 *
 * <b>Threading.</b> Every method here runs on the GLSurfaceView render
 * thread, not the UI thread and not the engine's game loop thread.
 */
final class GdxLifecycleBridge implements ApplicationListener
{
    /** Logcat tag. Android has no SLF4J binding, so platform code logs here. */
    private static final String TAG = "OpenFPS";

    /** The engine's side of the loop. */
    private final I_FrameCallback callback;

    /** Last surface width seen. MUTABLE: updated on every real resize. */
    private int lastWidth;

    /** Last surface height seen. MUTABLE: updated on every real resize. */
    private int lastHeight;

    /**
     * Wraps an engine frame callback.
     *
     * @param callback the engine callback to drive; never null
     */
    GdxLifecycleBridge(final I_FrameCallback callback)
    {
        this.callback = callback;
    }

    @Override
    public void create()
    {
        lastWidth = Gdx.graphics.getWidth();

        lastHeight = Gdx.graphics.getHeight();

        Log.i(TAG, "GL surface ready: " + lastWidth + "x" + lastHeight
            + " (GL30=" + (Gdx.gl30 != null) + ")");

        callback.onSurfaceReady(lastWidth, lastHeight);
    }

    @Override
    public void resize(final int width, final int height)
    {
        // See the class Javadoc: libGDX fires this once immediately after
        // create() with unchanged dimensions.
        if (width == lastWidth && height == lastHeight)
        {
            return;
        }

        lastWidth = width;

        lastHeight = height;

        callback.onResize(width, height);
    }

    @Override
    public void render()
    {
        callback.onFrame(Gdx.graphics.getDeltaTime());
    }

    @Override
    public void pause()
    {
        // Last guaranteed callback before the process may be killed. It runs
        // on the GL thread while the UI thread waits inside Activity.onPause,
        // so anything done here must be short and synchronous — there is no
        // "finish later".
        Log.i(TAG, "Paused");

        callback.onPause();
    }

    @Override
    public void resume()
    {
        Log.i(TAG, "Resumed");

        callback.onResume();
    }

    @Override
    public void dispose()
    {
        Log.i(TAG, "GL surface lost");

        callback.onSurfaceLost();
    }
}
