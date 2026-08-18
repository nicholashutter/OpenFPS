/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openfps.engine.hal.adapter.ActionBindings;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pins the round-trip, defaults, validation and immutability of
 * {@link PlayerSettings}. Lives next to the unit it tests so a future change
 * to either the file format or the validation rules is caught at the same
 * place it is made.
 */
final class PlayerSettingsTest
{
    @Nested
    final class Defaults
    {
        @Test
        void shouldStartAtDefaultSensitivityAndNonInverted()
        {
            final PlayerSettings settings = PlayerSettings.defaults(new ActionBindings());

            assertThat(settings.mouseSensitivityRadiansPerPixel())
                .isEqualTo(PlayerSettings.DEFAULT_SENSITIVITY_RADIANS_PER_PIXEL);

            assertThat(settings.invertY()).isFalse();
        }

        @Test
        void shouldStartWithNoBindings()
        {
            final PlayerSettings settings = PlayerSettings.defaults(new ActionBindings());

            for (final GameAction action : GameAction.values())
            {
                assertThat(settings.bindings().isBound(action))
                    .as("default for %s is unbound", action)
                    .isFalse();
            }
        }

        @Test
        void shouldRejectNullPlatformBindings()
        {
            assertThatThrownBy(() -> PlayerSettings.defaults(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platformBindings");
        }

        @Test
        void shouldUseTheSuppliedPlatformBindingsAsItsOwn()
        {
            final ActionBindings platform = new ActionBindings().bind(
                GameAction.FIRE, InputBinding.key(42));

            final PlayerSettings settings = PlayerSettings.defaults(platform);

            assertThat(settings.bindings()).isEqualTo(platform);

            assertThat(settings.bindings().isBound(GameAction.FIRE)).isTrue();
        }
    }

    @Nested
    final class Construction
    {
        @Test
        void shouldRejectNullBindings()
        {
            assertThatThrownBy(() -> new PlayerSettings(null, 0.005f, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bindings");
        }

        @Test
        void shouldRejectSensitivityAtOrBelowMinimum()
        {
            assertThatThrownBy(() -> new PlayerSettings(new ActionBindings(),
                PlayerSettings.MIN_SENSITIVITY_RADIANS_PER_PIXEL * 0.5f, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mouseSensitivityRadiansPerPixel");

            assertThatThrownBy(() -> new PlayerSettings(new ActionBindings(), 0.0f, false))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new PlayerSettings(new ActionBindings(), -0.01f, false))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldRejectSensitivityAtOrAboveMaximum()
        {
            assertThatThrownBy(() -> new PlayerSettings(new ActionBindings(),
                PlayerSettings.MAX_SENSITIVITY_RADIANS_PER_PIXEL * 1.5f, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mouseSensitivityRadiansPerPixel");
        }

        @Test
        void shouldRejectNonFiniteSensitivity()
        {
            assertThatThrownBy(() -> new PlayerSettings(new ActionBindings(),
                Float.NaN, false))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new PlayerSettings(new ActionBindings(),
                Float.POSITIVE_INFINITY, false))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldAcceptBoundarySensitivities()
        {
            final PlayerSettings min = new PlayerSettings(new ActionBindings(),
                PlayerSettings.MIN_SENSITIVITY_RADIANS_PER_PIXEL, false);

            assertThat(min.mouseSensitivityRadiansPerPixel())
                .isEqualTo(PlayerSettings.MIN_SENSITIVITY_RADIANS_PER_PIXEL);

            final PlayerSettings max = new PlayerSettings(new ActionBindings(),
                PlayerSettings.MAX_SENSITIVITY_RADIANS_PER_PIXEL, false);

            assertThat(max.mouseSensitivityRadiansPerPixel())
                .isEqualTo(PlayerSettings.MAX_SENSITIVITY_RADIANS_PER_PIXEL);
        }
    }

    @Nested
    final class WithSetters
    {
        @Test
        void shouldReturnFreshInstanceOnWithBindings()
        {
            final PlayerSettings original = PlayerSettings.defaults(new ActionBindings());

            final ActionBindings rebound = new ActionBindings().bind(
                GameAction.FIRE, InputBinding.key(42));

            final PlayerSettings updated = original.withBindings(rebound);

            assertThat(updated).isNotSameAs(original);

            assertThat(updated.bindings().isBound(GameAction.FIRE)).isTrue();

            assertThat(original.bindings().isBound(GameAction.FIRE)).isFalse();
        }

        @Test
        void shouldReturnFreshInstanceOnWithSensitivity()
        {
            final PlayerSettings original = PlayerSettings.defaults(new ActionBindings());

            final PlayerSettings updated = original.withSensitivity(0.01f);

            assertThat(updated).isNotSameAs(original);

            assertThat(updated.mouseSensitivityRadiansPerPixel()).isEqualTo(0.01f);

            assertThat(original.mouseSensitivityRadiansPerPixel())
                .isEqualTo(PlayerSettings.DEFAULT_SENSITIVITY_RADIANS_PER_PIXEL);
        }

        @Test
        void shouldRejectOutOfRangeSensitivityAtWithSetter()
        {
            // The with* setters route through the constructor; loud failure
            // is the contract rather than a silent clamp.
            assertThatThrownBy(() -> PlayerSettings.defaults(new ActionBindings()).withSensitivity(0.5f))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldFlipInvertYAtWithSetter()
        {
            final PlayerSettings flipped = PlayerSettings.defaults(new ActionBindings()).withInvertY(true);

            assertThat(flipped.invertY()).isTrue();

            assertThat(PlayerSettings.defaults(new ActionBindings()).invertY()).isFalse();
        }

        @Test
        void shouldRejectNullBindingsAtWithSetter()
        {
            assertThatThrownBy(() -> PlayerSettings.defaults(new ActionBindings()).withBindings(null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    final class RoundTrip
    {
        @Test
        void shouldRoundTripDefaultsThroughText()
        {
            final PlayerSettings original = PlayerSettings.defaults(new ActionBindings());

            final String spec = original.toSpec();

            final PlayerSettings parsed = PlayerSettings.fromSpec(spec);

            assertThat(parsed).isEqualTo(original);
        }

        @Test
        void shouldRoundTripInvertYThroughText()
        {
            final PlayerSettings original = PlayerSettings.defaults(new ActionBindings()).withInvertY(true);

            final PlayerSettings parsed = PlayerSettings.fromSpec(original.toSpec());

            assertThat(parsed).isEqualTo(original);
        }

        @Test
        void shouldRoundTripCustomSensitivityThroughText()
        {
            final PlayerSettings original = PlayerSettings.defaults(new ActionBindings()).withSensitivity(0.012f);

            final PlayerSettings parsed = PlayerSettings.fromSpec(original.toSpec());

            assertThat(parsed.mouseSensitivityRadiansPerPixel()).isEqualTo(0.012f);
        }

        @Test
        void shouldRoundTripBoundActionThroughText()
        {
            final ActionBindings bound = new ActionBindings().bind(
                GameAction.FIRE, InputBinding.key(42));

            final PlayerSettings original = PlayerSettings.defaults(new ActionBindings()).withBindings(bound);

            final PlayerSettings parsed = PlayerSettings.fromSpec(original.toSpec());

            assertThat(parsed.bindings().isBound(GameAction.FIRE)).isTrue();

            final InputBinding[] row = parsed.bindings().bindingsFor(GameAction.FIRE);

            assertThat(row).hasSize(1);

            assertThat(row[0].source()).isEqualTo(InputBinding.Source.KEY);

            assertThat(row[0].code()).isEqualTo(42);
        }

        @Test
        void shouldRoundTripMultipleBindingsOnSameActionThroughText()
        {
            final ActionBindings bound = new ActionBindings().bind(
                GameAction.FIRE,
                InputBinding.key(42),
                InputBinding.mouseButton(0));

            final PlayerSettings original = PlayerSettings.defaults(new ActionBindings()).withBindings(bound);

            final PlayerSettings parsed = PlayerSettings.fromSpec(original.toSpec());

            final InputBinding[] row = parsed.bindings().bindingsFor(GameAction.FIRE);

            assertThat(row).hasSize(2);

            assertThat(row[0].source()).isEqualTo(InputBinding.Source.KEY);

            assertThat(row[0].code()).isEqualTo(42);

            assertThat(row[1].source()).isEqualTo(InputBinding.Source.MOUSE_BUTTON);

            assertThat(row[1].code()).isEqualTo(0);
        }

        @Test
        void shouldRoundTripGamepadBindingThroughText()
        {
            // The whole point of the XInput-friendly enum is that pad codes
            // round-trip without help from a desktop-specific constant.
            final ActionBindings bound = new ActionBindings().bind(
                GameAction.FIRE, InputBinding.gamepadButton(7));

            final PlayerSettings original = PlayerSettings.defaults(new ActionBindings()).withBindings(bound);

            final PlayerSettings parsed = PlayerSettings.fromSpec(original.toSpec());

            final InputBinding[] row = parsed.bindings().bindingsFor(GameAction.FIRE);

            assertThat(row).hasSize(1);

            assertThat(row[0].source()).isEqualTo(InputBinding.Source.GAMEPAD_BUTTON);

            assertThat(row[0].code()).isEqualTo(7);
        }
    }

    @Nested
    final class Parsing
    {
        @Test
        void shouldParseEmptyInputAsDefaults()
        {
            final PlayerSettings parsed = PlayerSettings.fromSpec("");

            assertThat(parsed).isEqualTo(PlayerSettings.defaults(new ActionBindings()));
        }

        @Test
        void shouldSkipCommentLines()
        {
            final PlayerSettings parsed = PlayerSettings.fromSpec(
                "# this is a comment\n"
                + "   # indented comment\n"
                + "mouse_sensitivity_radians_per_pixel=0.01\n");

            assertThat(parsed.mouseSensitivityRadiansPerPixel()).isEqualTo(0.01f);
        }

        @Test
        void shouldParseAllThreeLinesInAnyOrder()
        {
            final PlayerSettings parsed = PlayerSettings.fromSpec(
                "invert_y=true\n"
                + "mouse_sensitivity_radians_per_pixel=0.015\n"
                + "bindings=FIRE:KEY:42\n");

            assertThat(parsed.invertY()).isTrue();

            assertThat(parsed.mouseSensitivityRadiansPerPixel()).isEqualTo(0.015f);

            assertThat(parsed.bindings().isBound(GameAction.FIRE)).isTrue();
        }

        @Test
        void shouldParseUnboundActionLine()
        {
            // bindings=ACTION: with no codes must round-trip to an unbound
            // action rather than throwing — the empty row is legal.
            final PlayerSettings parsed = PlayerSettings.fromSpec("bindings=FIRE:\n");

            assertThat(parsed.bindings().isBound(GameAction.FIRE)).isFalse();
        }

        @Test
        void shouldThrowWithLineNumberOnMalformedSensitivity()
        {
            assertThatThrownBy(() -> PlayerSettings.fromSpec("mouse_sensitivity_radians_per_pixel=abc\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line 1")
                .hasMessageContaining("sensitivity");
        }

        @Test
        void shouldThrowWithLineNumberOnUnknownAction()
        {
            assertThatThrownBy(() -> PlayerSettings.fromSpec("bindings=NOT_A_REAL_ACTION:KEY:1\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line 1")
                .hasMessageContaining("unknown action");
        }

        @Test
        void shouldThrowWithLineNumberOnUnknownSource()
        {
            assertThatThrownBy(() -> PlayerSettings.fromSpec("bindings=FIRE:MAGIC:1\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line 1")
                .hasMessageContaining("unknown source");
        }

        @Test
        void shouldThrowOnLineMissingEqualsSign()
        {
            assertThatThrownBy(() -> PlayerSettings.fromSpec("just a line\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing '='");
        }

        @Test
        void shouldThrowOnUnknownKey()
        {
            assertThatThrownBy(() -> PlayerSettings.fromSpec("not_a_real_key=1\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown key");
        }

        @Test
        void shouldThrowOnEmptyBindingEntry()
        {
            assertThatThrownBy(() -> PlayerSettings.fromSpec("bindings=FIRE:KEY:1,,KEY:3\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty binding");
        }

        @Test
        void shouldThrowOnBindingMissingSourceColon()
        {
            assertThatThrownBy(() -> PlayerSettings.fromSpec("bindings=FIRE:KEY42\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing SOURCE:CODE");
        }

        @Test
        void shouldThrowOnBindingCodeNotAnInteger()
        {
            assertThatThrownBy(() -> PlayerSettings.fromSpec("bindings=FIRE:KEY:abc\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an integer");
        }

        @Test
        void shouldThrowOnOutOfRangeSensitivity()
        {
            // The constructor validates the bound; fromSpec surfaces the
            // same exception without a line number, because the value
            // parsed is the value validated.
            assertThatThrownBy(() -> PlayerSettings.fromSpec(
                "mouse_sensitivity_radians_per_pixel=999.0\n"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    final class Equality
    {
        @Test
        void shouldBeEqualToItself()
        {
            final PlayerSettings s = PlayerSettings.defaults(new ActionBindings());

            assertThat(s).isEqualTo(s);

            assertThat(s.hashCode()).isEqualTo(s.hashCode());
        }

        @Test
        void shouldNotBeEqualToADifferentType()
        {
            assertThat(PlayerSettings.defaults(new ActionBindings()).equals("not a settings")).isFalse();
        }

        @Test
        void shouldNotBeEqualWhenSensitivityDiffers()
        {
            final PlayerSettings a = PlayerSettings.defaults(new ActionBindings());

            final PlayerSettings b = a.withSensitivity(0.01f);

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void shouldNotBeEqualWhenInvertYDiffers()
        {
            final PlayerSettings a = PlayerSettings.defaults(new ActionBindings());

            final PlayerSettings b = a.withInvertY(true);

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void shouldNotBeEqualWhenBindingsDiffer()
        {
            final PlayerSettings a = PlayerSettings.defaults(new ActionBindings());

            final PlayerSettings b = a.withBindings(
                new ActionBindings().bind(GameAction.FIRE, InputBinding.key(1)));

            assertThat(a).isNotEqualTo(b);
        }
    }
}
