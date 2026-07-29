/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Input;

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
        @DisplayName("jump is the space bar")
        void shouldBindJumpToSpaceWhenUsingDefaults()
        {
            assertThat(DesktopBindings.defaults().bindingsFor(GameAction.JUMP))
                .containsExactly(InputBinding.key(Input.Keys.SPACE));
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
        @DisplayName("fire also answers to left control, for trackpads")
        void shouldOfferAKeyboardAlternateForFireWhenUsingDefaults()
        {
            assertThat(DesktopBindings.defaults().bindingsFor(GameAction.FIRE))
                .contains(InputBinding.key(Input.Keys.CONTROL_LEFT))
                .hasSize(2);
        }

        @Test
        @DisplayName("leaving the match is Escape — the only way out of a captured cursor")
        void shouldBindLeaveMatchToEscapeWhenUsingDefaults()
        {
            assertThat(DesktopBindings.defaults().bindingsFor(GameAction.LEAVE_MATCH))
                .containsExactly(InputBinding.key(Input.Keys.ESCAPE));
        }

        @Test
        @DisplayName("movement is WASD with the arrow keys as alternates")
        void shouldBindMovementToWasdAndArrowsWhenUsingDefaults()
        {
            final ActionBindings bindings = DesktopBindings.defaults();

            assertThat(bindings.bindingsFor(GameAction.MOVE_FORWARD))
                .containsExactly(InputBinding.key(Input.Keys.W), InputBinding.key(Input.Keys.UP));
            assertThat(bindings.bindingsFor(GameAction.MOVE_BACKWARD))
                .containsExactly(InputBinding.key(Input.Keys.S), InputBinding.key(Input.Keys.DOWN));
            assertThat(bindings.bindingsFor(GameAction.STRAFE_LEFT))
                .containsExactly(InputBinding.key(Input.Keys.A), InputBinding.key(Input.Keys.LEFT));
            assertThat(bindings.bindingsFor(GameAction.STRAFE_RIGHT))
                .containsExactly(InputBinding.key(Input.Keys.D),
                    InputBinding.key(Input.Keys.RIGHT));
        }

        @Test
        @DisplayName("hands out a fresh table each call, so two players cannot share one scheme")
        void shouldReturnAFreshTableWhenCalledTwice()
        {
            final ActionBindings first = DesktopBindings.defaults();
            final ActionBindings second = DesktopBindings.defaults();

            first.bind(GameAction.JUMP, InputBinding.key(Input.Keys.J));

            assertThat(second.bindingsFor(GameAction.JUMP))
                .containsExactly(InputBinding.key(Input.Keys.SPACE));
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
