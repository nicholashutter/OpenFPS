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
 * Builds the Storage map's level model: a 320x320 chemical storage
 * facility with two warehouse buildings (one per team) at opposite
 * ends, and a maze of eight storage tanks in the centre. The full
 * design spec is in {@code docs/maps/industrial-complex/04-ctf-storage.md}.
 *
 * <p>Build-time only — this class is never on a runtime classpath. The
 * output is committed to {@code engine/src/main/resources/maps/storage/}
 * via {@code git add -f}.</p>
 */
public final class StorageMapBuilder
{
    /** Name of the produced model, written to {@code level.ofm}. */
    public static final String MODEL_NAME = "storage-level";

    /** File name of the produced model. */
    public static final String FILE_NAME = "level.ofm";

    /** Half the map's playable extent, in world units. The map is 320x320. */
    public static final float HALF_EXTENT = 160.0f;

    /** Wall thickness, in world units. */
    public static final float WALL_THICKNESS = 6.0f;

    /** Perimeter wall height, in world units. */
    public static final float WALL_HEIGHT = 80.0f;

    /** Warehouse height, in world units. */
    public static final float WAREHOUSE_HEIGHT = 32.0f;

    /** Storage tank radius, in world units. */
    public static final float TANK_RADIUS = 16.0f;

    /** Storage tank height, in world units. */
    public static final float TANK_HEIGHT = 32.0f;

    /** Catwalk height, in world units. */
    public static final float CATWALK_HEIGHT = 64.0F;

    /** Catwalk thickness, in world units. */
    public static final float CATWALK_THICKNESS = 8.0F;

    /** Texture edge, in texels. Power of two for the sampler's wrap. */
    public static final int TEXTURE_EDGE = 64;

    /** World units spanned by one repeat of a texture. */
    public static final float WORLD_UNITS_PER_TILE = 8.0f;

    private static final Logger LOG = LoggerFactory.getLogger(StorageMapBuilder.class);

    private StorageMapBuilder()
    {
        // entry point holder
    }

    /**
     * Builds the Storage level model and writes it to the given directory.
     *
     * @param args {@code --out=<dir>} required
     */
    public static void main(final String[] args)
    {
        final String out = option(args, "--out=");

        if (out == null)
        {
            LOG.error("usage: StorageMapBuilder --out=<directory>");

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
     * Returns the {@code .ofm} bytes for the Storage level.
     *
     * <p>Two submeshes: floor (ground + warehouse platforms) and walls
     * (perimeter, warehouses, tanks, catwalk). Two textures.</p>
     *
     * @return the .ofm file image
     */
    public static byte[] build()
    {
        final ModelBuilder builder = new ModelBuilder(MODEL_NAME);

        final int floorTexture = builder.addTexture("storage-floor", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE,
                floorTexels()));

        final int wallTexture = builder.addTexture("storage-wall", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE,
                wallTexels()));

        builder.beginSubmesh(floorTexture);

        addGroundSlab(builder);

        builder.endSubmesh();

        builder.beginSubmesh(wallTexture);

        addPerimeterWalls(builder);

        addWarehouses(builder);

        addStorageTanks(builder);

        addCatwalk(builder);

        builder.endSubmesh();

        return builder.toBytes();
    }

    /**
     * Returns the level's triangle count.
     *
     * <p>1 ground slab (12) + 4 perimeter walls (48) + 2 warehouses
     * (24) + 8 storage tanks (96) + 1 catwalk (12) = 192.</p>
     *
     * @return the level's triangle count
     */
    public static int triangleCount()
    {
        return (1 + 4 + 2 + 8 + 1) * 12;
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
     * The two warehouse buildings — RED at the south-west corner
     * (32, 32) and BLUE at the north-east corner (288, 288). Each is
     * a 64x64x32 box. The flag and the capture point sit at the centre
     * of the warehouse floor.
     */
    private static void addWarehouses(final ModelBuilder builder)
    {
        // RED warehouse (south-west)
        addBox(builder, 0.0f, 0.0f, 0.0f, 64.0f, WAREHOUSE_HEIGHT, 64.0f);

        // BLUE warehouse (north-east)
        addBox(builder, 256.0f, 0.0f, 256.0f, 320.0f, WAREHOUSE_HEIGHT, 320.0f);
    }

    /**
     * Eight storage tanks — two rows of four between the warehouses
     * (z=120..200). Each tank is a 32×32×32 box. The maze has gaps
     * at x=-50 and x=+50 in both rows.
     */
    private static void addStorageTanks(final ModelBuilder builder)
    {
        // North row (z=120..152) at x = -100, -32, 32, 100
        for (int i = 0; i < 4; i++)
        {
            final float x = -100.0f + i * 64.0f;

            addBox(builder, x - TANK_RADIUS, 0.0f, 120.0f, x + TANK_RADIUS, TANK_HEIGHT, 152.0f);
        }

        // South row (z=168..200) at the same x positions
        for (int i = 0; i < 4; i++)
        {
            final float x = -100.0f + i * 64.0f;

            addBox(builder, x - TANK_RADIUS, 0.0f, 168.0f, x + TANK_RADIUS, TANK_HEIGHT, 200.0f);
        }
    }

    /**
     * The central catwalk spine — a single north-south catwalk at x=0,
     * y=64, running the length of the map. 8 units thick, 8 units wide.
     */
    private static void addCatwalk(final ModelBuilder builder)
    {
        addBox(builder, -CATWALK_THICKNESS, CATWALK_HEIGHT, 0.0f, CATWALK_THICKNESS,
            CATWALK_HEIGHT + CATWALK_THICKNESS, 320.0f);
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
        final int base = Rgba.pack(86, 88, 92, 255);

        final int line = Rgba.pack(140, 144, 152, 255);

        final int[] out = new int[TEXTURE_EDGE * TEXTURE_EDGE];

        for (int y = 0; y < TEXTURE_EDGE; y++)
        {
            for (int x = 0; x < TEXTURE_EDGE; x++)
            {
                int colour = base;

                if (x == 0 || y == 0 || x == TEXTURE_EDGE - 1 || y == TEXTURE_EDGE - 1)
                {
                    colour = line;
                }

                out[y * TEXTURE_EDGE + x] = colour;
            }
        }

        return out;
    }

    private static int[] wallTexels()
    {
        final int base = Rgba.pack(120, 124, 132, 255);

        final int rib = Rgba.pack(96, 100, 108, 255);

        final int bandHeight = TEXTURE_EDGE / 8;

        final int[] out = new int[TEXTURE_EDGE * TEXTURE_EDGE];

        for (int y = 0; y < TEXTURE_EDGE; y++)
        {
            final int band = y / bandHeight;

            final boolean isRib = (y % bandHeight) == 0;

            for (int x = 0; x < TEXTURE_EDGE; x++)
            {
                int colour = base;

                if ((band % 2) != 0)
                {
                    colour = rib;
                }

                if (isRib)
                {
                    colour = rib;
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
