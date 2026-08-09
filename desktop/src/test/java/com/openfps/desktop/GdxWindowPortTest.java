/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link GdxWindowPort}'s lifecycle state machine and close flag.
 *
 * <b>Nothing here opens a window.</b> CI has no display, and
 * {@code Lwjgl3Application} would fail to create a GLFW context, so
 * {@link GdxWindowPort#runFrameLoop} is never called with valid state — it
 * is only exercised on the rejection paths, which return before any native
 * call. Everything else about the port is plain Java state and is covered
 * in full.
 */
class GdxWindowPortTest
{
    @Nested
    @DisplayName("lifecycle ordering")
    class Lifecycle
    {
        @Test
        @DisplayName("a fresh port is NEW")
        void shouldStartNew()
        {
            assertThat(new GdxWindowPort().state()).isEqualTo(GdxWindowPort.State.NEW);
        }

        @Test
        @DisplayName("init then create then shutdown walks the states in order")
        void shouldAdvanceThroughStatesInOrder()
        {
            final GdxWindowPort port = new GdxWindowPort();

            port.init();

            assertThat(port.state()).isEqualTo(GdxWindowPort.State.INITIALIZED);

            port.create(800, 600, "OpenFPS Test");

            assertThat(port.state()).isEqualTo(GdxWindowPort.State.CREATED);

            port.shutdown();

            assertThat(port.state()).isEqualTo(GdxWindowPort.State.SHUTDOWN);
        }

        @Test
        @DisplayName("create before init is rejected")
        void shouldRejectCreateBeforeInit()
        {
            final GdxWindowPort port = new GdxWindowPort();

            assertThatThrownBy(() -> port.create(800, 600, "OpenFPS Test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NEW");

            assertThat(port.state()).isEqualTo(GdxWindowPort.State.NEW);
        }

        @Test
        @DisplayName("create after shutdown is rejected until init runs again")
        void shouldRejectCreateAfterShutdown()
        {
            final GdxWindowPort port = new GdxWindowPort();

            port.init();

            port.create(800, 600, "OpenFPS Test");

            port.shutdown();

            assertThatThrownBy(() -> port.create(800, 600, "OpenFPS Test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHUTDOWN");
        }

        @Test
        @DisplayName("init after shutdown restarts the port")
        void shouldAllowRestartAfterShutdown()
        {
            final GdxWindowPort port = new GdxWindowPort();

            port.init();

            port.shutdown();

            port.init();

            assertThat(port.state()).isEqualTo(GdxWindowPort.State.INITIALIZED);

            port.create(640, 480, "Restarted");

            assertThat(port.state()).isEqualTo(GdxWindowPort.State.CREATED);
        }

        @Test
        @DisplayName("shutdown is idempotent")
        void shouldTolerateRepeatedShutdown()
        {
            final GdxWindowPort port = new GdxWindowPort();

            port.init();

            port.shutdown();

            port.shutdown();

            assertThat(port.state()).isEqualTo(GdxWindowPort.State.SHUTDOWN);
        }
    }

    @Nested
    @DisplayName("create() argument validation")
    class CreateArguments
    {
        @Test
        @DisplayName("a non-positive width is rejected")
        void shouldRejectNonPositiveWidth()
        {
            final GdxWindowPort port = new GdxWindowPort();

            port.init();

            assertThatThrownBy(() -> port.create(0, 600, "OpenFPS Test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        }

        @Test
        @DisplayName("a non-positive height is rejected")
        void shouldRejectNonPositiveHeight()
        {
            final GdxWindowPort port = new GdxWindowPort();

            port.init();

            assertThatThrownBy(() -> port.create(800, -1, "OpenFPS Test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        }

        @Test
        @DisplayName("a null title is rejected")
        void shouldRejectNullTitle()
        {
            final GdxWindowPort port = new GdxWindowPort();

            port.init();

            assertThatThrownBy(() -> port.create(800, 600, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
        }

        @Test
        @DisplayName("a rejected create leaves the port INITIALIZED")
        void shouldNotAdvanceStateOnRejectedCreate()
        {
            final GdxWindowPort port = new GdxWindowPort();

            port.init();

            assertThatThrownBy(() -> port.create(-1, -1, "OpenFPS Test"))
                .isInstanceOf(IllegalArgumentException.class);

            assertThat(port.state()).isEqualTo(GdxWindowPort.State.INITIALIZED);
        }
    }

    @Nested
    @DisplayName("close flag")
    class CloseFlag
    {
        @Test
        @DisplayName("close is not requested on a fresh port")
        void shouldNotBeCloseRequestedInitially()
        {
            assertThat(new GdxWindowPort().isCloseRequested()).isFalse();
        }

        @Test
        @DisplayName("requestClose sets the flag")
        void shouldBeCloseRequestedAfterRequestClose()
        {
            final GdxWindowPort port = new GdxWindowPort();

            port.init();

            assertThat(port.isCloseRequested()).isFalse();

            port.requestClose();

            assertThat(port.isCloseRequested()).isTrue();
        }

        @Test
        @DisplayName("requestClose is honoured before init, with no window alive")
        void shouldHonourRequestCloseBeforeInit()
        {
            final GdxWindowPort port = new GdxWindowPort();

            port.requestClose();

            assertThat(port.isCloseRequested()).isTrue();
        }

        @Test
        @DisplayName("init clears a stale close request")
        void shouldClearCloseFlagOnInit()
        {
            final GdxWindowPort port = new GdxWindowPort();

            port.requestClose();

            port.init();

            assertThat(port.isCloseRequested()).isFalse();
        }
    }

    @Nested
    @DisplayName("runFrameLoop guards")
    class FrameLoopGuards
    {
        @Test
        @DisplayName("a null callback is rejected before any native call")
        void shouldRejectNullCallback()
        {
            final GdxWindowPort port = new GdxWindowPort();

            port.init();

            port.create(800, 600, "OpenFPS Test");

            assertThatThrownBy(() -> port.runFrameLoop(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("callback");

            assertThat(port.state()).isEqualTo(GdxWindowPort.State.CREATED);
        }

        @Test
        @DisplayName("running before create is rejected")
        void shouldRejectFrameLoopBeforeCreate()
        {
            final GdxWindowPort port = new GdxWindowPort();

            port.init();

            assertThatThrownBy(() -> port.runFrameLoop(new RecordingFrameCallback()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INITIALIZED");
        }

        @Test
        @DisplayName("running after shutdown is rejected")
        void shouldRejectFrameLoopAfterShutdown()
        {
            final GdxWindowPort port = new GdxWindowPort();

            port.init();

            port.create(800, 600, "OpenFPS Test");

            port.shutdown();

            assertThatThrownBy(() -> port.runFrameLoop(new RecordingFrameCallback()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHUTDOWN");
        }
    }

    @Test
    @DisplayName("the GLFW-backed port always claims a real window")
    void shouldAlwaysReportRealWindow()
    {
        assertThat(new GdxWindowPort().isRealWindow()).isTrue();
    }

    @Nested
    @DisplayName("input attachment")
    class InputAttachment
    {
        @Test
        @DisplayName("a fresh port has no input attached")
        void shouldStartWithNoInput()
        {
            assertThat(new GdxWindowPort().inputPort()).isNull();
        }

        @Test
        @DisplayName("the attached port is the one the frame loop will be given")
        void shouldRememberTheAttachedPort()
        {
            final GdxWindowPort port = new GdxWindowPort();

            final GdxInputPort input = new GdxInputPort();

            port.init();

            port.attachInput(input);

            assertThat(port.inputPort()).isSameAs(input);
        }

        @Test
        @DisplayName("attaching null leaves a window that reads no input")
        void shouldAcceptNull()
        {
            final GdxWindowPort port = new GdxWindowPort();

            port.attachInput(new GdxInputPort());

            port.attachInput(null);

            assertThat(port.inputPort()).isNull();
        }

        @Test
        @DisplayName("attachment does not disturb the lifecycle state")
        void shouldNotAdvanceState()
        {
            final GdxWindowPort port = new GdxWindowPort();

            port.attachInput(new GdxInputPort());

            assertThat(port.state()).isEqualTo(GdxWindowPort.State.NEW);
        }
    }
}
