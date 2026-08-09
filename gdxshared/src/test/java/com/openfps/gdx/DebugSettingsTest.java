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
 * Tests for {@link DebugSettings} — the switch behind the debug toggle.
 *
 * <p>Small, and worth covering for one reason: the observer is what puts the
 * software renderer's outline pass behind the same toggle without either side
 * importing the other, and <b>when</b> it fires is the whole of that coupling.
 * Firing on attach would silently override whatever outline default the renderer
 * was built with, on every startup, and firing on a no-op set would have the
 * renderer reasserting its mode for nothing.</p>
 */
@DisplayName("DebugSettings")
class DebugSettingsTest
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
    @DisplayName("the switch itself")
    class Switching
    {
        @Test
        @DisplayName("starts off, which is what a player expects")
        void shouldStartOff()
        {
            final DebugSettings settings = new DebugSettings();

            assertThat(settings.isOverlayVisible()).isFalse();

            assertThat(settings.overlayLabel()).isEqualTo("OFF");
        }

        @Test
        @DisplayName("toggling returns the new value, so a caller need not read it back")
        void shouldReturnTheNewValueFromToggle()
        {
            final DebugSettings settings = new DebugSettings();

            assertThat(settings.toggleOverlay()).isTrue();

            assertThat(settings.toggleOverlay()).isFalse();
        }

        @Test
        @DisplayName("the label reports the state, not the action")
        void shouldLabelTheState()
        {
            // The distinction SettingsScreen turns on: a toggle labelled with
            // its action makes the reader invert it to work out where they are,
            // and they get it wrong about half the time.
            final DebugSettings settings = new DebugSettings();

            settings.setOverlayVisible(true);

            assertThat(settings.overlayLabel()).isEqualTo("ON");

            settings.setOverlayVisible(false);

            assertThat(settings.overlayLabel()).isEqualTo("OFF");
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
            // The whole of the loose coupling. Another agent may be tuning
            // outline defaults; this must not override one it knows nothing
            // about, merely by being wired up.
            final DebugSettings settings = new DebugSettings();

            final Recorder recorder = new Recorder();

            settings.onChange(recorder);

            assertThat(recorder.seen).isEmpty();
        }

        @Test
        @DisplayName("is told each genuine change, in order")
        void shouldReportEveryChange()
        {
            final DebugSettings settings = new DebugSettings();

            final Recorder recorder = new Recorder();

            settings.onChange(recorder);

            settings.toggleOverlay();

            settings.toggleOverlay();

            settings.setOverlayVisible(true);

            assertThat(recorder.seen).containsExactly(Boolean.TRUE, Boolean.FALSE,
                Boolean.TRUE);
        }

        @Test
        @DisplayName("is not fired when a caller reasserts the current value")
        void shouldIgnoreANoOpSet()
        {
            final DebugSettings settings = new DebugSettings();

            final Recorder recorder = new Recorder();

            settings.onChange(recorder);

            settings.setOverlayVisible(false);

            assertThat(recorder.seen).isEmpty();

            settings.setOverlayVisible(true);

            settings.setOverlayVisible(true);

            assertThat(recorder.seen).containsExactly(Boolean.TRUE);
        }

        @Test
        @DisplayName("detaching stops the reports without changing the switch")
        void shouldStopReportingWhenDetached()
        {
            final DebugSettings settings = new DebugSettings();

            final Recorder recorder = new Recorder();

            settings.onChange(recorder);

            settings.toggleOverlay();

            settings.onChange(null);

            settings.toggleOverlay();

            assertThat(recorder.seen).containsExactly(Boolean.TRUE);

            assertThat(settings.isOverlayVisible()).isFalse();
        }

        @Test
        @DisplayName("a switch with no observer still works")
        void shouldWorkWithNoObserver()
        {
            final DebugSettings settings = new DebugSettings();

            settings.toggleOverlay();

            assertThat(settings.isOverlayVisible()).isTrue();
        }
    }
}
