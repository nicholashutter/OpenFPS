/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import com.openfps.engine.hal.adapter.nulladapter.NullWindowPort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link GdxFrameLoopListener}'s forwarding to the engine
 * callback, and for the UI state it owns.
 *
 * Only the callbacks that need no GL context are covered:
 * {@code pause/resume/resize/dispose} touch nothing but the engine
 * callback when the menu has not been built. {@code create()} and
 * {@code render()} read {@code Gdx.graphics}, which does not exist without
 * a display, so they are left to the manual windowed run — including the
 * per-frame {@link GdxInputPort#pollDevice()} call {@code render()} makes.
 *
 * <p>The UI state is a different matter and is covered properly. The
 * <i>decision</i> — is the menu drawn, does it hold the input processor, does
 * the input port agree — is plain Java and is asserted here. Only the two
 * calls it turns into, {@code stage.draw()} and {@code setInputProcessor},
 * need a window.</p>
 */
class GdxFrameLoopListenerTest
{
    private static GdxFrameLoopListener listenerFor(final RecordingFrameCallback callback)
    {
        return new GdxFrameLoopListener(callback, new DefaultMenuActions(new NullWindowPort()));
    }

    @Test
    @DisplayName("a null callback is rejected")
    void shouldRejectNullCallback()
    {
        assertThatThrownBy(() -> new GdxFrameLoopListener(null, new DefaultMenuActions(new NullWindowPort())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("callback");
    }

    @Test
    @DisplayName("null menu actions are rejected")
    void shouldRejectNullActions()
    {
        assertThatThrownBy(() -> new GdxFrameLoopListener(new RecordingFrameCallback(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("actions");
    }

    @Test
    @DisplayName("pause forwards to onPause")
    void shouldForwardPause()
    {
        final RecordingFrameCallback callback = new RecordingFrameCallback();
        listenerFor(callback).pause();
        assertThat(callback.pauseCount()).isEqualTo(1);
        assertThat(callback.resumeCount()).isZero();
    }

    @Test
    @DisplayName("resume forwards to onResume")
    void shouldForwardResume()
    {
        final RecordingFrameCallback callback = new RecordingFrameCallback();
        listenerFor(callback).resume();
        assertThat(callback.resumeCount()).isEqualTo(1);
        assertThat(callback.pauseCount()).isZero();
    }

    @Test
    @DisplayName("resize forwards to onResize even before the menu exists")
    void shouldForwardResize()
    {
        final RecordingFrameCallback callback = new RecordingFrameCallback();
        listenerFor(callback).resize(1024, 768);
        assertThat(callback.resizeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("an input port is optional — the listener still validates its other arguments")
    void shouldAcceptAnOptionalInputPort()
    {
        final RecordingFrameCallback callback = new RecordingFrameCallback();
        final DefaultMenuActions actions = new DefaultMenuActions(new NullWindowPort());
        assertThat(new GdxFrameLoopListener(callback, actions, null, new GdxInputPort()))
            .isNotNull();
        assertThat(new GdxFrameLoopListener(callback, actions, null, null)).isNotNull();
        assertThatThrownBy(
            () -> new GdxFrameLoopListener(null, actions, null, new GdxInputPort()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("callback");
    }

    @Test
    @DisplayName("dispose forwards to onSurfaceLost")
    void shouldForwardDispose()
    {
        final RecordingFrameCallback callback = new RecordingFrameCallback();
        listenerFor(callback).dispose();
        assertThat(callback.surfaceLostCount()).isEqualTo(1);
        assertThat(callback.surfaceReadyCount()).isZero();
        assertThat(callback.frameCount()).isZero();
    }

    @Nested
    @DisplayName("menu and game are mutually exclusive")
    class UiStateOwnership
    {
        @Test
        @DisplayName("a new window opens on the menu")
        void shouldStartOnTheMenu()
        {
            final GdxFrameLoopListener listener = listenerFor(new RecordingFrameCallback());
            assertThat(listener.uiState()).isNotNull();
            assertThat(listener.uiState().state()).isEqualTo(UiState.MENU);
            assertThat(listener.isMenuActive()).isTrue();
        }

        @Test
        @DisplayName("Start Game hides the menu and stops it consuming input")
        void shouldLeaveTheMenuBehindOnStartGame()
        {
            // isMenuActive() is the single predicate both gates read: the draw
            // path skips stage.act()/stage.draw() when it is false, and the
            // input path detaches the Scene2D processor. Asserting it here is
            // asserting both, without a GL context to draw into.
            final GdxFrameLoopListener listener = listenerFor(new RecordingFrameCallback());

            listener.menuActions().onStartGame();

            assertThat(listener.uiState().state()).isEqualTo(UiState.PLAYING);
            assertThat(listener.isMenuActive()).isFalse();
        }

        @Test
        @DisplayName("Escape brings the menu back")
        void shouldBringTheMenuBackOnReturn()
        {
            final GdxFrameLoopListener listener = listenerFor(new RecordingFrameCallback());
            listener.menuActions().onStartGame();

            // What GdxInputPort raises when it sees the Escape key.
            listener.uiState().returnToMenu();

            assertThat(listener.isMenuActive()).isTrue();
        }

        @Test
        @DisplayName("Settings and Quit are forwarded and change nothing about the UI state")
        void shouldNotTransitionOnTheOtherButtons()
        {
            final NullWindowPort window = new NullWindowPort();
            window.init();
            final GdxFrameLoopListener listener = new GdxFrameLoopListener(
                new RecordingFrameCallback(), new DefaultMenuActions(window));

            listener.menuActions().onSettings();
            assertThat(listener.uiState().state()).isEqualTo(UiState.MENU);

            listener.menuActions().onQuit();
            assertThat(window.isCloseRequested()).isTrue();
            assertThat(listener.uiState().state()).isEqualTo(UiState.MENU);
        }

        @Test
        @DisplayName("--start-in-game is recognised, and absent by default")
        void shouldReadTheStartInGameFlag()
        {
            // The flag exists because PLAYING is otherwise unreachable without a
            // human: a screenshot run can only ever photograph the menu, and the
            // menu sits exactly where a crosshair would be.
            assertThat(DesktopLauncher.startInGameArg(
                new String[] {"--fps=60", "--start-in-game"})).isTrue();
            assertThat(DesktopLauncher.startInGameArg(new String[] {"--fps=60"})).isFalse();
            assertThat(DesktopLauncher.startInGameArg(null)).isFalse();
        }

        @Test
        @DisplayName("the input port is given the same state machine the menu obeys")
        void shouldBindTheInputPortToTheSameMachine()
        {
            // Two copies of "is the game running" is how the old build ended up
            // capturing the cursor while the menu was still on screen.
            final GdxInputPort input = new GdxInputPort();
            final GdxFrameLoopListener listener = new GdxFrameLoopListener(
                new RecordingFrameCallback(), new DefaultMenuActions(new NullWindowPort()),
                null, input);

            assertThat(input.uiState()).isSameAs(listener.uiState());

            listener.menuActions().onStartGame();
            assertThat(input.uiState().isPlaying()).isTrue();
            input.pollDevice();
            assertThat(input.isCursorCaptureWanted()).isTrue();
        }
    }
}
