/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;

import com.openfps.engine.hal.adapter.ActionBindings;
import com.openfps.engine.hal.port.GameAction;
import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.InputBinding;
import com.openfps.engine.hal.port.InputState;
import com.openfps.gdx.InputAccumulator;
import com.openfps.gdx.UiState;
import com.openfps.gdx.UiStateMachine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real desktop input: mouse-look and movement over libGDX's LWJGL3 backend.
 *
 * <p>The class is deliberately two thin halves. This one knows about
 * {@code Gdx.input} and nothing else — how to ask GLFW whether a control is
 * held, how to catch the cursor, when its numbers are worth believing. All the
 * arithmetic and all the cross-thread bookkeeping live in
 * {@link InputAccumulator}, which has no platform imports and is therefore the
 * part CI can actually test. Everything below the seam needs a human at a
 * keyboard.</p>
 *
 * <h2>It no longer knows which key means what</h2>
 *
 * <p>Every control comes from an {@link ActionBindings} table, looked up by
 * {@link GameAction}. This class names no key constant at all — the desktop
 * defaults live in {@link DesktopBindings}, and the table can be replaced at
 * runtime by {@link #bindActions}. Before that split, "left mouse fires" was a
 * literal in the middle of the polling loop below: nothing could report the
 * scheme, nothing could change it, and the Android port would have had to
 * duplicate this whole method with different numbers in it.</p>
 *
 * <h2>Two threads, two calls</h2>
 *
 * <p>{@link #pollDevice()} runs on the LWJGL3 render thread, once per
 * presented frame, driven by {@link GdxFrameLoopListener}. It is the only code
 * that touches {@code Gdx.input}, because GLFW input queries belong on the
 * thread that owns the window.</p>
 *
 * <p>{@link #sampleInput(int)} runs on the game loop thread, once per tic, and
 * touches no platform API at all: it drains the accumulator into an immutable
 * {@link InputState} and publishes it. The two rates are unrelated — vsync
 * versus a fixed 30/60/120 Hz — and {@code InputAccumulator} exists to make
 * that not matter.</p>
 *
 * <h2>A controller is an extra path, never a mode</h2>
 *
 * <p>There is no "gamepad mode" and no detection that switches one on. Keyboard,
 * mouse and pad are all polled every frame and all of them reach the same
 * accumulator, so a player may walk with the left stick, aim with the mouse and
 * fire with a trigger inside one tic. That falls out of two decisions rather
 * than needing arbitration: pad <b>buttons</b> resolve through the same
 * {@link ActionBindings} lookup as everything else (multiple bindings on an
 * action are alternates, so "left mouse OR right trigger" is just a row), and
 * pad <b>sticks</b> go into a second accumulator channel that is summed with the
 * keyboard's rather than overwriting it.</p>
 *
 * <p>{@link GamepadSource} is the seam: {@link GlfwGamepad} behind it in
 * production, a fake in tests, which is what makes hot-plug — the failure that
 * matters and the one no CI machine can stage — an ordinary unit test. See
 * {@link #pollGamepad}.</p>
 *
 * <h2>Mouse look</h2>
 *
 * <p>Relative motion only. {@code Gdx.input.getDeltaX()/getDeltaY()} report
 * how far the pointer moved since the previous frame, which is the quantity a
 * first-person camera actually wants. Absolute cursor position is useless for
 * looking: it saturates the moment the pointer reaches a screen edge, and the
 * player's view would simply stop turning. Deltas keep coming only while the
 * cursor is <b>caught</b> ({@link Input#setCursorCatched}), so capture is not
 * a nicety here — it is the mechanism.</p>
 *
 * <h2>Cursor capture follows the UI state, not the mouse</h2>
 *
 * <p>Capture is a consequence of {@link UiState} and nothing else. In
 * {@link UiState#MENU} the cursor is free and visible so the buttons are
 * clickable; in {@link UiState#PLAYING} it is caught and hidden so GLFW keeps
 * reporting relative motion. Every frame this port reconciles the device
 * against {@link UiStateMachine#state()}, which means the two can never
 * disagree — the older design inferred "the game is running" from whether the
 * cursor happened to be caught, so a click anywhere in the window started
 * mouse-look while the menu was still on screen and still taking clicks.</p>
 *
 * <p><b>Escape leaves {@link UiState#PLAYING}</b>, which releases the cursor as
 * a side effect of the transition. A captured cursor with no way out is a
 * window the user cannot close, cannot alt-tab away from cleanly, and cannot
 * reach the Quit button in; Escape is what stops this adapter from being
 * that.</p>
 *
 * <h2>Banked motion across a transition</h2>
 *
 * <p>Every state change clears the accumulator, before anything else and
 * whether or not there is a window. That is the fix for a specific bug: the
 * mouse moves hundreds of pixels crossing the menu to reach Start Game, and if
 * those pixels are still banked when {@link UiState#PLAYING} begins, the first
 * tic of play latches the lot and the player's view snaps round. The clear
 * happens in {@link #syncUiState()}, which runs before the {@code Gdx.input}
 * null check precisely so the behaviour is the same in a headless JVM and can
 * be tested there. The first look sample after capture is discarded on top of
 * that, because catching the cursor warps the pointer and the warp arrives as
 * a delta.</p>
 *
 * <p>The same clear going the other way stops a key held at the moment of
 * Escape from walking the player into a wall while they are in the menu.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class GdxInputPort implements I_InputPort
{
    /**
     * Mouse sensitivity for this port, in <b>radians of view rotation per
     * pixel of raw mouse motion</b>. See
     * {@link InputAccumulator#DEFAULT_RADIANS_PER_PIXEL} for how the value was
     * chosen; it is named here so a settings screen has one place to override.
     */
    public static final float MOUSE_SENSITIVITY_RADIANS_PER_PIXEL =
        InputAccumulator.DEFAULT_RADIANS_PER_PIXEL;

    /**
     * Polls of mouse motion discarded after the cursor is caught.
     *
     * <p>Two, not one. The catch and the warp delta it provokes do not land on
     * the same frame — see {@link #discardLookPolls} for the measurement that
     * settled it. Two covers the observed one-frame lag with a frame in hand;
     * the cost of an extra discarded poll is at most one frame of look at the
     * instant a match begins, which no player can perceive, against a view that
     * snaps a quarter turn if it is too low.</p>
     */
    public static final int DISCARD_LOOK_POLLS_AFTER_CAPTURE = 2;

    /**
     * System property: hold a movement input for this many tics from the start
     * of the run. Zero, the default, scripts nothing.
     *
     * <p><b>This exists because collision is the one feature nothing automated
     * could reach.</b> Everything else the screenshot harness photographs is
     * true of a stationary player: the room is drawn, the weapon is held, the
     * bots patrol. Walls only prove themselves when somebody walks into one, and
     * every route into this port needs a hand — {@code Gdx.input} answers for a
     * physical keyboard, and {@link #pollDevice} refuses to read the device at
     * all unless the cursor is caught and the window focused, which an
     * unattended capture run cannot promise. So the automated proof that the
     * player stops at the wall was impossible to take, and "it works, come and
     * try it" is not evidence.</p>
     *
     * <p>Opt-in and off by default, on the same pattern as
     * {@code GdxScreenshot.FRAME_PROPERTY} and
     * {@code DebugSettings.OVERLAY_PROPERTY}. It injects at
     * {@link #sampleInput}, downstream of the device and the accumulator, so it
     * needs neither a window nor focus and cannot fight a real key:</p>
     *
     * <pre>
     *   gradlew :desktop:run "--args=--start-in-game" -Dopenfps.autoWalkTics=600
     * </pre>
     */
    public static final String AUTO_WALK_TICS_PROPERTY = "openfps.autoWalkTics";

    /**
     * System property: the forward axis the scripted walk holds, default 1.
     * Positive walks forward; see {@link #AUTO_WALK_TICS_PROPERTY}.
     */
    public static final String AUTO_WALK_FORWARD_PROPERTY = "openfps.autoWalkForward";

    /**
     * System property: the strafe axis the scripted walk holds, default 0.
     *
     * <p>Given alongside a forward axis it makes the walk a diagonal, which is
     * the input that tells a sliding wall from a wall that stops the player
     * dead — the position log after it shows movement along one axis and none
     * along the other.</p>
     */
    public static final String AUTO_WALK_STRAFE_PROPERTY = "openfps.autoWalkStrafe";

    private static final Logger LOG = LoggerFactory.getLogger(GdxInputPort.class);

    /** Where the render thread deposits readings and the loop thread collects them. */
    private final InputAccumulator accumulator;

    /**
     * The snapshot the current tic sees.
     * MUTABLE: replaced by {@link #sampleInput}, read by {@link #currentInput}.
     * Volatile because those are different threads, and the value is immutable
     * so publishing the reference is the whole of the handoff.
     */
    private volatile InputState latched = InputState.NEUTRAL;

    /**
     * How many more polls of mouse delta to throw away.
     *
     * <p>MUTABLE: reloaded when the cursor is caught, counted down by
     * {@link #pollLook}. Only ever touched on the render thread.</p>
     *
     * <p><b>A counter and not a boolean, because one frame was not enough.</b>
     * Catching the cursor warps the pointer to the window centre, and GLFW
     * reports that warp as an ordinary motion delta — but not on the frame that
     * did the catching. It arrives on a later one. A single-frame flag is
     * therefore spent before the delta it exists to eat ever shows up, and the
     * whole warp lands on the camera.</p>
     *
     * <p>Measured, not guessed: entering a match with the mouse untouched
     * snapped the view by exactly 512 px of yaw — the
     * {@link InputAccumulator#MAX_PIXELS_PER_POLL} clamp, saturated — and
     * 412 px of pitch, reproducibly, to the same two angles on every run.</p>
     */
    private int discardLookPolls;

    /**
     * True once the window has gone and no GLFW call is safe any more.
     * MUTABLE: set by {@link #onWindowClosing()} on the render thread, read by
     * {@link #shutdown()} on the main thread — hence volatile.
     */
    private volatile boolean windowClosed;

    /**
     * Whether this port is looking at the menu or at the game.
     *
     * MUTABLE: replaced by {@link #bindUiState}. Defaults to a private machine
     * parked in {@link UiState#MENU} so an unbound port — every windowless
     * test — is never null and never captures anything.
     */
    private volatile UiStateMachine uiState = new UiStateMachine();

    /**
     * The UI state the device has already been reconciled with.
     * MUTABLE: render thread only, compared against {@link #uiState} once per
     * {@link #pollDevice()} so the transition work happens exactly once.
     */
    private UiState appliedState = UiState.MENU;

    /**
     * Which controls trigger which actions.
     *
     * MUTABLE: replaced by {@link #bindActions}, read once per action per frame
     * on the render thread. Volatile because a controls screen may swap the
     * whole table while the game behind it is still being polled.
     */
    private volatile ActionBindings bindings = DesktopBindings.defaults();

    /**
     * How many tics of the run the scripted walk covers, or zero for none.
     * Read once from {@link #AUTO_WALK_TICS_PROPERTY} at construction, because a
     * harness setting that could change mid-run would make the run
     * unreproducible.
     */
    private final int autoWalkTics;

    /**
     * The snapshot the scripted walk latches, or null when none is configured.
     * Built once and immutable, so the script allocates nothing per tic.
     */
    private final InputState autoWalk;

    /**
     * The controller, if there is one.
     *
     * MUTABLE: replaced by {@link #bindGamepad}, polled on the render thread.
     * Volatile for the same reason as the bindings table. Defaults to the real
     * GLFW-backed source, which reports no controller — and touches no native
     * call at all — until something actually polls it, so a windowless test is
     * unaffected by its presence.
     */
    private volatile GamepadSource gamepad = new GlfwGamepad();

    /**
     * Whether a controller was present at the previous poll.
     *
     * MUTABLE: render thread only. Exists so connecting and disconnecting are
     * each logged once rather than every frame, and so the accumulator's
     * gamepad channel is cleared on the edge — see {@link #pollGamepad}.
     */
    private boolean gamepadConnected;

    /** Creates a port at the default sensitivity on the default control scheme. */
    public GdxInputPort()
    {
        this(new InputAccumulator(MOUSE_SENSITIVITY_RADIANS_PER_PIXEL));
    }

    /**
     * Creates a port over an explicit accumulator. Exists so a test can drive
     * the arithmetic side without a window.
     *
     * @param inputAccumulator the accumulator to latch from; must not be null
     */
    public GdxInputPort(final InputAccumulator inputAccumulator)
    {
        if (inputAccumulator == null)
        {
            throw new IllegalArgumentException("inputAccumulator must not be null");
        }
        this.accumulator = inputAccumulator;
        this.autoWalkTics = Integer.getInteger(AUTO_WALK_TICS_PROPERTY, 0).intValue();
        if (autoWalkTics <= 0)
        {
            this.autoWalk = null;
        }
        else
        {
            // No look deltas and no action flags: the script walks, and nothing
            // else. A scripted turn would make the position log a function of
            // two things at once and stop being a measurement of the walls.
            this.autoWalk = InputState.of(axisProperty(AUTO_WALK_FORWARD_PROPERTY, 1.0f),
                axisProperty(AUTO_WALK_STRAFE_PROPERTY, 0.0f), 0.0f, 0.0f, false, false, false);
        }
    }

    // One movement axis from a system property, or the default if it is absent
    // or unreadable. A typo falls back loudly rather than aborting the run: the
    // point of the harness is to get a run and a log out of it.
    private static float axisProperty(final String name, final float fallback)
    {
        final String raw = System.getProperty(name);
        if (raw == null || raw.isEmpty())
        {
            return fallback;
        }
        try
        {
            return Float.parseFloat(raw);
        }
        catch (final NumberFormatException e)
        {
            LOG.warn("{} is not a number ({}) — using {}", name, raw, Float.valueOf(fallback));
            return fallback;
        }
    }

    /**
     * Replaces the control scheme this port reads.
     *
     * <p>Takes effect on the next {@link #pollDevice()}. The table is read, never
     * written, so a caller may keep editing the same instance to rebind a single
     * action without swapping it back in.</p>
     *
     * @param actionBindings the table to poll against; must not be null
     * @throws IllegalArgumentException if {@code actionBindings} is null
     */
    public void bindActions(final ActionBindings actionBindings)
    {
        if (actionBindings == null)
        {
            throw new IllegalArgumentException("actionBindings must not be null");
        }
        this.bindings = actionBindings;
    }

    /** Returns the control scheme this port polls against. Never null. */
    public ActionBindings actionBindings()
    {
        return bindings;
    }

    /**
     * Replaces the controller this port reads.
     *
     * <p>Exists so a test can drive every gamepad path — including the pad being
     * unplugged mid-match — in a JVM with no GLFW and no controller. See
     * {@link GamepadSource}.</p>
     *
     * @param source the gamepad to poll; must not be null
     * @throws IllegalArgumentException if {@code source} is null
     */
    public void bindGamepad(final GamepadSource source)
    {
        if (source == null)
        {
            throw new IllegalArgumentException("source must not be null");
        }
        this.gamepad = source;
    }

    /** Returns the controller this port polls. Never null. */
    public GamepadSource gamepad()
    {
        return gamepad;
    }

    /**
     * Tells the accumulator how long a tic lasts, from the configured frame
     * rate.
     *
     * <p><b>Only stick look depends on this, and it depends on it completely.</b>
     * A mouse delta is a displacement that already happened and needs no
     * duration; a held stick is a rate, and a rate is not an angle until
     * something supplies the seconds — see {@code InputAccumulator}. The
     * launcher is the only object that knows the rate, so the launcher is who
     * calls this.</p>
     *
     * @param ticsPerSecond the simulation rate in Hz; must be greater than zero
     * @throws IllegalArgumentException if the rate is not positive
     */
    public void setTicRate(final int ticsPerSecond)
    {
        if (ticsPerSecond <= 0)
        {
            throw new IllegalArgumentException(
                "tic rate must be positive, got " + ticsPerSecond);
        }
        accumulator.setTicDuration(1.0f / ticsPerSecond);
    }

    @Override
    public void init()
    {
        accumulator.clearAll();
        latched = InputState.NEUTRAL;
        // Armed, not cleared. A port that comes up already in PLAYING — which
        // --start-in-game does, because the match begins before this runs —
        // sees no MENU->PLAYING transition, so syncUiState() never arms the
        // discard and the capture warp lands on the camera unopposed. That is
        // not a harness quirk: any path that starts captured has the same hole.
        discardLookPolls = DISCARD_LOOK_POLLS_AFTER_CAPTURE;
        windowClosed = false;
        // Not "no controller" — "nothing known about a controller yet", so the
        // first poll that finds one logs it. A restarted port must announce the
        // pad again; the alternative is a second window in which a connected
        // controller is never mentioned anywhere.
        gamepadConnected = false;
        appliedState = uiState.state();
        final GameAction unbound = bindings.firstUnbound();
        if (unbound != null)
        {
            // Not fatal — the game is playable with no sprint key. Loud anyway,
            // because the same gap on LEAVE_MATCH is a captured cursor with no
            // way out, and nothing else in the system would report it.
            LOG.warn("No control is bound to {} — that action cannot be triggered", unbound);
        }
        LOG.info("GdxInputPort initialized — sensitivity {} rad/px, controls {}",
            accumulator.radiansPerPixel(), bindings);
        if (autoWalk != null)
        {
            // Loud, because a run that walks by itself and is not expected to
            // looks exactly like a stuck key, and the property survives in a
            // shell's history far longer than the reason for setting it.
            LOG.info("SCRIPTED WALK: holding forward {} strafe {} for the first {} tics"
                + " — set {}=0 to disable", Float.valueOf(autoWalk.forwardAxis()),
                Float.valueOf(autoWalk.strafeAxis()), Integer.valueOf(autoWalkTics),
                AUTO_WALK_TICS_PROPERTY);
        }
    }

    /**
     * Returns the scripted walk snapshot, or null when none is configured.
     *
     * <p>Exists so a test can assert the harness hook is off unless asked for,
     * which is the property that matters about it — a scripted input that leaked
     * into an ordinary run would move the player without a key being touched.</p>
     *
     * @return the snapshot the script latches, or null
     */
    InputState autoWalk()
    {
        return autoWalk;
    }

    /**
     * Names the UI state this port obeys.
     *
     * <p>Called once by {@link GdxFrameLoopListener}, which owns the machine
     * because it is the thing that draws the menu. Until then the port has a
     * private machine of its own so that a windowless test — and the null
     * backend's lifecycle — never sees a null here.</p>
     *
     * <p>Binding also resets the reconciliation point and drops anything
     * banked: whatever the previous machine was doing has nothing to say about
     * the new one.</p>
     *
     * @param machine the UI state machine to follow; must not be null
     */
    public void bindUiState(final UiStateMachine machine)
    {
        if (machine == null)
        {
            throw new IllegalArgumentException("machine must not be null");
        }
        this.uiState = machine;
        this.appliedState = machine.state();
        accumulator.clearAll();
    }

    /**
     * Returns the UI state machine this port follows. Never null.
     *
     * @return the bound machine, or the private default if none was bound
     */
    public UiStateMachine uiState()
    {
        return uiState;
    }

    /**
     * Returns whether the cursor should currently be caught and hidden.
     *
     * <p>This is the port's <i>intent</i>, reconciled onto GLFW by the next
     * {@link #pollDevice()}. It exists as a separate reading because the GLFW
     * side needs a window and CI has none — the decision is testable, the
     * {@code glfwSetInputMode} call is not.</p>
     *
     * @return true while {@link UiState#PLAYING} is in force
     */
    public boolean isCursorCaptureWanted()
    {
        return appliedState.capturesCursor();
    }

    @Override
    public void shutdown()
    {
        accumulator.clearAll();
        latched = InputState.NEUTRAL;
        // Only if the window is still there — see onWindowClosing().
        if (!windowClosed)
        {
            releaseCursor();
        }
        LOG.info("GdxInputPort shut down");
    }

    /**
     * Hands the cursor back while the window still exists, and marks this port
     * as past the point where GLFW may be called.
     *
     * <p><b>This exists because the obvious placement crashes every clean
     * exit.</b> Releasing the cursor in {@link #shutdown()} looks right — it is
     * the mirror of {@link #init()} — but {@code shutdown()} runs from
     * {@code EngineSession.stop()}, which the desktop launcher calls
     * <i>after</i> {@code awaitPlatformLoop()} has returned. By then
     * {@code Lwjgl3Application} has terminated GLFW, while {@code Gdx.input} is
     * still a live non-null object: the null guard in {@link #releaseCursor()}
     * passes, the call goes through to {@code glfwSetInputMode}, and LWJGL's
     * JNI dispatch fails against a torn-down library with
     * {@code IncompatibleClassChangeError}.</p>
     *
     * <p><b>An {@code Error}, not an exception</b> — so no {@code catch
     * (RuntimeException)} anywhere on the teardown path would have contained
     * it, and the process died after a completely successful run with a stack
     * trace that pointed at a class-loading problem it did not have.</p>
     *
     * <p>{@code GdxFrameLoopListener.dispose()} is the last moment at which the
     * window is guaranteed alive, so the release happens there instead.</p>
     */
    public void onWindowClosing()
    {
        releaseCursor();
        windowClosed = true;
    }

    /** Returns true once {@link #onWindowClosing()} has run. */
    public boolean isWindowClosed()
    {
        return windowClosed;
    }

    /**
     * Latches everything gathered since the previous tic.
     *
     * Runs on the game loop thread and touches no libGDX API, so it is safe
     * while the render thread is mid-frame.
     *
     * <p>When {@link #AUTO_WALK_TICS_PROPERTY} is set, the first that many tics
     * latch a fixed scripted snapshot instead. The accumulator is still drained
     * first, so nothing banks up behind the script and the tic a real player
     * takes over is not handed a saved-up sweep.</p>
     *
     * @param ticIndex the tic being processed
     */
    @Override
    public void sampleInput(final int ticIndex)
    {
        latched = accumulator.latch();
        if (autoWalk != null && ticIndex < autoWalkTics)
        {
            latched = autoWalk;
        }
    }

    /**
     * Returns the snapshot latched by the last {@link #sampleInput}.
     *
     * @return the current snapshot; {@link InputState#NEUTRAL} before the
     *     first sample
     */
    @Override
    public InputState currentInput()
    {
        return latched;
    }

    /**
     * Returns false always — closing is the window's business.
     *
     * {@code GdxWindowPort} already owns the close flag, and it hears about
     * the title-bar X, {@code Gdx.app.exit()} and the menu's Quit button.
     * Duplicating that here would give the engine two disagreeing shutdown
     * signals. Escape returns to the menu; it does not quit.
     *
     * @return false
     */
    @Override
    public boolean isShutdownRequested()
    {
        return false;
    }

    /**
     * Reads the device once. Call from the LWJGL3 render thread, once per
     * frame, before the engine's frame callback.
     *
     * <p>The UI reconciliation happens first and unconditionally, so a
     * transition clears banked input even in a JVM with no window. Everything
     * after that needs {@code Gdx.input}; headless — in tests, or before the
     * application starts — it is null and the device half is skipped, which is
     * what leaves the port usable in a windowless JVM.</p>
     */
    public void pollDevice()
    {
        syncUiState();
        final Input input = Gdx.input;
        if (input == null)
        {
            return;
        }
        applyCursorMode(input);
        final GamepadSource pad = gamepad;
        if (!appliedState.capturesCursor())
        {
            // The menu is in front. The pointer belongs to the buttons, so bank
            // nothing and forget anything still pending — the stick included. A
            // controller left leaning while the player reads the menu is not a
            // request to walk.
            accumulator.clearAll();
            return;
        }
        // Before any question is asked of the pad, and specifically before
        // LEAVE_MATCH: that action may be bound to Start, and a button edge is
        // only available on the frame the poll that saw it ran.
        pollGamepad(pad);
        if (isJustPressed(input, pad, GameAction.LEAVE_MATCH))
        {
            // Leaving play. Do the whole handover now rather than next frame,
            // so the cursor is free before the menu is drawn over it.
            uiState.returnToMenu();
            syncUiState();
            applyCursorMode(input);
            LOG.debug("Escape — back to the menu, cursor released");
            return;
        }
        if (!input.isCursorCatched())
        {
            // Asked for capture and did not get it: the window is not focused.
            // Do not read a half-real device.
            accumulator.clearAll();
            return;
        }
        pollInvertToggle(input, pad);
        pollKeys(input, pad);
        pollLook(input);
    }

    /**
     * Reads the controller and keeps the accumulator's gamepad channel honest
     * about whether there is one.
     *
     * <p><b>The disconnect branch is the whole point of this method.</b> A stick
     * deflection is a level: it persists until something overwrites it, and a
     * pad that has been unplugged never will. Without the clear, a controller
     * yanked out — or a wireless one whose battery dies — at full deflection
     * leaves the player walking into a wall for the rest of the match with
     * nobody touching anything. {@code InputAccumulator.clearGamepad()} is
     * deliberately partial, so the keyboard the player still has a hand on is
     * untouched.</p>
     *
     * <p>Cleared unconditionally while absent rather than only on the edge. The
     * edge is what gets logged; the clear is idempotent and costs seven field
     * writes, which is a cheaper guarantee than reasoning about whether some
     * later path could reintroduce a stale level.</p>
     *
     * <p><b>Package-private so a test can call it directly</b>, exactly as
     * {@link #isAnyActive} is. In production this runs behind the
     * {@code Gdx.input} null check, which is what guarantees GLFW is initialised
     * before {@link GlfwGamepad} touches it — so reaching the logic through
     * {@link #pollDevice()} would need a window, and the one behaviour most
     * worth testing would be the one nothing could test.</p>
     *
     * @param pad the controller to read; must not be null
     */
    void pollGamepad(final GamepadSource pad)
    {
        pad.poll();
        if (!pad.isConnected())
        {
            if (gamepadConnected)
            {
                gamepadConnected = false;
                LOG.info("Controller disconnected — gamepad input dropped,"
                    + " keyboard and mouse unaffected");
            }
            accumulator.clearGamepad();
            return;
        }
        if (!gamepadConnected)
        {
            gamepadConnected = true;
            LOG.info("Controller connected: {}", pad.name());
        }
        // Left stick moves. The vertical axis is negated because a pad reports
        // "pushed away from the player" as NEGATIVE y, and forward is positive
        // here; the pair is negated together so the magnitude — and therefore
        // the radial dead zone — is unchanged.
        accumulator.setGamepadMovementAxes(0.0f - pad.leftStickY(), pad.leftStickX());
        // Right stick looks. Handed over EXACTLY as reported, with no sign flip,
        // because the pad's vertical convention already IS the accumulator's
        // documented "+y downward" — the same fact that makes the desktop mouse
        // need no correction. See GdxInputPort.pollLook for what a second flip
        // cost the last time someone added one on a theory.
        accumulator.setGamepadLookAxes(pad.rightStickX(), pad.rightStickY());
    }

    // The invert-look switch, on the edge rather than the level: held down it
    // would flip once per frame and settle wherever the key happened to be
    // released, which is a coin toss rather than a setting.
    //
    // Before pollLook rather than after, so the tic the key is pressed already
    // rotates the way the player just asked for. The accumulator applies the
    // flag at latch, so nothing already banked is rotated twice.
    private void pollInvertToggle(final Input input, final GamepadSource pad)
    {
        if (!isJustPressed(input, pad, GameAction.TOGGLE_INVERT_LOOK))
        {
            return;
        }
        setInvertLook(!accumulator.isInvertPitch());
    }

    /**
     * Sets whether vertical look is inverted — mouse away from you aims down.
     *
     * <p>The programmatic half of {@link GameAction#TOGGLE_INVERT_LOOK}, so a
     * settings screen or a restored profile can drive the same preference the
     * key does. Takes effect on the next tic's latch.</p>
     *
     * <p><b>This is a preference and nothing else.</b> It is not the knob for a
     * platform whose device reports upside down — see {@link #pollLook}, where
     * exactly that confusion once shipped an inverted mouse. A backend that
     * disagrees with {@link InputAccumulator}'s "+y downward" contract owes the
     * accumulator the sign it asks for; it must not spend this flag, or the
     * player loses the ability to have the preference at all.</p>
     *
     * @param inverted true to make pushing the mouse away aim down
     */
    public void setInvertLook(final boolean inverted)
    {
        accumulator.setInvertPitch(inverted);
        LOG.info("Invert mouse look: {}", Boolean.valueOf(inverted));
    }

    /**
     * Returns whether vertical look is currently inverted.
     *
     * @return true if pushing the mouse away aims down
     */
    public boolean isInvertLook()
    {
        return accumulator.isInvertPitch();
    }

    /** Returns the accumulator this port latches from. Never null. */
    InputAccumulator accumulator()
    {
        return accumulator;
    }

    // Notices that the UI moved and pays for it once. Deliberately touches no
    // platform API: this is the half that has to run headless, because it is
    // the half that stops menu mouse motion reaching the first tic of play.
    private void syncUiState()
    {
        final UiState current = uiState.state();
        if (current == appliedState)
        {
            return;
        }
        appliedState = current;
        // Nothing gathered under the previous UI describes what the player
        // wants under this one. Crossing into PLAYING that is a mouse sweep
        // across the menu; crossing back it is a key still held at the moment
        // Escape was pressed.
        accumulator.clearAll();
        // Capture warps the pointer to the window centre, so the next delta
        // reported is the warp and not a hand movement.
        discardLookPolls = DISCARD_LOOK_POLLS_AFTER_CAPTURE;
    }

    // Makes GLFW agree with the UI state. Reconciled rather than toggled on the
    // edge so a cursor released by something else — an alt-tab, a driver reset
    // — is retaken on the next frame instead of leaving mouse-look dead.
    private void applyCursorMode(final Input input)
    {
        final boolean shouldCatch = appliedState.capturesCursor();
        if (input.isCursorCatched() != shouldCatch)
        {
            input.setCursorCatched(shouldCatch);
        }
    }

    // Every control this frame, resolved through the bindings table. No key
    // constant appears here on purpose — see the class Javadoc.
    //
    // The pad's BUTTONS come through this path rather than a separate one,
    // because on desktop every device is polled and the bindings table already
    // knows how to say "fire is left mouse OR left control OR the right
    // trigger". Alternates, not chords: isAnyActive answers "any". A pad button
    // therefore needs no channel of its own here — a disconnected pad simply
    // reports nothing down, so nothing can go stale.
    //
    // The pad's STICKS cannot come through here, and that asymmetry is the point
    // of the whole design. A stick has a direction, so "is the left stick held"
    // has no answer; isDown reports a stick axis inactive, and pollGamepad reads
    // the deflection into its own channel instead.
    private void pollKeys(final Input input, final GamepadSource pad)
    {
        accumulator.setMovementKeys(
            isHeld(input, pad, GameAction.MOVE_FORWARD),
            isHeld(input, pad, GameAction.MOVE_BACKWARD),
            isHeld(input, pad, GameAction.STRAFE_LEFT),
            isHeld(input, pad, GameAction.STRAFE_RIGHT));
        accumulator.setActionKeys(
            isHeld(input, pad, GameAction.FIRE),
            isHeld(input, pad, GameAction.JUMP),
            isHeld(input, pad, GameAction.SPRINT));
    }

    /**
     * Answers whether one physical control is currently active.
     *
     * <p>The seam that lets {@link #isAnyActive} be tested at all. In production
     * it closes over {@code Gdx.input} and forwards to {@code isKeyPressed} or
     * {@code isButtonPressed}; in a test it is two lines that report a fixed set
     * as down. Without it, "does any bound control trigger this action" — which
     * is real logic, with a real off-by-one available — would only be
     * exercisable by a human at a keyboard.</p>
     */
    interface ControlProbe
    {
        /**
         * Returns whether the given control is active right now.
         *
         * @param binding the control to test; never null
         * @return true if it is down
         */
        boolean isActive(InputBinding binding);
    }

    /**
     * Returns whether any control bound to an action is active.
     *
     * <p><b>Any, not all.</b> Multiple bindings are alternates rather than a
     * chord: fire is left mouse <i>or</i> left control, and requiring both would
     * turn the trackpad alternate from a convenience into a way of making the
     * mouse stop working.</p>
     *
     * <p>An unbound action reads as inactive, which is both the honest answer
     * and the safe one — a player who has cleared their sprint key simply never
     * sprints, rather than sprinting always.</p>
     *
     * @param table the control scheme; must not be null
     * @param action the action to test; must not be null
     * @param probe answers for one control at a time; must not be null
     * @return true if at least one bound control is active
     */
    static boolean isAnyActive(final ActionBindings table, final GameAction action,
        final ControlProbe probe)
    {
        final InputBinding[] bound = table.bindingsFor(action);
        for (int index = 0; index < bound.length; index++)
        {
            if (probe.isActive(bound[index]))
            {
                return true;
            }
        }
        return false;
    }

    // Held: true for every frame the control is down.
    private boolean isHeld(final Input input, final GamepadSource pad,
        final GameAction action)
    {
        return isAnyActive(bindings, action, binding -> isDown(input, pad, binding));
    }

    // Edge: true on the single frame a control goes down.
    //
    // Edge rather than level, because the actions that use this are toggles:
    // leaving the match on "held" would fire again every frame the key stayed
    // down and bounce the player straight back out of the menu.
    private boolean isJustPressed(final Input input, final GamepadSource pad,
        final GameAction action)
    {
        return isAnyActive(bindings, action, binding -> wentDown(input, pad, binding));
    }

    // Dispatches one binding onto the right platform query. Four device kinds
    // this port can answer — keys, mouse buttons, gamepad buttons and gamepad
    // triggers; a touch region in a desktop table reads as not-down rather than
    // throwing, so a shared scheme across platforms degrades instead of
    // crashing.
    //
    // GAMEPAD_AXIS deliberately does NOT mean "any axis". GamepadSource answers
    // only for triggers and reports a stick axis inactive, which is what lets
    // the left stick be bound to MOVE_FORWARD — so a settings screen can report
    // and rebind it — without that binding also reading as a held movement key
    // whenever the player strafes.
    private static boolean isDown(final Input input, final GamepadSource pad,
        final InputBinding binding)
    {
        if (binding.source() == InputBinding.Source.KEY)
        {
            return input.isKeyPressed(binding.code());
        }
        if (binding.source() == InputBinding.Source.MOUSE_BUTTON)
        {
            return input.isButtonPressed(binding.code());
        }
        if (binding.source() == InputBinding.Source.GAMEPAD_BUTTON)
        {
            return pad.isButtonDown(binding.code());
        }
        if (binding.source() == InputBinding.Source.GAMEPAD_AXIS)
        {
            return pad.isAxisPressed(binding.code());
        }
        return false;
    }

    // The edge-triggered counterpart of isDown.
    //
    // A gamepad has no platform-supplied "just pressed": GLFW reports levels, so
    // the edge is computed by GlfwGamepad against the previous poll. An axis has
    // no edge at all here — a trigger bound to a toggle would need its own
    // hysteresis to avoid chattering at the threshold, and nothing needs it, so
    // it reads inactive rather than shipping a half-considered answer.
    private static boolean wentDown(final Input input, final GamepadSource pad,
        final InputBinding binding)
    {
        if (binding.source() == InputBinding.Source.KEY)
        {
            return input.isKeyJustPressed(binding.code());
        }
        if (binding.source() == InputBinding.Source.MOUSE_BUTTON)
        {
            return input.isButtonJustPressed(binding.code());
        }
        if (binding.source() == InputBinding.Source.GAMEPAD_BUTTON)
        {
            return pad.didButtonGoDown(binding.code());
        }
        return false;
    }

    // One frame of relative motion, handed over EXACTLY as the device reports
    // it. The accumulator owns the units, the sign convention and the invert
    // preference; this method owns none of them.
    //
    // THERE IS NO NEGATION HERE, and its absence is measured rather than
    // argued. A negation lived here for a while, on the theory that GLFW
    // reported vertical motion upside down. It does not, and that extra flip is
    // what the player kept reporting as "the mouse is still inverted".
    //
    // The measurement, taken through the real LWJGL3 backend with the cursor
    // caught, by warping the pointer 100 px UP the screen — which is what
    // pushing the mouse away from you does:
    //
    //   warp                    cursor y 0 -> -100   (up the screen)
    //   Gdx.input.getDeltaY()   -100                 (so +y IS downward)
    //   with the negation       accumulated pitch pixels = +100
    //   latch() negates again   pitch = -0.22 rad
    //   run ended at            PlayerController{..., pitch=-0.22}
    //
    // Aimed DOWN for a mouse pushed away: inverted, exactly as reported.
    // Without the negation the same warp accumulates -100, latch() negates
    // once, and the view tilts UP — the conventional feel.
    //
    // libGDX's own backend agrees in one line. DefaultLwjgl3Input's cursor
    // callback is deltaY = (int) y - logicalMouseY, straight from GLFW's
    // top-left-origin cursor position and never flipped. So the desktop mouse
    // genuinely speaks the accumulator's documented "+y downward" and needs no
    // correction at all; the correction WAS the bug.
    //
    // A player who WANTS inverted look gets it from
    // InputAccumulator.setInvertPitch, reachable at runtime through
    // GameAction.TOGGLE_INVERT_LOOK and setInvertLook(boolean). That is a
    // preference, and keeping it distinct from a platform sign fix is exactly
    // what stops the next person guessing at this line.
    private void pollLook(final Input input)
    {
        if (discardLookPolls > 0)
        {
            discardLookPolls = discardLookPolls - 1;
            accumulator.resetLook();
            return;
        }
        accumulator.accumulateLook(input.getDeltaX(), input.getDeltaY());
    }

    // Hands the cursor back on the way out. Skipped headless, and skipped when
    // the application has already torn its input down.
    private void releaseCursor()
    {
        final Input input = Gdx.input;
        if (input == null)
        {
            return;
        }
        input.setCursorCatched(false);
    }
}
