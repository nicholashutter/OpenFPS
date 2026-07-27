/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.resource.adapter;

import com.openfps.engine.resource.WadException;

/**
 * W_ Parser for the DOOM map lumps: THINGS, LINEDEFS, SECTORS, VERTEXES.
 *
 * Platform adapter — must not import from core engine packages.
 *
 * Each map lump is a packed array of fixed-size little-endian records with
 * no header of its own; the entry count is the lump length divided by the
 * record size, and a length that is not an exact multiple is a corrupt
 * lump rather than something to round down.
 *
 *   THINGS   (10 bytes)  x:i16 y:i16 angle:i16 type:i16 flags:i16
 *   LINEDEFS (14 bytes)  start:u16 end:u16 flags:i16 special:i16 tag:i16
 *                        rightSide:u16 leftSide:u16
 *   SECTORS  (26 bytes)  floorHeight:i16 ceilingHeight:i16
 *                        floorTexture:char[8] ceilingTexture:char[8]
 *                        light:i16 special:i16 tag:i16
 *   VERTEXES (4 bytes)   x:i16 y:i16
 *
 * Sidedef references are UNSIGNED: {@link MapLumpParser#NO_SIDEDEF} (0xFFFF) means the
 * linedef has no side there, which is how one-sided walls are encoded.
 * Reading them signed would turn that sentinel into -1 and every valid
 * high index into a negative number, so they go through
 * {@link LittleEndian#uint16(byte[], int)}.
 *
 * Results are returned as parallel primitive arrays wrapped in small
 * immutable holders with indexed accessors — no boxing, no per-record
 * object, and no array handed out for a caller to mutate.
 */
public final class MapLumpParser
{
    /** Sidedef index meaning "this linedef has no side here". */
    public static final int NO_SIDEDEF = 0xFFFF;

    /** Bytes per THINGS record. */
    private static final int THING_SIZE = 10;

    /** Bytes per LINEDEFS record. */
    private static final int LINEDEF_SIZE = 14;

    /** Bytes per SECTORS record. */
    private static final int SECTOR_SIZE = 26;

    /** Bytes per VERTEXES record. */
    private static final int VERTEX_SIZE = 4;

    /** Characters in a flat or texture name. */
    private static final int TEXTURE_NAME_LENGTH = 8;

    // THINGS field offsets
    private static final int THING_X = 0;
    private static final int THING_Y = 2;
    private static final int THING_ANGLE = 4;
    private static final int THING_TYPE = 6;
    private static final int THING_FLAGS = 8;

    // LINEDEFS field offsets
    private static final int LINEDEF_START_VERTEX = 0;
    private static final int LINEDEF_END_VERTEX = 2;
    private static final int LINEDEF_FLAGS = 4;
    private static final int LINEDEF_SPECIAL = 6;
    private static final int LINEDEF_TAG = 8;
    private static final int LINEDEF_RIGHT_SIDE = 10;
    private static final int LINEDEF_LEFT_SIDE = 12;

    // SECTORS field offsets
    private static final int SECTOR_FLOOR_HEIGHT = 0;
    private static final int SECTOR_CEILING_HEIGHT = 2;
    private static final int SECTOR_FLOOR_TEXTURE = 4;
    private static final int SECTOR_CEILING_TEXTURE = 12;
    private static final int SECTOR_LIGHT = 20;
    private static final int SECTOR_SPECIAL = 22;
    private static final int SECTOR_TAG = 24;

    // VERTEXES field offsets
    private static final int VERTEX_X = 0;
    private static final int VERTEX_Y = 2;

    private MapLumpParser()
    {
        // utility class
    }

    /**
     * Parses a THINGS lump.
     *
     * @param lump raw lump payload
     * @return the parsed things
     * @throws WadException if the lump is null or its length is not a multiple of 10
     */
    public static Things parseThings(final byte[] lump)
    {
        final int count = entryCount(lump, THING_SIZE, "THINGS");
        final int[] x = new int[count];
        final int[] y = new int[count];
        final int[] angle = new int[count];
        final int[] type = new int[count];
        final int[] flags = new int[count];

        for (int i = 0; i < count; i++)
        {
            final int base = i * THING_SIZE;
            x[i] = LittleEndian.int16(lump, base + THING_X);
            y[i] = LittleEndian.int16(lump, base + THING_Y);
            angle[i] = LittleEndian.int16(lump, base + THING_ANGLE);
            type[i] = LittleEndian.int16(lump, base + THING_TYPE);
            flags[i] = LittleEndian.int16(lump, base + THING_FLAGS);
        }
        return new Things(count, x, y, angle, type, flags);
    }

    /**
     * Parses a LINEDEFS lump.
     *
     * @param lump raw lump payload
     * @return the parsed linedefs
     * @throws WadException if the lump is null or its length is not a multiple of 14
     */
    public static Linedefs parseLinedefs(final byte[] lump)
    {
        final int count = entryCount(lump, LINEDEF_SIZE, "LINEDEFS");
        final int[] startVertex = new int[count];
        final int[] endVertex = new int[count];
        final int[] flags = new int[count];
        final int[] special = new int[count];
        final int[] tag = new int[count];
        final int[] rightSide = new int[count];
        final int[] leftSide = new int[count];

        for (int i = 0; i < count; i++)
        {
            final int base = i * LINEDEF_SIZE;
            startVertex[i] = LittleEndian.uint16(lump, base + LINEDEF_START_VERTEX);
            endVertex[i] = LittleEndian.uint16(lump, base + LINEDEF_END_VERTEX);
            flags[i] = LittleEndian.int16(lump, base + LINEDEF_FLAGS);
            special[i] = LittleEndian.int16(lump, base + LINEDEF_SPECIAL);
            tag[i] = LittleEndian.int16(lump, base + LINEDEF_TAG);
            rightSide[i] = LittleEndian.uint16(lump, base + LINEDEF_RIGHT_SIDE);
            leftSide[i] = LittleEndian.uint16(lump, base + LINEDEF_LEFT_SIDE);
        }
        return new Linedefs(count, startVertex, endVertex, flags, special, tag,
            rightSide, leftSide);
    }

    /**
     * Parses a SECTORS lump.
     *
     * @param lump raw lump payload
     * @return the parsed sectors
     * @throws WadException if the lump is null or its length is not a multiple of 26
     */
    public static Sectors parseSectors(final byte[] lump)
    {
        final int count = entryCount(lump, SECTOR_SIZE, "SECTORS");
        final int[] floorHeight = new int[count];
        final int[] ceilingHeight = new int[count];
        final String[] floorTexture = new String[count];
        final String[] ceilingTexture = new String[count];
        final int[] light = new int[count];
        final int[] special = new int[count];
        final int[] tag = new int[count];

        for (int i = 0; i < count; i++)
        {
            final int base = i * SECTOR_SIZE;
            floorHeight[i] = LittleEndian.int16(lump, base + SECTOR_FLOOR_HEIGHT);
            ceilingHeight[i] = LittleEndian.int16(lump, base + SECTOR_CEILING_HEIGHT);
            floorTexture[i] = LittleEndian.ascii(
                lump, base + SECTOR_FLOOR_TEXTURE, TEXTURE_NAME_LENGTH);
            ceilingTexture[i] = LittleEndian.ascii(
                lump, base + SECTOR_CEILING_TEXTURE, TEXTURE_NAME_LENGTH);
            light[i] = LittleEndian.int16(lump, base + SECTOR_LIGHT);
            special[i] = LittleEndian.int16(lump, base + SECTOR_SPECIAL);
            tag[i] = LittleEndian.int16(lump, base + SECTOR_TAG);
        }
        return new Sectors(count, floorHeight, ceilingHeight, floorTexture,
            ceilingTexture, light, special, tag);
    }

    /**
     * Parses a VERTEXES lump.
     *
     * @param lump raw lump payload
     * @return the parsed vertexes
     * @throws WadException if the lump is null or its length is not a multiple of 4
     */
    public static Vertexes parseVertexes(final byte[] lump)
    {
        final int count = entryCount(lump, VERTEX_SIZE, "VERTEXES");
        final int[] x = new int[count];
        final int[] y = new int[count];

        for (int i = 0; i < count; i++)
        {
            final int base = i * VERTEX_SIZE;
            x[i] = LittleEndian.int16(lump, base + VERTEX_X);
            y[i] = LittleEndian.int16(lump, base + VERTEX_Y);
        }
        return new Vertexes(count, x, y);
    }

    // Records per lump, rejecting a length that is not an exact multiple.
    private static int entryCount(final byte[] lump, final int entrySize, final String lumpName)
    {
        if (lump == null)
        {
            throw new WadException(lumpName + " lump is null");
        }
        if (lump.length % entrySize != 0)
        {
            throw new WadException(lumpName + " lump is " + lump.length
                + " bytes, not a multiple of the " + entrySize + "-byte record size");
        }
        return lump.length / entrySize;
    }

    // ================================================================
    //  Parsed results — parallel primitive arrays, indexed accessors
    // ================================================================

    /**
     * Parsed THINGS: map spawn points for players, monsters and pickups.
     */
    public static final class Things
    {
        private final int count;
        private final int[] x;
        private final int[] y;
        private final int[] angle;
        private final int[] type;
        private final int[] flags;

        private Things(final int count, final int[] x, final int[] y, final int[] angle,
            final int[] type, final int[] flags)
        {
            this.count = count;
            this.x = x;
            this.y = y;
            this.angle = angle;
            this.type = type;
            this.flags = flags;
        }

        /** Returns the number of things. */
        public int count()
        {
            return count;
        }

        /**
         * Returns the map X coordinate of a thing, in whole map units.
         *
         * @param index zero-based thing index
         * @return X coordinate
         */
        public int x(final int index)
        {
            return x[index];
        }

        /**
         * Returns the map Y coordinate of a thing, in whole map units.
         *
         * @param index zero-based thing index
         * @return Y coordinate
         */
        public int y(final int index)
        {
            return y[index];
        }

        /**
         * Returns the facing angle of a thing, in degrees.
         *
         * @param index zero-based thing index
         * @return angle in degrees
         */
        public int angle(final int index)
        {
            return angle[index];
        }

        /**
         * Returns the DOOM thing type number.
         *
         * @param index zero-based thing index
         * @return thing type
         */
        public int type(final int index)
        {
            return type[index];
        }

        /**
         * Returns the spawn flag bits of a thing.
         *
         * @param index zero-based thing index
         * @return flag bits
         */
        public int flags(final int index)
        {
            return flags[index];
        }
    }

    /**
     * Parsed LINEDEFS: the wall segments joining two vertexes.
     */
    public static final class Linedefs
    {
        private final int count;
        private final int[] startVertex;
        private final int[] endVertex;
        private final int[] flags;
        private final int[] special;
        private final int[] tag;
        private final int[] rightSide;
        private final int[] leftSide;

        private Linedefs(final int count, final int[] startVertex, final int[] endVertex,
            final int[] flags, final int[] special, final int[] tag,
            final int[] rightSide, final int[] leftSide)
        {
            this.count = count;
            this.startVertex = startVertex;
            this.endVertex = endVertex;
            this.flags = flags;
            this.special = special;
            this.tag = tag;
            this.rightSide = rightSide;
            this.leftSide = leftSide;
        }

        /** Returns the number of linedefs. */
        public int count()
        {
            return count;
        }

        /**
         * Returns the index into VERTEXES of a linedef's start point.
         *
         * @param index zero-based linedef index
         * @return vertex index
         */
        public int startVertex(final int index)
        {
            return startVertex[index];
        }

        /**
         * Returns the index into VERTEXES of a linedef's end point.
         *
         * @param index zero-based linedef index
         * @return vertex index
         */
        public int endVertex(final int index)
        {
            return endVertex[index];
        }

        /**
         * Returns the linedef flag bits (blocking, two-sided, secret, ...).
         *
         * @param index zero-based linedef index
         * @return flag bits
         */
        public int flags(final int index)
        {
            return flags[index];
        }

        /**
         * Returns the linedef special action number, or 0 for none.
         *
         * @param index zero-based linedef index
         * @return special action
         */
        public int special(final int index)
        {
            return special[index];
        }

        /**
         * Returns the sector tag this linedef triggers, or 0 for none.
         *
         * @param index zero-based linedef index
         * @return sector tag
         */
        public int tag(final int index)
        {
            return tag[index];
        }

        /**
         * Returns the right (front) sidedef index, or {@link MapLumpParser#NO_SIDEDEF}.
         *
         * @param index zero-based linedef index
         * @return sidedef index
         */
        public int rightSide(final int index)
        {
            return rightSide[index];
        }

        /**
         * Returns the left (back) sidedef index, or {@link MapLumpParser#NO_SIDEDEF} for
         * a one-sided wall.
         *
         * @param index zero-based linedef index
         * @return sidedef index
         */
        public int leftSide(final int index)
        {
            return leftSide[index];
        }

        /**
         * Returns true when a linedef has a back side, i.e. it is two-sided.
         *
         * @param index zero-based linedef index
         * @return true if the left sidedef is present
         */
        public boolean isTwoSided(final int index)
        {
            return leftSide[index] != NO_SIDEDEF;
        }
    }

    /**
     * Parsed SECTORS: the floor and ceiling regions bounded by linedefs.
     */
    public static final class Sectors
    {
        private final int count;
        private final int[] floorHeight;
        private final int[] ceilingHeight;
        private final String[] floorTexture;
        private final String[] ceilingTexture;
        private final int[] light;
        private final int[] special;
        private final int[] tag;

        private Sectors(final int count, final int[] floorHeight, final int[] ceilingHeight,
            final String[] floorTexture, final String[] ceilingTexture,
            final int[] light, final int[] special, final int[] tag)
        {
            this.count = count;
            this.floorHeight = floorHeight;
            this.ceilingHeight = ceilingHeight;
            this.floorTexture = floorTexture;
            this.ceilingTexture = ceilingTexture;
            this.light = light;
            this.special = special;
            this.tag = tag;
        }

        /** Returns the number of sectors. */
        public int count()
        {
            return count;
        }

        /**
         * Returns a sector's floor height in whole map units.
         *
         * @param index zero-based sector index
         * @return floor height
         */
        public int floorHeight(final int index)
        {
            return floorHeight[index];
        }

        /**
         * Returns a sector's ceiling height in whole map units.
         *
         * @param index zero-based sector index
         * @return ceiling height
         */
        public int ceilingHeight(final int index)
        {
            return ceilingHeight[index];
        }

        /**
         * Returns the name of a sector's floor flat.
         *
         * @param index zero-based sector index
         * @return flat name, uppercased and trimmed
         */
        public String floorTexture(final int index)
        {
            return floorTexture[index];
        }

        /**
         * Returns the name of a sector's ceiling flat.
         *
         * @param index zero-based sector index
         * @return flat name, uppercased and trimmed
         */
        public String ceilingTexture(final int index)
        {
            return ceilingTexture[index];
        }

        /**
         * Returns a sector's light level, 0 (dark) to 255 (full bright).
         *
         * @param index zero-based sector index
         * @return light level
         */
        public int light(final int index)
        {
            return light[index];
        }

        /**
         * Returns a sector's special type, or 0 for none.
         *
         * @param index zero-based sector index
         * @return special type
         */
        public int special(final int index)
        {
            return special[index];
        }

        /**
         * Returns a sector's tag, matched against linedef tags, or 0 for none.
         *
         * @param index zero-based sector index
         * @return sector tag
         */
        public int tag(final int index)
        {
            return tag[index];
        }
    }

    /**
     * Parsed VERTEXES: the shared 2D points that linedefs join.
     */
    public static final class Vertexes
    {
        private final int count;
        private final int[] x;
        private final int[] y;

        private Vertexes(final int count, final int[] x, final int[] y)
        {
            this.count = count;
            this.x = x;
            this.y = y;
        }

        /** Returns the number of vertexes. */
        public int count()
        {
            return count;
        }

        /**
         * Returns a vertex X coordinate in whole map units.
         *
         * @param index zero-based vertex index
         * @return X coordinate
         */
        public int x(final int index)
        {
            return x[index];
        }

        /**
         * Returns a vertex Y coordinate in whole map units.
         *
         * @param index zero-based vertex index
         * @return Y coordinate
         */
        public int y(final int index)
        {
            return y[index];
        }
    }
}
