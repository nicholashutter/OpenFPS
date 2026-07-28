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
 * <h2>Cursor capture, and getting back out</h2>
 *
 * <p>The window starts <b>uncaught</b>, so the main menu is clickable the
 * moment it appears. A left click inside the window catches the cursor and
 * mouse-look begins. <b>Escape releases it</b> and clicking again re-catches
 * it. A captured cursor with no way out is a window the user cannot close,
 * cannot alt-tab away from cleanly, and cannot reach the Quit button in;
 * Escape is what stops this adapter from being that.</p>
 *
 * <p>While the cursor is free the accumulator is held at rest, so dragging the
 * mouse across the menu does not bank rotation that fires off the instant the
 * player clicks back in, and a key held at the moment of release does not walk
 * the player into a wall while they are in another application.</p>
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
        LOG.info("GdxInputPort initialized — click to capture the cursor, Escape to release "
            + "(sensitivity {} rad/px)", accumulator.radiansPerPixel());
    }

    @Override
    public void shutdown()
    {
        accumulator.clearAll();
        latched = InputState.NEUTRAL;
        releaseCursor();
        LOG.info("GdxInputPort shut down");
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
     * signals. Escape releases the cursor; it does not quit.
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
     * <p>Headless — in tests, or before the application starts — {@code
     * Gdx.input} is null and this does nothing, which is what leaves the port
     * usable in a windowless JVM.</p>
     */
    public void pollDevice()
    {
        final Input input = Gdx.input;
        if (input == null)
        {
            return;
        }
        updateCursorCapture(input);
        if (!input.isCursorCatched())
        {
            // Free cursor: the player is in the menu or another window. Bank
            // nothing, and forget anything still pending.
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

    // Escape lets go, a left click takes hold. Checked before anything else so
    // a frame never both releases the cursor and banks a delta from it.
    private void updateCursorCapture(final Input input)
    {
        if (input.isCursorCatched())
        {
            if (input.isKeyJustPressed(Input.Keys.ESCAPE))
            {
                input.setCursorCatched(false);
                accumulator.clearAll();
                LOG.debug("Cursor released — Escape");
            }
            return;
        }
        if (input.isButtonJustPressed(Input.Buttons.LEFT))
        {
            input.setCursorCatched(true);
            // The pointer is warped on capture, so the next frame's delta is
            // that warp and not the player's hand. Throw it away.
            discardNextLook = true;
            accumulator.resetLook();
            LOG.debug("Cursor captured — click");
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
