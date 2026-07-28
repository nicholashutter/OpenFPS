/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.InputState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for the parts of {@link GdxInputPort} that survive without a display.
 *
 * <p><b>Covered here:</b> the port's contract with the engine — that
 * {@code sampleInput} latches, that {@code currentInput} is stable and pure
 * between latches, that a port which has never sampled reads neutral, and that
 * every entry point is safe to call in a JVM where {@code Gdx.input} does not
 * exist. The arithmetic those latches perform is covered far more thoroughly
 * by {@code InputAccumulatorTest}, which is exactly why the class was split.</p>
 *
 * <p>Also covered, and the reason this class grew: the port's half of the
 * {@link UiState} contract. Cursor capture is asked for only in
 * {@link UiState#PLAYING}, and <b>every</b> transition drops what the previous
 * state banked. Both are reachable headlessly on purpose —
 * {@code GdxInputPort.pollDevice()} reconciles the UI state before it looks at
 * {@code Gdx.input}, precisely so the rule that protects the first tic of play
 * is testable in CI rather than only by playing the game.</p>
 *
 * <p><b>Not covered, and not coverable without a human at a keyboard:</b>
 * everything on the other side of {@code Gdx.input} — that GLFW reports the
 * per-frame deltas we believe it does, that {@code setCursorCatched(true)}
 * actually confines the pointer, that pressing the Escape key is what raises
 * {@code PLAYING -> MENU}, that the key constants map to the keys with those
 * letters on them, and whether the resulting camera feels right. Those need
 * {@code gradlew :desktop:run}.</p>
 */
class GdxInputPortTest
{
    /** Float comparison tolerance. */
    private static final float EPSILON = 1.0e-5f;

    @Test
    @DisplayName("is an I_InputPort and starts neutral")
    void shouldStartNeutral()
    {
        final GdxInputPort port = new GdxInputPort();
        assertThat(port).isInstanceOf(I_InputPort.class);
        assertThat(port.currentInput()).isSameAs(InputState.NEUTRAL);
    }

    @Test
    @DisplayName("a null accumulator is rejected")
    void shouldRejectNullAccumulator()
    {
        assertThatThrownBy(() -> new GdxInputPort(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inputAccumulator");
    }

    @Test
    @DisplayName("the named sensitivity is the accumulator's, in radians per pixel")
    void shouldExposeTheSensitivityConstant()
    {
        assertThat(GdxInputPort.MOUSE_SENSITIVITY_RADIANS_PER_PIXEL)
            .isEqualTo(InputAccumulator.DEFAULT_RADIANS_PER_PIXEL)
            .isPositive();
        assertThat(new GdxInputPort().accumulator().radiansPerPixel())
            .isEqualTo(GdxInputPort.MOUSE_SENSITIVITY_RADIANS_PER_PIXEL);
    }

    @Test
    @DisplayName("sampleInput latches what the device half deposited")
    void shouldLatchAccumulatedInput()
    {
        final InputAccumulator accumulator = new InputAccumulator(1.0f);
        final GdxInputPort port = new GdxInputPort(accumulator);
        port.init();

        // Stand in for a frame's worth of polling: mouse right, W held.
        accumulator.accumulateLook(11, 0);
        accumulator.setMovementKeys(true, false, false, false);

        assertThat(port.currentInput()).isEqualTo(InputState.NEUTRAL);
        port.sampleInput(0);
        assertThat(port.currentInput().yawDelta()).isCloseTo(11.0f, within(EPSILON));
        assertThat(port.currentInput().forwardAxis()).isEqualTo(1.0f);
    }

    @Test
    @DisplayName("currentInput is pure — reading it never consumes the snapshot")
    void shouldReadTheSnapshotWithoutConsumingIt()
    {
        final InputAccumulator accumulator = new InputAccumulator(1.0f);
        final GdxInputPort port = new GdxInputPort(accumulator);
        accumulator.accumulateLook(6, 0);
        port.sampleInput(0);

        final InputState first = port.currentInput();
        assertThat(port.currentInput()).isSameAs(first);
        assertThat(port.currentInput()).isSameAs(first);
        assertThat(first.yawDelta()).isCloseTo(6.0f, within(EPSILON));
    }

    @Test
    @DisplayName("the next tic sees no rotation unless the device moved again")
    void shouldNotRepeatRotationOnTheNextTic()
    {
        final InputAccumulator accumulator = new InputAccumulator(1.0f);
        final GdxInputPort port = new GdxInputPort(accumulator);
        accumulator.accumulateLook(6, 0);

        port.sampleInput(0);
        assertThat(port.currentInput().yawDelta()).isCloseTo(6.0f, within(EPSILON));
        port.sampleInput(1);
        assertThat(port.currentInput().yawDelta()).isZero();
    }

    @Test
    @DisplayName("init and shutdown return the port to neutral")
    void shouldResetOnLifecycleCalls()
    {
        final InputAccumulator accumulator = new InputAccumulator(1.0f);
        final GdxInputPort port = new GdxInputPort(accumulator);
        accumulator.accumulateLook(30, 30);
        accumulator.setActionKeys(true, true, true);
        port.sampleInput(0);
        assertThat(port.currentInput().isNeutral()).isFalse();

        port.init();
        assertThat(port.currentInput()).isEqualTo(InputState.NEUTRAL);
        assertThat(accumulator.latch()).isEqualTo(InputState.NEUTRAL);

        accumulator.accumulateLook(5, 5);
        port.sampleInput(1);
        port.shutdown();
        assertThat(port.currentInput()).isEqualTo(InputState.NEUTRAL);
    }

    @Test
    @DisplayName("never requests shutdown — the window owns closing")
    void shouldNeverRequestShutdown()
    {
        final GdxInputPort port = new GdxInputPort();
        port.init();
        port.sampleInput(0);
        assertThat(port.isShutdownRequested()).isFalse();
        port.shutdown();
        assertThat(port.isShutdownRequested()).isFalse();
    }

    @Test
    @DisplayName("every entry point is safe with no libGDX application present")
    void shouldSurviveHeadless()
    {
        final GdxInputPort port = new GdxInputPort();
        assertThatCode(port::init).doesNotThrowAnyException();
        assertThatCode(port::pollDevice).doesNotThrowAnyException();
        assertThatCode(port::pollDevice).doesNotThrowAnyException();
        assertThatCode(port::shutdown).doesNotThrowAnyException();
        // Polling did nothing, so the snapshot is still neutral.
        port.sampleInput(0);
        assertThat(port.currentInput()).isEqualTo(InputState.NEUTRAL);
    }

    @Test
    @DisplayName("shutdown does not touch GLFW once the window has gone")
    void shouldNotReleaseTheCursorAfterTheWindowClosed()
    {
        // The bug this guards: Gdx.input outlives the GLFW window, so the null
        // check inside releaseCursor() passes and the call reaches a
        // terminated library. It fails with IncompatibleClassChangeError — an
        // Error, so nothing on the teardown path catches it, and a completely
        // successful run dies at the very last step.
        final GdxInputPort port = new GdxInputPort();
        port.init();
        assertThat(port.isWindowClosed()).isFalse();

        port.onWindowClosing();
        assertThat(port.isWindowClosed()).isTrue();

        assertThatCode(port::shutdown).doesNotThrowAnyException();
        assertThat(port.isWindowClosed()).isTrue();
    }

    @Test
    @DisplayName("a re-initialised port is ready for a second window")
    void shouldClearTheWindowFlagOnInit()
    {
        final GdxInputPort port = new GdxInputPort();
        port.init();
        port.onWindowClosing();
        port.shutdown();

        // GdxWindowPort supports SHUTDOWN -> INITIALIZED for a restart, so the
        // input port must not stay permanently convinced the window is gone.
        port.init();
        assertThat(port.isWindowClosed()).isFalse();
    }

    @Nested
    @DisplayName("cursor capture follows the UI state")
    class CursorCapture
    {
        @Test
        @DisplayName("an unbound port still has a state machine, parked in the menu")
        void shouldNeverExposeANullUiState()
        {
            final GdxInputPort port = new GdxInputPort();
            assertThat(port.uiState()).isNotNull();
            assertThat(port.uiState().state()).isEqualTo(UiState.MENU);
            assertThat(port.isCursorCaptureWanted()).isFalse();
        }

        @Test
        @DisplayName("a null state machine is rejected")
        void shouldRejectANullMachine()
        {
            assertThatThrownBy(() -> new GdxInputPort().bindUiState(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("machine");
        }

        @Test
        @DisplayName("in MENU the cursor is free, so the buttons are clickable")
        void shouldNotWantTheCursorInTheMenu()
        {
            final GdxInputPort port = new GdxInputPort();
            final UiStateMachine ui = new UiStateMachine();
            port.bindUiState(ui);
            port.init();

            port.pollDevice();

            assertThat(port.isCursorCaptureWanted()).isFalse();
        }

        @Test
        @DisplayName("starting the game asks for the cursor; Escape hands it back")
        void shouldTrackTheUiStateBothWays()
        {
            // The GLFW half — glfwSetInputMode actually confining the pointer —
            // needs a window and is manual-only. What is asserted here is the
            // decision that drives it, and that it is reversible: a captured
            // cursor with no way out is a window that cannot be closed with the
            // mouse at all.
            final GdxInputPort port = new GdxInputPort();
            final UiStateMachine ui = new UiStateMachine();
            port.bindUiState(ui);
            port.init();

            ui.startGame();
            port.pollDevice();
            assertThat(port.isCursorCaptureWanted()).isTrue();

            ui.returnToMenu();
            port.pollDevice();
            assertThat(port.isCursorCaptureWanted()).isFalse();
        }
    }

    @Nested
    @DisplayName("a UI transition drops everything banked under the old state")
    class BankedInput
    {
        @Test
        @DisplayName("MENU -> PLAYING: the first tic of play sees no rotation")
        void shouldNotFlushMenuMouseMotionIntoTheFirstTic()
        {
            // The bug this guards: reaching Start Game means sweeping the mouse
            // hundreds of pixels across the menu. If those pixels are still
            // banked when play begins, the first tic latches the lot and the
            // player's view snaps round before they have touched anything.
            final InputAccumulator accumulator = new InputAccumulator(1.0f);
            final GdxInputPort port = new GdxInputPort(accumulator);
            final UiStateMachine ui = new UiStateMachine();
            port.bindUiState(ui);
            port.init();

            // A mouse crossing the menu.
            accumulator.accumulateLook(420, -260);
            assertThat(accumulator.pendingYawPixels()).isEqualTo(420);
            assertThat(accumulator.pendingPitchPixels()).isEqualTo(-260);

            ui.startGame();
            port.pollDevice();
            port.sampleInput(0);

            assertThat(port.currentInput().yawDelta()).isZero();
            assertThat(port.currentInput().pitchDelta()).isZero();
            assertThat(port.currentInput().isNeutral()).isTrue();
        }

        @Test
        @DisplayName("MENU -> PLAYING: keys held over the menu do not start the player walking")
        void shouldNotFlushMenuKeysIntoTheFirstTic()
        {
            final InputAccumulator accumulator = new InputAccumulator(1.0f);
            final GdxInputPort port = new GdxInputPort(accumulator);
            final UiStateMachine ui = new UiStateMachine();
            port.bindUiState(ui);
            port.init();

            accumulator.setMovementKeys(true, false, false, true);
            accumulator.setActionKeys(true, true, true);

            ui.startGame();
            port.pollDevice();
            port.sampleInput(0);

            assertThat(port.currentInput()).isEqualTo(InputState.NEUTRAL);
        }

        @Test
        @DisplayName("PLAYING -> MENU: a key held at the moment of Escape stops moving the player")
        void shouldNotKeepWalkingAfterEscape()
        {
            final InputAccumulator accumulator = new InputAccumulator(1.0f);
            final GdxInputPort port = new GdxInputPort(accumulator);
            final UiStateMachine ui = new UiStateMachine();
            port.bindUiState(ui);
            port.init();
            ui.startGame();
            port.pollDevice();

            // Forward held and the mouse moving, then Escape.
            accumulator.setMovementKeys(true, false, false, false);
            accumulator.accumulateLook(40, 40);

            ui.returnToMenu();
            port.pollDevice();
            port.sampleInput(0);

            assertThat(port.currentInput()).isEqualTo(InputState.NEUTRAL);
        }

        @Test
        @DisplayName("binding a machine drops whatever the previous one banked")
        void shouldClearOnBind()
        {
            final InputAccumulator accumulator = new InputAccumulator(1.0f);
            final GdxInputPort port = new GdxInputPort(accumulator);
            accumulator.accumulateLook(90, 90);

            port.bindUiState(new UiStateMachine());

            assertThat(accumulator.pendingYawPixels()).isZero();
            assertThat(accumulator.pendingPitchPixels()).isZero();
        }
    }
}
