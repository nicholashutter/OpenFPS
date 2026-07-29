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
     * @param ticIndex the tic being processed
     */
    @Override
    public void sampleInput(final int ticIndex)
    {
        latched = accumulator.latch();
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
        if (!appliedState.capturesCursor())
        {
            // The menu is in front. The pointer belongs to the buttons, so bank
            // nothing and forget anything still pending.
            accumulator.clearAll();
            return;
        }
        if (isJustPressed(input, GameAction.LEAVE_MATCH))
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
        pollKeys(input);
        pollLook(input);
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
    private void pollKeys(final Input input)
    {
        accumulator.setMovementKeys(
            isHeld(input, GameAction.MOVE_FORWARD),
            isHeld(input, GameAction.MOVE_BACKWARD),
            isHeld(input, GameAction.STRAFE_LEFT),
            isHeld(input, GameAction.STRAFE_RIGHT));
        accumulator.setActionKeys(
            isHeld(input, GameAction.FIRE),
            isHeld(input, GameAction.JUMP),
            isHeld(input, GameAction.SPRINT));
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
    private boolean isHeld(final Input input, final GameAction action)
    {
        return isAnyActive(bindings, action, binding -> isDown(input, binding));
    }

    // Edge: true on the single frame a control goes down.
    //
    // Edge rather than level, because the actions that use this are toggles:
    // leaving the match on "held" would fire again every frame the key stayed
    // down and bounce the player straight back out of the menu.
    private boolean isJustPressed(final Input input, final GameAction action)
    {
        return isAnyActive(bindings, action, binding -> wentDown(input, binding));
    }

    // Dispatches one binding onto the right GLFW query. The two device kinds
    // this port can answer are keys and mouse buttons; a touch region or a
    // gamepad binding in a desktop table reads as not-down rather than
    // throwing, so a shared scheme across platforms degrades instead of
    // crashing.
    private static boolean isDown(final Input input, final InputBinding binding)
    {
        if (binding.source() == InputBinding.Source.KEY)
        {
            return input.isKeyPressed(binding.code());
        }
        if (binding.source() == InputBinding.Source.MOUSE_BUTTON)
        {
            return input.isButtonPressed(binding.code());
        }
        return false;
    }

    // The edge-triggered counterpart of isDown.
    private static boolean wentDown(final Input input, final InputBinding binding)
    {
        if (binding.source() == InputBinding.Source.KEY)
        {
            return input.isKeyJustPressed(binding.code());
        }
        if (binding.source() == InputBinding.Source.MOUSE_BUTTON)
        {
            return input.isButtonJustPressed(binding.code());
        }
        return false;
    }

    // One frame of relative motion, in screen orientation. The accumulator
    // owns the units and the invert preference.
    //
    // THE VERTICAL DELTA IS NEGATED, and that is not a style choice. The
    // accumulator's contract is "+y downward", the screen convention, and it
    // negates once to reach positive-is-up. Feeding it getDeltaY() raw produced
    // a camera that plays inverted: pushing the mouse away aimed down. So this
    // backend hands over the sign the contract asks for rather than the sign it
    // happens to receive.
    //
    // Fixed here rather than in InputAccumulator because the accumulator is
    // shared with Android, where a drag up already tilts up correctly. Flipping
    // the shared multiply fixed the mouse and broke the touchscreen — the
    // Android look test caught it immediately, which is the whole argument for
    // putting a platform's correction inside that platform's adapter.
    //
    // A player who WANTS inverted look gets it from
    // InputAccumulator.setInvertPitch, which is a preference layered on top of
    // this and deliberately not the same knob.
    private void pollLook(final Input input)
    {
        if (discardLookPolls > 0)
        {
            discardLookPolls = discardLookPolls - 1;
            accumulator.resetLook();
            return;
        }
        accumulator.accumulateLook(input.getDeltaX(), -input.getDeltaY());
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
