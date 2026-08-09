/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;

import com.openfps.engine.hal.port.I_WindowPort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link AndroidWindowPort}'s lifecycle flags, its close flag, and
 * what it hands to libGDX when the frame loop starts.
 *
 * <b>Nothing here starts a render thread.</b> The port reaches the framework
 * in exactly one place — {@code application.initialize(...)} — and
 * {@link FakeAndroidApplication} overrides that one method, which is the same
 * seam the desktop tests use in reverse: they never enter
 * {@code runFrameLoop} because the framework call is the whole body, while
 * here the framework call is one line and everything around it is plain Java.
 *
 * <b>Not covered:</b> that {@code requestClose()} actually finishes the
 * Activity. {@code Activity.runOnUiThread} is final and its unit-test stub
 * does nothing, so the posted {@code finish()} is unobservable off-device.
 */
class AndroidWindowPortTest
{
    /** Nominal surface width, as AndroidLauncher passes it. */
    private static final int WIDTH = 1280;

    /** Nominal surface height, as AndroidLauncher passes it. */
    private static final int HEIGHT = 720;

    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        @DisplayName("a port with no Activity behind it is refused at construction")
        void shouldRejectNullApplication()
        {
            assertThatThrownBy(() -> new AndroidWindowPort(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("application");
        }

        @Test
        @DisplayName("is an I_WindowPort and reports a real window, not a headless stand-in")
        void shouldBeARealWindowPort()
        {
            final AndroidWindowPort port = new AndroidWindowPort(new FakeAndroidApplication());

            assertThat(port).isInstanceOf(I_WindowPort.class);

            assertThat(port.isRealWindow()).isTrue();
        }
    }

    @Nested
    @DisplayName("close flag")
    class CloseFlag
    {
        @Test
        @DisplayName("a fresh port is not asking to close")
        void shouldStartOpen()
        {
            assertThat(new AndroidWindowPort(new FakeAndroidApplication()).isCloseRequested())
                .isFalse();
        }

        @Test
        @DisplayName("requestClose is visible to the engine on whatever thread reads it")
        void shouldRecordACloseRequest()
        {
            final AndroidWindowPort port = new AndroidWindowPort(new FakeAndroidApplication());

            port.init();

            port.requestClose();

            assertThat(port.isCloseRequested()).isTrue();
        }

        @Test
        @DisplayName("two threads asking to close on the same frame leaves one close request")
        void shouldBeIdempotent()
        {
            // The port uses compareAndSet precisely so the Activity is
            // finished once. Only the flag is observable here; that the second
            // call takes the no-op branch is what this pins down.
            final AndroidWindowPort port = new AndroidWindowPort(new FakeAndroidApplication());

            port.init();

            port.requestClose();

            port.requestClose();

            assertThat(port.isCloseRequested()).isTrue();
        }

        @Test
        @DisplayName("a close asked for before the surface exists is still honoured")
        void shouldHonourCloseBeforeInit()
        {
            final AndroidWindowPort port = new AndroidWindowPort(new FakeAndroidApplication());

            port.requestClose();

            assertThat(port.isCloseRequested()).isTrue();
        }

        @Test
        @DisplayName("a relaunched Activity does not inherit the previous close request")
        void shouldClearTheCloseFlagOnInit()
        {
            final AndroidWindowPort port = new AndroidWindowPort(new FakeAndroidApplication());

            port.requestClose();

            port.init();

            assertThat(port.isCloseRequested()).isFalse();
        }

        @Test
        @DisplayName("shutdown leaves the port reusable rather than permanently closing")
        void shouldClearTheCloseFlagOnShutdown()
        {
            final AndroidWindowPort port = new AndroidWindowPort(new FakeAndroidApplication());

            port.init();

            port.requestClose();

            port.shutdown();

            assertThat(port.isCloseRequested()).isFalse();
        }
    }

    @Nested
    @DisplayName("frame loop registration")
    class FrameLoop
    {
        @Test
        @DisplayName("starting the loop registers with the framework and returns to the UI thread")
        void shouldRegisterAndReturn()
        {
            // The whole reason this port exists in this shape: blocking here
            // would park the thread that has to return to the looper before a
            // single frame can be scheduled, and trip the ANR watchdog. The
            // test completing at all is the assertion that it returned.
            final FakeAndroidApplication application = new FakeAndroidApplication();

            final AndroidWindowPort port = new AndroidWindowPort(application);

            port.init();

            port.create(WIDTH, HEIGHT, "OpenFPS");

            port.runFrameLoop(new RecordingFrameCallback());

            assertThat(application.initializeCount()).isEqualTo(1);

            assertThat(application.registeredListener()).isNotNull();
        }

        @Test
        @DisplayName("the engine callback is driven through the libGDX lifecycle bridge")
        void shouldWrapTheCallbackInTheBridge()
        {
            final FakeAndroidApplication application = new FakeAndroidApplication();

            final AndroidWindowPort port = new AndroidWindowPort(application);

            final RecordingFrameCallback callback = new RecordingFrameCallback();

            port.init();

            port.runFrameLoop(callback);

            assertThat(application.registeredListener()).isInstanceOf(GdxLifecycleBridge.class);

            // Type alone would not prove the callback is the one wired in, so
            // drive a lifecycle event libGDX would deliver and watch it land.
            application.registeredListener().pause();

            assertThat(callback.pauseCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a null callback is refused before the framework is touched")
        void shouldRejectNullCallback()
        {
            final FakeAndroidApplication application = new FakeAndroidApplication();

            final AndroidWindowPort port = new AndroidWindowPort(application);

            port.init();

            assertThatThrownBy(() -> port.runFrameLoop(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("callback");

            assertThat(application.initializeCount()).isZero();

            // The rejection must not burn the one-shot guard either.
            assertThatCode(() -> port.runFrameLoop(new RecordingFrameCallback()))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("one Activity cannot install a second content view")
        void shouldRejectASecondFrameLoop()
        {
            final FakeAndroidApplication application = new FakeAndroidApplication();

            final AndroidWindowPort port = new AndroidWindowPort(application);

            port.init();

            port.runFrameLoop(new RecordingFrameCallback());

            assertThatThrownBy(() -> port.runFrameLoop(new RecordingFrameCallback()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already started");

            assertThat(application.initializeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a destroyed and relaunched Activity may start the loop again")
        void shouldAllowRestartAfterShutdown()
        {
            final FakeAndroidApplication application = new FakeAndroidApplication();

            final AndroidWindowPort port = new AndroidWindowPort(application);

            port.init();

            port.runFrameLoop(new RecordingFrameCallback());

            port.shutdown();

            port.init();

            assertThatCode(() -> port.runFrameLoop(new RecordingFrameCallback()))
                .doesNotThrowAnyException();

            assertThat(application.initializeCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("the requested size is only advisory — asking for one does not start a loop")
        void shouldTreatCreateAsAdvisory()
        {
            // Android supplies the real surface size at onSurfaceReady, so
            // create() records the request and nothing more; the loop guard
            // must still be untouched afterwards.
            final FakeAndroidApplication application = new FakeAndroidApplication();

            final AndroidWindowPort port = new AndroidWindowPort(application);

            port.init();

            port.create(WIDTH, HEIGHT, "OpenFPS");

            assertThat(application.initializeCount()).isZero();

            assertThat(port.isCloseRequested()).isFalse();

            assertThatCode(() -> port.runFrameLoop(new RecordingFrameCallback()))
                .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("surface configuration")
    class SurfaceConfiguration
    {
        @Test
        @DisplayName("the surface is opaque 8888 with a depth buffer and no multisampling")
        void shouldRequestAnOpaqueEightBitSurface()
        {
            // 8888 rather than libGDX's RGB565 default, which bands on flat UI
            // fills; alpha 0 so the compositor can skip blending the surface.
            final AndroidApplicationConfiguration config = startAndCaptureConfig();

            assertThat(config.r).isEqualTo(8);

            assertThat(config.g).isEqualTo(8);

            assertThat(config.b).isEqualTo(8);

            assertThat(config.a).isZero();

            assertThat(config.depth).isEqualTo(16);

            assertThat(config.stencil).isZero();

            assertThat(config.numSamples).isZero();
        }

        @Test
        @DisplayName("GLES 3.0 is asked for, matching what the manifest declares as required")
        void shouldRequestGl30()
        {
            assertThat(startAndCaptureConfig().useGL30).isTrue();
        }

        @Test
        @DisplayName("no sensor is left running for a HAL that never reads one")
        void shouldLeaveEverySensorOff()
        {
            // Input reaches the engine through I_InputPort; every enabled
            // sensor is battery spent on data nothing consumes.
            final AndroidApplicationConfiguration config = startAndCaptureConfig();

            assertThat(config.useAccelerometer).isFalse();

            assertThat(config.useCompass).isFalse();

            assertThat(config.useGyroscope).isFalse();
        }

        // Starts one frame loop and returns the configuration the port built.
        private AndroidApplicationConfiguration startAndCaptureConfig()
        {
            final FakeAndroidApplication application = new FakeAndroidApplication();

            final AndroidWindowPort port = new AndroidWindowPort(application);

            port.init();

            port.runFrameLoop(new RecordingFrameCallback());

            return application.registeredConfig();
        }
    }
}
