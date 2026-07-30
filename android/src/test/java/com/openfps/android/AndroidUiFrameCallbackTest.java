/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import java.util.ArrayList;
import java.util.List;

import com.openfps.engine.gameplay.MatchMode;
import com.openfps.engine.gameplay.MatchState;
import com.openfps.engine.gameplay.MatchSummary;
import com.openfps.gdx.AccessibilitySettings;
import com.openfps.gdx.DebugSettings;
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
 *
 * <p>The debug switch is here for a different reason. It is the one piece of the
 * aiming-feedback set that this class can get wrong <i>silently</i>: the outline
 * pass, the reticle and the render mode all live in code both platforms share, so
 * they cannot be present on desktop and absent here, but the switch that drives
 * the outline is handed in by the launcher and could be replaced by a private one
 * without a single symptom on this side of the seam.</p>
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
    @DisplayName("the debug switch — the one link that silently disconnects")
    class DebugSwitch
    {
        @Test
        @DisplayName("the switch the launcher supplies is the switch this UI reads")
        void shouldReadTheSuppliedSwitch()
        {
            // AndroidLauncher builds one DebugSettings and hands it here for the
            // settings screen and the frame counter, exactly as DesktopLauncher
            // does. If this UI kept a switch of its own instead, nothing would
            // fail and nothing would throw: the button would still relabel itself
            // and the counter would still appear on the frames nobody was
            // watching for it. Identity is the only assertion that catches that.
            final DebugSettings shared = new DebugSettings();
            final AndroidUiFrameCallback ui = new AndroidUiFrameCallback(
                new RecordingActions(), null, null, shared);

            assertThat(ui.debugSettings()).isSameAs(shared);
        }

        @Test
        @DisplayName("the switch is live: a change made outside is seen inside")
        void shouldSeeChangesMadeThroughTheSharedSwitch()
        {
            final DebugSettings shared = new DebugSettings();
            final AndroidUiFrameCallback ui = new AndroidUiFrameCallback(
                new RecordingActions(), null, null, shared);

            assertThat(ui.debugSettings().isOverlayVisible()).isFalse();
            shared.setOverlayVisible(true);
            assertThat(ui.debugSettings().isOverlayVisible()).isTrue();
        }

        @Test
        @DisplayName("a UI given no switch gets a private one rather than a null")
        void shouldDefaultToItsOwnSwitch()
        {
            // Every plain-JVM test takes this path, and it must not be a
            // special case: the counter and the settings screen are built the
            // same way whether or not a launcher was interested in the switch.
            assertThat(menuOnly().debugSettings()).isNotNull();
        }

        @Test
        @DisplayName("a null switch is refused rather than quietly replaced")
        void shouldRejectANullSwitch()
        {
            assertThatThrownBy(() -> new AndroidUiFrameCallback(
                new RecordingActions(), null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("debugSettings");
        }
    }

    @Nested
    @DisplayName("the accessibility switches — the link that now carries the outline")
    class AccessibilitySwitches
    {
        @Test
        @DisplayName("the switches the launcher supplies are the switches this UI flips")
        void shouldReadTheSuppliedSwitches()
        {
            // This is the assertion the debug switch already had, moved to where
            // the outline actually lives now. AndroidLauncher builds one
            // AccessibilitySettings, hands it here for the settings screen, and
            // separately hangs SoftwareRenderPort.setOutlineEnabled off its
            // onChange. A private copy here would be the worst kind of bug: the
            // TARGET OUTLINE button would relabel itself perfectly and the outline
            // would stop responding, with nothing thrown and nothing logged.
            final AccessibilitySettings shared = new AccessibilitySettings();
            final AndroidUiFrameCallback ui = new AndroidUiFrameCallback(
                new RecordingActions(), null, null, new DebugSettings(), shared);

            assertThat(ui.accessibilitySettings()).isSameAs(shared);
        }

        @Test
        @DisplayName("the switches are live: a change made outside is seen inside")
        void shouldSeeChangesMadeThroughTheSharedSwitches()
        {
            final AccessibilitySettings shared = new AccessibilitySettings();
            final AndroidUiFrameCallback ui = new AndroidUiFrameCallback(
                new RecordingActions(), null, null, new DebugSettings(), shared);

            assertThat(ui.accessibilitySettings().isTargetOutlineVisible()).isTrue();
            shared.setTargetOutlineVisible(false);
            assertThat(ui.accessibilitySettings().isTargetOutlineVisible()).isFalse();
        }

        @Test
        @DisplayName("they are a separate object from the debug switch, not the same one twice")
        void shouldNotBeTheDebugSwitch()
        {
            // The whole point of the split. If these two ever became one object
            // again, the outline and the frame counter would share a default and
            // the toggle's label would go back to disagreeing with the game.
            final AndroidUiFrameCallback ui = menuOnly();

            assertThat((Object) ui.accessibilitySettings())
                .isNotSameAs((Object) ui.debugSettings());
        }

        @Test
        @DisplayName("a UI given none gets private ones rather than a null")
        void shouldDefaultToItsOwnSwitches()
        {
            // Every plain-JVM test takes this path, and the default has to be the
            // shipped default: a test that saw the outline off would be testing a
            // configuration no player has.
            assertThat(menuOnly().accessibilitySettings()).isNotNull();
            assertThat(menuOnly().accessibilitySettings().isTargetOutlineVisible())
                .as("and it is on, as it is for a player")
                .isTrue();
        }

        @Test
        @DisplayName("a null set is refused rather than quietly replaced")
        void shouldRejectNullSwitches()
        {
            assertThatThrownBy(() -> new AndroidUiFrameCallback(
                new RecordingActions(), null, null, new DebugSettings(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accessibilitySettings");
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

        @Test
        @DisplayName("the back key leaves the settings screen instead of quitting the game")
        void shouldReturnToTheMenuFromSettings()
        {
            // Found on the OpenFPS_API36 emulator, and it quit the app. The back key
            // reached AndroidInputPort and banked a leave request, and this callback
            // then threw it away because the old guard was "only a match can be
            // left" — so nothing acted on the press, Android's default handling did,
            // and reading the settings screen exited the game.
            final AndroidInputPort input = new AndroidInputPort(2.0f);
            final AndroidUiFrameCallback ui =
                new AndroidUiFrameCallback(new RecordingActions(), null, input);
            input.resize(1280, 720);
            ui.uiState().openSettings();

            input.keyDown(com.badlogic.gdx.Input.Keys.BACK);
            ui.onFrame(0.016f);

            assertThat(ui.uiState().state())
                .as("back on the settings screen goes to the menu, not out of the app")
                .isEqualTo(UiState.MENU);
        }

        @Test
        @DisplayName("the back key leaves the end screen without throwing the result away")
        void shouldReturnToTheMenuFromGameOver()
        {
            // The same defect on the screen where it costs more: the end screen is
            // the only place the match result is ever shown, and quitting the app
            // took it with it.
            final AndroidInputPort input = new AndroidInputPort(2.0f);
            final AndroidUiFrameCallback ui =
                new AndroidUiFrameCallback(new RecordingActions(), null, input);
            input.resize(1280, 720);
            ui.uiState().startGame();
            ui.uiState().endMatch(new MatchSummary(MatchState.WON, 7, 1, 7, 21, 13, 44, 56));

            input.keyDown(com.badlogic.gdx.Input.Keys.BACK);
            ui.onFrame(0.016f);

            assertThat(ui.uiState().state()).isEqualTo(UiState.MENU);
        }

        @Test
        @DisplayName("the input port consumes the back key, which is what stops Android acting")
        void shouldConsumeTheBackKeyInEveryPointerState()
        {
            // The property the multiplexer in keepBackKey exists to preserve, pinned
            // where it can be seen: catching the key is not what stops Android
            // finishing the Activity — something in the processor chain returning
            // true is. AndroidInputPort does, in every state, and a change that made
            // its keyDown conditional on isPlaying would silently restore the bug.
            final AndroidInputPort input = new AndroidInputPort(2.0f);
            final AndroidUiFrameCallback ui =
                new AndroidUiFrameCallback(new RecordingActions(), null, input);
            input.resize(1280, 720);

            assertThat(input.keyDown(com.badlogic.gdx.Input.Keys.BACK))
                .as("consumed in the menu")
                .isTrue();
            ui.uiState().openSettings();
            assertThat(input.keyDown(com.badlogic.gdx.Input.Keys.BACK))
                .as("consumed on the settings screen, where the stage would not")
                .isTrue();
        }

        @Test
        @DisplayName("the port swallows touch releases, so it must sit BEHIND the stage")
        void shouldPinWhyThePortGoesBehindTheStage()
        {
            // The hazard keepBackKey's ordering exists to avoid, pinned where a
            // future edit that "tidies" the multiplexer argument order will trip
            // over it.
            //
            // touchDown defers to the screen outside a match, which makes the port
            // look safe to put in FRONT of the stage. touchUp does not defer: it
            // returns true whatever the UI state, on purpose, because a release must
            // always be honoured or a finger held across a transition stays down
            // forever. In front of a stage those two combine into a screen whose
            // buttons all draw correctly and none of which work — the tap starts a
            // Scene2D click and the release never arrives to finish it. That is what
            // the emulator showed: RENDER, DEBUG OVERLAY and BACK all inert.
            final AndroidInputPort input = new AndroidInputPort(2.0f);
            final AndroidUiFrameCallback ui =
                new AndroidUiFrameCallback(new RecordingActions(), null, input);
            input.resize(1280, 720);
            ui.uiState().openSettings();

            assertThat(input.touchDown(640, 360, 0, 0))
                .as("a press outside a match is left to the screen")
                .isFalse();
            assertThat(input.touchUp(640, 360, 0, 0))
                .as("but the RELEASE is always claimed — hence the port goes last")
                .isTrue();
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
