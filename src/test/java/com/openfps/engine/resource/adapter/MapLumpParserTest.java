/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.resource.adapter;

import com.openfps.engine.resource.WadException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the map lump parser.
 *
 * Every fixture is assembled with {@link WadBuilder#words(int...)}, which
 * packs little-endian shorts through {@code ByteBuffer} — an independent
 * encoder from the shift-based one under test. Negative coordinates and
 * the 0xFFFF sidedef sentinel appear in every fixture because those are
 * the two places a sign-handling mistake shows up.
 */
class MapLumpParserTest
{
    /** Byte width of a SECTORS texture-name field. */
    private static final int TEXTURE_NAME_LENGTH = 8;

    private static byte[] sector(final int floorHeight, final int ceilingHeight,
        final String floorTexture, final String ceilingTexture,
        final int light, final int special, final int tag)
    {
        return WadBuilder.concat(
            WadBuilder.words(floorHeight, ceilingHeight),
            WadBuilder.padded(floorTexture, TEXTURE_NAME_LENGTH),
            WadBuilder.padded(ceilingTexture, TEXTURE_NAME_LENGTH),
            WadBuilder.words(light, special, tag));
    }

    // ===============================================================
    //  THINGS
    // ===============================================================

    @Nested
    @DisplayName("THINGS")
    class Things
    {
        @Test
        @DisplayName("a THINGS lump decodes into one record per 10 bytes")
        void shouldParseThings()
        {
            final byte[] lump = WadBuilder.concat(
                WadBuilder.words(1056, -3616, 90, 1, 7),
                WadBuilder.words(-32768, 32767, 270, 3004, 0));

            final MapLumpParser.Things things = MapLumpParser.parseThings(lump);

            assertThat(things.count()).isEqualTo(2);
            assertThat(things.x(0)).isEqualTo(1056);
            assertThat(things.y(0)).isEqualTo(-3616);
            assertThat(things.angle(0)).isEqualTo(90);
            assertThat(things.type(0)).isEqualTo(1);
            assertThat(things.flags(0)).isEqualTo(7);
            assertThat(things.x(1)).isEqualTo(-32768);
            assertThat(things.y(1)).isEqualTo(32767);
            assertThat(things.type(1)).isEqualTo(3004);
        }

        @Test
        @DisplayName("an empty THINGS lump parses to zero things")
        void shouldParseEmptyThings()
        {
            assertThat(MapLumpParser.parseThings(new byte[0]).count()).isZero();
        }

        @Test
        @DisplayName("a THINGS lump whose length is not a multiple of 10 is rejected")
        void shouldRejectMisalignedThings()
        {
            assertThatThrownBy(() -> MapLumpParser.parseThings(new byte[11]))
                .isInstanceOf(WadException.class)
                .hasMessageContaining("not a multiple of the 10-byte record size");
        }

        @Test
        @DisplayName("a null THINGS lump is rejected")
        void shouldRejectNullThings()
        {
            assertThatThrownBy(() -> MapLumpParser.parseThings(null))
                .isInstanceOf(WadException.class)
                .hasMessageContaining("THINGS lump is null");
        }
    }

    // ===============================================================
    //  LINEDEFS
    // ===============================================================

    @Nested
    @DisplayName("LINEDEFS")
    class Linedefs
    {
        @Test
        @DisplayName("a LINEDEFS lump decodes into one record per 14 bytes")
        void shouldParseLinedefs()
        {
            final byte[] lump = WadBuilder.concat(
                WadBuilder.words(0, 1, 0x0001, 0, 0, 0, 0xFFFF),
                WadBuilder.words(1, 2, 0x0004, 63, 12, 3, 4));

            final MapLumpParser.Linedefs lines = MapLumpParser.parseLinedefs(lump);

            assertThat(lines.count()).isEqualTo(2);
            assertThat(lines.startVertex(0)).isZero();
            assertThat(lines.endVertex(0)).isEqualTo(1);
            assertThat(lines.flags(0)).isEqualTo(1);
            assertThat(lines.rightSide(0)).isZero();
            assertThat(lines.startVertex(1)).isEqualTo(1);
            assertThat(lines.special(1)).isEqualTo(63);
            assertThat(lines.tag(1)).isEqualTo(12);
            assertThat(lines.rightSide(1)).isEqualTo(3);
            assertThat(lines.leftSide(1)).isEqualTo(4);
        }

        @Test
        @DisplayName("an absent sidedef reads as the unsigned 0xFFFF sentinel, not -1")
        void shouldReadNoSidedefAsUnsigned()
        {
            final byte[] lump = WadBuilder.words(0, 1, 0, 0, 0, 0, 0xFFFF);

            final MapLumpParser.Linedefs lines = MapLumpParser.parseLinedefs(lump);

            assertThat(lines.leftSide(0)).isEqualTo(MapLumpParser.NO_SIDEDEF);
            assertThat(lines.leftSide(0)).isEqualTo(65535);
            assertThat(lines.isTwoSided(0)).isFalse();
        }

        @Test
        @DisplayName("a linedef with both sides present is two-sided")
        void shouldReportTwoSidedLinedef()
        {
            final byte[] lump = WadBuilder.words(0, 1, 0, 0, 0, 5, 6);

            final MapLumpParser.Linedefs lines = MapLumpParser.parseLinedefs(lump);

            assertThat(lines.isTwoSided(0)).isTrue();
            assertThat(lines.leftSide(0)).isEqualTo(6);
        }

        @Test
        @DisplayName("a high vertex index above 32767 stays positive")
        void shouldReadHighVertexIndexAsUnsigned()
        {
            final byte[] lump = WadBuilder.words(40000, 50000, 0, 0, 0, 0, 0xFFFF);

            final MapLumpParser.Linedefs lines = MapLumpParser.parseLinedefs(lump);

            assertThat(lines.startVertex(0)).isEqualTo(40000);
            assertThat(lines.endVertex(0)).isEqualTo(50000);
        }

        @Test
        @DisplayName("a LINEDEFS lump whose length is not a multiple of 14 is rejected")
        void shouldRejectMisalignedLinedefs()
        {
            assertThatThrownBy(() -> MapLumpParser.parseLinedefs(new byte[20]))
                .isInstanceOf(WadException.class)
                .hasMessageContaining("not a multiple of the 14-byte record size");
        }
    }

    // ===============================================================
    //  SECTORS
    // ===============================================================

    @Nested
    @DisplayName("SECTORS")
    class Sectors
    {
        @Test
        @DisplayName("a SECTORS lump decodes into one record per 26 bytes")
        void shouldParseSectors()
        {
            final byte[] lump = WadBuilder.concat(
                sector(0, 128, "FLOOR4_8", "CEIL3_5", 160, 0, 0),
                sector(-64, 72, "NUKAGE1", "F_SKY1", 255, 7, 3));

            final MapLumpParser.Sectors sectors = MapLumpParser.parseSectors(lump);

            assertThat(sectors.count()).isEqualTo(2);
            assertThat(sectors.floorHeight(0)).isZero();
            assertThat(sectors.ceilingHeight(0)).isEqualTo(128);
            assertThat(sectors.floorTexture(0)).isEqualTo("FLOOR4_8");
            assertThat(sectors.ceilingTexture(0)).isEqualTo("CEIL3_5");
            assertThat(sectors.light(0)).isEqualTo(160);
            assertThat(sectors.floorHeight(1)).isEqualTo(-64);
            assertThat(sectors.floorTexture(1)).isEqualTo("NUKAGE1");
            assertThat(sectors.ceilingTexture(1)).isEqualTo("F_SKY1");
            assertThat(sectors.light(1)).isEqualTo(255);
            assertThat(sectors.special(1)).isEqualTo(7);
            assertThat(sectors.tag(1)).isEqualTo(3);
        }

        @Test
        @DisplayName("a SECTORS record is exactly 26 bytes wide")
        void shouldUseTwentySixByteRecords()
        {
            assertThat(sector(0, 0, "A", "B", 0, 0, 0)).hasSize(26);
        }

        @Test
        @DisplayName("an empty SECTORS lump parses to zero sectors")
        void shouldParseEmptySectors()
        {
            assertThat(MapLumpParser.parseSectors(new byte[0]).count()).isZero();
        }

        @Test
        @DisplayName("a SECTORS lump whose length is not a multiple of 26 is rejected")
        void shouldRejectMisalignedSectors()
        {
            assertThatThrownBy(() -> MapLumpParser.parseSectors(new byte[27]))
                .isInstanceOf(WadException.class)
                .hasMessageContaining("not a multiple of the 26-byte record size");
        }
    }

    // ===============================================================
    //  VERTEXES
    // ===============================================================

    @Nested
    @DisplayName("VERTEXES")
    class Vertexes
    {
        @Test
        @DisplayName("a VERTEXES lump decodes into one record per 4 bytes")
        void shouldParseVertexes()
        {
            final byte[] lump = WadBuilder.words(0, 0, 1024, -1024, -32768, 32767);

            final MapLumpParser.Vertexes vertexes = MapLumpParser.parseVertexes(lump);

            assertThat(vertexes.count()).isEqualTo(3);
            assertThat(vertexes.x(0)).isZero();
            assertThat(vertexes.y(0)).isZero();
            assertThat(vertexes.x(1)).isEqualTo(1024);
            assertThat(vertexes.y(1)).isEqualTo(-1024);
            assertThat(vertexes.x(2)).isEqualTo(-32768);
            assertThat(vertexes.y(2)).isEqualTo(32767);
        }

        @Test
        @DisplayName("a VERTEXES lump whose length is not a multiple of 4 is rejected")
        void shouldRejectMisalignedVertexes()
        {
            assertThatThrownBy(() -> MapLumpParser.parseVertexes(new byte[6]))
                .isInstanceOf(WadException.class)
                .hasMessageContaining("not a multiple of the 4-byte record size");
        }
    }

    // ===============================================================
    //  End to end through a synthesized WAD
    // ===============================================================

    @Test
    @DisplayName("a synthesized map reads back through the port and parses consistently")
    void shouldParseMapLumpsReadThroughAWad()
    {
        final byte[] image = WadBuilder.iwad()
            .marker("E1M1")
            .lump("THINGS", WadBuilder.words(1056, -3616, 90, 1, 7))
            .lump("LINEDEFS", WadBuilder.words(0, 1, 1, 0, 0, 0, 0xFFFF))
            .lump("VERTEXES", WadBuilder.words(0, 0, 1024, -1024))
            .lump("SECTORS", sector(0, 128, "FLOOR4_8", "CEIL3_5", 160, 0, 0))
            .build();
        final WadReader reader = WadReader.fromBytes(image);

        final MapLumpParser.Things things =
            MapLumpParser.parseThings(reader.sliceLump(reader.findLump("THINGS")));
        final MapLumpParser.Linedefs lines =
            MapLumpParser.parseLinedefs(reader.sliceLump(reader.findLump("LINEDEFS")));
        final MapLumpParser.Vertexes vertexes =
            MapLumpParser.parseVertexes(reader.sliceLump(reader.findLump("VERTEXES")));
        final MapLumpParser.Sectors sectors =
            MapLumpParser.parseSectors(reader.sliceLump(reader.findLump("SECTORS")));

        assertThat(things.count()).isEqualTo(1);
        assertThat(things.x(0)).isEqualTo(1056);
        assertThat(lines.count()).isEqualTo(1);
        assertThat(lines.isTwoSided(0)).isFalse();
        assertThat(vertexes.count()).isEqualTo(2);
        assertThat(vertexes.y(1)).isEqualTo(-1024);
        assertThat(sectors.count()).isEqualTo(1);
        assertThat(sectors.ceilingTexture(0)).isEqualTo("CEIL3_5");
    }
}
