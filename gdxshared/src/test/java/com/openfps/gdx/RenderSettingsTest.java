/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link RenderSettings} — the switch behind the render control.
 *
 * <p>Worth covering for the reason {@code DebugSettingsTest} gives about its own
 * subject, sharpened: this observer does not merely reassert a boolean, it tears
 * down and rebuilds a {@code Texture} and re-sizes a live framebuffer. So
 * <b>when</b> it fires is the whole of the coupling. Firing on attach would
 * re-size a framebuffer that has not been sized once yet, and firing on a no-op
 * set would rebuild GPU resources to arrive at the size they already had.</p>
 */
@DisplayName("RenderSettings")
class RenderSettingsTest
{
    /** Records every mode the observer is told. */
    private static final class Recorder implements Consumer<RenderMode>
    {
        /** What the observer received, in order. MUTABLE: appended per call. */
        private final List<RenderMode> seen = new ArrayList<>();

        @Override
        public void accept(final RenderMode value)
        {
            seen.add(value);
        }
    }

    @Nested
    @DisplayName("the switch itself")
    class Switching
    {
        @Test
        @DisplayName("starts at the platform default, which is 480p")
        void shouldStartAtTheDefault()
        {
            final RenderSettings settings = new RenderSettings();

            assertThat(settings.mode()).isEqualTo(RenderMode.P480);

            assertThat(settings.label()).isEqualTo("480P");
        }

        @Test
        @DisplayName("cycling returns the new mode, so a caller need not read it back")
        void shouldReturnTheNewModeFromCycle()
        {
            final RenderSettings settings = new RenderSettings();

            assertThat(settings.cycle()).isEqualTo(RenderMode.P720);

            assertThat(settings.cycle()).isEqualTo(RenderMode.NATIVE);

            assertThat(settings.cycle()).isEqualTo(RenderMode.P480);
        }

        @Test
        @DisplayName("refuses a null mode rather than quietly keeping the old one")
        void shouldRefuseNull()
        {
            final RenderSettings settings = new RenderSettings();

            assertThatThrownBy(() -> settings.setMode(null))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> new RenderSettings(null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("the change observer")
    class Observer
    {
        @Test
        @DisplayName("is not fired on attach, so the first resize is not doubled")
        void shouldNotFireOnAttach()
        {
            final RenderSettings settings = new RenderSettings();

            final Recorder recorder = new Recorder();

            settings.onChange(recorder);

            assertThat(recorder.seen).isEmpty();
        }

        @Test
        @DisplayName("is told each genuine change, in order")
        void shouldReportEveryChange()
        {
            final RenderSettings settings = new RenderSettings();

            final Recorder recorder = new Recorder();

            settings.onChange(recorder);

            settings.cycle();

            settings.cycle();

            settings.setMode(RenderMode.P480);

            assertThat(recorder.seen).containsExactly(RenderMode.P720, RenderMode.NATIVE,
                RenderMode.P480);
        }

        @Test
        @DisplayName("is not fired when a caller reasserts the current mode")
        void shouldIgnoreANoOpSet()
        {
            // A rebuilt Texture and a re-sized framebuffer to arrive where we
            // already were is not free, and it drops a published frame with it.
            final RenderSettings settings = new RenderSettings();

            final Recorder recorder = new Recorder();

            settings.onChange(recorder);

            settings.setMode(RenderMode.P480);

            assertThat(recorder.seen).isEmpty();

            settings.setMode(RenderMode.NATIVE);

            settings.setMode(RenderMode.NATIVE);

            assertThat(recorder.seen).containsExactly(RenderMode.NATIVE);
        }

        @Test
        @DisplayName("detaching stops the reports without changing the mode")
        void shouldStopReportingWhenDetached()
        {
            final RenderSettings settings = new RenderSettings();

            final Recorder recorder = new Recorder();

            settings.onChange(recorder);

            settings.cycle();

            settings.onChange(null);

            settings.cycle();

            assertThat(recorder.seen).containsExactly(RenderMode.P720);

            assertThat(settings.mode()).isEqualTo(RenderMode.NATIVE);
        }

        @Test
        @DisplayName("a switch with no observer still works")
        void shouldWorkWithNoObserver()
        {
            final RenderSettings settings = new RenderSettings();

            settings.cycle();

            assertThat(settings.mode()).isEqualTo(RenderMode.P720);
        }
    }
}
