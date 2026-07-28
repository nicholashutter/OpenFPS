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
 * Presentation is split by owner: this class draws the world and then the menu
 * (both platform concerns — a GL texture and Scene2D are libGDX types and must
 * never reach {@code :engine}), then hands the same frame to the engine
 * callback, which watches for the game loop dying so the window can follow it
 * down. The engine gets its frame notification either way, so the split is
 * invisible to it.
 *
 * <b>Draw order is load-bearing.</b> The world goes down first, as an opaque
 * fullscreen quad, and the menu is composited on top. Before the rasterizer was
 * wired the menu also owned the screen <i>clear</i>; now the presenter covers
 * every pixel, so the menu draws as an overlay and the clear would erase the
 * frame that had just been uploaded. With no presenter attached — every
 * existing test, and any run without a renderer — the old clear-and-draw path
 * is unchanged.
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

    /** Draws the software rasterizer's frame, or null when there is no renderer. */
    private final FramebufferPresenter presenter;

    /** Opt-in window capture; disabled unless its system property is set. */
    private final GdxScreenshot screenshot;

    /**
     * The input port to poll each frame, or null when nothing reads input.
     *
     * This is the render thread's half of the input handoff — see
     * {@link GdxInputPort}. It has to happen here because GLFW input queries
     * belong on the thread that owns the window, and because the per-frame
     * mouse delta is only valid once per frame.
     */
    private final GdxInputPort inputPort;

    /**
     * The menu UI.
     * MUTABLE: built in {@link #create()}, released in {@link #dispose()}.
     * It cannot be built earlier — there is no GL context until create().
     */
    private MainMenuScreen menu;

    /**
     * Creates the bridge with no world presentation — menu only.
     *
     * @param callback the engine callback to forward lifecycle to; must not
     *     be null
     * @param actions what the menu buttons do; must not be null
     */
    public GdxFrameLoopListener(final I_FrameCallback callback, final MenuActions actions)
    {
        this(callback, actions, null);
    }

    /**
     * Creates the bridge.
     *
     * @param callback the engine callback to forward lifecycle to; must not
     *     be null
     * @param actions what the menu buttons do; must not be null
     * @param framePresenter draws the rasterizer's finished frame under the
     *     menu, or null for a menu-only window
     */
    public GdxFrameLoopListener(final I_FrameCallback callback, final MenuActions actions,
        final FramebufferPresenter framePresenter)
    {
        this(callback, actions, framePresenter, null);
    }

    /**
     * Creates the bridge.
     *
     * @param callback the engine callback to forward lifecycle to; must not
     *     be null
     * @param actions what the menu buttons do; must not be null
     * @param framePresenter draws the rasterizer's finished frame under the
     *     menu, or null for a menu-only window
     * @param desktopInput polled once per frame for mouse and keyboard state,
     *     or null for a window that reads no input
     */
    public GdxFrameLoopListener(final I_FrameCallback callback, final MenuActions actions,
        final FramebufferPresenter framePresenter, final GdxInputPort desktopInput)
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
        this.presenter = framePresenter;
        this.inputPort = desktopInput;
        this.screenshot = new GdxScreenshot();
    }

    @Override
    public void create()
    {
        final int width = Gdx.graphics.getWidth();
        final int height = Gdx.graphics.getHeight();
        menu = new MainMenuScreen(actions);
        if (presenter != null)
        {
            presenter.resize(width, height);
        }
        callback.onSurfaceReady(width, height);
    }

    @Override
    public void render()
    {
        final float deltaSeconds = Gdx.graphics.getDeltaTime();
        // Input first: the per-frame mouse delta is only valid once per frame,
        // and the game loop may latch it at any moment after this returns.
        if (inputPort != null)
        {
            inputPort.pollDevice();
        }
        drawWorldAndMenu(deltaSeconds);
        callback.onFrame(deltaSeconds);
        screenshot.afterFrame();
    }

    // World first, menu on top. Which of the two owns the clear depends on
    // whether a presenter is attached — see the class Javadoc.
    private void drawWorldAndMenu(final float deltaSeconds)
    {
        if (presenter == null)
        {
            if (menu != null)
            {
                menu.render(deltaSeconds);
            }
            return;
        }
        if (!presenter.present())
        {
            // No frame yet: the game loop has not published one. Fall back to
            // the menu's own clear so the window is not left showing garbage.
            if (menu != null)
            {
                menu.render(deltaSeconds);
            }
            return;
        }
        if (menu != null)
        {
            menu.drawOverlay(deltaSeconds);
        }
    }

    @Override
    public void resize(final int width, final int height)
    {
        if (menu != null)
        {
            menu.resize(width, height);
        }
        if (presenter != null)
        {
            presenter.resize(width, height);
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
        if (presenter != null)
        {
            presenter.dispose();
        }
        // The last moment at which GLFW is still up: libGDX calls dispose()
        // before it terminates the library, whereas the engine's own
        // I_InputPort.shutdown() runs afterwards. See
        // GdxInputPort.onWindowClosing() for what that cost.
        if (inputPort != null)
        {
            inputPort.onWindowClosing();
        }
        callback.onSurfaceLost();
    }
}
