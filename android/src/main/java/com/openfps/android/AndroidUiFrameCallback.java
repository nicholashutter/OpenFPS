/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import java.util.function.Consumer;

import android.util.Log;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.utils.ScreenUtils;

import com.openfps.engine.gameplay.MatchMode;
import com.openfps.engine.hal.port.I_FrameCallback;
import com.openfps.gdx.FramebufferPresenter;
import com.openfps.gdx.MenuActions;
import com.openfps.gdx.UiState;
import com.openfps.gdx.UiStateMachine;

/**
 * The Android UI: menu or world, and everything that follows from which.
 *
 * <p>The counterpart of {@code GdxFrameLoopListener}, and it makes the same
 * three decisions for the same reasons:</p>
 *
 * <ul>
 *   <li><b>Exactly one of the two draws, and it draws the whole screen.</b> In
 *       {@link UiState#MENU} the menu clears and draws and the presenter is not
 *       called; in {@link UiState#PLAYING} the presenter's fullscreen quad is
 *       the frame, the touch controls go over it, and the menu is not reached.
 *       Compositing a menu over a live first-person render — the world visibly
 *       carrying on behind the buttons — is the thing the state machine exists
 *       to stop.</li>
 *   <li><b>Whoever is in front holds the input processor.</b> The menu's
 *       Scene2D stage in {@code MENU}, {@link AndroidInputPort} in
 *       {@code PLAYING}. Not both, and never a stage left installed under a
 *       player's aiming thumb.</li>
 *   <li><b>The match runs only while the world is in front.</b> Through
 *       {@link #attachMatchGate}, for the reason the desktop launcher records:
 *       the bots patrolled and fired from the moment the process started, so a
 *       player who read the menu for ten seconds began already hurt.</li>
 * </ul>
 *
 * <h2>Why this is a separate class from the menu</h2>
 *
 * <p>{@link MainMenuFrameCallback} was already the menu and already tested, and
 * a menu that also knew about the rasterizer, the touch overlay and the match
 * gate would be a class with two jobs. So the menu stayed what it was and this
 * composes it — which is also why the menu no longer claims the input processor
 * for itself.</p>
 *
 * <h2>Leaving a match</h2>
 *
 * <p>Two ways in, one way out: the on-screen button and the system back key,
 * both resolved through {@link AndroidBindings}. Back has to be
 * <i>caught</i> explicitly ({@link Input#setCatchKey}) or Android finishes the
 * Activity before libGDX sees the press — which on a phone is the difference
 * between "leave the match" and "quit the game", and the wrong one of those is
 * unrecoverable.</p>
 *
 * <p>The catch is asserted only while playing, so back still leaves the app
 * from the menu, which is what an Android user expects there.</p>
 *
 * <b>Threading.</b> Every method runs on the GLSurfaceView render thread.
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class AndroidUiFrameCallback implements I_FrameCallback
{
    /** Logcat tag. Android has no SLF4J binding, so platform code logs here. */
    private static final String TAG = "OpenFPS";

    /** The menu. Never null. */
    private final MainMenuFrameCallback menu;

    /** Menu or game, for this Activity. Never null, starts in {@link UiState#MENU}. */
    private final UiStateMachine uiState = new UiStateMachine();

    /** Draws the rasterizer's finished frame, or null for a menu-only build. */
    private final FramebufferPresenter presenter;

    /** Reads the touch controls, or null when nothing reads input. */
    private final AndroidInputPort input;

    /** Draws the touch controls, or null when there are none. */
    private final TouchOverlay overlay;

    /**
     * The UI state the callback has already reconciled with.
     * MUTABLE: render thread only, compared against {@link #uiState} once per
     * frame so a transition is applied exactly once rather than every frame.
     */
    private UiState appliedState = UiState.MENU;

    /**
     * Told whenever the world comes to the front or goes away, or null.
     * MUTABLE: set once by {@link #attachMatchGate} before the loop starts.
     */
    private Consumer<Boolean> matchGate;

    /**
     * Creates a menu-only UI — no renderer, no touch controls.
     *
     * @param menuActions what the buttons do; must not be null
     */
    public AndroidUiFrameCallback(final MenuActions menuActions)
    {
        this(menuActions, null, null);
    }

    /**
     * Creates the full UI.
     *
     * @param menuActions what the buttons do; must not be null
     * @param framePresenter draws the rasterizer's finished frame, or null for
     *     a menu-only build
     * @param touchInput reads the on-screen controls, or null for a build with
     *     no world to control
     */
    public AndroidUiFrameCallback(final MenuActions menuActions,
        final FramebufferPresenter framePresenter, final AndroidInputPort touchInput)
    {
        if (menuActions == null)
        {
            throw new IllegalArgumentException("menuActions must not be null");
        }
        this.menu = new MainMenuFrameCallback(new StartGameTransition(menuActions, uiState));
        this.presenter = framePresenter;
        this.input = touchInput;
        if (touchInput == null)
        {
            this.overlay = null;
        }
        else
        {
            this.overlay = new TouchOverlay(touchInput.layout());
            touchInput.bindUiState(uiState);
        }
    }

    /**
     * Returns the UI state machine this Activity runs on. Never null.
     *
     * @return the machine the menu, the input port and the match gate all read
     */
    public UiStateMachine uiState()
    {
        return uiState;
    }

    /**
     * Returns whether the menu should be drawn and fed events this frame.
     *
     * <p>The single predicate both the draw gate and the input gate read,
     * exposed so a plain-JVM test can assert it with no GL context.</p>
     *
     * @return true only in {@link UiState#MENU}
     */
    public boolean isMenuActive()
    {
        return uiState.state().drawsMenu();
    }

    /**
     * Names something to be told when the world comes to the front or goes away.
     *
     * <p>Setting it fires it immediately with the current state, so a gate
     * attached while the UI is already playing is not left believing the match
     * is frozen for the rest of the run.</p>
     *
     * @param gate told true on entering the world and false on leaving, or null
     */
    public void attachMatchGate(final Consumer<Boolean> gate)
    {
        this.matchGate = gate;
        notifyMatchGate();
    }

    @Override
    public void onSurfaceReady(final int width, final int height)
    {
        Log.i(TAG, "Android UI surface ready: " + width + "x" + height);
        menu.onSurfaceReady(width, height);
        resizeWorld(width, height);
        appliedState = uiState.state();
        applyState(appliedState);
    }

    @Override
    public void onFrame(final float deltaSeconds)
    {
        consumeLeaveRequest();
        syncUiState();
        draw(deltaSeconds);
    }

    @Override
    public void onResize(final int width, final int height)
    {
        menu.onResize(width, height);
        resizeWorld(width, height);
    }

    @Override
    public void onPause()
    {
        menu.onPause();
        if (input != null)
        {
            // Android delivers no touch-up when the app goes to the background,
            // so every finger currently down would still be down on the way
            // back — the player returns to the game already walking and firing.
            input.forgetEverything();
        }
    }

    @Override
    public void onResume()
    {
        menu.onResume();
    }

    @Override
    public void onSurfaceLost()
    {
        if (overlay != null)
        {
            overlay.dispose();
        }
        if (presenter != null)
        {
            presenter.dispose();
        }
        menu.onSurfaceLost();
    }

    // Exactly one of the two states draws, and it draws everything.
    private void draw(final float deltaSeconds)
    {
        if (isMenuActive())
        {
            menu.onFrame(deltaSeconds);
            return;
        }
        if (Gdx.gl == null)
        {
            // No context, so nothing to draw into. The state reconciliation
            // above has already happened, which is the ordering that matters:
            // a frame is first a decision and only then a picture, and the
            // decision must not depend on there being a surface.
            return;
        }
        if (presenter == null || !presenter.present())
        {
            // Playing, but nothing was uploaded: there is no renderer, or the
            // game loop has not published its first frame. Something still has
            // to own the clear or the screen shows the driver's leftovers.
            ScreenUtils.clear(0.0f, 0.0f, 0.0f, 1.0f);
        }
        if (overlay != null)
        {
            overlay.render(input);
        }
    }

    // The world's two sinks for a surface size, kept together so they can never
    // disagree about how big a frame is.
    private void resizeWorld(final int width, final int height)
    {
        if (presenter != null)
        {
            presenter.resize(width, height);
        }
        if (input != null)
        {
            input.resize(width, height);
        }
        if (overlay != null)
        {
            overlay.resize(width, height);
        }
    }

    // Notices that the UI moved and pays for it once.
    private void syncUiState()
    {
        final UiState current = uiState.state();
        if (current == appliedState)
        {
            return;
        }
        appliedState = current;
        applyState(current);
        notifyMatchGate();
    }

    // Everything that follows from which half of the UI is in front.
    private void applyState(final UiState state)
    {
        if (state.drawsMenu())
        {
            menu.attachInputProcessor();
            catchBackKey(false);
            if (input != null)
            {
                // A finger held at the moment of leaving must not still be held
                // when the player comes back.
                input.forgetEverything();
            }
            return;
        }
        menu.detachInputProcessor();
        if (input != null)
        {
            input.forgetEverything();
            if (Gdx.input != null)
            {
                Gdx.input.setInputProcessor(input);
            }
        }
        catchBackKey(true);
    }

    // Asks Android not to act on back itself while a match is up. Only for keys
    // something is actually bound to — swallowing back with nothing listening
    // would leave the app with no way out at all.
    private void catchBackKey(final boolean caught)
    {
        if (Gdx.input == null || input == null)
        {
            return;
        }
        if (input.isLeaveKey(Input.Keys.BACK))
        {
            Gdx.input.setCatchKey(Input.Keys.BACK, caught);
        }
    }

    // Acts on the leave button or the back key, once per press.
    private void consumeLeaveRequest()
    {
        if (input == null || !input.consumeLeaveRequest())
        {
            return;
        }
        if (!uiState.isPlaying())
        {
            return;
        }
        Log.i(TAG, "Leaving the match — back to the menu");
        uiState.returnToMenu();
    }

    // Tells the gate whether the world is in front. Driven from the transition
    // rather than polled, so the simulation side sees one call per change.
    private void notifyMatchGate()
    {
        if (matchGate == null)
        {
            return;
        }
        matchGate.accept(Boolean.valueOf(uiState.isPlaying()));
    }

    /**
     * The caller's menu actions, plus the {@code MENU -> PLAYING} transition
     * that starting a game implies.
     *
     * <p>A decorator for the same reason the desktop one is: the launcher
     * builds the actions, this class builds the state machine, and neither can
     * see the other's object. Wrapping where both are in scope keeps
     * {@code MenuActions} a pure command interface and leaves
     * {@code DefaultMenuActions} — shared with desktop — unaware that a UI state
     * exists at all.</p>
     *
     * <p>The delegate runs first. If it throws, the UI stays in the menu, which
     * is the honest outcome: the game did not start.</p>
     */
    private static final class StartGameTransition implements MenuActions
    {
        /** The actions the launcher supplied. */
        private final MenuActions delegate;

        /** The machine that starting a game advances. */
        private final UiStateMachine machine;

        StartGameTransition(final MenuActions target, final UiStateMachine uiStateMachine)
        {
            this.delegate = target;
            this.machine = uiStateMachine;
        }

        @Override
        public void onStartGame()
        {
            delegate.onStartGame();
            machine.startGame(MatchMode.SINGLE_PLAYER);
        }

        @Override
        public void onMultiplayer()
        {
            delegate.onMultiplayer();
            machine.startGame(MatchMode.MULTIPLAYER);
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
