/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import com.badlogic.gdx.Input;

import com.openfps.engine.hal.adapter.ActionBindings;
import com.openfps.engine.hal.port.GameAction;
import com.openfps.engine.hal.port.InputBinding;
import com.openfps.engine.hal.port.InputState;
import com.openfps.gdx.InputAccumulator;
import com.openfps.gdx.UiStateMachine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link AndroidInputPort} — the whole touch path, with no device.
 *
 * <p>The events a phone delivers are ordinary method calls, so the entire
 * gesture layer is reachable from a plain JVM: press here, drag there, assert
 * on the {@link InputState} the game loop would have latched. That covers the
 * multi-touch bookkeeping, which is where this class can actually be wrong —
 * one finger's drag being attributed to another's control is a bug that looks
 * like a physics problem when you meet it on a device.</p>
 *
 * <p>What is <b>not</b> covered here is anything needing a real
 * {@code Gdx.input}: catching the back key, and installing the processor. Those
 * are single framework calls with no logic in them.</p>
 */
@DisplayName("AndroidInputPort")
class AndroidInputPortTest
{
    /** A landscape phone at 2.75x. */
    private static final float DENSITY = 2.75f;

    private static final int WIDTH = 2340;

    private static final int HEIGHT = 1080;

    /** A port sized to a screen and already in a match. */
    private static AndroidInputPort playing()
    {
        final AndroidInputPort port = new AndroidInputPort(DENSITY);
        port.resize(WIDTH, HEIGHT);
        final UiStateMachine ui = new UiStateMachine();
        ui.startGame();
        port.bindUiState(ui);
        return port;
    }

    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        @DisplayName("null collaborators are refused")
        void shouldRejectNulls()
        {
            assertThatThrownBy(() -> new AndroidInputPort(null, new InputAccumulator()))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new AndroidInputPort(new TouchLayout(2.0f), null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> playing().bindActions(null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> playing().bindUiState(null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("sensitivity is per dp, so the same swipe turns the same on any screen")
        void shouldScaleSensitivityByDensity()
        {
            // A mouse reports counts that mean the same on every monitor. A
            // finger travels a physical distance, and the same thumb swipe is
            // 400 px on one phone and 1100 on another — so radians-per-PIXEL
            // would make the game turn nearly three times faster on the second.
            final AndroidInputPort coarse = new AndroidInputPort(2.0f);
            final AndroidInputPort fine = new AndroidInputPort(4.0f);

            assertThat(coarse.accumulator().radiansPerPixel())
                .isEqualTo(fine.accumulator().radiansPerPixel() * 2.0f);
        }

        @Test
        @DisplayName("the default scheme binds everything the player needs")
        void shouldBindTheEssentialActions()
        {
            final ActionBindings bindings = AndroidBindings.defaults();

            assertThat(bindings.bindingsFor(GameAction.FIRE)).isNotEmpty();
            assertThat(bindings.bindingsFor(GameAction.JUMP)).isNotEmpty();
            assertThat(bindings.bindingsFor(GameAction.MOVE_FORWARD)).isNotEmpty();
            assertThat(bindings.bindingsFor(GameAction.LEAVE_MATCH)).hasSize(2);
        }

        @Test
        @DisplayName("leaving a match is reachable two ways, and one of them is the back key")
        void shouldBindLeaveToBothAButtonAndTheBackKey()
        {
            // Being unable to leave a match is not cosmetic — it is an app that
            // has to be force-stopped. Back is what an Android user reaches for
            // first; the on-screen button is what still works on a device whose
            // back has been swallowed by gesture navigation.
            final ActionBindings bindings = AndroidBindings.defaults();

            assertThat(bindings.bindingsFor(GameAction.LEAVE_MATCH))
                .contains(InputBinding.key(Input.Keys.BACK))
                .contains(InputBinding.touchRegion(TouchLayout.REGION_LEAVE));
            assertThat(playing().isLeaveKey(Input.Keys.BACK)).isTrue();
            assertThat(playing().isLeaveKey(Input.Keys.A)).isFalse();
        }
    }

    @Nested
    @DisplayName("while a match is up")
    class Playing
    {
        @Test
        @DisplayName("dragging the left thumb walks, and letting go stops")
        void shouldWalkWithTheStick()
        {
            final AndroidInputPort port = playing();
            final float anchorX = 300.0f;
            final float anchorY = 800.0f;

            port.touchDown((int) anchorX, (int) anchorY, 0, 0);
            port.touchDragged((int) anchorX, (int) (anchorY - port.layout().stickRange()), 0);
            port.sampleInput(0);
            assertThat(port.currentInput().forwardAxis()).isCloseTo(1.0f, within(0.001f));

            port.touchUp((int) anchorX, (int) anchorY, 0, 0);
            port.sampleInput(1);
            assertThat(port.currentInput().forwardAxis()).isZero();
        }

        @Test
        @DisplayName("dragging the right thumb turns the view")
        void shouldLookByDragging()
        {
            final AndroidInputPort port = playing();

            port.touchDown(1600, 500, 0, 0);
            port.touchDragged(1700, 500, 0);
            port.sampleInput(0);

            // Positive yaw turns right, which is what dragging right must do.
            assertThat(port.currentInput().yawDelta()).isGreaterThan(0.0f);
        }

        @Test
        @DisplayName("dragging up tilts the view up")
        void shouldTiltUpWhenDraggingUp()
        {
            // The one sign in the chain that is inverted: the platform reports
            // +y downward and the snapshot's convention is positive-is-up.
            // InputAccumulator owns that flip, and this is the assertion that
            // the touch path feeds it the same convention a mouse does.
            final AndroidInputPort port = playing();

            port.touchDown(1600, 500, 0, 0);
            port.touchDragged(1600, 400, 0);
            port.sampleInput(0);

            assertThat(port.currentInput().pitchDelta()).isGreaterThan(0.0f);
        }

        @Test
        @DisplayName("walking and looking at once are two fingers that do not interfere")
        void shouldTrackTwoFingersIndependently()
        {
            // The whole reason pointers are tracked separately. Treating "a
            // touch" as one thing makes the camera jump to the movement thumb
            // every time the player takes a step.
            final AndroidInputPort port = playing();

            port.touchDown(300, 800, 0, 0);
            port.touchDown(1600, 500, 1, 0);
            port.touchDragged(300, 800 - (int) port.layout().stickRange(), 0);
            port.touchDragged(1750, 500, 1);
            port.sampleInput(0);

            final InputState state = port.currentInput();
            assertThat(state.forwardAxis()).isCloseTo(1.0f, within(0.001f));
            assertThat(state.yawDelta()).isGreaterThan(0.0f);
        }

        @Test
        @DisplayName("the fire button fires and holds")
        void shouldFire()
        {
            final AndroidInputPort port = playing();

            port.touchDown((int) port.layout().fireCentreX(),
                (int) port.layout().fireCentreY(), 0, 0);
            port.sampleInput(0);
            assertThat(port.currentInput().fire()).isTrue();

            port.sampleInput(1);
            assertThat(port.currentInput().fire())
                .as("a held trigger stays held across tics").isTrue();

            port.touchUp(0, 0, 0, 0);
            port.sampleInput(2);
            assertThat(port.currentInput().fire()).isFalse();
        }

        @Test
        @DisplayName("a tap shorter than one tic is still delivered")
        void shouldNotLoseAFastTap()
        {
            // The sticky half of the accumulator. At 60 Hz a tic is 16 ms and a
            // deliberate tap can be shorter; without this the shot the player
            // definitely took simply does not happen.
            final AndroidInputPort port = playing();

            port.touchDown((int) port.layout().fireCentreX(),
                (int) port.layout().fireCentreY(), 0, 0);
            port.touchUp(0, 0, 0, 0);
            port.sampleInput(0);

            assertThat(port.currentInput().fire()).isTrue();
        }

        @Test
        @DisplayName("the jump button jumps")
        void shouldJump()
        {
            final AndroidInputPort port = playing();

            port.touchDown((int) port.layout().jumpCentreX(),
                (int) port.layout().jumpCentreY(), 0, 0);
            port.sampleInput(0);

            assertThat(port.currentInput().jump()).isTrue();
            assertThat(port.currentInput().fire()).isFalse();
        }

        @Test
        @DisplayName("a finger keeps the control it started on")
        void shouldNotChangeControlMidGesture()
        {
            // Region is decided once, at touch-down. Otherwise every look sweep
            // across the lower right of the screen fires the weapon on its way
            // past the trigger.
            final AndroidInputPort port = playing();

            port.touchDown(1600, 500, 0, 0);
            port.touchDragged((int) port.layout().fireCentreX(),
                (int) port.layout().fireCentreY(), 0);
            port.sampleInput(0);

            assertThat(port.currentInput().fire()).isFalse();
            assertThat(port.regionOf(0)).isEqualTo(TouchLayout.REGION_LOOK);
        }

        @Test
        @DisplayName("the leave button asks to leave, exactly once")
        void shouldRequestLeaveOnce()
        {
            final AndroidInputPort port = playing();

            port.touchDown((int) port.layout().leaveCentreX(),
                (int) port.layout().leaveCentreY(), 0, 0);

            assertThat(port.consumeLeaveRequest()).isTrue();
            assertThat(port.consumeLeaveRequest())
                .as("edge-triggered, or a held control bounces the player straight back out")
                .isFalse();
        }

        @Test
        @DisplayName("the back key asks to leave")
        void shouldRequestLeaveOnBack()
        {
            final AndroidInputPort port = playing();

            assertThat(port.keyDown(Input.Keys.BACK)).isTrue();
            assertThat(port.consumeLeaveRequest()).isTrue();
        }

        @Test
        @DisplayName("a key nothing is bound to is not consumed")
        void shouldIgnoreUnboundKeys()
        {
            final AndroidInputPort port = playing();

            assertThat(port.keyDown(Input.Keys.VOLUME_UP)).isFalse();
            assertThat(port.consumeLeaveRequest()).isFalse();
        }

        @Test
        @DisplayName("a cancelled touch releases the control, like a normal lift")
        void shouldTreatACancelAsARelease()
        {
            // Android cancels every pointer when a system gesture takes over —
            // a notification shade pull, a split-screen drag. Without this the
            // finger on the stick is never released and the player walks
            // forever, in an app they are no longer looking at.
            final AndroidInputPort port = playing();

            port.touchDown(300, 800, 0, 0);
            port.touchDragged(300, 800 - (int) port.layout().stickRange(), 0);
            port.touchCancelled(300, 700, 0, 0);
            port.sampleInput(0);

            assertThat(port.currentInput().forwardAxis()).isZero();
        }

        @Test
        @DisplayName("a finger index past the tracked range is dropped, not written out of bounds")
        void shouldIgnoreImpossiblePointerIndices()
        {
            final AndroidInputPort port = playing();

            assertThat(port.touchDown(300, 800, AndroidInputPort.MAX_POINTERS, 0)).isFalse();
            assertThat(port.touchDown(300, 800, -1, 0)).isFalse();
            assertThat(port.regionOf(-1)).isEqualTo(TouchLayout.REGION_NONE);
        }
    }

    @Nested
    @DisplayName("while the menu is up")
    class Menu
    {
        @Test
        @DisplayName("touches are not read at all")
        void shouldIgnoreTouchesInTheMenu()
        {
            // The menu's stage holds the processor then, so in practice these
            // are not even reached. Checked anyway: "the wrong processor is
            // installed" should cost nothing rather than walk the player into
            // a wall behind the buttons.
            final AndroidInputPort port = new AndroidInputPort(DENSITY);
            port.resize(WIDTH, HEIGHT);

            assertThat(port.touchDown(300, 800, 0, 0)).isFalse();
            port.sampleInput(0);
            assertThat(port.currentInput().isNeutral()).isTrue();
        }
    }

    @Nested
    @DisplayName("across an interruption")
    class Interruptions
    {
        @Test
        @DisplayName("a resize forgets every finger")
        void shouldForgetFingersOnResize()
        {
            // A rotation moves the fire button somewhere else, so a finger that
            // went down on it is holding a control that is no longer there.
            final AndroidInputPort port = playing();
            port.touchDown((int) port.layout().fireCentreX(),
                (int) port.layout().fireCentreY(), 0, 0);

            port.resize(HEIGHT, WIDTH);
            port.sampleInput(0);

            assertThat(port.currentInput().fire()).isFalse();
            assertThat(port.regionOf(0)).isEqualTo(TouchLayout.REGION_NONE);
        }

        @Test
        @DisplayName("forgetting everything centres the stick and releases the buttons")
        void shouldForgetEverything()
        {
            final AndroidInputPort port = playing();
            port.touchDown(300, 800, 0, 0);
            port.touchDragged(300, 700, 0);
            port.touchDown((int) port.layout().fireCentreX(),
                (int) port.layout().fireCentreY(), 1, 0);

            port.forgetEverything();
            port.sampleInput(0);

            assertThat(port.currentInput().isNeutral()).isTrue();
            assertThat(port.stickPointer()).isEqualTo(-1);
        }

        @Test
        @DisplayName("shutdown leaves the snapshot neutral")
        void shouldNeutraliseOnShutdown()
        {
            final AndroidInputPort port = playing();
            port.touchDown(300, 800, 0, 0);
            port.touchDragged(300, 700, 0);

            port.shutdown();

            assertThat(port.currentInput()).isEqualTo(InputState.NEUTRAL);
        }

        @Test
        @DisplayName("closing is the Activity's business, not this port's")
        void shouldNeverRequestShutdown()
        {
            assertThat(playing().isShutdownRequested()).isFalse();
        }
    }
}
