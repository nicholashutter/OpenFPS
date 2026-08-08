/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link MapSelection}.
 *
 * <p>Covers the four things the class is asked to do: report a default when
 * nothing else has spoken, honour the {@link MapSelection#MAP_PROPERTY}
 * override, refuse to be set to a blank id, and fire the observer only on a
 * real change. The first three are the contract with the launcher; the fourth
 * is the same one {@code AccessibilitySettings} and {@code DebugSettings} made
 * before this class existed, and breaking it would have the menu relabel its
 * own button every time the player clicked the row they had already picked.</p>
 */
@DisplayName("MapSelection")
class MapSelectionTest
{
    /** Records every value the observer is told. */
    private static final class Recorder implements java.util.function.Consumer<String>
    {
        /** What the observer received, in order. MUTABLE: appended per call. */
        private final List<String> seen = new ArrayList<>();

        @Override
        public void accept(final String value)
        {
            seen.add(value);
        }
    }

    /** The class is a system-property reader; each test must clear it on the way out. */
    @AfterEach
    void clearProperty()
    {
        System.clearProperty(MapSelection.MAP_PROPERTY);
    }

    @Nested
    @DisplayName("default value")
    class Default
    {
        @Test
        @DisplayName("is the shipped cornerstone map, so a fresh checkout can run")
        void shouldDefaultToCornerstone()
        {
            // The shipped map every fresh checkout can run, and the value a
            // picker that has not been touched yet reports as "the current
            // map". A change here is a release note, not a refactor.
            final MapSelection selection = new MapSelection();

            assertThat(selection.currentMapId()).isEqualTo("cornerstone");
            assertThat(selection.currentMapId()).isEqualTo(MapSelection.DEFAULT_MAP_ID);
        }

        @Test
        @DisplayName("is overridden by the system property, in that priority order")
        void shouldHonourTheSystemProperty()
        {
            System.setProperty(MapSelection.MAP_PROPERTY, "overpass");

            assertThat(new MapSelection().currentMapId()).isEqualTo("overpass");
        }

        @Test
        @DisplayName("ignores a blank system property, so a half-set env var does not break boot")
        void shouldIgnoreABlankProperty()
        {
            // A blank value would make the engine's MapLibrary fall through to
            // its own default, which is a different code path than this class
            // promises. The default has to win.
            System.setProperty(MapSelection.MAP_PROPERTY, "   ");

            assertThat(new MapSelection().currentMapId()).isEqualTo("cornerstone");
        }
    }

    @Nested
    @DisplayName("mutator validation")
    class Setter
    {
        @Test
        @DisplayName("refuses a null id, because a null is the only way to make the launcher lie")
        void shouldRefuseNull()
        {
            final MapSelection selection = new MapSelection();

            assertThatThrownBy(() -> selection.setCurrentMapId(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
        }

        @Test
        @DisplayName("refuses a blank id, because a blank would fall through to the engine default")
        void shouldRefuseBlank()
        {
            final MapSelection selection = new MapSelection();

            assertThatThrownBy(() -> selection.setCurrentMapId(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null or blank");
            assertThatThrownBy(() -> selection.setCurrentMapId("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null or blank");
        }

        @Test
        @DisplayName("accepts a non-blank id and remembers it")
        void shouldRememberANewSelection()
        {
            final MapSelection selection = new MapSelection();

            selection.setCurrentMapId("tripoint");

            assertThat(selection.currentMapId()).isEqualTo("tripoint");
        }
    }

    @Nested
    @DisplayName("change observer")
    class Observer
    {
        @Test
        @DisplayName("is not fired on attach, so a launcher default survives startup")
        void shouldNotFireOnAttach()
        {
            // The same rule AccessibilitySettings follows, and the reason the
            // composition root has to push the initial value across itself.
            // Without that push the picker label and the launcher's read of
            // currentMapId would agree only by coincidence.
            final MapSelection selection = new MapSelection();
            final Recorder recorder = new Recorder();
            selection.onChange(recorder);

            assertThat(recorder.seen).isEmpty();
        }

        @Test
        @DisplayName("is told each genuine change, in order")
        void shouldReportEveryChange()
        {
            final MapSelection selection = new MapSelection();
            final Recorder recorder = new Recorder();
            selection.onChange(recorder);

            selection.setCurrentMapId("overpass");
            selection.setCurrentMapId("tripoint");
            selection.setCurrentMapId("extraction");

            assertThat(recorder.seen).containsExactly("overpass", "tripoint", "extraction");
        }

        @Test
        @DisplayName("is not fired when a caller reasserts the current value")
        void shouldIgnoreANoOpSet()
        {
            // The launcher reasserts the initial value on startup on purpose,
            // so this is not a hypothetical caller: it is the one that has to
            // be free, exactly as the same call is on AccessibilitySettings.
            final MapSelection selection = new MapSelection();
            final Recorder recorder = new Recorder();
            selection.onChange(recorder);

            selection.setCurrentMapId("cornerstone");
            assertThat(recorder.seen).isEmpty();

            selection.setCurrentMapId("overpass");
            selection.setCurrentMapId("overpass");
            assertThat(recorder.seen).containsExactly("overpass");
        }

        @Test
        @DisplayName("detaching stops the reports without changing the selection")
        void shouldStopReportingWhenDetached()
        {
            final MapSelection selection = new MapSelection();
            final Recorder recorder = new Recorder();
            selection.onChange(recorder);
            selection.setCurrentMapId("overpass");

            selection.onChange(null);
            selection.setCurrentMapId("tripoint");

            assertThat(recorder.seen).containsExactly("overpass");
            assertThat(selection.currentMapId()).isEqualTo("tripoint");
        }

        @Test
        @DisplayName("a selection with no observer still works")
        void shouldWorkWithNoObserver()
        {
            final MapSelection selection = new MapSelection();

            selection.setCurrentMapId("overpass");

            assertThat(selection.currentMapId()).isEqualTo("overpass");
        }
    }

    @Nested
    @DisplayName("toString")
    class Description
    {
        @Test
        @DisplayName("names the current selection, so a log line is readable")
        void shouldDescribeItself()
        {
            final MapSelection selection = new MapSelection();
            selection.setCurrentMapId("overpass");

            assertThat(selection.toString()).contains("overpass");
        }
    }
}
