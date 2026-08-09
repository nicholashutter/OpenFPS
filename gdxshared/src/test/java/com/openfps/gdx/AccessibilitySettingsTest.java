/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AccessibilitySettings} — the switches that make the game
 * readable rather than inspectable.
 *
 * <p>Small, and worth covering for two reasons. The first is the one
 * {@link DebugSettings} records: the observer is what puts the software
 * renderer's outline pass behind a toggle without either side importing the
 * other, and <b>when</b> it fires is the whole of that coupling.</p>
 *
 * <p>The second is why this class exists at all. The outline used to be a second
 * meaning bolted onto the debug switch, and the switch defaulted off while the
 * renderer defaulted on — so at startup the settings screen said {@code OFF}
 * over a game that was drawing outlines. The default here is therefore not a
 * detail to be left implicit: it is the property that was wrong, and
 * {@link Defaults} is the test that says so out loud.</p>
 */
@DisplayName("AccessibilitySettings")
class AccessibilitySettingsTest
{
    /** Records every value the observer is told. */
    private static final class Recorder implements java.util.function.Consumer<Boolean>
    {
        /** What the observer received, in order. MUTABLE: appended per call. */
        private final List<Boolean> seen = new ArrayList<>();

        @Override
        public void accept(final Boolean value)
        {
            seen.add(value);
        }
    }

    @Nested
    @DisplayName("defaults — the half of this that was broken")
    class Defaults
    {
        @Test
        @DisplayName("the target outline is ON out of the box, because it is a standard feature")
        void shouldStartOn()
        {
            // A player who needs the aid must not have to find it first, and a
            // player who does not need it can turn it off in one press. That is
            // the asymmetry that decides the default, and it is the opposite of
            // the one that decides DebugSettings' default — which is exactly why
            // the two cannot be the same boolean.
            final AccessibilitySettings settings = new AccessibilitySettings();

            assertThat(settings.isTargetOutlineVisible()).isTrue();
        }

        @Test
        @DisplayName("and its label agrees with it, so the screen is not lying on frame one")
        void shouldLabelTheDefaultTruthfully()
        {
            // THE regression guard. The symptom of the outline living on the
            // debug switch was a button reading OFF next to a game drawing
            // outlines: two defaults that disagreed, with nothing asserting that
            // they had to match. This is that assertion.
            final AccessibilitySettings settings = new AccessibilitySettings();

            assertThat(settings.targetOutlineLabel()).isEqualTo("ON");

            assertThat(settings.isTargetOutlineVisible()).isTrue();
        }

        @Test
        @DisplayName("its default is the opposite of the debug overlay's, which is the point")
        void shouldNotShareTheDebugDefault()
        {
            // Not a tautology dressed as a test. If someone later "tidies up" by
            // making these agree, or by merging the two classes back together,
            // this is the line that says the disagreement was deliberate.
            assertThat(new AccessibilitySettings().isTargetOutlineVisible())
                .as("a visual aid is on by default")
                .isNotEqualTo(new DebugSettings().isOverlayVisible());
        }
    }

    @Nested
    @DisplayName("the switch itself")
    class Switching
    {
        @Test
        @DisplayName("toggling returns the new value, so a caller need not read it back")
        void shouldReturnTheNewValueFromToggle()
        {
            final AccessibilitySettings settings = new AccessibilitySettings();

            assertThat(settings.toggleTargetOutline()).isFalse();

            assertThat(settings.toggleTargetOutline()).isTrue();
        }

        @Test
        @DisplayName("the label reports the state, not the action")
        void shouldLabelTheState()
        {
            // The distinction SettingsScreen turns on: a toggle labelled with
            // its action makes the reader invert it to work out where they are,
            // and they get it wrong about half the time.
            final AccessibilitySettings settings = new AccessibilitySettings();

            settings.setTargetOutlineVisible(false);

            assertThat(settings.targetOutlineLabel()).isEqualTo("OFF");

            settings.setTargetOutlineVisible(true);

            assertThat(settings.targetOutlineLabel()).isEqualTo("ON");
        }

        @Test
        @DisplayName("says which aid it is holding, so a log line is readable")
        void shouldDescribeItself()
        {
            final AccessibilitySettings settings = new AccessibilitySettings();

            assertThat(settings.toString()).contains("targetOutline").contains("ON");
        }
    }

    @Nested
    @DisplayName("the change observer")
    class Observer
    {
        @Test
        @DisplayName("is not fired on attach, so a renderer default survives startup")
        void shouldNotFireOnAttach()
        {
            // The same rule DebugSettings follows, and the reason the composition
            // root has to push the initial value across itself. Without that push
            // the label and the outline agree only by coincidence — and this test
            // is what makes the coincidence a documented one rather than a
            // surprise.
            final AccessibilitySettings settings = new AccessibilitySettings();

            final Recorder recorder = new Recorder();

            settings.onChange(recorder);

            assertThat(recorder.seen).isEmpty();
        }

        @Test
        @DisplayName("is told each genuine change, in order")
        void shouldReportEveryChange()
        {
            final AccessibilitySettings settings = new AccessibilitySettings();

            final Recorder recorder = new Recorder();

            settings.onChange(recorder);

            settings.toggleTargetOutline();

            settings.toggleTargetOutline();

            settings.setTargetOutlineVisible(false);

            assertThat(recorder.seen).containsExactly(Boolean.FALSE, Boolean.TRUE,
                Boolean.FALSE);
        }

        @Test
        @DisplayName("is not fired when a caller reasserts the current value")
        void shouldIgnoreANoOpSet()
        {
            // Both launchers reassert the current value at startup on purpose, so
            // this is not a hypothetical caller: it is the one that fixed the
            // label. It has to be free.
            final AccessibilitySettings settings = new AccessibilitySettings();

            final Recorder recorder = new Recorder();

            settings.onChange(recorder);

            settings.setTargetOutlineVisible(true);

            assertThat(recorder.seen).isEmpty();

            settings.setTargetOutlineVisible(false);

            settings.setTargetOutlineVisible(false);

            assertThat(recorder.seen).containsExactly(Boolean.FALSE);
        }

        @Test
        @DisplayName("detaching stops the reports without changing the switch")
        void shouldStopReportingWhenDetached()
        {
            final AccessibilitySettings settings = new AccessibilitySettings();

            final Recorder recorder = new Recorder();

            settings.onChange(recorder);

            settings.toggleTargetOutline();

            settings.onChange(null);

            settings.toggleTargetOutline();

            assertThat(recorder.seen).containsExactly(Boolean.FALSE);

            assertThat(settings.isTargetOutlineVisible()).isTrue();
        }

        @Test
        @DisplayName("a switch with no observer still works")
        void shouldWorkWithNoObserver()
        {
            final AccessibilitySettings settings = new AccessibilitySettings();

            settings.toggleTargetOutline();

            assertThat(settings.isTargetOutlineVisible()).isFalse();
        }
    }
}
