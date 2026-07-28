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
 * Tests for the desktop UI state machine and the two states it moves between.
 *
 * <b>All of this is headless and all of it is real.</b> The machine has no
 * platform dependency on purpose: the decision "menu or game" is the part that
 * has to be right, and it is fully testable here. What is NOT covered — and
 * cannot be without a display — is the two consequences the decision has:
 * {@code glfwSetInputMode} actually confining the pointer, and the Scene2D
 * stage actually stopping drawing. Those need
 * {@code gradlew :desktop:run}. {@link GdxInputPortTest} and
 * {@link GdxFrameLoopListenerTest} cover the seam up to that boundary.
 */
class UiStateMachineTest
{
    @Nested
    @DisplayName("the transition table")
    class Transitions
    {
        @Test
        @DisplayName("a window opens on the menu")
        void shouldStartInTheMenu()
        {
            final UiStateMachine machine = new UiStateMachine();
            assertThat(machine.state()).isEqualTo(UiState.MENU);
            assertThat(machine.isPlaying()).isFalse();
        }

        @Test
        @DisplayName("Start Game moves MENU -> PLAYING")
        void shouldEnterPlayingFromTheMenu()
        {
            final UiStateMachine machine = new UiStateMachine();
            machine.startGame();
            assertThat(machine.state()).isEqualTo(UiState.PLAYING);
            assertThat(machine.isPlaying()).isTrue();
        }

        @Test
        @DisplayName("Escape moves PLAYING -> MENU")
        void shouldReturnToTheMenuFromPlaying()
        {
            final UiStateMachine machine = new UiStateMachine();
            machine.startGame();
            machine.returnToMenu();
            assertThat(machine.state()).isEqualTo(UiState.MENU);
            assertThat(machine.isPlaying()).isFalse();
        }

        @Test
        @DisplayName("the two states cycle indefinitely")
        void shouldCycle()
        {
            final UiStateMachine machine = new UiStateMachine();
            for (int i = 0; i < 5; i++)
            {
                machine.startGame();
                assertThat(machine.state()).isEqualTo(UiState.PLAYING);
                machine.returnToMenu();
                assertThat(machine.state()).isEqualTo(UiState.MENU);
            }
        }

        @Test
        @DisplayName("only PLAYING follows MENU, and only MENU follows PLAYING")
        void shouldPermitExactlyTwoEdges()
        {
            assertThat(UiState.MENU.canTransitionTo(UiState.PLAYING)).isTrue();
            assertThat(UiState.PLAYING.canTransitionTo(UiState.MENU)).isTrue();
            assertThat(UiState.MENU.canTransitionTo(UiState.MENU)).isFalse();
            assertThat(UiState.PLAYING.canTransitionTo(UiState.PLAYING)).isFalse();
            assertThat(UiState.MENU.canTransitionTo(null)).isFalse();
            assertThat(UiState.PLAYING.canTransitionTo(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("illegal transitions are refused loudly")
    class Refusals
    {
        @Test
        @DisplayName("starting a game that is already running throws")
        void shouldRefuseASecondStart()
        {
            // Not pedantry: two callers each believing they own the transition
            // means the second re-clears input and re-warps the cursor under
            // the first. Silently ignoring it turns a wiring bug into an
            // intermittent input glitch nobody can reproduce.
            final UiStateMachine machine = new UiStateMachine();
            machine.startGame();

            assertThatThrownBy(machine::startGame)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PLAYING -> PLAYING");

            // Refused, not half-applied.
            assertThat(machine.state()).isEqualTo(UiState.PLAYING);
        }

        @Test
        @DisplayName("returning to a menu that is already up throws")
        void shouldRefuseASecondReturn()
        {
            final UiStateMachine machine = new UiStateMachine();

            assertThatThrownBy(machine::returnToMenu)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MENU -> MENU");

            assertThat(machine.state()).isEqualTo(UiState.MENU);
        }

        @Test
        @DisplayName("a null target is rejected as an argument, not a state error")
        void shouldRejectNullTargets()
        {
            final UiStateMachine machine = new UiStateMachine();
            assertThatThrownBy(() -> machine.transitionTo(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("target");
            assertThat(machine.state()).isEqualTo(UiState.MENU);
        }
    }

    @Nested
    @DisplayName("what each state means to its consumers")
    class Meanings
    {
        @Test
        @DisplayName("the menu is drawn and fed input only in MENU")
        void shouldDrawTheMenuOnlyInMenu()
        {
            assertThat(UiState.MENU.drawsMenu()).isTrue();
            assertThat(UiState.PLAYING.drawsMenu()).isFalse();
        }

        @Test
        @DisplayName("the cursor is captured only in PLAYING")
        void shouldCaptureTheCursorOnlyInPlaying()
        {
            // Capture is the mechanism, not a nicety: a free cursor stops
            // reporting relative motion at the screen edge and the view would
            // stop turning. So this predicate decides whether mouse-look works.
            assertThat(UiState.PLAYING.capturesCursor()).isTrue();
            assertThat(UiState.MENU.capturesCursor()).isFalse();
        }

        @Test
        @DisplayName("drawing the menu and capturing the cursor are never both true")
        void shouldNeverBothDrawAndCapture()
        {
            for (final UiState state : UiState.values())
            {
                assertThat(state.drawsMenu() && state.capturesCursor()).isFalse();
            }
        }

        @Test
        @DisplayName("toString names the current state, for logs and failures")
        void shouldDescribeItself()
        {
            final UiStateMachine machine = new UiStateMachine();
            assertThat(machine.toString()).contains("MENU");
            machine.startGame();
            assertThat(machine.toString()).contains("PLAYING");
        }
    }
}
