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
    /** Float comparison tolerance. */
    private static final float EPSILON = 1.0e-5f;

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
            // At least two, so leaving is always reachable. It is four now that a
            // controller's Start and Select are in the table as well — see
            // GamepadBindings for the exact row.
            assertThat(bindings.bindingsFor(GameAction.LEAVE_MATCH))
                .hasSizeGreaterThanOrEqualTo(2);
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
        @DisplayName("every button reports itself held while a finger is on it")
        void shouldReportEveryButtonAsHeld()
        {
            // What the overlay draws its pressed state from. It walks the
            // layout's button table rather than naming the three controls, so
            // this walks the same table: a button that never reports itself
            // held is a button that gives the player no feedback at all, and on
            // a screen with no click that is indistinguishable from a miss.
            final AndroidInputPort port = playing();
            final TouchLayout layout = port.layout();

            for (final int region : TouchLayout.buttonRegions())
            {
                port.touchDown((int) layout.buttonCentreX(region),
                    (int) layout.buttonCentreY(region), 0, 0);

                assertThat(port.isHeld(region)).as("region %d held", region).isTrue();

                port.touchUp((int) layout.buttonCentreX(region),
                    (int) layout.buttonCentreY(region), 0, 0);

                assertThat(port.isHeld(region)).as("region %d released", region).isFalse();
            }
        }

        @Test
        @DisplayName("an idle slot is not ten fingers holding nothing")
        void shouldNotReportTheEmptyRegionAsHeld()
        {
            // Every untouched pointer slot holds REGION_NONE, so asking the
            // question literally would answer yes on a screen nobody is
            // touching — and the overlay would draw everything pressed.
            assertThat(playing().isHeld(TouchLayout.REGION_NONE)).isFalse();
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

    @Nested
    @DisplayName("a controller, alongside the touch controls rather than instead of them")
    class Gamepad
    {
        /** 60 Hz, so one tic of full stick deflection is a known angle. */
        private static final int TIC_RATE = 60;

        private static AndroidInputPort padPlaying()
        {
            final AndroidInputPort port = playing();
            port.setTicRate(TIC_RATE);
            return port;
        }

        @Test
        @DisplayName("pushing the left stick away from the player walks forward, not backward")
        void shouldWalkForward()
        {
            // The single easiest sign to get wrong: Android reports "pushed away"
            // as NEGATIVE y while forward is positive in InputState. Backwards,
            // and pushing the stick away walks you backwards.
            final AndroidInputPort port = padPlaying();
            port.onGamepadAxes(0.0f, -1.0f, 0.0f, 0.0f, 0.0f);
            port.sampleInput(0);
            assertThat(port.currentInput().forwardAxis()).isCloseTo(1.0f, within(EPSILON));
        }

        @Test
        @DisplayName("pushing the right stick away from the player aims up — no sign flip here")
        void shouldAimUp()
        {
            // Android's vertical convention already IS the accumulator's
            // documented "+y downward", exactly as the desktop mouse's is. A
            // second negation in this port is what "the mouse is still inverted"
            // was last time.
            final AndroidInputPort port = padPlaying();
            port.onGamepadAxes(0.0f, 0.0f, 0.0f, -1.0f, 0.0f);
            port.sampleInput(0);
            assertThat(port.currentInput().pitchDelta()).isPositive();

            port.onGamepadAxes(0.0f, 0.0f, 0.0f, 1.0f, 0.0f);
            port.sampleInput(1);
            assertThat(port.currentInput().pitchDelta()).isNegative();
        }

        @Test
        @DisplayName("a resting stick leaves the snapshot exactly neutral")
        void shouldBeNeutralAtRest()
        {
            final AndroidInputPort port = padPlaying();
            // Resting noise, which is what a real pad reports.
            port.onGamepadAxes(0.05f, -0.04f, -0.03f, 0.06f, 0.0f);
            port.sampleInput(0);
            assertThat(port.currentInput().isNeutral()).isTrue();
        }

        @Test
        @DisplayName("reading the stick more often does not turn the view further")
        void shouldNotDependOnTheEventRate()
        {
            // Android pushes axis events at whatever rate the device chooses, and
            // that rate varies by pad and by connection. If a held stick were
            // summed rather than overwritten, a pad reporting at 250 Hz would
            // turn several times faster than one reporting at 60.
            final AndroidInputPort once = padPlaying();
            once.onGamepadAxes(0.0f, 0.0f, 1.0f, 0.0f, 0.0f);
            once.sampleInput(0);

            final AndroidInputPort many = padPlaying();
            for (int event = 0; event < 12; event++)
            {
                many.onGamepadAxes(0.0f, 0.0f, 1.0f, 0.0f, 0.0f);
            }
            many.sampleInput(0);

            assertThat(many.currentInput().yawDelta())
                .as("twelve events in one tic must turn as far as one, and no further")
                .isCloseTo(once.currentInput().yawDelta(), within(EPSILON));
        }

        @Test
        @DisplayName("the stick and a thumb on the fire button work in the same tic")
        void shouldCombineTouchAndPad()
        {
            // The requirement: an ADDITIONAL path. A phone with a pad clipped to
            // it still has a touchscreen, and the two must not fight.
            final AndroidInputPort port = padPlaying();
            port.onGamepadAxes(0.0f, -1.0f, 0.0f, 0.0f, 0.0f);
            port.touchDown((int) port.layout().fireCentreX(),
                (int) port.layout().fireCentreY(), 0, 0);
            port.sampleInput(0);

            final InputState snapshot = port.currentInput();
            assertThat(snapshot.forwardAxis()).as("stick walks").isCloseTo(1.0f, within(EPSILON));
            assertThat(snapshot.fire()).as("thumb fires").isTrue();
        }

        @Test
        @DisplayName("a pad button fires, and releasing it stops the weapon")
        void shouldFireFromAPadButton()
        {
            final AndroidInputPort port = padPlaying();

            assertThat(port.keyDown(Input.Keys.BUTTON_R1)).isTrue();
            port.sampleInput(0);
            assertThat(port.currentInput().fire()).isTrue();

            // A pad button is a LEVEL, unlike the back key. Without keyUp the
            // weapon fires for the rest of the match.
            assertThat(port.keyUp(Input.Keys.BUTTON_R1)).isTrue();
            port.sampleInput(1);
            assertThat(port.currentInput().fire()).isFalse();
        }

        @Test
        @DisplayName("the right trigger fires too — an axis resolved as a button")
        void shouldFireFromATrigger()
        {
            final AndroidInputPort port = padPlaying();

            // Below the threshold: a resting or barely-brushed trigger must not
            // fire the weapon.
            port.onGamepadAxes(0.0f, 0.0f, 0.0f, 0.0f, 0.2f);
            port.sampleInput(0);
            assertThat(port.currentInput().fire()).as("barely touched").isFalse();

            port.onGamepadAxes(0.0f, 0.0f, 0.0f, 0.0f, 1.0f);
            port.sampleInput(1);
            assertThat(port.currentInput().fire()).as("pulled").isTrue();

            port.onGamepadAxes(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
            port.sampleInput(2);
            assertThat(port.currentInput().fire()).as("released").isFalse();
        }

        @Test
        @DisplayName("A jumps and L1 sprints — sprint is reachable on a pad and only on a pad")
        void shouldJumpAndSprintFromThePad()
        {
            final AndroidInputPort port = padPlaying();
            port.keyDown(Input.Keys.BUTTON_A);
            port.keyDown(Input.Keys.BUTTON_L1);
            port.sampleInput(0);

            assertThat(port.currentInput().jump()).isTrue();
            assertThat(port.currentInput().sprint())
                .as("a shoulder costs no screen area, which is the whole objection"
                    + " a touch sprint button could not answer")
                .isTrue();
        }

        @Test
        @DisplayName("Start leaves the match, exactly as the back key does")
        void shouldLeaveTheMatchFromThePad()
        {
            final AndroidInputPort start = padPlaying();
            assertThat(start.keyDown(Input.Keys.BUTTON_START)).isTrue();
            assertThat(start.consumeLeaveRequest()).isTrue();
            assertThat(start.consumeLeaveRequest())
                .as("consumed exactly once, or a held button bounces the player"
                    + " straight back out of the menu")
                .isFalse();

            final AndroidInputPort select = padPlaying();
            assertThat(select.keyDown(Input.Keys.BUTTON_SELECT)).isTrue();
            assertThat(select.consumeLeaveRequest()).isTrue();
        }

        @Test
        @DisplayName("a key nothing is bound to is not consumed, so the system still gets it")
        void shouldNotSwallowUnboundKeys()
        {
            // Returning true for everything would eat volume keys and any other
            // key the platform needs.
            final AndroidInputPort port = padPlaying();
            assertThat(port.keyDown(Input.Keys.VOLUME_UP)).isFalse();
            assertThat(port.keyUp(Input.Keys.VOLUME_UP)).isFalse();
        }

        @Test
        @DisplayName("nothing on the pad is read while the menu is up")
        void shouldIgnoreThePadInTheMenu()
        {
            // Same rule as a touch. A pad left leaning on a table while the
            // player reads the menu is not a request to walk.
            final AndroidInputPort port = new AndroidInputPort(DENSITY);
            port.resize(WIDTH, HEIGHT);
            port.setTicRate(TIC_RATE);
            port.bindUiState(new UiStateMachine());

            port.onGamepadAxes(1.0f, -1.0f, 1.0f, 1.0f, 1.0f);
            port.keyDown(Input.Keys.BUTTON_R1);
            port.sampleInput(0);

            assertThat(port.currentInput()).isEqualTo(InputState.NEUTRAL);
        }

        @Test
        @DisplayName("HOT-PLUG: a pad that vanishes at full deflection stops the player at once")
        void shouldNotWalkForeverAfterDisconnect()
        {
            // THE hot-plug test, and on a phone it is the common case rather than
            // the exotic one: a Bluetooth pad going out of range or running its
            // battery down sends NO final event. The last stick position it
            // reported is a level and would sit in the accumulator forever.
            final AndroidInputPort port = padPlaying();
            port.onGamepadAxes(0.0f, -1.0f, 1.0f, 0.0f, 1.0f);
            port.keyDown(Input.Keys.BUTTON_A);
            port.sampleInput(0);
            assertThat(port.currentInput().isNeutral()).isFalse();

            port.onGamepadDisconnected();

            for (int tic = 1; tic < 120; tic++)
            {
                port.sampleInput(tic);
                assertThat(port.currentInput().isNeutral())
                    .as("tic %d after the pad went away", Integer.valueOf(tic))
                    .isTrue();
            }
        }

        @Test
        @DisplayName("losing the pad does not lift a thumb that is still on the screen")
        void shouldLeaveTouchAlone()
        {
            // Partial on purpose: a player whose controller battery dies still
            // has a touchscreen, and clearing a finger that is on the stick right
            // now would be a second bug.
            final AndroidInputPort port = padPlaying();
            port.onGamepadAxes(1.0f, 0.0f, 0.0f, 0.0f, 0.0f);
            port.touchDown((int) port.layout().fireCentreX(),
                (int) port.layout().fireCentreY(), 0, 0);

            port.onGamepadDisconnected();
            port.sampleInput(0);

            assertThat(port.currentInput().fire())
                .as("the thumb is still on the fire button")
                .isTrue();
            assertThat(port.currentInput().strafeAxis())
                .as("but the stick is gone")
                .isZero();
        }

        @Test
        @DisplayName("a pad reconnecting simply works again")
        void shouldRecoverAfterReconnect()
        {
            final AndroidInputPort port = padPlaying();
            port.onGamepadAxes(0.0f, -1.0f, 0.0f, 0.0f, 0.0f);
            port.onGamepadDisconnected();

            port.onGamepadAxes(0.0f, -1.0f, 0.0f, 0.0f, 0.0f);
            port.sampleInput(0);
            assertThat(port.currentInput().forwardAxis()).isCloseTo(1.0f, within(EPSILON));
        }

        @Test
        @DisplayName("backgrounding the app drops a pad button held at that moment")
        void shouldForgetPadButtonsOnBackground()
        {
            // Android delivers no key-up when the app goes away, so a button held
            // then would still be held on the way back — the player returns
            // already firing. forgetEverything is the edge the UI calls.
            final AndroidInputPort port = padPlaying();
            port.keyDown(Input.Keys.BUTTON_R1);
            port.forgetEverything();
            port.sampleInput(0);
            assertThat(port.currentInput()).isEqualTo(InputState.NEUTRAL);
        }

        @Test
        @DisplayName("a bad tic rate is refused")
        void shouldRejectABadTicRate()
        {
            assertThatThrownBy(() -> playing().setTicRate(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tic rate");
        }
    }

    @Nested
    @DisplayName("the default scheme carries touch and gamepad bindings on one action")
    class GamepadBindings
    {
        @Test
        @DisplayName("a shoulder button closes the sprint gap; invert-look stays deliberately open")
        void shouldBindSprintButNotInvertLook()
        {
            // Sprint used to be the phone's one unbound action, because a screen
            // button for a held modifier wants a thumb nobody has. A shoulder
            // costs no screen area, so the objection does not apply and the
            // action becomes reachable — with a controller, and only with one.
            final ActionBindings table = AndroidBindings.defaults();
            assertThat(table.bindingsFor(GameAction.SPRINT))
                .containsExactly(InputBinding.gamepadButton(Input.Keys.BUTTON_L1));

            // TOGGLE_INVERT_LOOK remains unbound, and that is the state of the
            // platform rather than an oversight: it is a setting pressed once
            // ever, and neither a scarce screen button nor a scarce pad button is
            // worth spending on it. Asserted so the gap stays a decision — if
            // someone later binds it, this test says so out loud.
            assertThat(table.firstUnbound()).isEqualTo(GameAction.TOGGLE_INVERT_LOOK);
        }

        @Test
        @DisplayName("fire is a screen region AND a trigger AND a shoulder — alternates, not a chord")
        void shouldBindFireToTouchAndPad()
        {
            assertThat(AndroidBindings.defaults().bindingsFor(GameAction.FIRE))
                .extracting(InputBinding::source)
                .containsExactlyInAnyOrder(
                    InputBinding.Source.TOUCH_REGION,
                    InputBinding.Source.GAMEPAD_AXIS,
                    InputBinding.Source.GAMEPAD_BUTTON);
        }

        @Test
        @DisplayName("every movement action names the touch stick and the pad stick")
        void shouldBindMovementToBothSticks()
        {
            final ActionBindings table = AndroidBindings.defaults();
            final GameAction[] movement =
            {
                GameAction.MOVE_FORWARD, GameAction.MOVE_BACKWARD,
                GameAction.STRAFE_LEFT, GameAction.STRAFE_RIGHT,
            };
            for (final GameAction action : movement)
            {
                assertThat(table.bindingsFor(action))
                    .as("%s", action)
                    .containsExactly(
                        InputBinding.touchRegion(TouchLayout.REGION_MOVE_STICK),
                        InputBinding.gamepadAxis(AndroidInputPort.AXIS_LEFT_STICK));
            }
        }

        @Test
        @DisplayName("leaving the match keeps all four of its ways out")
        void shouldKeepEveryWayOut()
        {
            // Being unable to leave a match is not a cosmetic failure — it is an
            // app that has to be force-stopped.
            assertThat(AndroidBindings.defaults().bindingsFor(GameAction.LEAVE_MATCH))
                .containsExactly(
                    InputBinding.touchRegion(TouchLayout.REGION_LEAVE),
                    InputBinding.key(Input.Keys.BACK),
                    InputBinding.gamepadButton(Input.Keys.BUTTON_START),
                    InputBinding.gamepadButton(Input.Keys.BUTTON_SELECT));
        }

        @Test
        @DisplayName("no action exceeds the four-binding cap")
        void shouldStayWithinTheBindingCap()
        {
            final ActionBindings table = AndroidBindings.defaults();
            for (final GameAction action : GameAction.values())
            {
                assertThat(table.bindingsFor(action))
                    .as("%s", action)
                    .hasSizeLessThanOrEqualTo(ActionBindings.MAX_BINDINGS_PER_ACTION);
            }
        }

        @Test
        @DisplayName("the codes are Android key constants, not the desktop table's GLFW indices")
        void shouldUsePlatformOwnedCodes()
        {
            // A binding code is opaque and belongs to the platform that wrote it.
            // The same physical button is 96 here and 0 on desktop, and Source is
            // what a loader checks before trusting a saved scheme.
            assertThat(AndroidBindings.defaults().bindingsFor(GameAction.JUMP))
                .contains(InputBinding.gamepadButton(Input.Keys.BUTTON_A));
            assertThat(Input.Keys.BUTTON_A).isNotZero();
        }
    }
}
