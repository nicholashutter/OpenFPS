/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import com.openfps.engine.gameplay.MatchState;
import com.openfps.engine.gameplay.MatchSummary;

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
    /** A finished round, for driving the end-of-match transition. */
    private static MatchSummary won()
    {
        return new MatchSummary(MatchState.WON, 7, 1, 7, 21, 13, 44, 56);
    }

    /** A lost round. */
    private static MatchSummary lost()
    {
        return new MatchSummary(MatchState.LOST, 3, 4, 7, 18, 9, 100, 0);
    }

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
        @DisplayName("Settings moves MENU -> SETTINGS, and Back moves it home")
        void shouldEnterAndLeaveSettings()
        {
            final UiStateMachine machine = new UiStateMachine();
            machine.openSettings();
            assertThat(machine.state()).isEqualTo(UiState.SETTINGS);
            assertThat(machine.isPlaying()).isFalse();

            machine.returnToMenu();
            assertThat(machine.state()).isEqualTo(UiState.MENU);
        }

        @Test
        @DisplayName("a decided round moves PLAYING -> GAME_OVER, and Back moves it home")
        void shouldEnterAndLeaveTheEndScreen()
        {
            final UiStateMachine machine = new UiStateMachine();
            machine.startGame();
            machine.endMatch(won());
            assertThat(machine.state()).isEqualTo(UiState.GAME_OVER);
            assertThat(machine.isPlaying()).isFalse();

            machine.returnToMenu();
            assertThat(machine.state()).isEqualTo(UiState.MENU);
        }

        @Test
        @DisplayName("the table names every legal edge and refuses the rest")
        void shouldPermitExactlyTheDocumentedEdges()
        {
            assertThat(UiState.MENU.canTransitionTo(UiState.PLAYING)).isTrue();
            assertThat(UiState.MENU.canTransitionTo(UiState.SETTINGS)).isTrue();
            assertThat(UiState.PLAYING.canTransitionTo(UiState.MENU)).isTrue();
            assertThat(UiState.PLAYING.canTransitionTo(UiState.GAME_OVER)).isTrue();
            assertThat(UiState.SETTINGS.canTransitionTo(UiState.MENU)).isTrue();
            assertThat(UiState.GAME_OVER.canTransitionTo(UiState.MENU)).isTrue();

            // Settings is reachable only from the menu. Mid-match it would be a
            // pause screen, and this game has no pause — the loop keeps ticking
            // and the bots would keep shooting a player who is reading a menu.
            assertThat(UiState.PLAYING.canTransitionTo(UiState.SETTINGS)).isFalse();
            assertThat(UiState.SETTINGS.canTransitionTo(UiState.PLAYING)).isFalse();
            assertThat(UiState.SETTINGS.canTransitionTo(UiState.GAME_OVER)).isFalse();

            // The rematch edge, which was refused for as long as it would have
            // been a lie: the demo built one Match and nothing could restore it,
            // so an edge back into the world led into a room already cleared.
            // Match.reset() is what earned this line — see UiState's Javadoc.
            assertThat(UiState.GAME_OVER.canTransitionTo(UiState.PLAYING)).isTrue();
            // Settings from the end screen stays refused, for the same reason it
            // is refused mid-match: it would be a pause screen, and this game has
            // no pause.
            assertThat(UiState.GAME_OVER.canTransitionTo(UiState.SETTINGS)).isFalse();

            // The menu cannot jump to a result it has not played for.
            assertThat(UiState.MENU.canTransitionTo(UiState.GAME_OVER)).isFalse();
        }

        @Test
        @DisplayName("Play Again moves GAME_OVER -> PLAYING")
        void shouldRestartFromTheEndScreen()
        {
            final UiStateMachine machine = new UiStateMachine();
            machine.startGame();
            machine.endMatch(won());

            machine.restartMatch();

            assertThat(machine.state()).isEqualTo(UiState.PLAYING);
            assertThat(machine.isPlaying()).isTrue();
        }

        @Test
        @DisplayName("a round can be played, finished and replayed indefinitely")
        void shouldCycleThroughRematches()
        {
            // The property that matters to a player rather than to the table: the
            // end screen is not a dead end and never becomes one. Before the
            // rematch edge existed, the second round of a session was unreachable
            // — the guard that stopped the end screen re-firing was latched for
            // the life of the process.
            final UiStateMachine machine = new UiStateMachine();
            machine.startGame();
            for (int round = 0; round < 5; round++)
            {
                machine.endMatch(won());
                assertThat(machine.state()).isEqualTo(UiState.GAME_OVER);
                machine.restartMatch();
                assertThat(machine.state()).isEqualTo(UiState.PLAYING);
            }
        }

        @Test
        @DisplayName("a rematch cannot be asked for by a game that is already running")
        void shouldRefuseARestartWhileAlreadyPlaying()
        {
            // The same refusal startGame carries, and for the same reason: two
            // callers each believing they own the move into the world means the
            // second re-clears input and re-warps the cursor under the first.
            //
            // It is NOT refused from the menu, and that is deliberate rather than
            // an oversight — the edge {@code MENU -> PLAYING} is legal because
            // that is how a game starts. What restartMatch adds is a legal move
            // out of GAME_OVER, and the honest statement of its precondition is
            // the transition table, not a second copy of it here.
            final UiStateMachine machine = new UiStateMachine();
            machine.startGame();

            assertThatThrownBy(machine::restartMatch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PLAYING -> PLAYING");

            assertThat(machine.state()).isEqualTo(UiState.PLAYING);
        }

        @Test
        @DisplayName("no state may re-enter itself, and null is never a target")
        void shouldRefuseSelfEdgesAndNull()
        {
            for (final UiState state : UiState.values())
            {
                assertThat(state.canTransitionTo(state))
                    .as("%s may re-enter itself", state).isFalse();
                assertThat(state.canTransitionTo(null))
                    .as("%s accepts a null target", state).isFalse();
            }
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

        @Test
        @DisplayName("opening settings from anywhere but the menu throws")
        void shouldRefuseSettingsOutsideTheMenu()
        {
            final UiStateMachine machine = new UiStateMachine();
            machine.startGame();

            assertThatThrownBy(machine::openSettings)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PLAYING -> SETTINGS");

            assertThat(machine.state()).isEqualTo(UiState.PLAYING);
        }

        @Test
        @DisplayName("ending a match that is not being played throws")
        void shouldRefuseEndingAMatchFromTheMenu()
        {
            final UiStateMachine machine = new UiStateMachine();

            assertThatThrownBy(() -> machine.endMatch(won()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MENU -> GAME_OVER");

            assertThat(machine.state()).isEqualTo(UiState.MENU);
        }

        @Test
        @DisplayName("ending a match twice throws rather than replacing the result")
        void shouldRefuseASecondEnd()
        {
            final UiStateMachine machine = new UiStateMachine();
            machine.startGame();
            machine.endMatch(won());

            assertThatThrownBy(() -> machine.endMatch(lost()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GAME_OVER -> GAME_OVER");
        }

        @Test
        @DisplayName("a null summary is rejected as an argument, not a state error")
        void shouldRejectANullSummary()
        {
            final UiStateMachine machine = new UiStateMachine();
            machine.startGame();

            assertThatThrownBy(() -> machine.endMatch(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summary");

            // Refused before anything moved: an end screen with no result to
            // draw is worse than no end screen.
            assertThat(machine.state()).isEqualTo(UiState.PLAYING);
            assertThat(machine.result()).isNull();
        }
    }

    @Nested
    @DisplayName("the recorded match result")
    class Result
    {
        @Test
        @DisplayName("is absent until a match has been played to a decision")
        void shouldStartWithNoResult()
        {
            final UiStateMachine machine = new UiStateMachine();
            assertThat(machine.result()).isNull();
            machine.startGame();
            assertThat(machine.result()).isNull();
        }

        @Test
        @DisplayName("is the summary the match ended with")
        void shouldHoldTheSummary()
        {
            final UiStateMachine machine = new UiStateMachine();
            final MatchSummary summary = won();
            machine.startGame();
            machine.endMatch(summary);

            assertThat(machine.result()).isSameAs(summary);
        }

        @Test
        @DisplayName("survives the walk back to the menu")
        void shouldSurviveTheReturnToTheMenu()
        {
            // Not cleared on the way out, because the end screen is still
            // drawing from it while that transition is being applied.
            final UiStateMachine machine = new UiStateMachine();
            machine.startGame();
            machine.endMatch(lost());
            machine.returnToMenu();

            assertThat(machine.result()).isNotNull();
            assertThat(machine.result().isWin()).isFalse();
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
            // The sharp part: the other two states also put a stage on the
            // glass, and it is not this one. A consumer that conflated them
            // would draw the main menu over the settings screen.
            assertThat(UiState.SETTINGS.drawsMenu()).isFalse();
            assertThat(UiState.GAME_OVER.drawsMenu()).isFalse();
        }

        @Test
        @DisplayName("exactly one screen claims each state")
        void shouldGiveEachStateAtMostOneScreen()
        {
            for (final UiState state : UiState.values())
            {
                int claims = 0;
                if (state.drawsMenu())
                {
                    claims++;
                }
                if (state.drawsSettings())
                {
                    claims++;
                }
                if (state.drawsGameOver())
                {
                    claims++;
                }
                assertThat(claims).as("%s is drawn by %s screens", state, claims)
                    .isLessThanOrEqualTo(1);
            }
            assertThat(UiState.SETTINGS.drawsSettings()).isTrue();
            assertThat(UiState.GAME_OVER.drawsGameOver()).isTrue();
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
            // Both new screens have buttons on them, so neither may take the
            // pointer away from the player.
            assertThat(UiState.SETTINGS.capturesCursor()).isFalse();
            assertThat(UiState.GAME_OVER.capturesCursor()).isFalse();
        }

        @Test
        @DisplayName("a caught cursor and a usable pointer are exact opposites")
        void shouldMakePointerUseTheComplementOfCapture()
        {
            for (final UiState state : UiState.values())
            {
                assertThat(state.usesPointer())
                    .as("%s captures and offers the pointer at once", state)
                    .isNotEqualTo(state.capturesCursor());
            }
        }

        @Test
        @DisplayName("drawing a screen and capturing the cursor are never both true")
        void shouldNeverBothDrawAndCapture()
        {
            for (final UiState state : UiState.values())
            {
                assertThat(state.drawsMenu() && state.capturesCursor()).isFalse();
                assertThat(state.drawsSettings() && state.capturesCursor()).isFalse();
                assertThat(state.drawsGameOver() && state.capturesCursor()).isFalse();
            }
        }

        @Test
        @DisplayName("back leaves every screen except the menu, which it leaves the app from")
        void shouldSendBackToTheMenuFromEveryScreenButTheMenu()
        {
            // Found on the OpenFPS_API36 emulator: back on the settings screen quit
            // the game outright. Android's default handling finishes the Activity,
            // and SETTINGS and GAME_OVER had both taken the input processor away
            // from the thing that was bound to the key while neither catching it nor
            // acting on it. From GAME_OVER that threw the match result away with it.
            assertThat(UiState.SETTINGS.backReturnsToMenu())
                .as("a settings screen is left, not quit from")
                .isTrue();
            assertThat(UiState.GAME_OVER.backReturnsToMenu())
                .as("and so is an end screen")
                .isTrue();
            assertThat(UiState.PLAYING.backReturnsToMenu())
                .as("back has always left a match")
                .isTrue();
            assertThat(UiState.MENU.backReturnsToMenu())
                .as("but back from the front screen leaves the app, per Android")
                .isFalse();
        }

        @Test
        @DisplayName("every state that owns a stage is one back can be escaped from")
        void shouldNeverStrandAScreenThatOwnsTheProcessor()
        {
            // The property rather than the four answers: a screen that holds the
            // input processor and is not the menu must have a back route, because it
            // has taken the key away from whatever else was listening. Adding a
            // fifth state — a pause or loading screen — has to answer this or fail
            // here rather than becoming another dead end on a phone.
            for (final UiState state : UiState.values())
            {
                if (state.usesPointer() && !state.drawsMenu())
                {
                    assertThat(state.backReturnsToMenu())
                        .as("%s owns a stage with no way back off it", state)
                        .isTrue();
                }
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
