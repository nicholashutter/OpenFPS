/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.openfps.engine.hal.port.GameAction;
import com.openfps.engine.hal.port.InputBinding;

/**
 * Tests for {@link ActionBindings} and {@link InputBinding}.
 *
 * <p>The behaviour worth protecting here is not "a map stores things". It is the
 * four decisions that would each produce a specific, hard-to-diagnose bug if
 * they were reversed: that a fresh table is empty rather than guessing at
 * defaults, that binding <b>replaces</b> rather than accumulates, that the
 * varargs array is copied so a caller cannot mutate a live scheme, and that a
 * source and a code together identify a control because a code alone does
 * not.</p>
 */
@DisplayName("ActionBindings")
class ActionBindingsTest
{
    /** A key code. Arbitrary — the engine never interprets one. */
    private static final int SOME_KEY = 51;

    /** A different key code. */
    private static final int OTHER_KEY = 62;

    @Nested
    @DisplayName("a fresh table")
    class Fresh
    {
        @Test
        @DisplayName("has nothing bound to any action")
        void shouldBindNothingWhenNewlyConstructed()
        {
            final ActionBindings bindings = new ActionBindings();

            for (final GameAction action : GameAction.values())
            {
                assertThat(bindings.isBound(action))
                    .as("%s must start unbound", action)
                    .isFalse();
                assertThat(bindings.bindingsFor(action)).isEmpty();
            }
        }

        @Test
        @DisplayName("is not complete, and names the first gap")
        void shouldReportIncompleteWhenNothingIsBound()
        {
            final ActionBindings bindings = new ActionBindings();

            assertThat(bindings.isComplete()).isFalse();
            // Declaration order, so the report is stable rather than whichever
            // gap the iteration happened to reach first.
            assertThat(bindings.firstUnbound()).isEqualTo(GameAction.values()[0]);
        }

        @Test
        @DisplayName("carries no platform defaults — that is the whole point")
        void shouldCarryNoPlatformDefaultsWhenConstructed()
        {
            // If this ever fails, someone has put a libGDX key constant into
            // :engine. The module does not depend on libGDX and must not: the
            // codes are the platform's, and only the platform knows them.
            assertThat(new ActionBindings().isBound(GameAction.FIRE)).isFalse();
            assertThat(new ActionBindings().isBound(GameAction.JUMP)).isFalse();
        }
    }

    @Nested
    @DisplayName("binding")
    class Binding
    {
        @Test
        @DisplayName("makes the action reachable through its control")
        void shouldReportTheControlWhenAnActionIsBound()
        {
            final ActionBindings bindings = new ActionBindings()
                .bind(GameAction.JUMP, InputBinding.key(SOME_KEY));

            assertThat(bindings.isBound(GameAction.JUMP)).isTrue();
            assertThat(bindings.bindingsFor(GameAction.JUMP))
                .containsExactly(InputBinding.key(SOME_KEY));
        }

        @Test
        @DisplayName("REPLACES the previous controls rather than adding to them")
        void shouldReplacePreviousControlsWhenRebinding()
        {
            final ActionBindings bindings = new ActionBindings()
                .bind(GameAction.JUMP, InputBinding.key(SOME_KEY))
                .bind(GameAction.JUMP, InputBinding.key(OTHER_KEY));

            // The behaviour a controls screen depends on. An additive API leaves
            // the old key working too, which is precisely what makes a rebind
            // feel broken to the player who just performed it.
            assertThat(bindings.bindingsFor(GameAction.JUMP))
                .containsExactly(InputBinding.key(OTHER_KEY));
        }

        @Test
        @DisplayName("with no controls unbinds the action")
        void shouldUnbindWhenGivenAnEmptyList()
        {
            final ActionBindings bindings = new ActionBindings()
                .bind(GameAction.SPRINT, InputBinding.key(SOME_KEY))
                .bind(GameAction.SPRINT);

            assertThat(bindings.isBound(GameAction.SPRINT)).isFalse();
        }

        @Test
        @DisplayName("keeps several alternates for one action")
        void shouldKeepEveryAlternateWhenAnActionHasSeveral()
        {
            final ActionBindings bindings = new ActionBindings().bind(GameAction.FIRE,
                InputBinding.mouseButton(0), InputBinding.key(SOME_KEY));

            assertThat(bindings.bindingsFor(GameAction.FIRE)).hasSize(2);
        }

        @Test
        @DisplayName("copies the caller's array, so a live scheme cannot be mutated behind it")
        void shouldCopyTheCallersArrayWhenBinding()
        {
            final InputBinding[] caller = { InputBinding.key(SOME_KEY) };
            final ActionBindings bindings = new ActionBindings().bind(GameAction.JUMP, caller);

            caller[0] = InputBinding.key(OTHER_KEY);

            // Without the copy this is a data race with a poll in progress: the
            // caller keeps a reference to the array the input port is reading.
            assertThat(bindings.bindingsFor(GameAction.JUMP))
                .containsExactly(InputBinding.key(SOME_KEY));
        }

        @Test
        @DisplayName("rejects more alternates than the per-frame poll is sized for")
        void shouldRejectTooManyBindingsWhenOverTheCap()
        {
            final InputBinding[] tooMany =
                new InputBinding[ActionBindings.MAX_BINDINGS_PER_ACTION + 1];
            for (int index = 0; index < tooMany.length; index++)
            {
                tooMany[index] = InputBinding.key(index);
            }

            assertThatThrownBy(() -> new ActionBindings().bind(GameAction.FIRE, tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most");
        }

        @Test
        @DisplayName("rejects a null action, a null array and a null entry")
        void shouldRejectNullsWhenBinding()
        {
            assertThatThrownBy(() -> new ActionBindings().bind(null, InputBinding.key(SOME_KEY)))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(
                () -> new ActionBindings().bind(GameAction.FIRE, (InputBinding[]) null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ActionBindings().bind(GameAction.FIRE,
                InputBinding.key(SOME_KEY), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("binding 1");
        }

        @Test
        @DisplayName("does not disturb any other action")
        void shouldLeaveOtherActionsAloneWhenBindingOne()
        {
            final ActionBindings bindings = new ActionBindings()
                .bind(GameAction.FIRE, InputBinding.mouseButton(0))
                .bind(GameAction.JUMP, InputBinding.key(SOME_KEY));

            assertThat(bindings.bindingsFor(GameAction.FIRE))
                .containsExactly(InputBinding.mouseButton(0));
        }
    }

    @Nested
    @DisplayName("completeness")
    class Completeness
    {
        @Test
        @DisplayName("is reported only when every action has a control")
        void shouldReportCompleteWhenEveryActionIsBound()
        {
            final ActionBindings bindings = new ActionBindings();
            for (final GameAction action : GameAction.values())
            {
                bindings.bind(action, InputBinding.key(action.ordinal()));
            }

            assertThat(bindings.isComplete()).isTrue();
            assertThat(bindings.firstUnbound()).isNull();
        }

        @Test
        @DisplayName("names the one missing action when a single gap is left")
        void shouldNameTheGapWhenOneActionIsUnbound()
        {
            final ActionBindings bindings = new ActionBindings();
            for (final GameAction action : GameAction.values())
            {
                if (action != GameAction.SPRINT)
                {
                    bindings.bind(action, InputBinding.key(action.ordinal()));
                }
            }

            assertThat(bindings.isComplete()).isFalse();
            assertThat(bindings.firstUnbound()).isEqualTo(GameAction.SPRINT);
        }
    }

    @Nested
    @DisplayName("InputBinding")
    class BindingValue
    {
        @Test
        @DisplayName("the same code on two different devices is two different controls")
        void shouldDistinguishSourcesWhenTheCodeIsTheSame()
        {
            // The reason a bare int is not enough. On the desktop backend mouse
            // button 0 is the left button and key 0 is ANY_KEY — collapse the
            // two and fire binds to every key on the keyboard.
            assertThat(InputBinding.key(0)).isNotEqualTo(InputBinding.mouseButton(0));
            assertThat(InputBinding.key(0).hashCode())
                .isNotEqualTo(InputBinding.mouseButton(0).hashCode());
        }

        @Test
        @DisplayName("equal source and code compare and hash equal")
        void shouldCompareEqualWhenSourceAndCodeMatch()
        {
            assertThat(InputBinding.key(SOME_KEY)).isEqualTo(InputBinding.key(SOME_KEY));
            assertThat(InputBinding.key(SOME_KEY).hashCode())
                .isEqualTo(InputBinding.key(SOME_KEY).hashCode());
        }

        @Test
        @DisplayName("the factories set the source they name")
        void shouldSetTheNamedSourceWhenUsingAFactory()
        {
            assertThat(InputBinding.key(1).source()).isEqualTo(InputBinding.Source.KEY);
            assertThat(InputBinding.mouseButton(1).source())
                .isEqualTo(InputBinding.Source.MOUSE_BUTTON);
            assertThat(InputBinding.touchRegion(1).source())
                .isEqualTo(InputBinding.Source.TOUCH_REGION);
            assertThat(InputBinding.key(7).code()).isEqualTo(7);
        }

        @Test
        @DisplayName("rejects a null source")
        void shouldRejectANullSourceWhenConstructed()
        {
            assertThatThrownBy(() -> new InputBinding(null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("GameAction")
    class Actions
    {
        @Test
        @DisplayName("exactly the four movement actions are axis contributors")
        void shouldMarkOnlyMovementActionsAsAxes()
        {
            assertThat(GameAction.MOVE_FORWARD.isAxis()).isTrue();
            assertThat(GameAction.MOVE_BACKWARD.isAxis()).isTrue();
            assertThat(GameAction.STRAFE_LEFT.isAxis()).isTrue();
            assertThat(GameAction.STRAFE_RIGHT.isAxis()).isTrue();

            assertThat(GameAction.FIRE.isAxis()).isFalse();
            assertThat(GameAction.JUMP.isAxis()).isFalse();
            assertThat(GameAction.SPRINT.isAxis()).isFalse();
            assertThat(GameAction.LEAVE_MATCH.isAxis()).isFalse();
        }

        @Test
        @DisplayName("there are four axis actions and four buttons")
        void shouldSplitEvenlyBetweenAxesAndButtons()
        {
            // Not arithmetic for its own sake: it fails when an action is added
            // without deciding which kind it is, which is the moment a bindings
            // UI would silently put it in the wrong group.
            int axes = 0;
            for (final GameAction action : GameAction.values())
            {
                if (action.isAxis())
                {
                    axes++;
                }
            }

            assertThat(axes).isEqualTo(4);
            assertThat(GameAction.values()).hasSize(8);
        }
    }
}
