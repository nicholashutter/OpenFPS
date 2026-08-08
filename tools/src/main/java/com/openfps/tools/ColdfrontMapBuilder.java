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
 * Builds the Coldfront map's level model: a 320x320 polar-research
 * base split across two sides of a frozen river. RED base on the
 * west bank, BLUE base on the east bank, with watchtowers watching
 * the river. The full design spec is in
 * {@code docs/maps/arctic-station/04-ctf-arctic.md}.
 *
 * <p>Build-time only — this class is never on a runtime classpath.
 * The output is committed to
 * {@code engine/src/main/resources/maps/coldfront/} via
 * {@code git add -f}.</p>
 */
public final class ColdfrontMapBuilder
{
    /** Name of the produced model, written to {@code level.ofm}. */
    public static final String MODEL_NAME = "coldfront-level";

    /** File name of the produced model. */
    public static final String FILE_NAME = "level.ofm";

    /** Half the map's playable extent, in world units. The map is 320x320. */
    public static final float HALF_EXTENT = 160.0f;

    /** Wall thickness, in world units. */
    public static final float WALL_THICKNESS = 6.0f;

    /** Perimeter wall height, in world units. */
    public static final float WALL_HEIGHT = 80.0f;

    /** Main hut edge length, in world units. */
    public static final float HUT_EDGE = 32.0f;

    /** Main hut height, in world units. */
    public static final float HUT_HEIGHT = 24.0f;

    /** Watchtower edge length, in world units. */
    public static final float TOWER_EDGE = 16.0f;

    /** Watchtower height, in world units. */
    public static final float TOWER_HEIGHT = 48.0f;

    /** Service shed edge length, in world units. */
    public static final float SHED_EDGE = 24.0f;

    /** Service shed height, in world units. */
    public static final float SHED_HEIGHT = 16.0f;

    /** Texture edge, in texels. Power of two for the sampler's wrap. */
    public static final int TEXTURE_EDGE = 64;

    /** World units spanned by one repeat of a texture. */
    public static final float WORLD_UNITS_PER_TILE = 8.0f;

    private static final Logger LOG = LoggerFactory.getLogger(ColdfrontMapBuilder.class);

    private ColdfrontMapBuilder()
    {
        // entry point holder
    }

    /**
     * Builds the Coldfront level model and writes it to the given directory.
     *
     * @param args {@code --out=<dir>} required
     */
    public static void main(final String[] args)
    {
        final String out = option(args, "--out=");
        if (out == null)
        {
            LOG.error("usage: ColdfrontMapBuilder --out=<directory>");
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
     * Returns the {@code .ofm} bytes for the Coldfront level.
     *
     * <p>Two submeshes: floor (ground slab) and walls (perimeter,
     * main huts, watchtowers, service sheds). Two textures.</p>
     *
     * @return the .ofm file image
     */
    public static byte[] build()
    {
        final ModelBuilder builder = new ModelBuilder(MODEL_NAME);
        final int floorTexture = builder.addTexture("coldfront-floor", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE,
                floorTexels()));
        final int wallTexture = builder.addTexture("coldfront-wall", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE,
                wallTexels()));

        builder.beginSubmesh(floorTexture);
        addGroundSlab(builder);
        builder.endSubmesh();

        builder.beginSubmesh(wallTexture);
        addPerimeterWalls(builder);
        addMainHuts(builder);
        addWatchtowers(builder);
        addServiceSheds(builder);
        builder.endSubmesh();

        return builder.toBytes();
    }

    /**
     * Returns the level's triangle count.
     *
     * <p>1 ground slab (12) + 4 perimeter walls (48) + 2 main huts
     * (24) + 2 watchtowers (24) + 2 service sheds (24) = 132.</p>
     *
     * @return the level's triangle count
     */
    public static int triangleCount()
    {
        return (1 + 4 + 2 + 2 + 2) * 12;
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
     * The two main huts — RED at the west bank, BLUE at the east
     * bank. Each is a 32×32×24 box. The flag and the capture point
     * sit at the centre of the hut floor.
     */
    private static void addMainHuts(final ModelBuilder builder)
    {
        // RED main hut (west bank, at the flag position (-128, 160))
        addBox(builder, -128.0f - HUT_EDGE / 2.0f, 0.0f, 160.0f - HUT_EDGE / 2.0f,
            -128.0f + HUT_EDGE / 2.0f, HUT_HEIGHT, 160.0f + HUT_EDGE / 2.0f);
        // BLUE main hut (east bank, at the flag position (128, 160))
        addBox(builder, 128.0f - HUT_EDGE / 2.0f, 0.0f, 160.0f - HUT_EDGE / 2.0f,
            128.0f + HUT_EDGE / 2.0f, HUT_HEIGHT, 160.0f + HUT_EDGE / 2.0f);
    }

    /**
     * The two watchtowers — RED's at the river side of the west
     * compound, BLUE's at the river side of the east compound. Each
     * is a 16×16×48 block.
     */
    private static void addWatchtowers(final ModelBuilder builder)
    {
        // RED watchtower
        addBox(builder, -32.0f - TOWER_EDGE / 2.0f, 0.0f, 160.0f - TOWER_EDGE / 2.0f,
            -32.0f + TOWER_EDGE / 2.0f, TOWER_HEIGHT, 160.0f + TOWER_EDGE / 2.0f);
        // BLUE watchtower
        addBox(builder, 32.0f - TOWER_EDGE / 2.0f, 0.0f, 160.0f - TOWER_EDGE / 2.0f,
            32.0f + TOWER_EDGE / 2.0f, TOWER_HEIGHT, 160.0f + TOWER_EDGE / 2.0f);
    }

    /**
     * The two service sheds — RED's at (-64, 120) and BLUE's at
     * (64, 120). Each is a 24×24×16 block.
     */
    private static void addServiceSheds(final ModelBuilder builder)
    {
        // RED service shed
        addBox(builder, -64.0f - SHED_EDGE / 2.0f, 0.0f, 120.0f - SHED_EDGE / 2.0f,
            -64.0f + SHED_EDGE / 2.0f, SHED_HEIGHT, 120.0f + SHED_EDGE / 2.0f);
        // BLUE service shed
        addBox(builder, 64.0f - SHED_EDGE / 2.0f, 0.0f, 120.0f - SHED_EDGE / 2.0f,
            64.0f + SHED_EDGE / 2.0f, SHED_HEIGHT, 120.0f + SHED_EDGE / 2.0f);
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
        final int base = Rgba.pack(232, 240, 248, 255);
        final int shade = Rgba.pack(208, 222, 236, 255);
        final int drift = Rgba.pack(190, 210, 228, 255);
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
                if (x % 16 == 0)
                {
                    colour = drift;
                }
                out[y * TEXTURE_EDGE + x] = colour;
            }
        }
        return out;
    }

    private static int[] wallTexels()
    {
        final int base = Rgba.pack(196, 208, 220, 255);
        final int shade = Rgba.pack(168, 184, 200, 255);
        final int rib = Rgba.pack(140, 156, 172, 255);
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
                    colour = shade;
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
