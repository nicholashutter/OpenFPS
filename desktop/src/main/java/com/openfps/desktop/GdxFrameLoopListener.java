/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;

import com.openfps.engine.hal.port.I_FrameCallback;

/**
 * Bridges libGDX's {@link ApplicationListener} to the engine's
 * {@link I_FrameCallback}, and draws the desktop UI on the way through.
 *
 * The two interfaces line up one-to-one, which is not a coincidence —
 * {@code I_FrameCallback} was shaped so an Android {@code GLSurfaceView}
 * and a desktop GLFW loop could both satisfy it:
 *
 * <pre>
 *   create()  -&gt; onSurfaceReady(w, h)
 *   render()  -&gt; onFrame(deltaSeconds)
 *   resize()  -&gt; onResize(w, h)
 *   pause()   -&gt; onPause()
 *   resume()  -&gt; onResume()
 *   dispose() -&gt; onSurfaceLost()
 * </pre>
 *
 * Presentation is split by owner: this class draws the menu (a platform
 * concern — Scene2D is a libGDX type and must never reach {@code :engine}),
 * then hands the same frame to the engine callback, which watches for the
 * game loop dying so the window can follow it down. The engine gets its
 * frame notification either way, so the split is invisible to it.
 *
 * <b>Threading:</b> every method runs on the LWJGL3 main/render thread, not
 * the game loop thread.
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class GdxFrameLoopListener implements ApplicationListener
{
    /** The engine's side of the frame loop. */
    private final I_FrameCallback callback;

    /** What the menu buttons do. */
    private final MenuActions actions;

    /**
     * The menu UI.
     * MUTABLE: built in {@link #create()}, released in {@link #dispose()}.
     * It cannot be built earlier — there is no GL context until create().
     */
    private MainMenuScreen menu;

    /**
     * Creates the bridge.
     *
     * @param callback the engine callback to forward lifecycle to; must not
     *     be null
     * @param actions what the menu buttons do; must not be null
     */
    public GdxFrameLoopListener(final I_FrameCallback callback, final MenuActions actions)
    {
        if (callback == null)
        {
            throw new IllegalArgumentException("callback must not be null");
        }
        if (actions == null)
        {
            throw new IllegalArgumentException("actions must not be null");
        }
        this.callback = callback;
        this.actions = actions;
    }

    @Override
    public void create()
    {
        final int width = Gdx.graphics.getWidth();
        final int height = Gdx.graphics.getHeight();
        menu = new MainMenuScreen(actions);
        callback.onSurfaceReady(width, height);
    }

    @Override
    public void render()
    {
        final float deltaSeconds = Gdx.graphics.getDeltaTime();
        if (menu != null)
        {
            menu.render(deltaSeconds);
        }
        callback.onFrame(deltaSeconds);
    }

    @Override
    public void resize(final int width, final int height)
    {
        if (menu != null)
        {
            menu.resize(width, height);
        }
        callback.onResize(width, height);
    }

    @Override
    public void pause()
    {
        callback.onPause();
    }

    @Override
    public void resume()
    {
        callback.onResume();
    }

    @Override
    public void dispose()
    {
        if (menu != null)
        {
            menu.dispose();
            menu = null;
        }
        callback.onSurfaceLost();
    }
}
