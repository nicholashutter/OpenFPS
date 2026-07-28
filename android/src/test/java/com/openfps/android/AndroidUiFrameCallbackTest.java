/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import java.util.ArrayList;
import java.util.List;

import com.openfps.engine.gameplay.MatchMode;
import com.openfps.gdx.DefaultMenuActions;
import com.openfps.gdx.MenuActions;
import com.openfps.gdx.UiState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link AndroidUiFrameCallback} — which half of the UI is in front,
 * and everything that follows from the answer.
 *
 * <p>Nothing here draws. What is covered is the decision layer: the transition
 * a button press causes, the match gate that freezes the bots while the menu is
 * up, and the leave path. All three are logic, all three have been wrong on
 * desktop at some point, and none of them needs a screen.</p>
 */
@DisplayName("AndroidUiFrameCallback")
class AndroidUiFrameCallbackTest
{
    /** A UI with no renderer and no input — the menu-only shape. */
    private static AndroidUiFrameCallback menuOnly()
    {
        return new AndroidUiFrameCallback(new DefaultMenuActions(
            new AndroidWindowPort(new FakeAndroidApplication())));
    }

    /** Records what the menu was asked to do. */
    private static final class RecordingActions implements MenuActions
    {
        private final List<String> calls = new ArrayList<>();

        @Override
        public void onStartGame()
        {
            calls.add("startGame");
        }

        @Override
        public void onMultiplayer()
        {
            calls.add("multiplayer");
        }

        @Override
        public void onSettings()
        {
            calls.add("settings");
        }

        @Override
        public void onQuit()
        {
            calls.add("quit");
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        @DisplayName("a UI with no actions is refused")
        void shouldRejectNullActions()
        {
            assertThatThrownBy(() -> new AndroidUiFrameCallback(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("menuActions");
        }

        @Test
        @DisplayName("an Activity opens on the menu")
        void shouldStartInTheMenu()
        {
            final AndroidUiFrameCallback ui = menuOnly();

            assertThat(ui.uiState().state()).isEqualTo(UiState.MENU);
            assertThat(ui.isMenuActive()).isTrue();
        }

        @Test
        @DisplayName("the input port is told which UI state to obey")
        void shouldBindTheInputPortToTheUiState()
        {
            // Both halves must read the same answer, or the port banks touches
            // the menu is also acting on.
            final AndroidInputPort input = new AndroidInputPort(2.0f);
            final AndroidUiFrameCallback ui = new AndroidUiFrameCallback(
                new RecordingActions(), null, input);

            assertThat(input.uiState()).isSameAs(ui.uiState());
        }
    }

    @Nested
    @DisplayName("entering and leaving a match")
    class Transitions
    {
        @Test
        @DisplayName("Single Player runs the action and then enters the world")
        void shouldEnterTheWorldOnStartGame()
        {
            final RecordingActions actions = new RecordingActions();
            final AndroidUiFrameCallback ui =
                new AndroidUiFrameCallback(actions, null, null);

            ui.uiState().startGame(MatchMode.SINGLE_PLAYER);

            assertThat(ui.isMenuActive()).isFalse();
            assertThat(ui.uiState().mode()).isEqualTo(MatchMode.SINGLE_PLAYER);
            assertThat(actions.calls).isEmpty();
        }

        @Test
        @DisplayName("Multiplayer records the mode the match is in")
        void shouldRecordMultiplayerMode()
        {
            final AndroidUiFrameCallback ui = menuOnly();

            ui.uiState().startGame(MatchMode.MULTIPLAYER);

            assertThat(ui.uiState().mode()).isEqualTo(MatchMode.MULTIPLAYER);
        }

        @Test
        @DisplayName("the leave button returns to the menu on the next frame")
        void shouldReturnToTheMenuOnLeave()
        {
            final AndroidInputPort input = new AndroidInputPort(2.0f);
            final AndroidUiFrameCallback ui =
                new AndroidUiFrameCallback(new RecordingActions(), null, input);
            input.resize(1280, 720);
            ui.uiState().startGame();

            input.touchDown((int) input.layout().leaveCentreX(),
                (int) input.layout().leaveCentreY(), 0, 0);
            ui.onFrame(0.016f);

            assertThat(ui.isMenuActive()).isTrue();
        }

        @Test
        @DisplayName("a leave request made in the menu is discarded, not queued")
        void shouldNotLeaveFromTheMenu()
        {
            // returnToMenu() from the menu is an illegal transition and throws.
            // A back press landing in the menu must therefore be dropped rather
            // than acted on, or the first frame after returning crashes.
            final AndroidInputPort input = new AndroidInputPort(2.0f);
            final AndroidUiFrameCallback ui =
                new AndroidUiFrameCallback(new RecordingActions(), null, input);
            input.resize(1280, 720);
            ui.uiState().startGame();
            ui.uiState().returnToMenu();

            input.keyDown(com.badlogic.gdx.Input.Keys.BACK);

            assertThatCode(() -> ui.onFrame(0.016f)).doesNotThrowAnyException();
            assertThat(ui.isMenuActive()).isTrue();
        }
    }

    @Nested
    @DisplayName("the match gate")
    class MatchGate
    {
        @Test
        @DisplayName("attaching reports the current state immediately")
        void shouldReportOnAttach()
        {
            // A gate attached while the UI is already playing must not be left
            // believing the match is frozen for the rest of the run.
            final AndroidUiFrameCallback ui = menuOnly();
            final List<Boolean> seen = new ArrayList<>();

            ui.uiState().startGame();
            ui.attachMatchGate(seen::add);

            assertThat(seen).containsExactly(Boolean.TRUE);
        }

        @Test
        @DisplayName("the match is frozen while the menu is up")
        void shouldFreezeTheMatchInTheMenu()
        {
            // The bug the desktop build shipped into view: the bots patrolled
            // and fired from the moment the process started, so reading the
            // title screen for ten seconds cost a fifth of the player's health.
            final AndroidUiFrameCallback ui = menuOnly();
            final List<Boolean> seen = new ArrayList<>();

            ui.attachMatchGate(seen::add);

            assertThat(seen).containsExactly(Boolean.FALSE);
        }

        @Test
        @DisplayName("the gate fires once per transition, not once per frame")
        void shouldFireOncePerTransition()
        {
            final AndroidUiFrameCallback ui = menuOnly();
            final List<Boolean> seen = new ArrayList<>();
            ui.attachMatchGate(seen::add);

            ui.uiState().startGame();
            ui.onFrame(0.016f);
            ui.onFrame(0.016f);
            ui.onFrame(0.016f);

            assertThat(seen).containsExactly(Boolean.FALSE, Boolean.TRUE);
        }

        @Test
        @DisplayName("a UI with no match to gate is not a special case")
        void shouldTolerateNoGate()
        {
            final AndroidUiFrameCallback ui = menuOnly();

            ui.uiState().startGame();

            assertThatCode(() -> ui.onFrame(0.016f)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("suspension")
    class Suspension
    {
        @Test
        @DisplayName("going to the background releases every finger")
        void shouldForgetFingersOnPause()
        {
            // Android delivers no touch-up when the app is backgrounded, so a
            // finger down at that moment is still down on the way back — the
            // player returns already walking and firing.
            final AndroidInputPort input = new AndroidInputPort(2.0f);
            final AndroidUiFrameCallback ui =
                new AndroidUiFrameCallback(new RecordingActions(), null, input);
            input.resize(1280, 720);
            ui.uiState().startGame();
            input.touchDown((int) input.layout().fireCentreX(),
                (int) input.layout().fireCentreY(), 0, 0);

            ui.onPause();
            input.sampleInput(0);

            assertThat(input.currentInput().fire()).isFalse();
        }
    }
}
