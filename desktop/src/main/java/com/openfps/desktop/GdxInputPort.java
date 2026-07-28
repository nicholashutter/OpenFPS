/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;

import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.InputState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real desktop input: mouse-look and WASD over libGDX's LWJGL3 backend.
 *
 * <p>The class is deliberately two thin halves. This one knows about
 * {@code Gdx.input} and nothing else — which key constant means "forward",
 * how to catch the cursor, when GLFW's numbers are worth believing. All the
 * arithmetic and all the cross-thread bookkeeping live in
 * {@link InputAccumulator}, which has no platform imports and is therefore the
 * part CI can actually test. Everything below the seam needs a human at a
 * keyboard.</p>
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
     * Drop the next frame's mouse delta.
     * MUTABLE: set when the cursor is caught, cleared by the next poll. Only
     * ever touched on the render thread.
     */
    private boolean discardNextLook;

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

    /** Creates a port at the default sensitivity. */
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

    @Override
    public void init()
    {
        accumulator.clearAll();
        latched = InputState.NEUTRAL;
        discardNextLook = false;
        windowClosed = false;
        appliedState = uiState.state();
        LOG.info("GdxInputPort initialized — Start Game captures the cursor, Escape returns to "
            + "the menu (sensitivity {} rad/px)", accumulator.radiansPerPixel());
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
        if (input.isKeyJustPressed(Input.Keys.ESCAPE))
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
        // Capture warps the pointer to the window centre, so the first delta
        // reported afterwards is the warp and not a hand movement.
        discardNextLook = true;
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

    // WASD to the movement axes; mouse-left / space / left-shift to the
    // actions. Left control is accepted for fire as well so the port is usable
    // on a trackpad.
    private void pollKeys(final Input input)
    {
        accumulator.setMovementKeys(
            input.isKeyPressed(Input.Keys.W),
            input.isKeyPressed(Input.Keys.S),
            input.isKeyPressed(Input.Keys.A),
            input.isKeyPressed(Input.Keys.D));
        accumulator.setActionKeys(
            input.isButtonPressed(Input.Buttons.LEFT)
                || input.isKeyPressed(Input.Keys.CONTROL_LEFT),
            input.isKeyPressed(Input.Keys.SPACE),
            input.isKeyPressed(Input.Keys.SHIFT_LEFT));
    }

    // One frame of relative motion, in screen orientation. The accumulator
    // owns the units and the pitch sign.
    private void pollLook(final Input input)
    {
        if (discardNextLook)
        {
            discardNextLook = false;
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
