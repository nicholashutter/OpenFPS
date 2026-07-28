/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import com.badlogic.gdx.Gdx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link GdxLifecycleBridge}, the translation from libGDX's
 * {@code ApplicationListener} to the engine's {@code I_FrameCallback}.
 *
 * <b>Nothing here has a GL context.</b> The bridge reads three values from the
 * global {@code Gdx.graphics} and does nothing else with the framework, so
 * {@link StubGraphics} supplies those three and the translation itself — which
 * is where the one piece of real logic lives — is covered on a plain JVM.
 *
 * <b>Not covered:</b> that libGDX really does call {@code create()} once per
 * Activity and follow it with an identical {@code resize()}, and that a
 * context loss really arrives as {@code pause()}/{@code resume()} with no
 * second {@code create()}. Those are facts about the backend, not about this
 * class, and only a device can demonstrate them.
 */
class GdxLifecycleBridgeTest
{
    /** Float comparison tolerance. */
    private static final float EPSILON = 1.0e-6f;

    /** Portrait surface width, as a phone reports it. */
    private static final int WIDTH = 1080;

    /** Portrait surface height. */
    private static final int HEIGHT = 2340;

    /** Frame delta the stubbed graphics reports. */
    private static final float DELTA = 0.016666f;

    /** The callback the bridge drives. MUTABLE: fresh per test. */
    private RecordingFrameCallback callback;

    /** The bridge under test. MUTABLE: fresh per test. */
    private GdxLifecycleBridge bridge;

    /** Installs a stand-in for the device's graphics and a fresh bridge. */
    @BeforeEach
    void setUp()
    {
        Gdx.graphics = StubGraphics.of(WIDTH, HEIGHT, DELTA);
        callback = new RecordingFrameCallback();
        bridge = new GdxLifecycleBridge(callback);
    }

    /** Removes the stand-in so no other test inherits a fake device. */
    @AfterEach
    void tearDown()
    {
        Gdx.graphics = null;
    }

    @Nested
    @DisplayName("surface creation")
    class Creation
    {
        @Test
        @DisplayName("the engine is told the size the device actually gave, not the size asked for")
        void shouldReportTheRealSurfaceSize()
        {
            bridge.create();

            assertThat(callback.surfaceReadyCount()).isEqualTo(1);
            assertThat(callback.lastWidth()).isEqualTo(WIDTH);
            assertThat(callback.lastHeight()).isEqualTo(HEIGHT);
        }
    }

    @Nested
    @DisplayName("resize suppression")
    class ResizeSuppression
    {
        @Test
        @DisplayName("libGDX's redundant first resize does not make the engine rebuild its viewport")
        void shouldSwallowTheResizeThatFollowsCreate()
        {
            bridge.create();

            bridge.resize(WIDTH, HEIGHT);

            assertThat(callback.resizeCount()).isZero();
        }

        @Test
        @DisplayName("a real rotation reaches the engine")
        void shouldForwardAGenuineResize()
        {
            bridge.create();

            bridge.resize(HEIGHT, WIDTH);

            assertThat(callback.resizeCount()).isEqualTo(1);
            assertThat(callback.lastWidth()).isEqualTo(HEIGHT);
            assertThat(callback.lastHeight()).isEqualTo(WIDTH);
        }

        @Test
        @DisplayName("onResize stays a genuine 'the surface changed' signal, never a repeat")
        void shouldSwallowARepeatOfTheCurrentSize()
        {
            bridge.create();
            bridge.resize(HEIGHT, WIDTH);
            bridge.resize(HEIGHT, WIDTH);
            bridge.resize(HEIGHT, WIDTH);

            assertThat(callback.resizeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("rotating back to the original size is a change, not a repeat")
        void shouldForwardAReturnToTheCreateSize()
        {
            bridge.create();
            bridge.resize(HEIGHT, WIDTH);

            bridge.resize(WIDTH, HEIGHT);

            assertThat(callback.resizeCount()).isEqualTo(2);
            assertThat(callback.lastWidth()).isEqualTo(WIDTH);
            assertThat(callback.lastHeight()).isEqualTo(HEIGHT);
        }
    }

    @Nested
    @DisplayName("frame and lifecycle translation")
    class Translation
    {
        @Test
        @DisplayName("each rendered frame carries the platform's own delta to the engine")
        void shouldForwardThePlatformDelta()
        {
            bridge.render();
            bridge.render();

            assertThat(callback.frameCount()).isEqualTo(2);
            assertThat(callback.lastDeltaSeconds()).isCloseTo(DELTA, within(EPSILON));
        }

        @Test
        @DisplayName("pause, resume and dispose map one-to-one onto the engine's callback")
        void shouldTranslateTheRemainingLifecycle()
        {
            bridge.pause();
            bridge.resume();
            bridge.dispose();

            assertThat(callback.pauseCount()).isEqualTo(1);
            assertThat(callback.resumeCount()).isEqualTo(1);
            assertThat(callback.surfaceLostCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a context rebuild is a pause/resume pair and never a second surface-ready")
        void shouldNotReportTheSurfaceReadyTwice()
        {
            // libGDX issues create() once per Activity. Anything allocated in
            // onSurfaceReady has to survive the gap on its own, and that only
            // holds while this stays true.
            bridge.create();
            bridge.pause();
            bridge.resume();

            assertThat(callback.surfaceReadyCount()).isEqualTo(1);
        }
    }
}
