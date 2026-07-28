/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import com.openfps.engine.hal.port.I_InputPort;
import com.openfps.engine.hal.port.InputState;

import org.junit.jupiter.api.DisplayName;
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
 * <p><b>Not covered, and not coverable without a human at a keyboard:</b>
 * everything on the other side of {@code Gdx.input} — that GLFW reports the
 * per-frame deltas we believe it does, that {@code setCursorCatched(true)}
 * actually confines the pointer, that Escape releases it and a click takes it
 * back, that the key constants map to the keys with those letters on them, and
 * whether the resulting camera feels right. Those need
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
}
