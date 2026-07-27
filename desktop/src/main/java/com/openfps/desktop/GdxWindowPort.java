/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

import com.openfps.engine.hal.port.I_FrameCallback;
import com.openfps.engine.hal.port.I_WindowPort;
import com.openfps.engine.render.adapter.SoftwareRenderPort;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real desktop window port, backed by libGDX's LWJGL3 backend.
 *
 * {@link #runFrameLoop} constructs an {@link Lwjgl3Application}, which
 * creates the GLFW window and does not return until that window closes.
 * That is exactly the contract {@code I_WindowPort} asks for: the platform
 * owns the loop and blocks the caller's thread. The engine's
 * {@code I_FrameCallback} is driven through {@link GdxFrameLoopListener}.
 *
 * <b>Why the state machine.</b> GLFW is unforgiving about ordering —
 * creating a window before initialising, or running a loop on a shut-down
 * context, is a native crash rather than an exception. The explicit
 * {@link State} turns each of those into a Java {@code IllegalStateException}
 * on the calling thread, and makes the ordering testable without a display,
 * which is the only part of a window port CI can actually cover.
 *
 * <b>Threading:</b> {@link #init}, {@link #create}, {@link #runFrameLoop}
 * and {@link #shutdown} are main-thread only. {@link #requestClose} and
 * {@link #isCloseRequested} are safe from any thread — the game loop and the
 * Quit button both use them.
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class GdxWindowPort implements I_WindowPort
{
    /** Default window width if {@link #create} is never called. */
    public static final int DEFAULT_WIDTH = 1280;

    /** Default window height if {@link #create} is never called. */
    public static final int DEFAULT_HEIGHT = 720;

    /** Default window title if {@link #create} is never called. */
    public static final String DEFAULT_TITLE = "OpenFPS";

    private static final Logger LOG = LoggerFactory.getLogger(GdxWindowPort.class);

    /**
     * Lifecycle position of the port.
     *
     * Transitions are strictly forward except {@code SHUTDOWN -> INITIALIZED}
     * via a fresh {@link GdxWindowPort#init()}, which lets the same instance
     * be reused across a restart.
     */
    public enum State
    {
        /** Constructed, nothing initialised. */
        NEW,

        /** {@link GdxWindowPort#init()} has run. No window yet. */
        INITIALIZED,

        /** {@link GdxWindowPort#create} has run. Window parameters fixed. */
        CREATED,

        /** Inside {@link GdxWindowPort#runFrameLoop}. */
        RUNNING,

        /** {@link GdxWindowPort#shutdown()} has run. */
        SHUTDOWN
    }

    /** Close flag. Set from any thread, read by the engine and the loop. */
    private final AtomicBoolean closeRequested = new AtomicBoolean(false);

    /** Lifecycle position. MUTABLE: advanced by the lifecycle methods. */
    private volatile State state = State.NEW;

    /** Requested client width. MUTABLE: set by {@link #create}. */
    private volatile int width = DEFAULT_WIDTH;

    /** Requested client height. MUTABLE: set by {@link #create}. */
    private volatile int height = DEFAULT_HEIGHT;

    /** Requested title bar text. MUTABLE: set by {@link #create}. */
    private volatile String title = DEFAULT_TITLE;

    /**
     * The software renderer whose frames this window presents, or null for a
     * menu-only window. MUTABLE: set by {@link #attachRenderer} before
     * {@link #runFrameLoop}.
     */
    private volatile SoftwareRenderPort renderer;

    @Override
    public void init()
    {
        if (state == State.RUNNING)
        {
            throw new IllegalStateException("init() while the frame loop is running");
        }
        closeRequested.set(false);
        state = State.INITIALIZED;
        LOG.info("GdxWindowPort initialized (LWJGL3 backend)");
    }

    @Override
    public void create(final int windowWidth, final int windowHeight, final String windowTitle)
    {
        if (state != State.INITIALIZED)
        {
            throw new IllegalStateException("create() requires INITIALIZED, was " + state);
        }
        if (windowWidth <= 0 || windowHeight <= 0)
        {
            throw new IllegalArgumentException(
                "window size must be positive, got " + windowWidth + "x" + windowHeight);
        }
        if (windowTitle == null)
        {
            throw new IllegalArgumentException("title must not be null");
        }
        this.width = windowWidth;
        this.height = windowHeight;
        this.title = windowTitle;
        state = State.CREATED;
        LOG.info("Window configured: {}x{} '{}'", windowWidth, windowHeight, windowTitle);
    }

    /**
     * Hands the calling thread to LWJGL3 and blocks until the window closes.
     *
     * The GLFW window is created here rather than in {@link #create} because
     * {@code Lwjgl3Application}'s constructor does both — it opens the
     * window and then runs the loop, and there is no supported way to split
     * them. {@link #create} therefore records the geometry and this applies
     * it.
     *
     * @param callback the engine's frame callback; must not be null
     */
    @Override
    public void runFrameLoop(final I_FrameCallback callback)
    {
        if (callback == null)
        {
            throw new IllegalArgumentException("callback must not be null");
        }
        if (state != State.CREATED)
        {
            throw new IllegalStateException("runFrameLoop() requires CREATED, was " + state);
        }
        state = State.RUNNING;
        LOG.info("Entering LWJGL3 frame loop — the platform owns the thread from here");
        try
        {
            new Lwjgl3Application(
                new GdxFrameLoopListener(callback, new DefaultMenuActions(this), presenter()),
                buildConfiguration());
        }
        finally
        {
            // The application has exited, by user close or Gdx.app.exit().
            // Either way the window is gone, so the close flag reflects
            // reality and the port drops back to a shutdown-able state.
            closeRequested.set(true);
            state = State.CREATED;
            LOG.info("LWJGL3 frame loop exited");
        }
    }

    /**
     * Names the renderer whose finished frames this window should present.
     *
     * <b>This is not a new port and it is not a presentation hook.</b>
     * {@code render/README.md} § 12 keeps presentation on the platform side and
     * out of {@code I_RenderPort}, so the adapter needs some way to reach the
     * finished {@code int[]}. That is all this does: it names the object whose
     * {@code copyColorInto} the presenter will call. The engine still never
     * learns a window exists, and {@code I_WindowPort} is unchanged — this
     * method is on the concrete class, not on the interface.
     *
     * <p>Call before {@link #runFrameLoop}; the listener that holds the
     * presenter is constructed inside it. Passing null leaves a menu-only
     * window, which is what every windowless test gets.</p>
     *
     * @param softwareRenderer the renderer to present, or null for none
     */
    public void attachRenderer(final SoftwareRenderPort softwareRenderer)
    {
        if (state == State.RUNNING)
        {
            throw new IllegalStateException("attachRenderer() while the frame loop is running");
        }
        this.renderer = softwareRenderer;
    }

    /** Returns the renderer named by {@link #attachRenderer}, or null. */
    public SoftwareRenderPort renderer()
    {
        return renderer;
    }

    // Builds the presenter for one run, or null when no renderer was attached.
    // Built here rather than held as a field because it owns GPU resources and
    // there is no context until Lwjgl3Application starts.
    private FramebufferPresenter presenter()
    {
        final SoftwareRenderPort attached = renderer;
        if (attached == null)
        {
            return null;
        }
        return new FramebufferPresenter(attached);
    }

    // Translates the recorded geometry into an LWJGL3 configuration.
    private Lwjgl3ApplicationConfiguration buildConfiguration()
    {
        final Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle(title);
        config.setWindowedMode(width, height);
        // Vsync governs presentation rate. It must not govern anything else:
        // GameLoop is the simulation clock at a fixed 30/60/120 Hz on its own
        // thread, so a 144 Hz panel changes how often the menu redraws and
        // nothing about the tic rate.
        config.useVsync(true);
        return config;
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
        // Only meaningful while an application is live. Headless — in tests,
        // or before runFrameLoop — Gdx.app is null and the flag alone is the
        // whole answer.
        final Application app = Gdx.app;
        if (app != null)
        {
            app.exit();
        }
    }

    @Override
    public boolean isRealWindow()
    {
        return true;
    }

    @Override
    public void shutdown()
    {
        if (state == State.RUNNING)
        {
            throw new IllegalStateException("shutdown() while the frame loop is running");
        }
        state = State.SHUTDOWN;
        LOG.info("GdxWindowPort shut down");
    }

    /** Returns the current lifecycle position. Never null. */
    public State state()
    {
        return state;
    }
}
