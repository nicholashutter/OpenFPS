/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools;

import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.engine.render.adapter.Rgba;
import com.openfps.tools.model.MipGenerator;
import com.openfps.tools.model.ModelBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds the Stronghold map's level model: a 320x320 sandstone
 * fortress with two gate towers (east and west), four corner towers,
 * a central courtyard, and two flanking cliff walls. The full design
 * spec is in {@code docs/maps/desert-ravine/04-ctf-stronghold.md}.
 *
 * <p>Build-time only — this class is never on a runtime classpath.
 * The output is committed to
 * {@code engine/src/main/resources/maps/stronghold/} via
 * {@code git add -f}.</p>
 */
public final class StrongholdMapBuilder
{
    /** Name of the produced model, written to {@code level.ofm}. */
    public static final String MODEL_NAME = "stronghold-level";

    /** File name of the produced model. */
    public static final String FILE_NAME = "level.ofm";

    /** Half the map's playable extent, in world units. The map is 320x320. */
    public static final float HALF_EXTENT = 160.0f;

    /** Wall thickness, in world units. */
    public static final float WALL_THICKNESS = 6.0f;

    /** Perimeter wall height, in world units. */
    public static final float WALL_HEIGHT = 80.0f;

    /** Tower edge length, in world units. The four corner towers. */
    public static final float TOWER_EDGE = 16.0f;

    /** Tower height, in world units. */
    public static final float TOWER_HEIGHT = 32.0f;

    /** Cliff wall height, in world units. */
    public static final float CLIFF_HEIGHT = 32.0f;

    /** Fountain edge length, in world units. */
    public static final float FOUNTAIN_EDGE = 16.0f;

    /** Fountain height, in world units. */
    public static final float FOUNTAIN_HEIGHT = 8.0f;

    /** Texture edge, in texels. Power of two for the sampler's wrap. */
    public static final int TEXTURE_EDGE = 64;

    /** World units spanned by one repeat of a texture. */
    public static final float WORLD_UNITS_PER_TILE = 8.0f;

    private static final Logger LOG = LoggerFactory.getLogger(StrongholdMapBuilder.class);

    private StrongholdMapBuilder()
    {
        // entry point holder
    }

    /**
     * Builds the Stronghold level model and writes it to the given directory.
     *
     * @param args {@code --out=<dir>} required
     */
    public static void main(final String[] args)
    {
        final String out = option(args, "--out=");

        if (out == null)
        {
            LOG.error("usage: StrongholdMapBuilder --out=<directory>");

            return;
        }

        final Path outDir = Path.of(out);

        try
        {
            Files.createDirectories(outDir);
        }
        catch (final IOException e)
        {
            throw new UncheckedIOException("could not create output directory: " + outDir, e);
        }

        final byte[] bytes = build();

        final Path outFile = outDir.resolve(FILE_NAME);

        try
        {
            Files.write(outFile, bytes);
        }
        catch (final IOException e)
        {
            throw new UncheckedIOException("could not write " + outFile, e);
        }

        final ModelFormat parsed = ModelFormat.read(bytes);

        LOG.info("Wrote {} ({} triangles, {} vertices, {} textures)",
            outFile, parsed.indexCount() / 3, parsed.vertexCount(), parsed.textureCount());
    }

    /**
     * Returns the {@code .ofm} bytes for the Stronghold level.
     *
     * <p>Two submeshes: floor (ground slab) and walls (perimeter,
     * gate towers, corner towers, cliff walls, courtyard, fountain).
     * Two textures.</p>
     *
     * @return the .ofm file image
     */
    public static byte[] build()
    {
        final ModelBuilder builder = new ModelBuilder(MODEL_NAME);

        final int floorTexture = builder.addTexture("stronghold-floor", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE,
                floorTexels()));

        final int wallTexture = builder.addTexture("stronghold-wall", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE,
                wallTexels()));

        builder.beginSubmesh(floorTexture);

        addGroundSlab(builder);

        builder.endSubmesh();

        builder.beginSubmesh(wallTexture);

        addPerimeterWalls(builder);

        addGateTowers(builder);

        addCornerTowers(builder);

        addCliffWalls(builder);

        addFountain(builder);

        builder.endSubmesh();

        return builder.toBytes();
    }

    /**
     * Returns the level's triangle count.
     *
     * <p>1 ground slab (12) + 4 perimeter walls (48) + 2 gate towers
     * (24) + 4 corner towers (48) + 2 cliff walls (24) + 1 fountain
     * (12) = 168.</p>
     *
     * @return the level's triangle count
     */
    public static int triangleCount()
    {
        return (1 + 4 + 2 + 4 + 2 + 1) * 12;
    }

    // ------------------------------------------------------------------
    // Geometry construction
    // ------------------------------------------------------------------

    private static void addGroundSlab(final ModelBuilder builder)
    {
        addBox(builder, -HALF_EXTENT, -4.0f, -HALF_EXTENT, HALF_EXTENT, 0.0f, HALF_EXTENT);
    }

    private static void addPerimeterWalls(final ModelBuilder builder)
    {
        final float e = HALF_EXTENT;

        addBox(builder, -e, 0.0f, -e - WALL_THICKNESS, e, WALL_HEIGHT, -e);

        addBox(builder, -e, 0.0f, e, e, WALL_HEIGHT, e + WALL_THICKNESS);

        addBox(builder, -e - WALL_THICKNESS, 0.0f, -e, -e, WALL_HEIGHT, e);

        addBox(builder, e, 0.0f, -e, e + WALL_THICKNESS, WALL_HEIGHT, e);
    }

    /**
     * The two gate towers at the east and west gates. Each is a
     * 16×16×16 block; the gate itself is the open area below the
     * tower.
     */
    private static void addGateTowers(final ModelBuilder builder)
    {
        // West gate tower
        addBox(builder, 16.0f, 0.0f, 152.0f, 32.0f, 16.0F, 168.0f);

        // East gate tower
        addBox(builder, 288.0f, 0.0f, 152.0f, 304.0f, 16.0F, 168.0f);
    }

    /**
     * The four corner towers (NW, NE, SW, SE), each a 16×16×32 block.
     * A defender on the tower roof can see the gate approach, the
     * cliff top, and the courtyard.
     */
    private static void addCornerTowers(final ModelBuilder builder)
    {
        // NW
        addBox(builder, 112.0f, 0.0f, 112.0f, 112.0f + TOWER_EDGE, TOWER_HEIGHT, 112.0f + TOWER_EDGE);

        // NE
        addBox(builder, 192.0f - TOWER_EDGE, 0.0f, 112.0f, 192.0f, TOWER_HEIGHT, 128.0f);

        // SW
        addBox(builder, 112.0f, 0.0f, 192.0f - TOWER_EDGE, 128.0f, TOWER_HEIGHT, 192.0f);

        // SE
        addBox(builder, 192.0f - TOWER_EDGE, 0.0f, 192.0f - TOWER_EDGE, 192.0f, TOWER_HEIGHT,
            192.0f);
    }

    /**
     * The two flanking cliff walls (north and south), each a long
     * east-west wall at y=0..32. They are the high-ground option
     * flanking the courtyard.
     */
    private static void addCliffWalls(final ModelBuilder builder)
    {
        // North cliff
        addBox(builder, -HALF_EXTENT, 0.0f, 56.0f, HALF_EXTENT, CLIFF_HEIGHT, 72.0f);

        // South cliff
        addBox(builder, -HALF_EXTENT, 0.0f, 248.0f, HALF_EXTENT, CLIFF_HEIGHT, 264.0f);
    }

    /**
     * The fountain at the centre of the courtyard. A small 16×16×8
     * block at (152, 152) — the courtyard's only cover.
     */
    private static void addFountain(final ModelBuilder builder)
    {
        addBox(builder, 152.0f, 0.0f, 152.0f, 152.0f + FOUNTAIN_EDGE, FOUNTAIN_HEIGHT,
            152.0f + FOUNTAIN_EDGE);
    }

    /**
     * Adds a closed axis-aligned box to the open submesh.
     */
    private static void addBox(final ModelBuilder builder, final float minX, final float minY,
        final float minZ, final float maxX, final float maxY, final float maxZ)
    {
        addFace(builder, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, maxX, minY, minZ);

        addFace(builder, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, minX, minY, maxZ);

        addFace(builder, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, minX, maxY, minZ);

        addFace(builder, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ);

        addFace(builder, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, minY, maxZ);

        addFace(builder, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ, minX, minY, minZ);
    }

    private static void addFace(final ModelBuilder builder, final float ax, final float ay,
        final float az, final float bx, final float by, final float bz, final float cx,
        final float cy, final float cz, final float dx, final float dy, final float dz)
    {
        final float uScale = 1.0f / WORLD_UNITS_PER_TILE;

        final int a = builder.addVertex(ax, ay, az, ax * uScale, az * uScale,
            Rgba.pack(255, 255, 255, 255));

        final int b = builder.addVertex(bx, by, bz, bx * uScale, bz * uScale,
            Rgba.pack(255, 255, 255, 255));

        final int c = builder.addVertex(cx, cy, cz, cx * uScale, cz * uScale,
            Rgba.pack(255, 255, 255, 255));

        final int d = builder.addVertex(dx, dy, dz, dx * uScale, dz * uScale,
            Rgba.pack(255, 255, 255, 255));

        builder.addTriangle(a, b, c);

        builder.addTriangle(a, c, d);
    }

    // ------------------------------------------------------------------
    // Textures
    // ------------------------------------------------------------------

    private static int[] floorTexels()
    {
        final int base = Rgba.pack(218, 198, 158, 255);

        final int shade = Rgba.pack(196, 174, 130, 255);

        final int[] out = new int[TEXTURE_EDGE * TEXTURE_EDGE];

        for (int y = 0; y < TEXTURE_EDGE; y++)
        {
            for (int x = 0; x < TEXTURE_EDGE; x++)
            {
                int colour = base;

                if ((y / 8) % 2 == 0)
                {
                    colour = shade;
                }

                out[y * TEXTURE_EDGE + x] = colour;
            }
        }

        return out;
    }

    private static int[] wallTexels()
    {
        final int base = Rgba.pack(216, 188, 142, 255);

        final int shade = Rgba.pack(192, 162, 116, 255);

        final int mortar = Rgba.pack(150, 122, 82, 255);

        final int bandHeight = TEXTURE_EDGE / 8;

        final int[] out = new int[TEXTURE_EDGE * TEXTURE_EDGE];

        for (int y = 0; y < TEXTURE_EDGE; y++)
        {
            final int band = y / bandHeight;

            final boolean isMortar = (y % bandHeight) == 0;

            for (int x = 0; x < TEXTURE_EDGE; x++)
            {
                int colour = base;

                if ((band % 2) != 0)
                {
                    colour = shade;
                }

                if (isMortar)
                {
                    colour = mortar;
                }

                out[y * TEXTURE_EDGE + x] = colour;
            }
        }

        return out;
    }

    // ------------------------------------------------------------------
    // Argument helpers
    // ------------------------------------------------------------------

    private static String option(final String[] args, final String prefix)
    {
        for (final String arg : args)
        {
            if (arg != null && arg.startsWith(prefix))
            {
                return arg.substring(prefix.length());
            }
        }

        return null;
    }
}
