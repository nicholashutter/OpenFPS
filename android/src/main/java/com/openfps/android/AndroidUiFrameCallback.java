/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import java.util.function.Consumer;
import java.util.function.Supplier;

import android.util.Log;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.utils.ScreenUtils;

import com.openfps.engine.gameplay.MatchMode;
import com.openfps.engine.gameplay.MatchStatus;
import com.openfps.engine.gameplay.MatchSummary;
import com.openfps.engine.hal.port.I_FrameCallback;
import com.openfps.gdx.AccessibilitySettings;
import com.openfps.gdx.DebugOverlay;
import com.openfps.gdx.DebugSettings;
import com.openfps.gdx.FramebufferPresenter;
import com.openfps.gdx.GameOverScreen;
import com.openfps.gdx.MenuActions;
import com.openfps.gdx.RenderSettings;
import com.openfps.gdx.ScoreOverlay;
import com.openfps.gdx.SettingsScreen;
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
 * <p>The catch is asserted in every state that has somewhere to go back <i>to</i>
 * — see {@link UiState#backReturnsToMenu} — so back still leaves the app from the
 * menu, which is what an Android user expects there, and leaves the screen
 * everywhere else.</p>
 *
 * <p><b>Catching the key is only half of it, and the settings screen was missing
 * both halves.</b> The catch stops <i>Android</i> acting; something still has to
 * act. On the settings and end-of-match screens the input processor is a Scene2D
 * stage, which has no listener for {@code Keys.BACK} and drops it, so
 * {@link #keepBackKey} leaves {@link AndroidInputPort} in front of those stages in a
 * multiplexer and {@link #consumeLeaveRequest} picks the request up as usual. Before
 * that, back on the settings screen quit the game outright — and on the end screen
 * it took the match result with it.</p>
 *
 * <b>Threading.</b> Every method runs on the GLSurfaceView render thread.
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class AndroidUiFrameCallback implements I_FrameCallback
{
    /** Logcat tag. Android has no SLF4J binding, so platform code logs here. */
    private static final String TAG = "OpenFPS";

    /** Nanoseconds in a second, for turning libGDX's frame delta into a sample. */
    private static final float NANOS_PER_SECOND = 1_000_000_000.0f;

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

    /** The switch the settings screen flips and the frame counter reads. Never null. */
    private final DebugSettings debug;

    /**
     * The visual aids the settings screen flips. Never null.
     *
     * <p>Nothing in this class reads it — the renderer's outline pass answers to
     * it, through an observer the launcher hung on it. It is held here so the
     * settings screen flips <b>the launcher's object</b>, which is the only way
     * the toggle and the outline can be the same switch.</p>
     */
    private final AccessibilitySettings accessibility;

    /**
     * The corner frame counter. Never null; it draws only while
     * {@link DebugSettings#isOverlayVisible()} and builds no GL resource until
     * the first time it does.
     */
    private final DebugOverlay fpsCounter = new DebugOverlay();

    /**
     * The settings UI.
     * MUTABLE: built in {@link #onSurfaceReady}, released in
     * {@link #onSurfaceLost}. It cannot be built earlier — there is no GL
     * context until the surface exists.
     */
    private SettingsScreen settings;

    /**
     * The end-of-match UI, or null while no match has finished.
     * MUTABLE: built when the round is decided and released when the player
     * leaves it. See {@link GameOverScreen} on why it is per result.
     */
    private GameOverScreen gameOver;

    /**
     * Asked once a frame whether the round has been decided, or null.
     * MUTABLE: set once by {@link #attachMatchResult} before the loop starts.
     */
    private Supplier<MatchSummary> matchResult;

    /**
     * Whether the end screen has been shown for the round currently in the
     * gameplay port.
     *
     * <p>MUTABLE: set on the transition into {@link UiState#GAME_OVER} and
     * cleared by {@link #restartMatch} — and only there, in the same call that
     * resets the simulation, for the reason the desktop listener records at
     * greater length. It used to be latched for the life of the process, because
     * the demo built one {@code Match} and nothing could restore it.</p>
     */
    private boolean resultShown;

    /**
     * Restores the world for another round, or null when nothing can be.
     * MUTABLE: set once by {@link #attachMatchRestart} before the loop starts.
     */
    private Runnable matchRestart;

    /**
     * Asked once a frame for the live score, or null.
     * MUTABLE: set once by {@link #attachMatchStatus} before the loop starts.
     */
    private Supplier<MatchStatus> matchStatus;

    /** The simulation rate, for the respawn countdown. MUTABLE: set with the supplier. */
    private int ticsPerSecond;

    /**
     * The in-game score and the death notice. Never null; it builds no GL
     * resource until a match first gives it something to draw.
     */
    private final ScoreOverlay score = new ScoreOverlay();

    /** The surface size, for placing the frame counter. MUTABLE: set on resize. */
    private int surfaceWidth;

    /** The surface height. MUTABLE: set on resize. */
    private int surfaceHeight;

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
     * Run once as soon as there is a surface, or null.
     * MUTABLE: set once by {@link #attachAudioWarmup} before the loop starts.
     */
    private Runnable audioWarmup;

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
        this(menuActions, framePresenter, touchInput, new DebugSettings());
    }

    /**
     * Creates the full UI with a caller-supplied debug switch.
     *
     * @param menuActions what the buttons do; must not be null
     * @param framePresenter draws the rasterizer's finished frame, or null for
     *     a menu-only build
     * @param touchInput reads the on-screen controls, or null for a build with
     *     no world to control
     * @param debugSettings the switch the settings screen flips; must not be
     *     null
     */
    public AndroidUiFrameCallback(final MenuActions menuActions,
        final FramebufferPresenter framePresenter, final AndroidInputPort touchInput,
        final DebugSettings debugSettings)
    {
        this(menuActions, framePresenter, touchInput, debugSettings,
            new AccessibilitySettings());
    }

    /**
     * Creates the full UI with caller-supplied debug and accessibility switches.
     *
     * <p>The launcher supplies both when it wants to observe them — which it does
     * for the accessibility group, to put the renderer's outline pass behind the
     * target outline toggle. A UI built without them gets private ones nothing
     * else can see, which is what every plain-JVM test wants.</p>
     *
     * <p><b>The accessibility switch in particular has to be the launcher's own
     * object.</b> A private copy relabels its button exactly as convincingly and
     * the outline simply stops answering — no exception, no log line, nothing to
     * notice until a player presses the toggle and the game ignores them. That is
     * the same failure mode the debug switch had, and it is why
     * {@code AndroidUiFrameCallbackTest} asserts identity rather than
     * behaviour.</p>
     *
     * @param menuActions what the buttons do; must not be null
     * @param framePresenter draws the rasterizer's finished frame, or null for
     *     a menu-only build
     * @param touchInput reads the on-screen controls, or null for a build with
     *     no world to control
     * @param debugSettings the switch the settings screen flips; must not be
     *     null
     * @param accessibilitySettings the visual aids the settings screen flips;
     *     must not be null
     */
    public AndroidUiFrameCallback(final MenuActions menuActions,
        final FramebufferPresenter framePresenter, final AndroidInputPort touchInput,
        final DebugSettings debugSettings,
        final AccessibilitySettings accessibilitySettings)
    {
        if (menuActions == null)
        {
            throw new IllegalArgumentException("menuActions must not be null");
        }

        if (debugSettings == null)
        {
            throw new IllegalArgumentException("debugSettings must not be null");
        }

        if (accessibilitySettings == null)
        {
            throw new IllegalArgumentException("accessibilitySettings must not be null");
        }

        this.debug = debugSettings;

        this.accessibility = accessibilitySettings;

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

    /**
     * Names something to ask, once a frame, whether the round has been decided.
     *
     * <p>A poll rather than a callback, for the reason the desktop listener
     * records: the match is decided on the game loop thread, and a callback from
     * there would have the simulation building a Scene2D stage on a thread with
     * no GL context.</p>
     *
     * @param result asked once a frame; returns null while the match is running
     *     and a frozen summary once it is not, or null to disable the end screen
     */
    public void attachMatchResult(final Supplier<MatchSummary> result)
    {
        this.matchResult = result;
    }

    /**
     * Names the thing that restores the world for another round.
     *
     * <p>The Android half of the rematch, and identical in shape and in obligation
     * to the desktop one: it runs on the GL thread from a Scene2D click and
     * mutates state the game loop thread owns, which is safe only because
     * {@code DemoGameplayPort.restartMatch} takes the tic lock.</p>
     *
     * @param restart run before the {@code GAME_OVER -> PLAYING} transition, or
     *     null for a build with no match — which leaves the end screen with only
     *     its Back button, the honest presentation when nothing can be restarted
     */
    public void attachMatchRestart(final Runnable restart)
    {
        this.matchRestart = restart;
    }

    /**
     * Names something to ask, once a frame, how the round is going.
     *
     * @param status asked once a frame; returns null for a build with no match
     * @param simulationTicsPerSecond the configured tic rate, which turns the
     *     respawn countdown into seconds
     */
    public void attachMatchStatus(final Supplier<MatchStatus> status,
        final int simulationTicsPerSecond)
    {
        this.matchStatus = status;

        this.ticsPerSecond = simulationTicsPerSecond;
    }

    /**
     * Names something to run once, as soon as there is a surface to run against.
     *
     * <p><b>This exists because entering a match is too late to warm the sounds
     * up.</b> The warm-up used to hang off the match gate, on the reasoning that
     * the surface is up by then and "the player is still finding their thumbs".
     * The emulator disproved the second half: the gate is also what unfreezes the
     * bots, so they open fire on the same edge, and the first bot shot arrived
     * with {@code W/SoundPool: play soundID 2 not READY} — {@code SoundPool.load}
     * is asynchronous and had not finished. The lead time the old seam assumed was
     * zero.</p>
     *
     * <p>This edge is the earliest one that still satisfies the real constraint.
     * {@code Gdx.audio} does not exist until the frame loop is running, so the
     * Activity's {@code onCreate} cannot do it; {@link #onSurfaceReady} runs on the
     * render thread once the surface exists, which is while the <b>menu</b> is
     * still on screen. That buys the whole time a player spends reading it.</p>
     *
     * @param warmup run once from {@link #onSurfaceReady}, or null for a build
     *     with nothing to warm up. Expected to be idempotent — the match gate
     *     still calls it too, as a cheap backstop for a run that somehow reached a
     *     match without a surface-ready
     */
    public void attachAudioWarmup(final Runnable warmup)
    {
        this.audioWarmup = warmup;
    }

    /** Returns the debug switch this UI's settings screen flips. Never null. */
    public DebugSettings debugSettings()
    {
        return debug;
    }

    /** Returns the visual aids this UI's settings screen flips. Never null. */
    public AccessibilitySettings accessibilitySettings()
    {
        return accessibility;
    }

    @Override
    public void onSurfaceReady(final int width, final int height)
    {
        Log.i(TAG, "Android UI surface ready: " + width + "x" + height);

        menu.onSurfaceReady(width, height);

        // Laid out at the screen's density, so a button stays the same physical
        // size on a 160 dpi tablet and a 560 dpi handset — the same rule
        // MainMenuFrameCallback applies, and the reason these screens take a
        // scale at all.
        settings = new SettingsScreen(accessibility, debug, renderSettings(),
            uiState::returnToMenu, density());

        settings.resize(width, height);

        resizeWorld(width, height);

        appliedState = uiState.state();

        applyState(appliedState);

        // Last, and while the menu is still what is on screen. See
        // attachAudioWarmup for why this is not left to the match gate.
        if (audioWarmup != null)
        {
            audioWarmup.run();
        }
    }

    @Override
    public void onFrame(final float deltaSeconds)
    {
        consumeLeaveRequest();

        // Before the state sync, so a round decided on this frame is shown on
        // this frame rather than the next one.
        checkMatchResult();

        syncUiState();

        draw(deltaSeconds);
    }

    @Override
    public void onResize(final int width, final int height)
    {
        menu.onResize(width, height);

        if (settings != null)
        {
            settings.resize(width, height);
        }

        if (gameOver != null)
        {
            gameOver.resize(width, height);
        }

        resizeWorld(width, height);
    }

    // The screen's density, or 1 when there is no graphics context to ask —
    // which is every plain-JVM test.
    private static float density()
    {
        if (Gdx.graphics == null || !(Gdx.graphics.getDensity() > 0.0f))
        {
            return 1.0f;
        }

        return Gdx.graphics.getDensity();
    }

    // Notices that the round has been decided, once, and moves onto the end
    // screen. See attachMatchResult for why this is a poll.
    private void checkMatchResult()
    {
        if (matchResult == null || resultShown || !uiState.isPlaying())
        {
            return;
        }

        final MatchSummary summary = matchResult.get();

        if (summary == null)
        {
            return;
        }

        resultShown = true;

        gameOver = new GameOverScreen(summary, this::restartMatch, uiState::returnToMenu,
            density());

        gameOver.resize(surfaceWidth, surfaceHeight);

        Log.i(TAG, "Match decided: " + summary);

        uiState.endMatch(summary);
    }

    // Play Again: restore the world, THEN move the UI into it. The order is not
    // interchangeable — see the desktop listener's restartMatch, which carries the
    // argument. A UI that entered PLAYING first would show one or more frames of
    // the room the player had just cleared.
    private void restartMatch()
    {
        if (matchRestart == null)
        {
            return;
        }

        matchRestart.run();

        resultShown = false;

        uiState.restartMatch();
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

        fpsCounter.dispose();

        score.dispose();

        if (settings != null)
        {
            settings.detachInputProcessor();

            settings.dispose();

            settings = null;
        }

        if (gameOver != null)
        {
            gameOver.detachInputProcessor();

            gameOver.dispose();

            gameOver = null;
        }

        if (presenter != null)
        {
            presenter.dispose();
        }

        menu.onSurfaceLost();
    }

    // Exactly one screen draws, and it draws the whole surface.
    private void draw(final float deltaSeconds)
    {
        final UiState current = uiState.state();

        if (current.drawsMenu())
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

        if (settings != null && current.drawsSettings())
        {
            settings.render(deltaSeconds);

            return;
        }

        if (gameOver != null && current.drawsGameOver())
        {
            gameOver.render(deltaSeconds);

            return;
        }

        drawGame(deltaSeconds);
    }

    // The world, the touch controls, and the frame counter if it was asked for.
    private void drawGame(final float deltaSeconds)
    {
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

        // Over the touch controls but under the frame counter, and unconditional —
        // there is no setting behind it. A player has to be able to see that they
        // died without having found a debug menu first, which is what this is for.
        drawScore();

        sampleFpsCounter(deltaSeconds);

        if (debug.isOverlayVisible())
        {
            // Last, so it sits over the world and the touch controls rather
            // than under them. Drawn at the SURFACE size while the world behind
            // it was rasterized at the render size — which is the visible payoff
            // of scaling the world alone, and is why the counter reports both.
            fpsCounter.render(surfaceWidth, surfaceHeight, renderWidth(), renderHeight());
        }
    }

    // The kill and death count, and the notice a dead player needs to see.
    //
    // Drawn at the SURFACE size while the world behind it was rasterized at the
    // render size, exactly as the frame counter is: the score is UI, so it stays
    // sharp on a phone that scales the world down.
    private void drawScore()
    {
        if (matchStatus == null)
        {
            return;
        }

        score.render(matchStatus.get(), surfaceWidth, surfaceHeight, ticsPerSecond);
    }

    // The framebuffer the rasterizer is filling, or 0 when there is no presenter
    // to have scaled anything — which the overlay reads as "the same as the
    // surface", the only honest answer with no renderer in the run.
    private int renderWidth()
    {
        if (presenter == null)
        {
            return 0;
        }

        return presenter.renderWidth();
    }

    // The same, for the other edge.
    private int renderHeight()
    {
        if (presenter == null)
        {
            return 0;
        }

        return presenter.renderHeight();
    }

    // The presenter's render switch, or a detached one when this run has no
    // presenter. Never null, because the settings screen must be able to show a
    // mode whether or not anything can act on it.
    private RenderSettings renderSettings()
    {
        if (presenter == null)
        {
            return new RenderSettings();
        }

        return presenter.renderSettings();
    }

    // Feeds the counter whether or not it is drawn, so switching it on shows a
    // settled reading rather than one climbing out of nothing.
    private void sampleFpsCounter(final float deltaSeconds)
    {
        long rendererNanos = 0L;

        if (presenter != null)
        {
            rendererNanos = presenter.lastRenderNanos();
        }

        fpsCounter.sample((long) (deltaSeconds * NANOS_PER_SECOND), rendererNanos);
    }

    // The world's sinks for a surface size, kept together so they can never
    // disagree about how big a frame is.
    private void resizeWorld(final int width, final int height)
    {
        this.surfaceWidth = width;

        this.surfaceHeight = height;

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

    // Everything that follows from which screen is in front.
    //
    // Branching on usesPointer() rather than on drawsMenu(), which is the change
    // the settings and end-of-match states forced. The old test was "is this the
    // menu, else it is the game" — with four states that reads SETTINGS as the
    // game, hands the touch controls the input processor and swallows the back
    // key, leaving a settings screen whose buttons do nothing and no way off it.
    private void applyState(final UiState state)
    {
        // Caught in every state that has somewhere to go back TO, which is all of
        // them but the menu — see UiState.backReturnsToMenu. It used to be caught
        // only while playing, which left back on the settings and end screens
        // falling through to Android and finishing the Activity.
        catchBackKey(state.backReturnsToMenu());

        if (state.usesPointer())
        {
            attachPointerScreen(state);

            if (input != null)
            {
                // A finger held at the moment of leaving must not still be held
                // when the player comes back.
                input.forgetEverything();
            }

            releaseFinishedResult(state);

            return;
        }

        menu.detachInputProcessor();

        if (settings != null)
        {
            settings.detachInputProcessor();
        }

        if (input != null)
        {
            input.forgetEverything();

            if (Gdx.input != null)
            {
                Gdx.input.setInputProcessor(input);
            }
        }
    }

    // Gives the processor to whichever of the three button-bearing screens is
    // in front. At most one is ever attached — Gdx.input holds exactly one
    // processor and each attach replaces the last.
    private void attachPointerScreen(final UiState state)
    {
        if (state.drawsSettings() && settings != null)
        {
            settings.attachInputProcessor();
        }
        else if (state.drawsGameOver() && gameOver != null)
        {
            gameOver.attachInputProcessor();
        }
        else
        {
            menu.attachInputProcessor();
        }

        keepBackKey(state);
    }

    // Puts the input port BEHIND the screen's stage, for the back key alone.
    //
    // A MULTIPLEXER AS WELL AS setCatchKey, because the two do different halves and
    // the settings screen was missing both. catchBackKey is what stops ANDROID
    // acting on the press — libGDX's view-level handler reports the key as consumed
    // only when it is in the caught set, and without that the Activity is finished
    // out from under the app. This method is what gets the press to something that
    // ACTS on it: in PLAYING the processor is AndroidInputPort, whose keyDown banks a
    // leave request, but on the settings and end screens it is a Scene2D stage, which
    // has no listener for Keys.BACK and drops it. So the port stays in the chain in
    // front of the stage, and consumeLeaveRequest picks the request up as usual.
    //
    // Both halves were verified on the emulator by logging the catch state either
    // side of the press, because each one alone looks like it works: with only the
    // catch, the app stays put and the screen never changes; with only the
    // multiplexer, the UI returns to the menu and the app goes to the home screen in
    // the same press.
    //
    // THE ORDER IS THE WHOLE THING, and putting the port first breaks every button
    // on both screens. AndroidInputPort.touchUp returns true UNCONDITIONALLY — it
    // deliberately does not ask isPlayable, because a release must always be
    // honoured or a finger held across a UI transition is left stuck down. In front
    // of the stage that is fatal: touchDown falls through and starts a Scene2D
    // click, touchUp is swallowed, the ClickListener never completes, and every
    // button is drawn perfectly and does nothing. The emulator showed exactly that —
    // RENDER, DEBUG OVERLAY and BACK all inert while the screen looked correct.
    //
    // Behind the stage, the stage takes what it wants and only what it drops reaches
    // the port. A Scene2D stage has no listener for Keys.BACK, so the key falls
    // through and nothing else does.
    private void keepBackKey(final UiState state)
    {
        if (input == null || Gdx.input == null || !state.backReturnsToMenu())
        {
            return;
        }

        final InputProcessor screen = Gdx.input.getInputProcessor();

        if (screen == null)
        {
            return;
        }

        Gdx.input.setInputProcessor(new InputMultiplexer(screen, input));
    }

    // Drops the end screen once the player has left it. It holds a texture, a
    // font and a stage, and resultShown is never cleared, so it can never be
    // shown again — keeping it alive would hold three GPU resources for a
    // screen with no way back to it.
    private void releaseFinishedResult(final UiState state)
    {
        if (gameOver == null || state.drawsGameOver())
        {
            return;
        }

        gameOver.dispose();

        gameOver = null;
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
    //
    // Every state that has somewhere to go back TO is honoured, not just PLAYING.
    // The old early return read "only a match can be left", which was true of the
    // on-screen LEAVE button — it is only drawn over the world — but not of the back
    // key, which is a hardware control the player can press on any screen. Its
    // effect was that back on the settings or end screen banked a leave request that
    // nothing ever acted on, while Android finished the Activity underneath.
    private void consumeLeaveRequest()
    {
        if (input == null || !input.consumeLeaveRequest())
        {
            return;
        }

        final UiState current = uiState.state();

        if (!current.backReturnsToMenu())
        {
            return;
        }

        Log.i(TAG, "Leaving " + current + " — back to the menu");

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

            machine.openSettings();
        }

        @Override
        public void onMapSelection()
        {
            delegate.onMapSelection();

            machine.openMapSelect();
        }

        @Override
        public void onQuit()
        {
            delegate.onQuit();
        }
    }
}
