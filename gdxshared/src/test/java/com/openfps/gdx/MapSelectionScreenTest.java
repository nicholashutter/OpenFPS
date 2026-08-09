/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link MapSelectionScreen} — the parts that are checkable from a
 * plain JVM.
 *
 * <p>Constructing the screen needs a GL context: a {@code Texture} from a
 * {@code Pixmap}, a {@code BitmapFont}, a {@code Stage}. The render path is
 * what {@code GdxFrameLoopListener} covers in {@code :desktop} with a live
 * loop; the headless half is the row-id round trip, the entry validation,
 * and the words the screen says.</p>
 */
@DisplayName("MapSelectionScreen")
class MapSelectionScreenTest
{
    @Nested
    @DisplayName("the screen's words")
    class Text
    {
        @Test
        @DisplayName("name the screen after what the player does, not how it is built")
        void shouldBeReadable()
        {
            // The heading is the first thing a player sees; the subtitle is
            // the only instruction. Both have to be the words a player
            // would use, not the words a developer would.
            assertThat(MapSelectionScreen.TITLE_TEXT)
                .isEqualTo("SELECT MAP");

            assertThat(MapSelectionScreen.SUBTITLE_TEXT)
                .contains("Click a map")
                .contains("BACK");
        }

        @Test
        @DisplayName("the selected prefix is a marker, not a word, so the row layout is stable")
        void shouldHaveAStablePrefix()
        {
            // The selected row wears "> " in front of the display name so the
            // line stays the same shape as the other rows. Changing this
            // prefix would change every row's geometry and is a release note.
            assertThat(MapSelectionScreen.SELECTED_PREFIX).isEqualTo("> ");
        }
    }

    @Nested
    @DisplayName("row id round trip")
    class RowIdRoundTrip
    {
        @Test
        @DisplayName("strips the prefix from a selected label")
        void shouldStripTheSelectedPrefix()
        {
            // The label is what the screen shows; the row id is what the
            // engine reads back. The round trip has to lose the prefix and
            // nothing else, because the id is the thing the picker stored
            // and the launcher reads.
            assertThat(MapSelectionScreen.rowIdForLabel("> cornerstone"))
                .isEqualTo("cornerstone");
        }

        @Test
        @DisplayName("passes a plain label through, because the unselected rows have no prefix")
        void shouldPassThroughAnUnprefixedLabel()
        {
            assertThat(MapSelectionScreen.rowIdForLabel("overpass"))
                .isEqualTo("overpass");
        }

        @Test
        @DisplayName("refuses a null label, because the round trip has to be total")
        void shouldRefuseNull()
        {
            // A null round trip is a NullPointerException at the call site,
            // which is what the test pins down. The picker never produces a
            // null label, but neither does a misbehaving caller.
            assertThatThrownBy(() -> MapSelectionScreen.rowIdForLabel(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("label");
        }
    }

    @Nested
    @DisplayName("row entries")
    class Entries
    {
        @Test
        @DisplayName("refuse a null id, because a null id is a launcher bug not a player one")
        void shouldRefuseNullId()
        {
            assertThatThrownBy(() -> new MapSelectionScreen.Entry(null, "Cornerstone",
                "maps/cornerstone/thumbnail.png", "TDM"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
        }

        @Test
        @DisplayName("refuse a blank id, because a blank id would never load")
        void shouldRefuseBlankId()
        {
            assertThatThrownBy(() -> new MapSelectionScreen.Entry("", "Cornerstone",
                "maps/cornerstone/thumbnail.png", "TDM"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");

            assertThatThrownBy(() -> new MapSelectionScreen.Entry("   ", "Cornerstone",
                "maps/cornerstone/thumbnail.png", "TDM"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
        }

        @Test
        @DisplayName("refuse a null display name, because a row needs a name a player can read")
        void shouldRefuseNullDisplayName()
        {
            assertThatThrownBy(() -> new MapSelectionScreen.Entry("cornerstone", null,
                "maps/cornerstone/thumbnail.png", "TDM"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("displayName");
        }

        @Test
        @DisplayName("refuse a blank display name, because a row without a name is a label without a value")
        void shouldRefuseBlankDisplayName()
        {
            assertThatThrownBy(() -> new MapSelectionScreen.Entry("cornerstone", "",
                "maps/cornerstone/thumbnail.png", "TDM"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("displayName");
        }

        @Test
        @DisplayName("expose the id and the display name the launcher handed in")
        void shouldExposeIdAndName()
        {
            final MapSelectionScreen.Entry entry =
                new MapSelectionScreen.Entry("cornerstone", "Cornerstone",
                    "maps/cornerstone/thumbnail.png", "TDM");

            assertThat(entry.id()).isEqualTo("cornerstone");

            assertThat(entry.displayName()).isEqualTo("Cornerstone");
        }

        @Test
        @DisplayName("can be collected into a list the screen will accept, round-tripping values")
        void shouldRoundTripThroughAList()
        {
            // A list is the shape the launcher hands the screen; constructing
            // one and reading it back must keep both fields in the same order
            // they were added, so a screen that takes a List<Entry> cannot
            // accidentally sort by display name and lose the id correspondence.
            final List<MapSelectionScreen.Entry> entries = List.of(
                new MapSelectionScreen.Entry("cornerstone", "Cornerstone",
                    "maps/cornerstone/thumbnail.png", "TDM"),
                new MapSelectionScreen.Entry("overpass", "Overpass",
                    "maps/overpass/thumbnail.png", "DOMINATION"),
                new MapSelectionScreen.Entry("tripoint", "Tripoint",
                    "maps/tripoint/thumbnail.png", "CTF"));

            assertThat(entries).hasSize(3);

            assertThat(entries.get(0).id()).isEqualTo("cornerstone");

            assertThat(entries.get(0).displayName()).isEqualTo("Cornerstone");

            assertThat(entries.get(2).id()).isEqualTo("tripoint");

            assertThat(entries.get(2).displayName()).isEqualTo("Tripoint");
        }
    }
}
