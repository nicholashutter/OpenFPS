/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the settings screen says, which is all a headless test can see of it.
 *
 * <p>The layout needs a GL context and a pair of eyes. The <b>words</b> need
 * neither, and the words are where this screen has actually gone wrong: a toggle
 * that reported the wrong state, a hint that described behaviour the control no
 * longer had, and a visual aid labelled in developer language and filed under
 * developer tooling. All three are assertable from a plain JVM, so all three are
 * asserted here.</p>
 */
@DisplayName("what the settings screen says")
class SettingsScreenTextTest
{
    @Nested
    @DisplayName("the target outline, as a player reads it")
    class TargetOutline
    {
        @Test
        @DisplayName("is named for what it marks, not for what drew it")
        void shouldBeNamedInPlayerLanguage()
        {
            // It was "debug outline" while it lived on the debug switch. A player
            // who needs a visual aid should not have to recognise themselves in
            // the word "debug", and a player who does not need it learns nothing
            // from it either. "TARGET" is the word for the thing it marks.
            assertThat(SettingsScreen.OUTLINE_LABEL)
                .isEqualTo("TARGET OUTLINE")
                .doesNotContainIgnoringCase("debug");

            assertThat(SettingsScreen.OUTLINE_HINT)
                .as("and the hint says what it does, in the same language")
                .containsIgnoringCase("aiming at")
                .doesNotContainIgnoringCase("debug");
        }

        @Test
        @DisplayName("its button reports the state in force, so the screen cannot lie")
        void shouldReportTheStateNotTheAction()
        {
            // THE regression guard, in the place a player would notice it. The
            // symptom of the outline being gated by the debug switch was a button
            // that said OFF over a game drawing outlines.
            final AccessibilitySettings settings = new AccessibilitySettings();

            assertThat(SettingsScreen.outlineButtonLabel(settings))
                .as("on out of the box, and saying so")
                .isEqualTo("TARGET OUTLINE   ON");

            settings.toggleTargetOutline();

            assertThat(SettingsScreen.outlineButtonLabel(settings))
                .as("and it follows the switch rather than predicting the next press")
                .isEqualTo("TARGET OUTLINE   OFF");
        }

        @Test
        @DisplayName("rejects a null setting by name, because a label with no state is a lie")
        void shouldRejectNull()
        {
            assertThatThrownBy(() -> SettingsScreen.outlineButtonLabel(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accessibilitySettings");
        }
    }

    @Nested
    @DisplayName("the grouping")
    class Grouping
    {
        @Test
        @DisplayName("names an accessibility group, separate from the diagnostics one")
        void shouldNameTwoDistinctGroups()
        {
            // The heading is the whole of the separation, so it is worth pinning
            // that there really are two of them and that the accessible one says
            // the word a player would look for.
            assertThat(SettingsScreen.ACCESSIBILITY_GROUP)
                .isEqualTo("ACCESSIBILITY");

            assertThat(SettingsScreen.DISPLAY_GROUP)
                .isNotEqualTo(SettingsScreen.ACCESSIBILITY_GROUP)
                .containsIgnoringCase("diagnostics");
        }

        @Test
        @DisplayName("the debug overlay no longer claims to draw outlines")
        void shouldNotAdvertiseTheOutline()
        {
            // The hint used to end "and outlines around targets", because the
            // switch did both. It does not any more, and a hint left describing
            // the old behaviour would be the second place this screen lied about
            // the outline — harder to spot than the first, because a hint is
            // prose and nobody diffs prose against behaviour.
            assertThat(SettingsScreen.DEBUG_HINT)
                .doesNotContainIgnoringCase("outline");

            assertThat(SettingsScreen.debugButtonLabel(new DebugSettings()))
                .as("and its own button still starts off, as a diagnostic should")
                .isEqualTo("DEBUG OVERLAY   OFF");
        }
    }
}
