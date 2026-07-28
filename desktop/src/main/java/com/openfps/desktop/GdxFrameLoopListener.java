/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;

import java.util.function.Consumer;

import com.openfps.engine.gameplay.MatchMode;
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
 * Presentation is split by owner: this class draws whichever UI is in front
 * (both are platform concerns — a GL texture and Scene2D are libGDX types and
 * must never reach {@code :engine}), then hands the same frame to the engine
 * callback, which watches for the game loop dying so the window can follow it
 * down. The engine gets its frame notification either way, so the split is
 * invisible to it.
 *
 * <b>This class owns the {@link UiStateMachine}.</b> It is the desktop UI, so
 * it is the thing that knows whether the menu or the game is in front, and
 * every consequence of that answer is its to apply:
 *
 * <ul>
 *   <li><b>Exactly one of the two draws, and it draws the whole window.</b> In
 *       {@link UiState#MENU} the menu clears and draws and the presenter is not
 *       called; in {@link UiState#PLAYING} the presenter's fullscreen quad is
 *       the frame and the menu is not reached. The menu used to be composited
 *       <i>over</i> a live first-person render — the world visibly carrying on
 *       behind the buttons — and that is the thing this state machine exists to
 *       stop. It is one branch, so restoring the overlay is one branch too, if
 *       a paused-behind look is ever wanted.</li>
 *   <li><b>The menu holds the input processor only while it is drawn.</b> Not
 *       drawn transparent, not drawn and ignored — {@code stage.act()} and
 *       {@code stage.draw()} are simply not reached and the processor is
 *       detached, so in {@code PLAYING} there is no hit testing, no hover
 *       animation and no invisible Quit button under the crosshair.</li>
 *   <li>{@link GdxInputPort} is handed the same machine at construction, which
 *       is how cursor capture, mouse-look and WASD come and go with the menu.
 *       It reconciles itself; this class does not drive it.</li>
 * </ul>
 *
 * The transition itself is driven from two places and only two: the menu's
 * Start Game button, wrapped here by {@link StartGameTransition}, and the
 * Escape key, seen by {@code GdxInputPort} while it polls.
 *
 * <b>Threading:</b> every method runs on the LWJGL3 main/render thread, not
 * the game loop thread.
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class GdxFrameLoopListener implements ApplicationListener
{
    /**
     * System property that boots straight into {@link UiState#PLAYING},
     * skipping the menu. Off unless set.
     *
     * <p>Opt-in, on the {@link GdxScreenshot} pattern and for the same reason:
     * {@code PLAYING} is now a state no automated capture can otherwise reach,
     * because reaching it needs a human to click Start Game. Without this, a
     * screenshot run can only ever photograph the menu — and the menu sits dead
     * centre, exactly where a crosshair or an outlined target would be.</p>
     *
     * <p>{@code -D} on a Gradle command line lands on the daemon, not on the
     * forked application, so {@link DesktopLauncher} sets this from its
     * {@code --start-in-game} argument instead:</p>
     *
     * <pre>
     *   gradlew :desktop:run --args="--start-in-game"
     * </pre>
     */
    public static final String START_IN_GAME_PROPERTY = "openfps.startInGame";

    /** The engine's side of the frame loop. */
    private final I_FrameCallback callback;

    /**
     * What the menu buttons do: the caller's actions, plus the one transition
     * the menu itself owns.
     */
    private final MenuActions actions;

    /** Menu or game, for this window. Never null, starts in {@link UiState#MENU}. */
    private final UiStateMachine uiState = new UiStateMachine();

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
     * The UI state the menu has already been reconciled with.
     * MUTABLE: render thread only, compared against {@link #uiState} once per
     * frame so the processor is attached or detached exactly once per change
     * rather than reasserted every frame.
     */
    private UiState appliedState = UiState.MENU;

    /**
     * Told whenever the world comes to the front or goes away, or null.
     *
     * MUTABLE: set once by {@link #attachMatchGate} before the loop starts. See
     * {@link GdxWindowPort#attachMatchGate} for what it is for.
     */
    private Consumer<Boolean> matchGate;

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
        this.actions = new StartGameTransition(actions, uiState);
        this.presenter = framePresenter;
        this.inputPort = desktopInput;
        this.screenshot = new GdxScreenshot();
        if (Boolean.parseBoolean(System.getProperty(START_IN_GAME_PROPERTY, "false")))
        {
            uiState.startGame();
        }
        if (desktopInput != null)
        {
            // Both halves of the UI must read the same answer. Bound here
            // rather than in the launcher because this is where the machine is
            // created, and because the port is otherwise free to be used
            // without a window at all.
            desktopInput.bindUiState(uiState);
        }
    }

    /**
     * Returns the UI state machine this window runs on. Never null.
     *
     * @return the machine the menu, the cursor and the input port all read
     */
    public UiStateMachine uiState()
    {
        return uiState;
    }

    /**
     * Names something to be told when the world comes to the front or goes away.
     *
     * <p>Called once by {@link GdxWindowPort#runFrameLoop} with whatever the
     * launcher attached. See {@link GdxWindowPort#attachMatchGate} for what this
     * is for and why it is a {@code Consumer<Boolean>} rather than a gameplay
     * reference.</p>
     *
     * <p>Setting it fires it immediately with the current state, so a gate
     * attached while the UI is already in {@link UiState#PLAYING} — which
     * {@code --start-in-game} produces — is not left believing the match is
     * frozen for the rest of the run.</p>
     *
     * @param gate told true on entering the world and false on leaving, or null
     */
    public void attachMatchGate(final Consumer<Boolean> gate)
    {
        this.matchGate = gate;
        notifyMatchGate();
    }

    /**
     * Returns whether the menu should be drawn and fed events this frame.
     *
     * <p>The single predicate both the draw gate and the input-processor gate
     * read, exposed so a headless test can assert the gate without a GL
     * context.</p>
     *
     * @return true only in {@link UiState#MENU}
     */
    public boolean isMenuActive()
    {
        return uiState.state().drawsMenu();
    }

    /**
     * Returns the actions the menu buttons are wired to.
     *
     * Not the object handed to the constructor: it is that object wrapped so
     * Start Game also performs {@code MENU -> PLAYING}. Package-private for the
     * test that drives the transition without a window.
     *
     * @return the wrapped actions; never null
     */
    MenuActions menuActions()
    {
        return actions;
    }

    @Override
    public void create()
    {
        final int width = Gdx.graphics.getWidth();
        final int height = Gdx.graphics.getHeight();
        // Here rather than in the window port's configuration, because libGDX's
        // setWindowIcon takes file paths and this icon is generated. This is the
        // first moment a GLFW window handle exists, and it is on the right
        // thread — see WindowIcon.
        WindowIcon.apply();
        menu = new MainMenuScreen(actions);
        menu.layoutFor(width, height);
        appliedState = uiState.state();
        applyMenuInput(appliedState);
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
        // After the poll, because Escape is seen there and the menu should come
        // back on the same frame the cursor is released, not the one after.
        // Scene2D button callbacks have already fired by now too: libGDX pumps
        // its input events before it calls render().
        syncUiState();
        drawWorld(deltaSeconds);
        callback.onFrame(deltaSeconds);
        screenshot.afterFrame();
    }

    // Exactly one of the two states draws, and it draws everything.
    private void drawWorld(final float deltaSeconds)
    {
        if (menu != null && isMenuActive())
        {
            // The menu owns the whole window, clear included. The world is not
            // presented at all — see the class Javadoc for why that is the
            // point rather than an optimisation.
            menu.render(deltaSeconds);
            return;
        }
        if (presenter != null && presenter.present())
        {
            return;
        }
        // Playing, but nothing was uploaded: there is no renderer, or the game
        // loop has not published its first frame. Something still has to own
        // the clear or the window shows the driver's leftovers.
        MainMenuScreen.clearBackground();
    }

    // Applies a UI change to the menu, once. Nothing here reasserts state every
    // frame: setInputProcessor is cheap but not free, and a per-frame write
    // would hide whoever else was fighting over it.
    private void syncUiState()
    {
        final UiState current = uiState.state();
        if (current == appliedState)
        {
            return;
        }
        appliedState = current;
        applyMenuInput(current);
        notifyMatchGate();
    }

    // Tells the gate whether the world is in front. Driven from the UI change
    // rather than polled every frame, so the simulation side sees exactly one
    // call per transition.
    private void notifyMatchGate()
    {
        if (matchGate == null)
        {
            return;
        }
        matchGate.accept(Boolean.valueOf(uiState.isPlaying()));
    }

    // The menu holds the Scene2D input processor only while it is on screen.
    // Detaching is what makes PLAYING genuinely free of the menu rather than
    // merely hiding it.
    private void applyMenuInput(final UiState state)
    {
        if (menu == null)
        {
            return;
        }
        if (state.drawsMenu())
        {
            menu.attachInputProcessor();
            return;
        }
        menu.detachInputProcessor();
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
            // Give the processor back before the stage goes: leaving a disposed
            // stage installed would have GLFW dispatching into freed actors.
            menu.detachInputProcessor();
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

    /**
     * The caller's menu actions, plus the {@code MENU -> PLAYING} transition
     * that starting the game implies.
     *
     * <p>A decorator rather than a change to {@link DefaultMenuActions} because
     * of who builds what: {@code GdxWindowPort.runFrameLoop} constructs the
     * actions, this class constructs the state machine, and neither can see the
     * other's object. Wrapping at the point where both are in scope keeps
     * {@code MenuActions} a pure command interface and leaves
     * {@code DefaultMenuActions} — which only knows how to close a window —
     * unaware that a UI state exists.</p>
     *
     * <p>The delegate runs first. If it throws, the UI stays in the menu, which
     * is the honest outcome: the game did not start.</p>
     */
    private static final class StartGameTransition implements MenuActions
    {
        /** The actions the window port supplied. */
        private final MenuActions delegate;

        /** The machine that Start Game advances. */
        private final UiStateMachine uiState;

        StartGameTransition(final MenuActions target, final UiStateMachine machine)
        {
            if (target == null)
            {
                throw new IllegalArgumentException("actions must not be null");
            }
            this.delegate = target;
            this.uiState = machine;
        }

        @Override
        public void onStartGame()
        {
            delegate.onStartGame();
            uiState.startGame(MatchMode.SINGLE_PLAYER);
        }

        @Override
        public void onMultiplayer()
        {
            delegate.onMultiplayer();
            uiState.startGame(MatchMode.MULTIPLAYER);
        }

        @Override
        public void onSettings()
        {
            delegate.onSettings();
        }

        @Override
        public void onQuit()
        {
            delegate.onQuit();
        }
    }
}
