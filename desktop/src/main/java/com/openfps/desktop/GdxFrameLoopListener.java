/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openfps.engine.gameplay.MatchMode;
import com.openfps.engine.gameplay.MatchStatus;
import com.openfps.engine.gameplay.MatchSummary;
import com.openfps.engine.hal.port.I_FrameCallback;
import com.openfps.gdx.AccessibilitySettings;
import com.openfps.gdx.DebugOverlay;
import com.openfps.gdx.DebugSettings;
import com.openfps.gdx.DefaultMenuActions;
import com.openfps.gdx.FramebufferPresenter;
import com.openfps.gdx.GameOverScreen;
import com.openfps.gdx.LoadingScreen;
import com.openfps.gdx.LobbyScreen;
import com.openfps.gdx.MainMenuScreen;
import com.openfps.gdx.MapSelection;
import com.openfps.gdx.MapSelectionScreen;
import com.openfps.gdx.MenuActions;
import com.openfps.gdx.ModeSelectionScreen;
import com.openfps.gdx.RenderSettings;
import com.openfps.gdx.ScoreOverlay;
import com.openfps.gdx.SettingsScreen;
import com.openfps.gdx.UiState;
import com.openfps.gdx.UiStateMachine;

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
 * Transitions are driven from four places and only four: the menu's Start Game
 * and Settings buttons, wrapped here by {@link StartGameTransition}; the Escape
 * key, seen by {@code GdxInputPort} while it polls; the Back buttons on the
 * settings and end-of-match screens; and {@link #checkMatchResult()}, which is
 * this class noticing on the render thread that the round has been decided on
 * the game loop thread.
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

    /** Nanoseconds in a second, for turning libGDX's frame delta into a sample. */
    private static final float NANOS_PER_SECOND = 1_000_000_000.0f;

    /**
     * The frame loop's own logger. Logs flow through the slf4j bridge
     * into the engine's main log bus, so a player with a debug overlay
     * subscribed to the bus sees the map-loading flow alongside the
     * engine's own logs.
     */
    private static final Logger LOG = LoggerFactory.getLogger(GdxFrameLoopListener.class);

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

    /** The debug switch the settings screen flips and the overlay reads. Never null. */
    private final DebugSettings debug;

    /**
     * The visual aids the settings screen flips. Never null.
     *
     * <p>Nothing in this class reads it — the renderer's outline pass is what
     * answers to it, through an observer the launcher hung on it. It is held here
     * so that the settings screen flips <b>the launcher's object</b>, which is
     * the only way the toggle and the outline can be the same switch.</p>
     */
    private final AccessibilitySettings accessibility;

    /**
     * The corner frame counter. Never null; it draws only while
     * {@link DebugSettings#isOverlayVisible()} and costs no GL resource until
     * the first time it does.
     */
    private final DebugOverlay overlay = new DebugOverlay();

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
     * The settings UI.
     * MUTABLE: built in {@link #create()}, released in {@link #dispose()}, for
     * the same reason the menu is — there is no GL context until create().
     */
    private SettingsScreen settings;

    /**
     * The mode picker UI. The first sub-screen after the menu when the
     * player picks single- or multiplayer and wants to be asked which
     * rule set next.
     * MUTABLE: built in {@link #create()}, released in {@link #dispose()}.
     */
    private ModeSelectionScreen modeSelect;

    /**
     * The map browser UI. The four-thumbnail grid for the rule set the
     * player just chose.
     * MUTABLE: built in {@link #create()}, released in {@link #dispose()}.
     */
    private MapSelectionScreen mapSelect;

    /**
     * The loading screen. Shown between map pick and game start so the
     * engine has time to swap the scene.
     * MUTABLE: built lazily, released when the player leaves the state.
     */
    private LoadingScreen loading;

    /**
     * The multiplayer lobby. Shown between map pick and loading on the
     * multiplayer branch.
     * MUTABLE: built lazily, released when the player leaves the state.
     */
    private LobbyScreen lobby;

    /**
     * The shared map selection the picker writes to. Held by the launcher
     * and passed through the window port; the screen reads it for the
     * current selection and writes it on click.
     */
    private final MapSelection mapSelection;

    /**
     * The rows the picker shows, in display order. The launcher supplies
     * this from {@code MapLibrary.ids()}; never null while the picker is
     * wired, and never empty.
     */
    private final List<MapSelectionScreen.Entry> mapEntries;

    /**
     * The end-of-match UI, or null while no match has finished.
     *
     * <p>MUTABLE: built when the round is decided and released when the player
     * leaves it. Built per result rather than once, because the heading word and
     * every figure on it are fixed at construction — see
     * {@link GameOverScreen}.</p>
     */
    private GameOverScreen gameOver;

    /**
     * Asked once per frame whether the round has been decided, or null.
     *
     * <p>MUTABLE: set once by {@link #attachMatchResult} before the loop starts.
     * Returns null while the match is still running, which is the normal answer
     * on all but one frame of a session.</p>
     */
    private Supplier<MatchSummary> matchResult;

    /**
     * Whether the end screen has been shown for the round currently in the
     * gameplay port.
     *
     * <p>MUTABLE, and <b>it is now cleared rather than latched for the life of
     * the process</b> — that change is the whole of the rematch on this side of
     * the seam. It used to be set once and never reset, because the demo built
     * exactly one {@code Match} and nothing could restore it: a finished round
     * stayed finished, and without the latch a player who read their summary,
     * went back to the menu and pressed Single Player would be thrown straight
     * onto the same screen with no way to reach the world at all.</p>
     *
     * <p>{@link #restartMatch} now genuinely restores the round, so the guard is
     * cleared there — and only there, in the same call that resets the
     * simulation. Clearing it anywhere else would let the end screen fire again
     * for a round that had not been restarted, which is the trap the latch
     * existed to prevent.</p>
     */
    private boolean resultShown;

    /**
     * Restores the world for another round, or null when nothing can be.
     *
     * <p>MUTABLE: set once by {@link #attachMatchRestart} before the loop starts.
     * Null is the {@code --model=} case and every windowless test, and it is what
     * makes Play Again <b>refuse to appear</b> rather than appear and lie: see
     * {@link #canRestart()}.</p>
     */
    private Runnable matchRestart;

    /**
     * Asked once a frame for the live score, or null.
     *
     * <p>MUTABLE: set once by {@link #attachMatchStatus} before the loop starts.
     * Null is a window with no match, and {@link ScoreOverlay} draws nothing at
     * all for it.</p>
     */
    private Supplier<MatchStatus> matchStatus;

    /**
     * The simulation rate, for turning the respawn countdown into seconds.
     *
     * <p>MUTABLE: set with the status supplier. Zero until then, which
     * {@link MatchStatus#respawnSecondsRemaining} answers with a zero rather than
     * a division by it.</p>
     */
    private int ticsPerSecond;

    /**
     * The in-game score and the death notice. Never null; it builds no GL
     * resource until a match first gives it something to draw.
     */
    private final ScoreOverlay score = new ScoreOverlay();

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
     * Runs when the player picks a map from the menu, or null when the
     * menu-driven map load is not wired.
     *
     * <p>MUTABLE: set by the launcher via
     * {@link #setLoadMapCallback(java.util.function.Consumer)} before
     * the loop starts. The default &mdash; a no-op &mdash; is what
     * windowless tests get, and what the menu flow falls back to when
     * no launcher is around. The callback receives the picked map id;
     * the launcher uses it to build a {@code MapRuntime.loadMap} call
     * and re-attach the match hooks before the loading screen hands
     * control to {@code uiState.startGame}.</p>
     */
    private Consumer<String> loadMapCallback;

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
        this(callback, actions, framePresenter, desktopInput, new DebugSettings());
    }

    /**
     * Creates the bridge with a caller-supplied debug switch.
     *
     * <p>The launcher supplies one when it wants to observe the switch — which
     * it does, to put the renderer's outline pass behind the same toggle. A
     * window built without one gets a private switch that nothing else can see,
     * which is what every windowless test wants.</p>
     *
     * @param callback the engine callback to forward lifecycle to; must not
     *     be null
     * @param actions what the menu buttons do; must not be null
     * @param framePresenter draws the rasterizer's finished frame, or null for
     *     a menu-only window
     * @param desktopInput polled once per frame for mouse and keyboard state,
     *     or null for a window that reads no input
     * @param debugSettings the switch the settings screen flips; must not be
     *     null
     */
    public GdxFrameLoopListener(final I_FrameCallback callback, final MenuActions actions,
        final FramebufferPresenter framePresenter, final GdxInputPort desktopInput,
        final DebugSettings debugSettings)
    {
        this(callback, actions, framePresenter, desktopInput, debugSettings,
            new AccessibilitySettings());
    }

    /**
     * Creates the bridge with caller-supplied debug and accessibility switches.
     *
     * <p>The launcher supplies both when it wants to observe them — which it
     * does for the accessibility group, to put the renderer's outline pass behind
     * the target outline toggle. A window built without them gets private ones
     * that nothing else can see, which is what every windowless test wants.</p>
     *
     * <p><b>The accessibility switch has to be the launcher's own object and not
     * a copy of it.</b> A private one here would relabel its button perfectly
     * well and the outline would simply stop answering — nothing would throw and
     * nothing would look wrong until a player pressed the toggle and the game
     * ignored them. {@code GdxFrameLoopListenerTest} asserts the identity for
     * exactly that reason.</p>
     *
     * @param callback the engine callback to forward lifecycle to; must not
     *     be null
     * @param actions what the menu buttons do; must not be null
     * @param framePresenter draws the rasterizer's finished frame, or null for
     *     a menu-only window
     * @param desktopInput polled once per frame for mouse and keyboard state,
     *     or null for a window that reads no input
     * @param debugSettings the switch the settings screen flips; must not be
     *     null
     * @param accessibilitySettings the visual aids the settings screen flips;
     *     must not be null
     */
    public GdxFrameLoopListener(final I_FrameCallback callback, final MenuActions actions,
        final FramebufferPresenter framePresenter, final GdxInputPort desktopInput,
        final DebugSettings debugSettings,
        final AccessibilitySettings accessibilitySettings)
    {
        this(callback, actions, framePresenter, desktopInput, debugSettings, accessibilitySettings,
            new MapSelection(), java.util.Collections.emptyList());
    }

    /**
     * Creates the bridge with caller-supplied debug, accessibility, and
     * map-selection state.
     *
     * <p>The launcher supplies all three when it wants to observe them —
     * which it does for the accessibility group and the map selection, so
     * the renderer's outline pass follows the same toggle and the launcher
     * can read the picked map back when the engine starts. A window built
     * without them gets a private map selection the menu cannot change,
     * which is what every windowless test wants.</p>
     *
     * <p>{@code mapEntries} is the list the picker shows. It is not null and
     * not empty when the launcher supplies it; the picker is built only
     * when both fields are present. A window without a picker is fine —
     * a no-op {@link UiState#MAP_SELECT} transition would still resolve
     * to the menu, because the state machine refuses nothing the transition
     * table does not list, and the table lists {@code MAP_SELECT -> MENU}.</p>
     *
     * @param callback the engine callback to forward lifecycle to; must not
     *     be null
     * @param actions what the menu buttons do; must not be null
     * @param framePresenter draws the rasterizer's finished frame, or null
     *     for a menu-only window
     * @param desktopInput polled once per frame, or null for a window that
     *     reads no input
     * @param debugSettings the switch the settings screen flips; must not be
     *     null
     * @param accessibilitySettings the visual aids the settings screen flips;
     *     must not be null
     * @param mapSelection the shared map selection; must not be null
     * @param mapEntries the rows the picker shows; must not be null; can be
     *     empty, in which case the picker is not built
     */
    public GdxFrameLoopListener(final I_FrameCallback callback, final MenuActions actions,
        final FramebufferPresenter framePresenter, final GdxInputPort desktopInput,
        final DebugSettings debugSettings,
        final AccessibilitySettings accessibilitySettings,
        final MapSelection mapSelection,
        final List<MapSelectionScreen.Entry> mapEntries)
    {
        if (callback == null)
        {
            throw new IllegalArgumentException("callback must not be null");
        }

        if (actions == null)
        {
            throw new IllegalArgumentException("actions must not be null");
        }

        if (debugSettings == null)
        {
            throw new IllegalArgumentException("debugSettings must not be null");
        }

        if (accessibilitySettings == null)
        {
            throw new IllegalArgumentException("accessibilitySettings must not be null");
        }

        if (mapSelection == null)
        {
            throw new IllegalArgumentException("mapSelection must not be null");
        }

        if (mapEntries == null)
        {
            throw new IllegalArgumentException("mapEntries must not be null");
        }

        this.debug = debugSettings;

        this.accessibility = accessibilitySettings;

        this.mapSelection = mapSelection;

        this.mapEntries = List.copyOf(mapEntries);

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
     * Names something to ask, once a frame, whether the round has been decided.
     *
     * <p>A poll rather than a callback, and for a sharper version of the reason
     * {@link UiStateMachine} documents: the match is decided on the <b>game loop
     * thread</b>, inside {@code Match.tick}. A callback from there would have
     * the simulation constructing a Scene2D stage and uploading a texture, on a
     * thread with no GL context, part-way through a tic. Asking on the render
     * thread costs a field read per frame and puts every consequence where it
     * belongs.</p>
     *
     * <p>The supplier reads simulation state from the render thread, which is a
     * race in form only: {@code Match.tick} returns early once the round is
     * over and the trigger is gated on the same check, so the figures stop
     * moving before this can first observe them non-null. The worst outcome is
     * that a stale read delays the end screen by one frame.</p>
     *
     * @param result asked once a frame; returns null while the match is running
     *     and a frozen summary once it is not, or null to disable the end
     *     screen entirely — which is what a window with no match gets
     */
    public void attachMatchResult(final Supplier<MatchSummary> result)
    {
        this.matchResult = result;
    }

    /**
     * Names the thing that restores the world for another round.
     *
     * <p>The counterpart to {@link #attachMatchResult}: one direction learns that
     * a round has finished, this one starts a new one. It is a bare
     * {@link Runnable} rather than a gameplay reference for the reason
     * {@link #attachMatchGate} takes a {@code Consumer<Boolean>} — this class is
     * the desktop UI and must not import the simulation it is a front end
     * for.</p>
     *
     * <p><b>It runs on the render thread, from a Scene2D click handler, and it
     * mutates simulation state the game loop thread owns.</b> That is only safe
     * because {@code DemoGameplayPort.restartMatch} takes the tic lock, which is
     * the same lock a tic is atomic under. Anything attached here has the same
     * obligation.</p>
     *
     * <p>Null leaves the end screen with a Back button and no Play Again, which is
     * the honest presentation for a window that cannot restart anything — a
     * button that appeared and did nothing would be the "button that lies"
     * {@code UiState} refused a rematch edge over for so long.</p>
     *
     * @param restart run before the {@code GAME_OVER -> PLAYING} transition, or
     *     null for a window with no match
     */
    public void attachMatchRestart(final Runnable restart)
    {
        this.matchRestart = restart;
    }

    /**
     * Names something to ask, once a frame, how the round is going.
     *
     * <p>A poll for the same reason {@link #attachMatchResult} is one, and the
     * argument is sharper here because this is asked on <i>every</i> frame rather
     * than on one: the figures live on the game loop thread, and
     * {@link MatchStatus} is the immutable copy that crosses over. See that
     * class.</p>
     *
     * @param status asked once a frame; returns null for a window with no match,
     *     or null itself to draw no score at all
     * @param simulationTicsPerSecond the configured tic rate, which is what turns
     *     the respawn countdown into seconds; must be positive to show a count
     */
    public void attachMatchStatus(final Supplier<MatchStatus> status,
        final int simulationTicsPerSecond)
    {
        this.matchStatus = status;

        this.ticsPerSecond = simulationTicsPerSecond;
    }

    /**
     * Names the consumer the picker fires when the player picks a map.
     *
     * <p>The launcher supplies one so the menu pick can build a
     * {@code MapRuntime.loadMap} call and re-attach the match hooks
     * (gate, result, status, restart) against the freshly loaded
     * port, before the loading screen hands control to
     * {@code uiState.startGame}. A null callback (the default) is
     * what windowless tests get, and what a launcher that does not
     * drive the map load wires up.</p>
     *
     * <p><b>Setting it does not fire on existing picks.</b> The picker
     * is built in {@link #create()} and reads the same callback for
     * the life of the window, exactly the same shape as
     * {@link #attachMatchGate}.</p>
     *
     * @param callback told the picked map id on every pick from the
     *     browser, or null to clear
     */
    public void setLoadMapCallback(final Consumer<String> callback)
    {
        this.loadMapCallback = callback;
    }

    /**
     * Drops every match-related hook this window was given.
     *
     * <p>The counterpart to the four {@code attachMatch*} methods:
     * the gate, the result supplier, the restart runnable and the
     * status supplier are all nulled out, so a window that returns
     * to the menu and tears down its map no longer reads from a
     * port that no longer exists. A future state change after this
     * call has no match gate to fire, which is the goal &mdash; the
     * match flow is over until the launcher attaches a fresh set
     * (which {@code bindAndAttachMap} does, when the player picks
     * a new map).</p>
     */
    public void detachMatchHooks()
    {
        this.matchResult = null;

        this.matchRestart = null;

        this.matchStatus = null;

        this.matchGate = null;

        this.ticsPerSecond = 0;
    }

    /**
     * Returns whether this window can start another round.
     *
     * <p>Exposed so a headless test can assert the presentation rule rather than
     * the wiring: a window with nothing to restart must not offer Play Again.</p>
     *
     * @return true when something was attached by {@link #attachMatchRestart}
     */
    public boolean canRestart()
    {
        return matchRestart != null;
    }

    /** Returns the debug switch this window's settings screen flips. Never null. */
    public DebugSettings debugSettings()
    {
        return debug;
    }

    /** Returns the visual aids this window's settings screen flips. Never null. */
    public AccessibilitySettings accessibilitySettings()
    {
        return accessibility;
    }

    /** Returns the corner frame counter. Never null. */
    public DebugOverlay debugOverlay()
    {
        return overlay;
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

        settings = new SettingsScreen(accessibility, debug, renderSettings(),
            uiState::returnToMenu);

        settings.layoutFor(width, height);

        modeSelect = new ModeSelectionScreen(
            mode -> uiState.pickMode(mode),
            uiState::returnToMenu);

        modeSelect.layoutFor(width, height);

        // The picker is built only when the launcher supplied entries. An empty
        // entries list is the "no map picker wired" case every windowless test
        // gets; the menu's SELECT MAP button still works (it transitions to
        // MAP_SELECT), but the screen will not be drawn because it is null.
        // A future pass can decide whether a null picker should refuse the
        // transition outright — for now, a silent fallback to the menu is
        // the lesser surprise.
        if (!mapEntries.isEmpty())
        {
            mapSelect = new MapSelectionScreen(mapSelection, mapEntries,
                this::onMapPicked, uiState::returnToModeSelect);

            mapSelect.layoutFor(width, height);
        }

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

        // Before the state sync, so a round decided on this frame is shown on
        // this frame rather than the next one.
        checkMatchResult();

        // After the poll, because Escape is seen there and the menu should come
        // back on the same frame the cursor is released, not the one after.
        // Scene2D button callbacks have already fired by now too: libGDX pumps
        // its input events before it calls render().
        syncUiState();

        drawWorld(deltaSeconds);

        callback.onFrame(deltaSeconds);

        screenshot.afterFrame();
    }

    // Notices that the round has been decided, once, and moves the UI onto the
    // end screen. See attachMatchResult for why this is a poll.
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

        // Built here rather than in the draw, because a screen is a GL resource
        // and building one inside the paint of the frame that needs it is how
        // you get a first frame that is half old and half new.
        gameOver = new GameOverScreen(summary, this::restartMatch, uiState::returnToMenu);

        gameOver.layoutFor(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());

        uiState.endMatch(summary);
    }

    // Play Again: restore the world, THEN move the UI into it.
    //
    // The order is the whole of the correctness here and it is not
    // interchangeable. The UI transition is what un-freezes the match gate and
    // gives the cursor back, so a transition that happened first would leave one
    // or more frames in which the player is standing in the room they cleared,
    // with seven invisible corpses, before the reset landed. Restoring first means
    // the first frame of PLAYING is already the first frame of a new round.
    //
    // Runs on the render thread from a Scene2D click. restartMatch takes the tic
    // lock, which is what makes that safe — see attachMatchRestart.
    //
    // Package-private rather than private, on the same terms as menuActions()
    // above and for the same reason: the only caller in production is a button on
    // a GameOverScreen, and a GameOverScreen cannot be constructed without a GL
    // context. Widening it by one level is what lets the ORDERING be asserted
    // headlessly, and the ordering is the part that can be got wrong.
    void restartMatch()
    {
        if (matchRestart == null)
        {
            // Nothing to restart, so there is nothing honest to do. The button
            // should not have been offered; canRestart() is the check that stops
            // it being.
            return;
        }

        matchRestart.run();

        // Cleared here and nowhere else, in the same call that reset the
        // simulation, so the end screen can only re-arm for a round that has
        // actually been restarted.
        resultShown = false;

        uiState.restartMatch();
    }

    // Called from a Scene2D click on a map tile. The body's source lives on
    // the state machine as the "mode" field, set by openModeSelect earlier
    // in the flow: SINGLE_PLAYER forks straight to the loading screen,
    // MULTIPLAYER forks to the lobby first. Either path then advances to
    // loading on the next user action.
    void onMapPicked(final String mapId)
    {
        if (mapId == null || mapId.isBlank())
        {
            LOG.warn("onMapPicked: blank map id ignored");

            return;
        }

        // Fire BEFORE the state transition so the launcher can build the
        // map (and re-attach the match hooks) before the loading screen's
        // onReady fires uiState.startGame. By the time the state machine
        // reaches PLAYING, the gate is in place and the match runs against
        // the freshly built port.
        if (loadMapCallback != null)
        {
            LOG.info("onMapPicked: dispatching map load to launcher: id={}", mapId);

            loadMapCallback.accept(mapId);
        }
        else
        {
            LOG.warn("onMapPicked: no loadMapCallback wired; the menu is in front but the"
                + " engine will not load '{}'", mapId);
        }

        if (uiState.mode() == MatchMode.MULTIPLAYER)
        {
            LOG.info("Map picked: id={} mode=MULTIPLAYER -> opening lobby", mapId);

            uiState.openLobby();
        }
        else
        {
            LOG.info("Map picked: id={} mode={} -> opening loading screen", mapId,
                uiState.mode());

            uiState.openLoading();
        }
    }

    // Exactly one screen draws, and it draws the whole window.
    private void drawWorld(final float deltaSeconds)
    {
        final UiState current = uiState.state();

        if (menu != null && current.drawsMenu())
        {
            // The menu owns the whole window, clear included. The world is not
            // presented at all — see the class Javadoc for why that is the
            // point rather than an optimisation.
            menu.render(deltaSeconds);

            return;
        }

        if (settings != null && current.drawsSettings())
        {
            settings.render(deltaSeconds);

            return;
        }

        if (modeSelect != null && current.drawsModeSelect())
        {
            modeSelect.render(deltaSeconds);

            return;
        }

        if (mapSelect != null && current.drawsMapSelect())
        {
            ensureLoadingAndLobbyBuilt();

            mapSelect.render(deltaSeconds);

            return;
        }

        if (lobby != null && current.drawsLobby())
        {
            lobby.render(deltaSeconds);

            return;
        }

        if (loading != null && current.drawsLoading())
        {
            loading.render(deltaSeconds);

            return;
        }

        if (gameOver != null && current.drawsGameOver())
        {
            gameOver.render(deltaSeconds);

            return;
        }

        drawGame(deltaSeconds);
    }

    // The loading and lobby screens are built lazily: the map browser is the
    // first thing the player sees that might advance to either, and the cost
    // of a Texture, a font and a Stage is wasted if the player only ever
    // browses. The idleness the engine tolerates is a second Texture upload
    // per map visit, which is well under a frame.
    private void ensureLoadingAndLobbyBuilt()
    {
        final UiState current = uiState.state();

        if (current == UiState.MAP_SELECT || current == UiState.LOBBY
            || current == UiState.LOADING)
        {
            if (mapSelection == null || mapSelection.currentMapId() == null)
            {
                return;
            }

            final String mapId = mapSelection.currentMapId();

            final MapSelectionScreen.Entry entry = findEntry(mapId);

            if (entry == null)
            {
                return;
            }

            if (loading == null)
            {
                // onReady advances the state machine to PLAYING once the
                // loading screen's auto-start timer fires. For the shipped
                // maps the .ofm is already in the classpath, so this is
                // a UX delay rather than a real wait; the same hook will
                // fire when a real map loader finishes its background
                // read.
                LOG.info("Building LoadingScreen for map: id={} displayName={} thumbnail={}",
                    entry.id(), entry.displayName(), entry.thumbnailPath());

                loading = new LoadingScreen(entry.displayName(),
                    entry.thumbnailPath(),
                    () -> uiState.startGame(uiState.mode()),
                    uiState::returnFromLoading);

                loading.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            }

            if (lobby == null && uiState.mode() == MatchMode.MULTIPLAYER)
            {
                LOG.info("Building LobbyScreen for map: id={} displayName={}", entry.id(),
                    entry.displayName());

                lobby = new LobbyScreen(entry.displayName(), uiState.mode(),
                    entry.thumbnailPath(), uiState::openLoading,
                    uiState::openLoading, uiState::returnToMapBrowser);

                lobby.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            }
        }
    }

    // Look up the entry for a given map id, falling back to the first entry
    // if the id is not in the list (a stale selection from a previous
    // launcher run is the failure mode this catches).
    private MapSelectionScreen.Entry findEntry(final String mapId)
    {
        if (mapEntries == null || mapEntries.isEmpty())
        {
            return null;
        }

        for (final MapSelectionScreen.Entry entry : mapEntries)
        {
            if (entry.id().equals(mapId))
            {
                return entry;
            }
        }

        return mapEntries.get(0);
    }

    // The world, plus the frame counter if it has been asked for.
    private void drawGame(final float deltaSeconds)
    {
        if (presenter == null || !presenter.present())
        {
            // Playing, but nothing was uploaded: there is no renderer, or the
            // game loop has not published its first frame. Something still has
            // to own the clear or the window shows the driver's leftovers.
            MainMenuScreen.clearBackground();
        }

        // BEFORE the debug counter, so the frame counter is the topmost thing on
        // screen when both are up. The score panel is right-aligned and the
        // counter left-aligned, so they do not overlap in any case — but the
        // ordering is what makes that a choice rather than a coincidence.
        drawScore();

        sampleDebugOverlay(deltaSeconds);

        if (debug.isOverlayVisible())
        {
            // Last, so it is over the world rather than under it. Drawn at the
            // WINDOW size while the world behind it was rasterized at the render
            // size — which is the visible payoff of scaling the world alone, and
            // is why the counter reports both.
            overlay.render(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(),
                renderWidth(), renderHeight());
        }
    }

    // The kill and death count, and the notice a dead player needs to see.
    //
    // Drawn at the WINDOW size while the world behind it was rasterized at the
    // render size, exactly as the frame counter is: the score is UI, so it stays
    // sharp when the world is scaled down.
    //
    // Unconditional — there is no setting behind this. A player must be able to
    // see that they died without having opened a debug menu first, which is the
    // whole reason it exists; the frame counter is diagnostics and is behind a
    // toggle, and conflating the two would put "why did I teleport" behind a
    // switch nobody knows about.
    private void drawScore()
    {
        if (matchStatus == null)
        {
            return;
        }

        score.render(matchStatus.get(), Gdx.graphics.getWidth(), Gdx.graphics.getHeight(),
            ticsPerSecond);
    }

    // The framebuffer the rasterizer is filling, or 0 when there is no
    // presenter to have scaled anything — which the overlay reads as "the same
    // as the surface", the only honest answer with no renderer in the run.
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
    // mode whether or not anything can act on it — a headless desktop test
    // builds this listener with no renderer at all.
    private RenderSettings renderSettings()
    {
        if (presenter == null)
        {
            return new RenderSettings();
        }

        return presenter.renderSettings();
    }

    // Feeds the counter whether or not it is being drawn, so switching it on
    // shows a settled reading rather than one climbing out of nothing.
    //
    // The renderer's own figure comes from SoftwareRenderPort.lastFrameNanos()
    // rather than from anything measured here: it is already published, it is
    // measured around the actual pipeline on the worker threads, and a second
    // clock around the presenter would be timing the upload instead.
    private void sampleDebugOverlay(final float deltaSeconds)
    {
        long rendererNanos = 0L;

        if (presenter != null)
        {
            rendererNanos = presenter.lastRenderNanos();
        }

        overlay.sample((long) (deltaSeconds * NANOS_PER_SECOND), rendererNanos);
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

    // Whichever screen is in front holds the Scene2D input processor, and only
    // while it is on screen. Detaching is what makes PLAYING genuinely free of
    // the menu rather than merely hiding it — without it the stage still hit
    // tests every mouse position and a click aimed at the game lands on
    // whichever invisible button was under the crosshair.
    //
    // At most one of these is ever attached, because Gdx.input holds exactly one
    // processor and each attach replaces the last. The detaches are still
    // written out: leaving the settings stage installed while the menu is in
    // front would be the same invisible-button bug one screen over.
    private void applyMenuInput(final UiState state)
    {
        LOG.info("UI state -> {}", state);

        if (menu != null && state.drawsMenu())
        {
            menu.attachInputProcessor();

            return;
        }

        if (settings != null && state.drawsSettings())
        {
            settings.attachInputProcessor();

            return;
        }

        if (modeSelect != null && state.drawsModeSelect())
        {
            modeSelect.attachInputProcessor();

            return;
        }

        if (mapSelect != null && state.drawsMapSelect())
        {
            mapSelect.attachInputProcessor();

            return;
        }

        if (lobby != null && state.drawsLobby())
        {
            lobby.attachInputProcessor();

            return;
        }

        if (loading != null && state.drawsLoading())
        {
            loading.attachInputProcessor();

            return;
        }

        if (gameOver != null && state.drawsGameOver())
        {
            gameOver.attachInputProcessor();

            return;
        }

        if (menu != null)
        {
            menu.detachInputProcessor();
        }

        releaseFinishedResult(state);
    }

    // Drops the end screen once the player has left it, by either door — Back to
    // the menu or Play Again into a new round. It holds a texture, a font and a
    // stage, so keeping it alive would be keeping three GPU resources for a
    // screen nobody is looking at.
    //
    // Rebuilding it for the NEXT result rather than reusing it is the cheap
    // direction here, and deliberately so: every figure on that screen is fixed
    // at construction (see GameOverScreen), so a reused instance would be one
    // object holding two headings and seven mutable labels that have to be kept
    // in step with whichever round last ended. A match ends once per round.
    private void releaseFinishedResult(final UiState state)
    {
        if (gameOver == null || state.drawsGameOver())
        {
            return;
        }

        gameOver.dispose();

        gameOver = null;
    }

    @Override
    public void resize(final int width, final int height)
    {
        if (menu != null)
        {
            menu.resize(width, height);
        }

        if (settings != null)
        {
            settings.resize(width, height);
        }

        if (gameOver != null)
        {
            gameOver.resize(width, height);
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

        if (settings != null)
        {
            settings.detachInputProcessor();

            settings.dispose();

            settings = null;
        }

        if (mapSelect != null)
        {
            mapSelect.detachInputProcessor();

            mapSelect.dispose();

            mapSelect = null;
        }

        if (modeSelect != null)
        {
            modeSelect.detachInputProcessor();

            modeSelect.dispose();

            modeSelect = null;
        }

        if (lobby != null)
        {
            lobby.detachInputProcessor();

            lobby.dispose();

            lobby = null;
        }

        if (loading != null)
        {
            loading.detachInputProcessor();

            loading.dispose();

            loading = null;
        }

        if (gameOver != null)
        {
            gameOver.detachInputProcessor();

            gameOver.dispose();

            gameOver = null;
        }

        overlay.dispose();

        score.dispose();

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

            uiState.openModeSelect(MatchMode.SINGLE_PLAYER);
        }

        @Override
        public void onMultiplayer()
        {
            delegate.onMultiplayer();

            uiState.openModeSelect(MatchMode.MULTIPLAYER);
        }

        @Override
        public void onSettings()
        {
            delegate.onSettings();

            uiState.openSettings();
        }

        @Override
        public void onMapSelection()
        {
            delegate.onMapSelection();

            uiState.openMapSelect();
        }

        @Override
        public void onQuit()
        {
            delegate.onQuit();
        }
    }
}
