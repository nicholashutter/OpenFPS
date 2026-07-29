/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Input;

import org.lwjgl.glfw.GLFW;

import com.openfps.engine.hal.adapter.ActionBindings;
import com.openfps.engine.hal.port.GameAction;
import com.openfps.engine.hal.port.InputBinding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DesktopBindings} and for the binding-resolution loop in
 * {@link GdxInputPort}.
 *
 * <p>These run headless. {@code Input.Keys} is a table of {@code int} constants
 * with no native code behind it, so naming one costs nothing; the actual GLFW
 * query is the part that needs a window, and {@link GdxInputPort.ControlProbe}
 * is the seam that keeps it out of the way. What is under test here is the
 * decision — which control triggers which action, and whether any of an action's
 * alternates is enough — not the {@code glfwGetKey} call.</p>
 */
@DisplayName("DesktopBindings")
class DesktopBindingsTest
{
    /**
     * A probe that reports a fixed set of controls as active.
     *
     * Stands in for {@code Gdx.input} so the resolution loop can be driven
     * without a window.
     */
    private static final class HeldControls implements GdxInputPort.ControlProbe
    {
        private final List<InputBinding> down = new ArrayList<>();

        private HeldControls press(final InputBinding binding)
        {
            down.add(binding);
            return this;
        }

        @Override
        public boolean isActive(final InputBinding binding)
        {
            return down.contains(binding);
        }
    }

    @Nested
    @DisplayName("the default scheme")
    class Defaults
    {
        @Test
        @DisplayName("binds every action, so no control is unreachable")
        void shouldBindEveryActionWhenBuildingDefaults()
        {
            final ActionBindings bindings = DesktopBindings.defaults();

            assertThat(bindings.isComplete())
                .as("unbound: %s", bindings.firstUnbound())
                .isTrue();
        }

        @Test
        @DisplayName("fire is the left mouse button")
        void shouldBindFireToTheLeftMouseButtonWhenUsingDefaults()
        {
            assertThat(DesktopBindings.defaults().bindingsFor(GameAction.FIRE))
                .contains(InputBinding.mouseButton(Input.Buttons.LEFT));
        }

        @Test
        @DisplayName("jump is the space bar, with the pad's bottom face button alongside")
        void shouldBindJumpToSpaceWhenUsingDefaults()
        {
            assertThat(DesktopBindings.defaults().bindingsFor(GameAction.JUMP))
                .startsWith(InputBinding.key(Input.Keys.SPACE))
                .contains(InputBinding.gamepadButton(GLFW.GLFW_GAMEPAD_BUTTON_A));
        }

        @Test
        @DisplayName("the invert-look toggle is I, and it is bound at all")
        void shouldBindInvertLookToIWhenUsingDefaults()
        {
            // Bound rather than merely declared is the point. An unbound
            // toggle would leave "is my mouse inverted?" answerable only by
            // editing source and rebuilding, which is how the wrong sign came
            // to ship undetected in the first place.
            assertThat(DesktopBindings.defaults().bindingsFor(GameAction.TOGGLE_INVERT_LOOK))
                .containsExactly(InputBinding.key(Input.Keys.I));
        }

        @Test
        @DisplayName("fire answers to left control for trackpads, and to a trigger and a face button")
        void shouldOfferAKeyboardAlternateForFireWhenUsingDefaults()
        {
            // Four alternates — the cap ActionBindings sets — and the reason the
            // cap is four: a primary, a device alternate, and a pad needing two
            // of its own because a trigger and a face button are different
            // sources.
            assertThat(DesktopBindings.defaults().bindingsFor(GameAction.FIRE))
                .contains(InputBinding.key(Input.Keys.CONTROL_LEFT))
                .hasSize(4);
        }

        @Test
        @DisplayName("leaving the match is Escape, or either of the pad's two menu buttons")
        void shouldBindLeaveMatchToEscapeWhenUsingDefaults()
        {
            // Escape stays first and stays the documented way out of a captured
            // cursor. Start AND Back because pads label and place those two very
            // differently, and being unable to leave a match is not cosmetic.
            assertThat(DesktopBindings.defaults().bindingsFor(GameAction.LEAVE_MATCH))
                .startsWith(InputBinding.key(Input.Keys.ESCAPE))
                .contains(InputBinding.gamepadButton(GLFW.GLFW_GAMEPAD_BUTTON_START),
                    InputBinding.gamepadButton(GLFW.GLFW_GAMEPAD_BUTTON_BACK));
        }

        @Test
        @DisplayName("movement is WASD, the arrow keys, and the left stick")
        void shouldBindMovementToWasdAndArrowsWhenUsingDefaults()
        {
            final ActionBindings bindings = DesktopBindings.defaults();
            // All four name the SAME stick, which on a stick is the literal
            // truth: the four directions are one control read four ways.
            final InputBinding stick =
                InputBinding.gamepadAxis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X);

            assertThat(bindings.bindingsFor(GameAction.MOVE_FORWARD))
                .startsWith(InputBinding.key(Input.Keys.W), InputBinding.key(Input.Keys.UP))
                .contains(stick);
            assertThat(bindings.bindingsFor(GameAction.MOVE_BACKWARD))
                .startsWith(InputBinding.key(Input.Keys.S), InputBinding.key(Input.Keys.DOWN))
                .contains(stick);
            assertThat(bindings.bindingsFor(GameAction.STRAFE_LEFT))
                .startsWith(InputBinding.key(Input.Keys.A), InputBinding.key(Input.Keys.LEFT))
                .contains(stick);
            assertThat(bindings.bindingsFor(GameAction.STRAFE_RIGHT))
                .startsWith(InputBinding.key(Input.Keys.D), InputBinding.key(Input.Keys.RIGHT))
                .contains(stick);
        }

        @Test
        @DisplayName("sprint is a shoulder rather than a stick click, so it is not under the aiming thumb")
        void shouldBindSprintToAShoulder()
        {
            assertThat(DesktopBindings.defaults().bindingsFor(GameAction.SPRINT))
                .startsWith(InputBinding.key(Input.Keys.SHIFT_LEFT))
                .contains(InputBinding.gamepadButton(GLFW.GLFW_GAMEPAD_BUTTON_LEFT_BUMPER));
        }

        @Test
        @DisplayName("no action exceeds the four-binding cap")
        void shouldStayWithinTheBindingCap()
        {
            // Adding pad alternates to a table that already had keyboard ones is
            // exactly how a row quietly overflows. bind() throws, so this would
            // fail loudly — but it would fail at startup rather than in review.
            final ActionBindings bindings = DesktopBindings.defaults();
            for (final GameAction action : GameAction.values())
            {
                assertThat(bindings.bindingsFor(action))
                    .as("%s", action)
                    .hasSizeLessThanOrEqualTo(ActionBindings.MAX_BINDINGS_PER_ACTION);
            }
        }

        @Test
        @DisplayName("hands out a fresh table each call, so two players cannot share one scheme")
        void shouldReturnAFreshTableWhenCalledTwice()
        {
            final ActionBindings first = DesktopBindings.defaults();
            final ActionBindings second = DesktopBindings.defaults();

            first.bind(GameAction.JUMP, InputBinding.key(Input.Keys.J));

            assertThat(second.bindingsFor(GameAction.JUMP))
                .startsWith(InputBinding.key(Input.Keys.SPACE));
        }
    }

    @Nested
    @DisplayName("resolving an action against held controls")
    class Resolution
    {
        @Test
        @DisplayName("either alternate on its own triggers the action")
        void shouldTriggerOnEitherAlternateWhenOnlyOneIsHeld()
        {
            final ActionBindings bindings = DesktopBindings.defaults();

            final HeldControls mouseOnly =
                new HeldControls().press(InputBinding.mouseButton(Input.Buttons.LEFT));
            final HeldControls keyOnly =
                new HeldControls().press(InputBinding.key(Input.Keys.CONTROL_LEFT));

            // Alternates, not a chord. Requiring both would turn the trackpad
            // fallback into a way of stopping the mouse working.
            assertThat(GdxInputPort.isAnyActive(bindings, GameAction.FIRE, mouseOnly)).isTrue();
            assertThat(GdxInputPort.isAnyActive(bindings, GameAction.FIRE, keyOnly)).isTrue();
        }

        @Test
        @DisplayName("nothing held triggers nothing")
        void shouldTriggerNothingWhenNoControlIsHeld()
        {
            final HeldControls none = new HeldControls();

            for (final GameAction action : GameAction.values())
            {
                assertThat(GdxInputPort.isAnyActive(DesktopBindings.defaults(), action, none))
                    .as("%s must be inactive when nothing is pressed", action)
                    .isFalse();
            }
        }

        @Test
        @DisplayName("an unbound action never triggers, whatever is held")
        void shouldNeverTriggerAnUnboundActionWhenControlsAreHeld()
        {
            final ActionBindings cleared =
                DesktopBindings.defaults().bind(GameAction.SPRINT);
            final HeldControls everything =
                new HeldControls().press(InputBinding.key(Input.Keys.SHIFT_LEFT));

            // The safe direction. A player who clears their sprint key must
            // never sprint, not always sprint.
            assertThat(GdxInputPort.isAnyActive(cleared, GameAction.SPRINT, everything)).isFalse();
        }

        @Test
        @DisplayName("a rebind takes effect and the old control goes dead")
        void shouldFollowTheRebindWhenAnActionIsMoved()
        {
            final ActionBindings rebound = DesktopBindings.defaults()
                .bind(GameAction.JUMP, InputBinding.key(Input.Keys.J));

            final HeldControls oldKey =
                new HeldControls().press(InputBinding.key(Input.Keys.SPACE));
            final HeldControls newKey = new HeldControls().press(InputBinding.key(Input.Keys.J));

            assertThat(GdxInputPort.isAnyActive(rebound, GameAction.JUMP, newKey)).isTrue();
            assertThat(GdxInputPort.isAnyActive(rebound, GameAction.JUMP, oldKey)).isFalse();
        }

        @Test
        @DisplayName("a touch-region binding in a desktop scheme is inert, not fatal")
        void shouldIgnoreATouchBindingWhenResolvingOnDesktop()
        {
            // A shared scheme should degrade rather than crash. The desktop
            // probe answers false for a source it cannot query.
            final ActionBindings mixed = DesktopBindings.defaults()
                .bind(GameAction.FIRE, InputBinding.touchRegion(1));
            final HeldControls nothingRelevant =
                new HeldControls().press(InputBinding.mouseButton(Input.Buttons.LEFT));

            assertThat(GdxInputPort.isAnyActive(mixed, GameAction.FIRE, nothingRelevant)).isFalse();
        }
    }

    @Nested
    @DisplayName("the port's scheme")
    class PortScheme
    {
        @Test
        @DisplayName("starts on the desktop defaults")
        void shouldStartOnDesktopDefaultsWhenConstructed()
        {
            assertThat(new GdxInputPort().actionBindings().isComplete()).isTrue();
        }

        @Test
        @DisplayName("can be replaced wholesale")
        void shouldAdoptANewSchemeWhenRebound()
        {
            final GdxInputPort port = new GdxInputPort();
            final ActionBindings custom = new ActionBindings()
                .bind(GameAction.FIRE, InputBinding.key(Input.Keys.Z));

            port.bindActions(custom);

            assertThat(port.actionBindings()).isSameAs(custom);
        }
    }
}
