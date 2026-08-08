/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools;

import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.engine.render.adapter.Rgba;
import com.openfps.tools.model.KenneyTexture;
import com.openfps.tools.model.MipGenerator;
import com.openfps.tools.model.ModelBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds the Arctic-Dom (Frostline) map's level model: a 320x320
 * polar ice road with three flag platforms at z=80, z=160, and z=240.
 * Each platform is a 16x16 raised ice block (y=0..16) with a radar
 * mast in the centre. The central ice road runs east-west at
 * y=0, x=144..176, z=80..240. Two snow walls run east-west at z=64
 * and z=256, with 32-wide gaps at x=-100 and x=100. Two road
 * underpasses at x=-100 and x=100 cut 32-wide gaps through the road.
 *
 * <p>Build-time only — this class is never on a runtime classpath. The
 * output is committed to {@code engine/src/main/resources/maps/arctic-dom/}
 * via {@code git add -f}, the same exception the tripoint model uses.</p>
 *
 * <h2>Geometry</h2>
 *
 * <p>The level is a flat polar pad. The ground slab is a flat 320x320
 * floor at y=0. The central ice road is a 32-wide strip at
 * x=144..176, z=80..240, y=0..4. The three platforms sit on the
 * east edge of the road: FLAG_C at z=80, FLAG_B at z=160, FLAG_A at
 * z=240. Each platform is 16x16x16 (x=176..192, y=0..16, z=centre-8
 * to centre+8), with a 4-tread ramp on the road side (x=176..192,
 * y=0..4, z=centre-12 to centre+12). A 4-wide, 16-tall radar mast
 * rises from the centre of each platform. Two snow walls run
 * east-west at z=64 (north wall) and z=256 (south wall), each 16
 * tall, with 32-wide gaps at x=-100 and x=100. Four perimeter walls
 * enclose the playable area.</p>
 *
 * <h2>Textures — Kenney Prototype Kit</h2>
 *
 * <p>Floor and wall tiles are sampled from the Kenney Prototype Kit's
 * colormap.png (CC0). The pack's neutral floor and wall swatches
 * match the polar palette. Without the atlas the builder falls back
 * to a procedural generator (kept for clone-without-pack testing).</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 *   --out=&lt;dir&gt;     where the .ofm file goes; required
 *   --atlas=&lt;path&gt;  the Kenney Prototype Kit colormap.png; optional
 * </pre>
 */
public final class ArcticDomMapBuilder
{
    /** Name of the produced model, written to {@code level.ofm}. */
    public static final String MODEL_NAME = "arctic-dom-level";

    /** File name of the produced model. */
    public static final String FILE_NAME = "level.ofm";

    /** Half the map's playable extent, in world units. The map is 320x320. */
    public static final float HALF_EXTENT = 160.0f;

    /** Wall thickness, in world units. */
    public static final float WALL_THICKNESS = 6.0f;

    /** Perimeter wall height, in world units. */
    public static final float WALL_HEIGHT = 32.0f;

    /** Ice-road width, in world units. */
    public static final float ROAD_WIDTH = 32.0f;

    /** Ice-road height (above the ground slab). */
    public static final float ROAD_HEIGHT = 4.0f;

    /** Road centre x, in world units. */
    public static final float ROAD_CENTRE_X = 160.0f;

    /** Road north-edge z, in world units. */
    public static final float ROAD_MIN_Z = 80.0f;

    /** Road south-edge z, in world units. */
    public static final float ROAD_MAX_Z = 240.0f;

    /** North snow-wall z, in world units. */
    public static final float NORTH_WALL_Z = 64.0f;

    /** South snow-wall z, in world units. */
    public static final float SOUTH_WALL_Z = 256.0f;

    /** Snow-wall height, in world units. */
    public static final float SNOW_WALL_HEIGHT = 16.0f;

    /** Platform edge, in world units. The platform is square. */
    public static final float PLATFORM_EDGE = 16.0f;

    /** Platform height (above the ground slab). */
    public static final float PLATFORM_HEIGHT = 16.0f;

    /** Radar mast width, in world units. */
    public static final float MAST_WIDTH = 4.0f;

    /** Radar mast height, in world units. */
    public static final float MAST_HEIGHT = 32.0f;

    /** Texture edge, in texels. Power of two for the sampler's wrap. */
    public static final int TEXTURE_EDGE = 64;

    /** World units spanned by one repeat of a texture. */
    public static final float WORLD_UNITS_PER_TILE = 8.0f;

    private static final Logger LOG = LoggerFactory.getLogger(ArcticDomMapBuilder.class);

    private ArcticDomMapBuilder()
    {
        // entry point holder
    }

    /**
     * Builds the Arctic-Dom level model and writes it to the given directory.
     *
     * @param args {@code --out=<dir>} required, {@code --atlas=<path>} optional
     */
    public static void main(final String[] args)
    {
        final String out = option(args, "--out=");
        if (out == null)
        {
            LOG.error("usage: ArcticDomMapBuilder --out=<directory>"
                + " [--atlas=<colormap.png>]");
            return;
        }
        final String atlasOption = option(args, "--atlas=");
        final Path atlasPath;
        if (atlasOption == null)
        {
            atlasPath = null;
        }
        else
        {
            atlasPath = Path.of(atlasOption);
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
        final byte[] bytes = build(atlasPath);
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
     * Returns the {@code .ofm} bytes for the Arctic-Dom level, using the
     * procedural fallback (no Kenney atlas).
     *
     * @return the .ofm file image
     */
    public static byte[] build()
    {
        return build(null);
    }

    /**
     * Returns the {@code .ofm} bytes for the Arctic-Dom level.
     *
     * <p>Three submeshes (floor, walls, accents) with three textures.
     * The geometry is a flat ground slab, the central ice road with
     * two underpass gaps, two snow walls (each split into three runs
     * around the underpasses), three ice platforms with ramps, three
     * radar masts (the accent submesh), and four perimeter walls.</p>
     *
     * @param atlasPath the Kenney Prototype Kit colormap.png, or null
     *     for the procedural fallback
     * @return the .ofm file image
     */
    public static byte[] build(final Path atlasPath)
    {
        final ModelBuilder builder = new ModelBuilder(MODEL_NAME);
        final int[] floorTexels;
        if (atlasPath != null)
        {
            floorTexels = KenneyTexture.forceOpaque(KenneyTexture.floor(atlasPath));
        }
        else
        {
            floorTexels = floorTexels();
        }
        final int[] wallTexels;
        if (atlasPath != null)
        {
            wallTexels = KenneyTexture.forceOpaque(KenneyTexture.wall(atlasPath));
        }
        else
        {
            wallTexels = wallTexels();
        }
        final int[] accentTexels = accentTexels();
        final int floorTexture = builder.addTexture("arctic-dom-floor", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, floorTexels));
        final int wallTexture = builder.addTexture("arctic-dom-wall", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, wallTexels));
        final int accentTexture = builder.addTexture("arctic-dom-accent", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, accentTexels));

        builder.beginSubmesh(floorTexture);
        addGroundSlab(builder);
        addIceRoad(builder);
        builder.endSubmesh();

        builder.beginSubmesh(wallTexture);
        addPerimeterWalls(builder);
        addNorthSnowWall(builder);
        addSouthSnowWall(builder);
        addPlatformC(builder);
        addPlatformB(builder);
        addPlatformA(builder);
        addPlatformRamps(builder);
        builder.endSubmesh();

        builder.beginSubmesh(accentTexture);
        addAccentGeometry(builder);
        builder.endSubmesh();

        return builder.toBytes();
    }

    /**
     * Returns the {@code .ofm} triangle count.
     *
     * <p>Stated as arithmetic: 1 ground slab (12) + 1 ice road
     * (12) + 4 perimeter walls (48) + 2 snow walls (each split into
     * 3 runs around the underpasses = 6 runs = 72) + 3 platforms
     * (36) + 3 ramps (each 4 treads = 12 treads = 144) + 3 radar
     * masts (each is a mast + dish = 2 boxes = 24 triangles; 3
     * masts = 72) = 396.</p>
     *
     * @return the level's triangle count
     */
    public static int triangleCount()
    {
        return (1 + 1 + 4 + 6 + 3 + 12 + 6) * 12;
    }

    // ------------------------------------------------------------------
    // Geometry construction
    // ------------------------------------------------------------------

    /**
     * The ground slab, a flat 320x320 floor at y=0 with a 4-unit
     * thickness below for visual depth.
     */
    private static void addGroundSlab(final ModelBuilder builder)
    {
        addBox(builder, -HALF_EXTENT, -4.0f, -HALF_EXTENT, HALF_EXTENT, 0.0f, HALF_EXTENT);
    }

    /**
     * The central ice road. A 32-wide, 4-tall, 160-long strip
     * (x=144..176, y=0..4, z=80..240). The road is the contested
     * ground; the player who holds the road controls the rotation
     * between flags.
     */
    private static void addIceRoad(final ModelBuilder builder)
    {
        final float xMin = ROAD_CENTRE_X - ROAD_WIDTH / 2.0f;
        final float xMax = ROAD_CENTRE_X + ROAD_WIDTH / 2.0f;
        addBox(builder, xMin, 0.0f, ROAD_MIN_Z, xMax, ROAD_HEIGHT, ROAD_MAX_Z);
    }

    /**
     * The four perimeter walls. Short (32 units) so the snow
     * drifts at the edges do not block long sightlines.
     */
    private static void addPerimeterWalls(final ModelBuilder builder)
    {
        final float e = HALF_EXTENT;
        addBox(builder, -e, 0.0f, -e - WALL_THICKNESS, e, WALL_HEIGHT, -e);
        addBox(builder, -e, 0.0f, e, e, WALL_HEIGHT, e + WALL_THICKNESS);
        addBox(builder, -e - WALL_THICKNESS, 0.0f, -e, -e, WALL_HEIGHT, e);
        addBox(builder, e, 0.0f, -e, e + WALL_THICKNESS, WALL_HEIGHT, e);
    }

    /**
     * The north snow wall at z=64, 16 tall. Split into three runs
     * (west of x=-100, x=-68..100, east of x=100) so the underpass
     * gaps at x=-100 and x=100 are 32 wide.
     */
    private static void addNorthSnowWall(final ModelBuilder builder)
    {
        addSnowWallRun(builder, NORTH_WALL_Z, -HALF_EXTENT, -132.0f);
        addSnowWallRun(builder, NORTH_WALL_Z, -68.0f, 132.0f);
        addSnowWallRun(builder, NORTH_WALL_Z, 68.0f, HALF_EXTENT);
    }

    /**
     * The south snow wall at z=256, 16 tall. Same shape as the
     * north wall.
     */
    private static void addSouthSnowWall(final ModelBuilder builder)
    {
        addSnowWallRun(builder, SOUTH_WALL_Z, -HALF_EXTENT, -132.0f);
        addSnowWallRun(builder, SOUTH_WALL_Z, -68.0f, 132.0f);
        addSnowWallRun(builder, SOUTH_WALL_Z, 68.0f, HALF_EXTENT);
    }

    /**
     * One run of a snow wall (a single rectangular box) between two
     * x coordinates.
     */
    private static void addSnowWallRun(final ModelBuilder builder, final float z, final float minX,
        final float maxX)
    {
        addBox(builder, minX, 0.0f, z - WALL_THICKNESS / 2.0f, maxX, SNOW_WALL_HEIGHT,
            z + WALL_THICKNESS / 2.0f);
    }

    /**
     * The FLAG_C platform (North Platform) at z=80, x=176..192,
     * y=0..16. The flag is centred on (160, 16, 80).
     */
    private static void addPlatformC(final ModelBuilder builder)
    {
        addBox(builder, 176.0f, 0.0f, 72.0f, 192.0f, PLATFORM_HEIGHT, 88.0f);
    }

    /**
     * The FLAG_B platform (Centre Platform) at z=160, x=176..192,
     * y=0..16. The flag is centred on (160, 16, 160).
     */
    private static void addPlatformB(final ModelBuilder builder)
    {
        addBox(builder, 176.0f, 0.0f, 152.0f, 192.0f, PLATFORM_HEIGHT, 168.0f);
    }

    /**
     * The FLAG_A platform (South Platform) at z=240, x=176..192,
     * y=0..16. The flag is centred on (160, 16, 240).
     */
    private static void addPlatformA(final ModelBuilder builder)
    {
        addBox(builder, 176.0f, 0.0f, 232.0f, 192.0f, PLATFORM_HEIGHT, 248.0f);
    }

    /**
     * The three platform ramps. Each ramp is on the road side of
     * the platform (x=176..192, y=0..4, z=centre-12 to centre+12).
     */
    private static void addPlatformRamps(final ModelBuilder builder)
    {
        addPlatformRamp(builder, 80.0f);
        addPlatformRamp(builder, 160.0f);
        addPlatformRamp(builder, 240.0f);
    }

    /**
     * One platform ramp. 4 treads, each 4 wide, 4 tall, 6 deep,
     * climbing from y=0 to y=16.
     */
    private static void addPlatformRamp(final ModelBuilder builder, final float platformZ)
    {
        for (int i = 0; i < 4; i++)
        {
            final float yBottom = (float) i * 4.0f;
            final float yTop = yBottom + 4.0f;
            final float zMin = platformZ - 12.0f + (float) i * 6.0f;
            final float zMax = zMin + 6.0f;
            addBox(builder, 176.0f, yBottom, zMin, 180.0f, yTop, zMax);
        }
    }

    /**
     * The accent geometry. Three radar masts, one on each platform.
     * Each mast is a 4-wide, 32-tall column with a small dish on top.
     */
    private static void addAccentGeometry(final ModelBuilder builder)
    {
        // FLAG_C mast
        addBox(builder, 182.0f, PLATFORM_HEIGHT, 78.0f, 186.0f,
            PLATFORM_HEIGHT + MAST_HEIGHT, 82.0f);
        addBox(builder, 178.0f, PLATFORM_HEIGHT + MAST_HEIGHT - 8.0f, 76.0f, 190.0f,
            PLATFORM_HEIGHT + MAST_HEIGHT, 84.0f);
        // FLAG_B mast
        addBox(builder, 182.0f, PLATFORM_HEIGHT, 158.0f, 186.0f,
            PLATFORM_HEIGHT + MAST_HEIGHT, 162.0f);
        addBox(builder, 178.0f, PLATFORM_HEIGHT + MAST_HEIGHT - 8.0f, 156.0f, 190.0f,
            PLATFORM_HEIGHT + MAST_HEIGHT, 164.0f);
        // FLAG_A mast
        addBox(builder, 182.0f, PLATFORM_HEIGHT, 238.0f, 186.0f,
            PLATFORM_HEIGHT + MAST_HEIGHT, 242.0f);
        addBox(builder, 178.0f, PLATFORM_HEIGHT + MAST_HEIGHT - 8.0f, 236.0f, 190.0f,
            PLATFORM_HEIGHT + MAST_HEIGHT, 244.0f);
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

    /**
     * Adds one face to the open submesh.
     */
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
        final int base = Rgba.pack(200, 212, 224, 255);
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

    private static int[] accentTexels()
    {
        final int colour = Rgba.pack(192, 80, 80, 255);
        final int[] out = new int[TEXTURE_EDGE * TEXTURE_EDGE];
        for (int index = 0; index < out.length; index++)
        {
            out[index] = colour;
        }
        return out;
    }

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
