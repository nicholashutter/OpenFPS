/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import com.badlogic.gdx.Input;

import org.lwjgl.glfw.GLFW;

import com.openfps.engine.hal.adapter.ActionBindings;
import com.openfps.engine.hal.port.GameAction;
import com.openfps.engine.hal.port.InputBinding;
import com.openfps.engine.hal.port.InputState;
import com.openfps.gdx.AnalogStick;
import com.openfps.gdx.InputAccumulator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for the desktop controller path.
 *
 * <p>Everything here runs with no window, no GLFW context and — the point — no
 * controller. That is possible because {@link GamepadSource} is a seam: the
 * production implementation is {@link GlfwGamepad}, six native calls and no
 * decisions, and every decision worth testing sits on this side of it. See
 * {@link FakeGamepad}.</p>
 *
 * <p><b>Not covered, and not coverable without hardware:</b> that GLFW numbers
 * the axes the way {@code DesktopBindings} believes, that
 * {@code GLFW_GAMEPAD_BUTTON_A} really is the bottom face button on a given
 * pad, that the SDL mapping database recognises any particular controller, that
 * a real stick rests inside {@link AnalogStick#DEAD_ZONE}, and whether the
 * resulting camera feels right in the hand. Those need a controller and a
 * person.</p>
 */
class GdxGamepadInputTest
{
    /** Float comparison tolerance. */
    private static final float EPSILON = 1.0e-5f;

    /** 60 Hz, so one tic of full stick deflection is a round-ish number. */
    private static final int TIC_RATE = 60;

    private static GdxInputPort portOn(final InputAccumulator accumulator)
    {
        final GdxInputPort port = new GdxInputPort(accumulator);
        port.setTicRate(TIC_RATE);
        return port;
    }

    @Nested
    @DisplayName("the sticks")
    class Sticks
    {
        @Test
        @DisplayName("pushing the left stick away from the player walks forward, not backward")
        void shouldWalkForward()
        {
            // The single easiest thing to get wrong here, and the reason it has
            // its own test: a pad reports "pushed away" as NEGATIVE y, while
            // forward is positive in InputState. Getting it backwards produces a
            // game in which pushing the stick away walks you backwards.
            final InputAccumulator accumulator = new InputAccumulator(1.0f);
            final GdxInputPort port = portOn(accumulator);
            final FakeGamepad pad = new FakeGamepad().withLeftStick(0.0f, -1.0f);

            port.pollGamepad(pad);
            port.sampleInput(0);

            assertThat(port.currentInput().forwardAxis())
                .as("stick away from you walks forward")
                .isCloseTo(1.0f, within(EPSILON));
        }

        @Test
        @DisplayName("pushing the left stick right strafes right")
        void shouldStrafeRight()
        {
            final InputAccumulator accumulator = new InputAccumulator(1.0f);
            final GdxInputPort port = portOn(accumulator);
            port.pollGamepad(new FakeGamepad().withLeftStick(1.0f, 0.0f));
            port.sampleInput(0);
            assertThat(port.currentInput().strafeAxis()).isCloseTo(1.0f, within(EPSILON));
        }

        @Test
        @DisplayName("pushing the right stick right turns the view right")
        void shouldTurnRight()
        {
            final InputAccumulator accumulator = new InputAccumulator(1.0f);
            final GdxInputPort port = portOn(accumulator);
            port.pollGamepad(new FakeGamepad().withRightStick(1.0f, 0.0f));
            port.sampleInput(0);
            assertThat(port.currentInput().yawDelta()).isPositive();
        }

        @Test
        @DisplayName("pushing the right stick away from the player aims up — the mouse's convention, untouched")
        void shouldAimUp()
        {
            // There is NO sign flip in the port, and its absence is the whole
            // point. The pad's vertical convention already IS the accumulator's
            // documented "+y downward", exactly as the desktop mouse's is; a
            // second negation here is what "the mouse is still inverted" was
            // last time, and it cost this project several rounds.
            final InputAccumulator accumulator = new InputAccumulator(1.0f);
            final GdxInputPort port = portOn(accumulator);

            port.pollGamepad(new FakeGamepad().withRightStick(0.0f, -1.0f));
            port.sampleInput(0);
            assertThat(port.currentInput().pitchDelta())
                .as("stick away from you aims up")
                .isPositive();

            port.pollGamepad(new FakeGamepad().withRightStick(0.0f, 1.0f));
            port.sampleInput(1);
            assertThat(port.currentInput().pitchDelta())
                .as("stick toward you aims down")
                .isNegative();
        }

        @Test
        @DisplayName("a stick at rest leaves the snapshot exactly neutral")
        void shouldBeNeutralAtRest()
        {
            final InputAccumulator accumulator = new InputAccumulator(1.0f);
            final GdxInputPort port = portOn(accumulator);
            // Resting noise, not a clean zero — a real pad never reports one.
            port.pollGamepad(new FakeGamepad()
                .withLeftStick(0.05f, -0.04f)
                .withRightStick(-0.03f, 0.06f));
            port.sampleInput(0);
            assertThat(port.currentInput().isNeutral()).isTrue();
        }

        @Test
        @DisplayName("one tic of full deflection turns by the documented rate times the tic")
        void shouldTurnAtTheDocumentedRate()
        {
            final InputAccumulator accumulator = new InputAccumulator(1.0f);
            final GdxInputPort port = portOn(accumulator);
            port.pollGamepad(new FakeGamepad().withRightStick(1.0f, 0.0f));
            port.sampleInput(0);

            assertThat(port.currentInput().yawDelta()).isCloseTo(
                InputAccumulator.GAMEPAD_LOOK_RADIANS_PER_SECOND / TIC_RATE,
                within(EPSILON));
        }

        @Test
        @DisplayName("polling the pad more often does not turn the view further")
        void shouldNotDependOnThePollRate()
        {
            // The port-level restatement of InputAccumulatorTest's headline. It
            // is repeated here because the bug it guards would be reintroduced
            // HERE — by a pollGamepad that called accumulateLook instead of
            // setGamepadLookAxes, which is a plausible-looking one-word change.
            final InputAccumulator once = new InputAccumulator(1.0f);
            final GdxInputPort onePoll = portOn(once);
            onePoll.pollGamepad(new FakeGamepad().withRightStick(1.0f, 0.0f));
            onePoll.sampleInput(0);

            final InputAccumulator many = new InputAccumulator(1.0f);
            final GdxInputPort manyPolls = portOn(many);
            final FakeGamepad held = new FakeGamepad().withRightStick(1.0f, 0.0f);
            for (int poll = 0; poll < 8; poll++)
            {
                manyPolls.pollGamepad(held);
            }
            manyPolls.sampleInput(0);

            assertThat(manyPolls.currentInput().yawDelta())
                .as("eight polls in one tic must turn as far as one, and no further")
                .isCloseTo(onePoll.currentInput().yawDelta(), within(EPSILON));
        }

        @Test
        @DisplayName("a bad tic rate is refused")
        void shouldRejectABadTicRate()
        {
            assertThatThrownBy(() -> new GdxInputPort().setTicRate(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tic rate");
        }
    }

    @Nested
    @DisplayName("hot-plug")
    class HotPlug
    {
        @Test
        @DisplayName("a pad unplugged at full deflection stops walking the player immediately")
        void shouldNotWalkForeverAfterUnplug()
        {
            // THE hot-plug test. A stick deflection is a level: it persists until
            // something overwrites it, and a pad that has been unplugged never
            // will. Without the clear in pollGamepad, this walks the player into
            // a wall for the rest of the match with nobody touching anything —
            // which is exactly what a held key did before clearAll() existed.
            final InputAccumulator accumulator = new InputAccumulator(1.0f);
            final GdxInputPort port = portOn(accumulator);
            final FakeGamepad pad = new FakeGamepad()
                .withLeftStick(0.0f, -1.0f)
                .withRightStick(1.0f, 0.0f);

            port.pollGamepad(pad);
            port.sampleInput(0);
            assertThat(port.currentInput().forwardAxis()).isCloseTo(1.0f, within(EPSILON));

            // The cable comes out. The fake deliberately keeps the deflection in
            // its fields — a real device does not centre itself on the way out.
            pad.unplug();
            port.pollGamepad(pad);
            port.sampleInput(1);

            assertThat(port.currentInput().isNeutral())
                .as("the player stops the instant the pad goes")
                .isTrue();
        }

        @Test
        @DisplayName("and stays stopped on every later tic")
        void shouldStayStopped()
        {
            final InputAccumulator accumulator = new InputAccumulator(1.0f);
            final GdxInputPort port = portOn(accumulator);
            final FakeGamepad pad = new FakeGamepad().withRightStick(1.0f, 1.0f);
            port.pollGamepad(pad);
            pad.unplug();

            for (int tic = 0; tic < 240; tic++)
            {
                port.pollGamepad(pad);
                port.sampleInput(tic);
                assertThat(port.currentInput().isNeutral())
                    .as("tic %d after the pad went away", Integer.valueOf(tic))
                    .isTrue();
            }
        }

        @Test
        @DisplayName("unplugging a pad does not release a key the player is still holding")
        void shouldLeaveTheKeyboardAlone()
        {
            // Partial on purpose. A player who unplugs a pad mid-match usually
            // still has a hand on the keyboard, and a total clear would drop an
            // input they are making right now.
            final InputAccumulator accumulator = new InputAccumulator(1.0f);
            final GdxInputPort port = portOn(accumulator);
            final FakeGamepad pad = new FakeGamepad().withLeftStick(1.0f, 0.0f);
            port.pollGamepad(pad);

            // Stand in for the keyboard half of the same frame.
            accumulator.setMovementKeys(true, false, false, false);
            accumulator.accumulateLook(20, 0);

            pad.unplug();
            port.pollGamepad(pad);
            port.sampleInput(0);

            assertThat(port.currentInput().forwardAxis())
                .as("W is still held")
                .isCloseTo(1.0f, within(EPSILON));
            assertThat(port.currentInput().strafeAxis())
                .as("the stick is gone")
                .isZero();
            assertThat(port.currentInput().yawDelta())
                .as("and the mouse still turns the view")
                .isPositive();
        }

        @Test
        @DisplayName("plugging a pad back in simply works — no reset, no restart")
        void shouldRecoverOnReplug()
        {
            final InputAccumulator accumulator = new InputAccumulator(1.0f);
            final GdxInputPort port = portOn(accumulator);
            final FakeGamepad pad = new FakeGamepad().withLeftStick(0.0f, -1.0f);
            port.pollGamepad(pad);
            pad.unplug();
            port.pollGamepad(pad);

            pad.replug();
            port.pollGamepad(pad);
            port.sampleInput(0);

            assertThat(port.currentInput().forwardAxis()).isCloseTo(1.0f, within(EPSILON));
        }

        @Test
        @DisplayName("a session that never sees a controller is unaffected")
        void shouldTolerateNoControllerAtAll()
        {
            final InputAccumulator accumulator = new InputAccumulator(1.0f);
            final GdxInputPort port = portOn(accumulator);
            final FakeGamepad pad = new FakeGamepad().unplug();

            port.pollGamepad(pad);
            accumulator.setMovementKeys(true, false, false, false);
            port.sampleInput(0);

            assertThat(pad.pollCount()).isEqualTo(1);
            assertThat(port.currentInput().forwardAxis())
                .as("keyboard play is untouched by the absence of a pad")
                .isCloseTo(1.0f, within(EPSILON));
        }

        @Test
        @DisplayName("a null gamepad is rejected, and the default is never null")
        void shouldRejectANullGamepad()
        {
            assertThat(new GdxInputPort().gamepad()).isNotNull();
            assertThatThrownBy(() -> new GdxInputPort().bindGamepad(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
        }
    }

    @Nested
    @DisplayName("gamepad bindings coexist with keyboard bindings on one action")
    class Coexistence
    {
        @Test
        @DisplayName("the default scheme binds fire to a mouse button, a key, a trigger AND a face button")
        void shouldBindFireToEveryDeviceKind()
        {
            // Multiple bindings are ALTERNATES, not a chord — the property that
            // makes a pad an additional path rather than a replacement. If they
            // were a chord, adding the trigger would have stopped the mouse
            // working.
            final InputBinding[] fire =
                DesktopBindings.defaults().bindingsFor(GameAction.FIRE);
            assertThat(fire).extracting(InputBinding::source)
                .containsExactlyInAnyOrder(
                    InputBinding.Source.MOUSE_BUTTON,
                    InputBinding.Source.KEY,
                    InputBinding.Source.GAMEPAD_AXIS,
                    InputBinding.Source.GAMEPAD_BUTTON);
        }

        @Test
        @DisplayName("the default scheme is complete, and every movement action names the stick")
        void shouldBindEveryAction()
        {
            final ActionBindings table = DesktopBindings.defaults();
            assertThat(table.isComplete()).isTrue();
            assertThat(table.firstUnbound()).isNull();

            final GameAction[] movement =
            {
                GameAction.MOVE_FORWARD, GameAction.MOVE_BACKWARD,
                GameAction.STRAFE_LEFT, GameAction.STRAFE_RIGHT,
            };
            for (final GameAction action : movement)
            {
                assertThat(table.bindingsFor(action))
                    .as("%s must name the left stick as an alternate", action)
                    .contains(InputBinding.gamepadAxis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X));
            }
        }

        @Test
        @DisplayName("a face button fires through the same lookup a mouse button does")
        void shouldFireFromAFaceButton()
        {
            final FakeGamepad pad = new FakeGamepad().press(GLFW.GLFW_GAMEPAD_BUTTON_A);
            assertThat(GdxInputPort.isAnyActive(DesktopBindings.defaults(), GameAction.FIRE,
                binding -> probe(pad, binding))).isTrue();
        }

        @Test
        @DisplayName("a trigger fires too — an axis resolved as a button")
        void shouldFireFromATrigger()
        {
            final FakeGamepad pad =
                new FakeGamepad().pullTrigger(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER);
            assertThat(GdxInputPort.isAnyActive(DesktopBindings.defaults(), GameAction.FIRE,
                binding -> probe(pad, binding))).isTrue();
        }

        @Test
        @DisplayName("a shoulder sprints and Start leaves the match")
        void shouldBindTheRemainingControls()
        {
            final FakeGamepad sprinting =
                new FakeGamepad().press(GLFW.GLFW_GAMEPAD_BUTTON_LEFT_BUMPER);
            assertThat(GdxInputPort.isAnyActive(DesktopBindings.defaults(), GameAction.SPRINT,
                binding -> probe(sprinting, binding))).isTrue();

            final FakeGamepad leaving =
                new FakeGamepad().press(GLFW.GLFW_GAMEPAD_BUTTON_START);
            assertThat(GdxInputPort.isAnyActive(DesktopBindings.defaults(),
                GameAction.LEAVE_MATCH, binding -> probe(leaving, binding))).isTrue();

            // Both Start and Back, because pads label and place them differently
            // and being unable to leave a match is not a cosmetic failure.
            final FakeGamepad backing =
                new FakeGamepad().press(GLFW.GLFW_GAMEPAD_BUTTON_BACK);
            assertThat(GdxInputPort.isAnyActive(DesktopBindings.defaults(),
                GameAction.LEAVE_MATCH, binding -> probe(backing, binding))).isTrue();
        }

        @Test
        @DisplayName("the left stick bound to MOVE_FORWARD does NOT read as a held button")
        void shouldNotResolveAStickAxisAsAButton()
        {
            // The asymmetry that makes the whole scheme work. MOVE_FORWARD names
            // the left stick so a settings screen can report and rebind it — but
            // if that binding ALSO answered "held", strafing right would set the
            // forward key, and the player would drift forwards whenever they
            // moved sideways. A stick axis has a direction; "is it held" has no
            // answer, so it reports inactive.
            final FakeGamepad pushedHard = new FakeGamepad().withLeftStick(1.0f, 1.0f);
            assertThat(GdxInputPort.isAnyActive(DesktopBindings.defaults(),
                GameAction.MOVE_FORWARD, binding -> probe(pushedHard, binding)))
                .as("no keyboard key and no pad button is down, so nothing is held")
                .isFalse();
        }

        @Test
        @DisplayName("a touch-region binding in a desktop table degrades rather than throwing")
        void shouldDegradeAnUnanswerableSource()
        {
            // The rule this port already applied to a shared scheme, restated now
            // that there are five sources rather than three.
            final ActionBindings shared = new ActionBindings()
                .bind(GameAction.JUMP, InputBinding.touchRegion(2));
            assertThat(GdxInputPort.isAnyActive(shared, GameAction.JUMP,
                binding -> probe(new FakeGamepad(), binding))).isFalse();
        }

        @Test
        @DisplayName("a stick and the keyboard drive the player in the same tic")
        void shouldCombineBothDevicesInOneTic()
        {
            // The requirement, end to end at the port: an ADDITIONAL input path.
            // Walk with the stick, aim with the mouse, in one snapshot.
            final InputAccumulator accumulator = new InputAccumulator(1.0f);
            final GdxInputPort port = portOn(accumulator);

            port.pollGamepad(new FakeGamepad().withLeftStick(0.0f, -1.0f));
            accumulator.accumulateLook(30, 0);
            accumulator.setActionKeys(true, false, false);
            port.sampleInput(0);

            final InputState snapshot = port.currentInput();
            assertThat(snapshot.forwardAxis()).as("stick walks").isCloseTo(1.0f, within(EPSILON));
            assertThat(snapshot.yawDelta()).as("mouse turns").isPositive();
            assertThat(snapshot.fire()).as("mouse button fires").isTrue();
        }
    }

    // Stands in for the production dispatch, which is private and closes over
    // Gdx.input. Keys and mouse buttons read as up — there is no keyboard in a
    // headless JVM — which is precisely the case worth asserting: the pad alone
    // must be able to trigger an action.
    private static boolean probe(final GamepadSource pad, final InputBinding binding)
    {
        if (binding.source() == InputBinding.Source.GAMEPAD_BUTTON)
        {
            return pad.isButtonDown(binding.code());
        }
        if (binding.source() == InputBinding.Source.GAMEPAD_AXIS)
        {
            return pad.isAxisPressed(binding.code());
        }
        return false;
    }

    @Nested
    @DisplayName("the real GLFW-backed pad, without GLFW")
    class RealPadOffline
    {
        @Test
        @DisplayName("reports no controller and answers every accessor safely before anything is polled")
        void shouldBeSafeUnpolled()
        {
            // GlfwGamepad's constructor must touch no native call — a windowless
            // JVM constructs one every time GdxInputPort is created, including in
            // every test in this module.
            final GlfwGamepad pad = new GlfwGamepad();
            assertThat(pad.isConnected()).isFalse();
            assertThat(pad.slot()).isNegative();
            assertThat(pad.name()).isEqualTo(GlfwGamepad.NO_PAD);
            assertThat(pad.leftStickX()).isZero();
            assertThat(pad.leftStickY()).isZero();
            assertThat(pad.rightStickX()).isZero();
            assertThat(pad.rightStickY()).isZero();
            assertThat(pad.isButtonDown(GLFW.GLFW_GAMEPAD_BUTTON_A)).isFalse();
            assertThat(pad.didButtonGoDown(GLFW.GLFW_GAMEPAD_BUTTON_A)).isFalse();
            assertThat(pad.isAxisPressed(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER)).isFalse();
            assertThat(pad.toString()).contains(GlfwGamepad.NO_PAD);
        }

        @Test
        @DisplayName("an out-of-range button code is answered rather than throwing")
        void shouldBoundsCheckButtonCodes()
        {
            // A binding table is opaque data that can outlive the platform that
            // wrote it. A code from another platform must not be an
            // ArrayIndexOutOfBoundsException in the middle of a frame.
            final GlfwGamepad pad = new GlfwGamepad();
            assertThat(pad.isButtonDown(-1)).isFalse();
            assertThat(pad.isButtonDown(Input.Keys.BUTTON_A)).isFalse();
            assertThat(pad.didButtonGoDown(9999)).isFalse();
        }
    }
}
