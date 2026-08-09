/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import com.openfps.engine.hal.port.I_FrameCallback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link CompositeFrameCallback}, the fan-out that lets the single
 * callback {@code I_WindowPort} accepts drive both the engine and the menu.
 *
 * Nothing here touches the Android framework — the class under test imports
 * none — so this is ordinary JVM logic and is covered completely.
 */
class CompositeFrameCallbackTest
{
    /** Float comparison tolerance. */
    private static final float EPSILON = 1.0e-6f;

    /** Surface width used throughout. */
    private static final int WIDTH = 1080;

    /** Surface height used throughout. */
    private static final int HEIGHT = 2340;

    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        @DisplayName("a missing first callback is refused rather than silently halving the fan-out")
        void shouldRejectNullFirst()
        {
            assertThatThrownBy(() ->
                new CompositeFrameCallback(null, new RecordingFrameCallback()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("first");
        }

        @Test
        @DisplayName("a missing second callback is refused rather than silently halving the fan-out")
        void shouldRejectNullSecond()
        {
            assertThatThrownBy(() ->
                new CompositeFrameCallback(new RecordingFrameCallback(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("second");
        }
    }

    @Nested
    @DisplayName("fan-out")
    class FanOut
    {
        @Test
        @DisplayName("every lifecycle event reaches both callbacks exactly once")
        void shouldForwardEveryEventToBoth()
        {
            final RecordingFrameCallback engine = new RecordingFrameCallback();

            final RecordingFrameCallback menu = new RecordingFrameCallback();

            final CompositeFrameCallback composite = new CompositeFrameCallback(engine, menu);

            composite.onSurfaceReady(WIDTH, HEIGHT);

            composite.onFrame(0.016f);

            composite.onResize(HEIGHT, WIDTH);

            composite.onPause();

            composite.onResume();

            composite.onSurfaceLost();

            for (final RecordingFrameCallback recorder : List.of(engine, menu))
            {
                assertThat(recorder.surfaceReadyCount()).isEqualTo(1);

                assertThat(recorder.frameCount()).isEqualTo(1);

                assertThat(recorder.resizeCount()).isEqualTo(1);

                assertThat(recorder.pauseCount()).isEqualTo(1);

                assertThat(recorder.resumeCount()).isEqualTo(1);

                assertThat(recorder.surfaceLostCount()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("both callbacks are told the same dimensions and the same delta")
        void shouldForwardArgumentsUnchanged()
        {
            final RecordingFrameCallback engine = new RecordingFrameCallback();

            final RecordingFrameCallback menu = new RecordingFrameCallback();

            final CompositeFrameCallback composite = new CompositeFrameCallback(engine, menu);

            composite.onSurfaceReady(WIDTH, HEIGHT);

            composite.onFrame(0.032f);

            assertThat(engine.lastWidth()).isEqualTo(WIDTH);

            assertThat(engine.lastHeight()).isEqualTo(HEIGHT);

            assertThat(menu.lastWidth()).isEqualTo(WIDTH);

            assertThat(menu.lastHeight()).isEqualTo(HEIGHT);

            assertThat(engine.lastDeltaSeconds()).isCloseTo(0.032f, within(EPSILON));

            assertThat(menu.lastDeltaSeconds()).isCloseTo(0.032f, within(EPSILON));
        }
    }

    @Nested
    @DisplayName("ordering")
    class Ordering
    {
        @Test
        @DisplayName("the engine is given every event before the menu, teardown included")
        void shouldRunFirstBeforeSecondForEveryEvent()
        {
            // The class documents this as load-bearing in both directions: the
            // engine sees a frame before anything draws, and it hears the
            // surface is gone before the menu releases the GL resources it was
            // drawing with. Swapping the constructor arguments must break this.
            final List<String> log = new ArrayList<>();

            final CompositeFrameCallback composite = new CompositeFrameCallback(
                new RecordingFrameCallback("engine", log),
                new RecordingFrameCallback("menu", log));

            composite.onSurfaceReady(WIDTH, HEIGHT);

            composite.onFrame(0.016f);

            composite.onResize(HEIGHT, WIDTH);

            composite.onPause();

            composite.onResume();

            composite.onSurfaceLost();

            assertThat(log).containsExactly(
                "engine:onSurfaceReady", "menu:onSurfaceReady",
                "engine:onFrame", "menu:onFrame",
                "engine:onResize", "menu:onResize",
                "engine:onPause", "menu:onPause",
                "engine:onResume", "menu:onResume",
                "engine:onSurfaceLost", "menu:onSurfaceLost");
        }
    }

    @Nested
    @DisplayName("failure handling")
    class Failure
    {
        @Test
        @DisplayName("a callback that throws mid-frame surfaces instead of being swallowed")
        void shouldPropagateFailureFromTheFirstCallback()
        {
            final RecordingFrameCallback menu = new RecordingFrameCallback();

            final CompositeFrameCallback composite =
                new CompositeFrameCallback(new ExplodingFrameCallback(), menu);

            assertThatThrownBy(() -> composite.onFrame(0.016f))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");

            // And the second callback is not run behind a broken first one,
            // which would leave the two halves of the frame disagreeing.
            assertThat(menu.frameCount()).isZero();
        }
    }

    /** A callback whose frame always fails, standing in for a mid-frame bug. */
    private static final class ExplodingFrameCallback implements I_FrameCallback
    {
        @Override
        public void onSurfaceReady(final int width, final int height)
        {
            // not exercised
        }

        @Override
        public void onFrame(final float deltaSeconds)
        {
            throw new IllegalStateException("boom");
        }

        @Override
        public void onResize(final int width, final int height)
        {
            // not exercised
        }

        @Override
        public void onPause()
        {
            // not exercised
        }

        @Override
        public void onResume()
        {
            // not exercised
        }

        @Override
        public void onSurfaceLost()
        {
            // not exercised
        }
    }
}
