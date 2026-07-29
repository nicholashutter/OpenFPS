/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import com.openfps.engine.gameplay.MatchMode;
import com.openfps.engine.gameplay.MatchState;
import com.openfps.engine.gameplay.MatchSummary;
import com.openfps.engine.hal.adapter.nulladapter.NullWindowPort;
import com.openfps.gdx.DebugSettings;
import com.openfps.gdx.DefaultMenuActions;
import com.openfps.gdx.MenuActions;
import com.openfps.gdx.UiState;
import com.openfps.gdx.UiStateMachine;

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
        @DisplayName("Single Player and Multiplayer both enter the world, in different modes")
        void shouldRecordWhichKindOfMatchWasStarted()
        {
            // Both buttons make the same UI transition, so the state alone
            // cannot say which was pressed. The mode is what a networked match
            // is dispatched on, and reading it back is the only way to tell a
            // wiring mistake — both buttons on the same handler — from a
            // working menu, since the screen looks identical either way.
            final GdxFrameLoopListener single = listenerFor(new RecordingFrameCallback());
            single.menuActions().onStartGame();

            assertThat(single.uiState().state()).isEqualTo(UiState.PLAYING);
            assertThat(single.uiState().mode()).isEqualTo(MatchMode.SINGLE_PLAYER);

            final GdxFrameLoopListener networked = listenerFor(new RecordingFrameCallback());
            networked.menuActions().onMultiplayer();

            assertThat(networked.uiState().state()).isEqualTo(UiState.PLAYING);
            assertThat(networked.uiState().mode()).isEqualTo(MatchMode.MULTIPLAYER);
            assertThat(networked.isMenuActive()).isFalse();
        }

        @Test
        @DisplayName("a fresh machine reports single player before anything is started")
        void shouldDefaultToSinglePlayerBeforeAnyMatch()
        {
            assertThat(new UiStateMachine().mode()).isEqualTo(MatchMode.SINGLE_PLAYER);
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
        @DisplayName("Settings opens the settings screen, and Back returns")
        void shouldTransitionOnSettings()
        {
            // This button used to be inert — it logged "no settings screen yet"
            // and left the UI where it was. It now owns a transition of its own,
            // wrapped here exactly as Start Game is.
            final NullWindowPort window = new NullWindowPort();
            window.init();
            final GdxFrameLoopListener listener = new GdxFrameLoopListener(
                new RecordingFrameCallback(), new DefaultMenuActions(window));

            listener.menuActions().onSettings();
            assertThat(listener.uiState().state()).isEqualTo(UiState.SETTINGS);
            assertThat(listener.isMenuActive()).isFalse();

            // What the settings screen's Back button raises.
            listener.uiState().returnToMenu();
            assertThat(listener.uiState().state()).isEqualTo(UiState.MENU);
        }

        @Test
        @DisplayName("Quit is forwarded and changes nothing about the UI state")
        void shouldNotTransitionOnQuit()
        {
            final NullWindowPort window = new NullWindowPort();
            window.init();
            final GdxFrameLoopListener listener = new GdxFrameLoopListener(
                new RecordingFrameCallback(), new DefaultMenuActions(window));

            listener.menuActions().onQuit();
            assertThat(window.isCloseRequested()).isTrue();
            assertThat(listener.uiState().state()).isEqualTo(UiState.MENU);
        }

        @Test
        @DisplayName("every window gets a debug switch, shared or private")
        void shouldAlwaysHaveDebugSettings()
        {
            final NullWindowPort window = new NullWindowPort();
            window.init();
            final GdxFrameLoopListener listener = new GdxFrameLoopListener(
                new RecordingFrameCallback(), new DefaultMenuActions(window));

            // A window built without one keeps a private switch, so the settings
            // screen still works and nothing outside the window is told.
            assertThat(listener.debugSettings()).isNotNull();
            assertThat(listener.debugSettings().isOverlayVisible()).isFalse();
            assertThat(listener.debugOverlay()).isNotNull();

            final DebugSettings shared = new DebugSettings();
            final GdxFrameLoopListener wired = new GdxFrameLoopListener(
                new RecordingFrameCallback(), new DefaultMenuActions(window), null, null,
                shared);
            assertThat(wired.debugSettings()).isSameAs(shared);
        }

        @Test
        @DisplayName("a window with nothing to restart does not offer Play Again")
        void shouldNotOfferARematchWithoutSomethingToRestart()
        {
            // The presentation rule, and the reason it is a rule: a button that
            // appeared and did nothing would be exactly the "button that lies"
            // UiState refused a GAME_OVER -> PLAYING edge over for so long. The
            // --model= path and every windowless test have no match at all.
            final GdxFrameLoopListener listener =
                listenerFor(new RecordingFrameCallback());

            assertThat(listener.canRestart()).isFalse();

            listener.attachMatchRestart(() -> { });

            assertThat(listener.canRestart()).isTrue();
        }

        @Test
        @DisplayName("the world is restored BEFORE the UI enters it, never after")
        void shouldRestoreTheWorldBeforeTransitioning()
        {
            // The ordering is the whole of the correctness here and it is not
            // interchangeable. The UI transition is what un-freezes the match gate
            // and gives the cursor back, so a transition that landed first would
            // leave one or more frames in which the player is standing in the room
            // they just cleared, with seven invisible corpses, before the reset
            // arrived.
            //
            // Asserted by recording what the UI state WAS at the moment the restore
            // ran, which is the only way to observe an ordering from outside.
            final GdxFrameLoopListener listener =
                listenerFor(new RecordingFrameCallback());
            final UiStateMachine machine = listener.uiState();
            final UiState[] seenByTheRestore = new UiState[1];
            listener.attachMatchRestart(() -> seenByTheRestore[0] = machine.state());

            machine.startGame();
            machine.endMatch(new MatchSummary(MatchState.WON, 7, 1, 7, 21, 13, 44, 56));

            // The listener's own Play Again path — exactly what the button runs.
            listener.restartMatch();

            assertThat(seenByTheRestore[0])
                .as("the UI had already entered the world before the world was restored")
                .isEqualTo(UiState.GAME_OVER);
            assertThat(machine.state()).isEqualTo(UiState.PLAYING);
        }

        @Test
        @DisplayName("a rematch re-arms the end screen, so a second round can also finish")
        void shouldAllowASecondRoundToEnd()
        {
            // The guard that stops the end screen re-firing used to be latched for
            // the life of the process, because nothing could restore the round. It
            // is cleared by the restart and NOWHERE else — clearing it anywhere
            // else would let the end screen fire again for a round that had not
            // been restarted, which is the trap the latch existed to prevent.
            final GdxFrameLoopListener listener =
                listenerFor(new RecordingFrameCallback());
            final UiStateMachine machine = listener.uiState();
            listener.attachMatchRestart(() -> { });

            machine.startGame();
            for (int round = 0; round < 3; round++)
            {
                machine.endMatch(new MatchSummary(MatchState.WON, 7, round, 7, 21, 13, 44, 56));
                assertThat(machine.state()).isEqualTo(UiState.GAME_OVER);
                listener.restartMatch();
                assertThat(machine.state()).isEqualTo(UiState.PLAYING);
            }
        }

        @Test
        @DisplayName("Play Again with nothing attached leaves the UI where it was")
        void shouldDoNothingWhenThereIsNothingToRestart()
        {
            // Belt and braces beside canRestart(): if the button is ever offered
            // when it should not be, the honest outcome is that nothing happens
            // rather than a UI that claims to be in a world it never restored.
            final GdxFrameLoopListener listener =
                listenerFor(new RecordingFrameCallback());
            final UiStateMachine machine = listener.uiState();
            machine.startGame();
            machine.endMatch(new MatchSummary(MatchState.WON, 7, 1, 7, 21, 13, 44, 56));

            listener.restartMatch();

            assertThat(machine.state()).isEqualTo(UiState.GAME_OVER);
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
