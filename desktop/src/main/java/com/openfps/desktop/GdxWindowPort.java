/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

import com.openfps.engine.gameplay.MatchSummary;
import com.openfps.engine.hal.port.I_FrameCallback;
import com.openfps.engine.hal.port.I_WindowPort;
import com.openfps.engine.render.adapter.SoftwareRenderPort;
import com.openfps.gdx.DebugSettings;
import com.openfps.gdx.DefaultMenuActions;
import com.openfps.gdx.FramebufferPresenter;
import com.openfps.gdx.MenuActions;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

    /**
     * The input port the frame loop should poll, or null for a window that
     * reads no input. MUTABLE: set by {@link #attachInput} before
     * {@link #runFrameLoop}.
     */
    private volatile GdxInputPort inputPort;

    /**
     * Told true when the player enters the world and false when they leave it,
     * or null when nothing cares. MUTABLE: set by {@link #attachMatchGate}
     * before {@link #runFrameLoop}.
     */
    private volatile Consumer<Boolean> matchGate;

    /**
     * Asked once a frame whether the round has been decided, or null when this
     * window has no match behind it. MUTABLE: set by
     * {@link #attachMatchResult} before {@link #runFrameLoop}.
     */
    private volatile Supplier<MatchSummary> matchResult;

    /**
     * The debug switch the settings screen flips, or null to let the listener
     * keep a private one. MUTABLE: set by {@link #attachDebugSettings} before
     * {@link #runFrameLoop}.
     */
    private volatile DebugSettings debugSettings;

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
            final GdxFrameLoopListener listener = new GdxFrameLoopListener(
                callback, new DefaultMenuActions(this), presenter(), inputPort,
                settings());
            listener.attachMatchGate(matchGate);
            listener.attachMatchResult(matchResult);
            new Lwjgl3Application(listener, buildConfiguration());
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

    /**
     * Names the input port the frame loop should poll each frame.
     *
     * Same shape and same reasoning as {@link #attachRenderer}: reading the
     * mouse is a GLFW call and so belongs to the thread that owns the window,
     * but the port itself is a HAL adapter the engine holds. This is how the
     * two are introduced, without {@code I_WindowPort} growing an input
     * method. Passing null leaves a window that reads no input, which is what
     * every windowless test gets.
     *
     * @param desktopInput the port to poll, or null for none
     */
    public void attachInput(final GdxInputPort desktopInput)
    {
        if (state == State.RUNNING)
        {
            throw new IllegalStateException("attachInput() while the frame loop is running");
        }
        this.inputPort = desktopInput;
    }

    /** Returns the input port named by {@link #attachInput}, or null. */
    public GdxInputPort inputPort()
    {
        return inputPort;
    }

    /**
     * Names something to be told when the player enters or leaves the world.
     *
     * <p><b>Why this exists.</b> The game loop starts with the process and ticks
     * at a fixed rate whatever is on screen — that is the whole design, and it
     * is correct. But a <i>match</i> starts when somebody presses a button on a
     * menu, and the two were the same thing until this seam existed: the bots
     * began patrolling and shooting the moment the window opened, so a player
     * who read the title screen for ten seconds entered the world already down a
     * fifth of their health. It was visible in the first run of the packaged
     * build as "took 2 damage" scrolling past under a menu.</p>
     *
     * <p>A {@code Consumer<Boolean>} rather than a typed gameplay reference,
     * because this class is a window port: it has no business importing the
     * engine's demo package to hold one method call. The launcher, which is the
     * composition root and already knows both objects, supplies the lambda.</p>
     *
     * <p>Call before {@link #runFrameLoop}; the listener that drives it is
     * constructed inside. Passing null means nothing is told, which is what
     * every windowless test gets.</p>
     *
     * @param gate told true on entering the world and false on leaving, or null
     */
    public void attachMatchGate(final Consumer<Boolean> gate)
    {
        if (state == State.RUNNING)
        {
            throw new IllegalStateException("attachMatchGate() while the frame loop is running");
        }
        this.matchGate = gate;
    }

    /** Returns the gate named by {@link #attachMatchGate}, or null. */
    public Consumer<Boolean> matchGate()
    {
        return matchGate;
    }

    /**
     * Names something to ask, once a frame, whether the round has been decided.
     *
     * <p>The return direction of {@link #attachMatchGate}: that one tells the
     * match when the player is in the world, this one asks the match whether the
     * world still has a game in it. Same shape and same reasoning — a
     * {@link Supplier} of a plain value rather than a gameplay reference, so a
     * window port does not import the demo package to hold one call. See
     * {@link GdxFrameLoopListener#attachMatchResult} for why it is a poll.</p>
     *
     * <p>Call before {@link #runFrameLoop}. Passing null leaves a window whose
     * match can never end, which is what every windowless test gets.</p>
     *
     * @param result returns null while the match runs and a frozen summary once
     *     it does not, or null for a window with no match
     */
    public void attachMatchResult(final Supplier<MatchSummary> result)
    {
        if (state == State.RUNNING)
        {
            throw new IllegalStateException("attachMatchResult() while the frame loop is running");
        }
        this.matchResult = result;
    }

    /** Returns the supplier named by {@link #attachMatchResult}, or null. */
    public Supplier<MatchSummary> matchResult()
    {
        return matchResult;
    }

    /**
     * Names the debug switch this window's settings screen should flip.
     *
     * <p>Supplied by the launcher when it wants to observe the switch — which it
     * does, to put the renderer's outline pass behind the same toggle without
     * either of them knowing about the other. Passing null, or not calling this,
     * leaves the frame loop with a private switch: the settings screen still
     * works and still shows the counter, and nothing outside the window is
     * told.</p>
     *
     * @param settings the switch to share, or null for a private one
     */
    public void attachDebugSettings(final DebugSettings settings)
    {
        if (state == State.RUNNING)
        {
            throw new IllegalStateException("attachDebugSettings() while the loop is running");
        }
        this.debugSettings = settings;
    }

    /** Returns the switch named by {@link #attachDebugSettings}, or null. */
    public DebugSettings debugSettings()
    {
        return debugSettings;
    }

    // The attached switch, or a fresh private one. Resolved here so the
    // listener's five-argument constructor never sees a null.
    private DebugSettings settings()
    {
        final DebugSettings attached = debugSettings;
        if (attached == null)
        {
            return new DebugSettings();
        }
        return attached;
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
