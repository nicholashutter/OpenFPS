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
 * Builds the Extraction map's level model: a 320x320 urban block split
 * by a long boulevard. Each team's base sits at one end of the
 * boulevard, with the flag in a small structure inside the base. Lane
 * B is the boulevard; lanes A and C run parallel on either side and
 * are the routes a defender uses to flank the carrier.
 *
 * <p>Build-time only — this class is never on a runtime classpath. The
 * output is committed to {@code engine/src/main/resources/maps/extraction/}
 * via {@code git add -f}, the same exception the cornerstone model
 * uses.</p>
 *
 * <h2>Geometry</h2>
 *
 * <p>The level is a flat urban block. The boulevard runs along the
 * centre (z=120..200, x=-160..160) at y=2 (slightly raised to read as
 * a road). Red's base sits at the south-west corner (x=16..80,
 * z=16..80), Blue's base at the north-east corner (x=240..304,
 * z=240..304). Two cover walls flank the boulevard in lanes A and C
 * (x=64 and x=256, at z=120 and z=200). Two cut-throughs align at
 * x=160 to allow lateral movement between lanes.</p>
 *
 * <h2>Textures — Kenney Prototype Kit</h2>
 *
 * <p>Floor and wall tiles are sampled from the Kenney Prototype Kit's
 * colormap.png. Bases use a slightly raised (4 unit) platform with
 * the kit's column colour (deep blue) to read as "owned ground".
 * Accent geometry (flagpoles, base trim) is procedural red. Without
 * the atlas the build falls back to the pre-Pass 2 procedural
 * generator.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 *   --out=&lt;dir&gt;     where the .ofm file goes; required
 *   --atlas=&lt;path&gt;  the Kenney Prototype Kit colormap.png; optional
 * </pre>
 */
public final class ExtractionMapBuilder
{
    /** Name of the produced model, written to {@code level.ofm}. */
    public static final String MODEL_NAME = "extraction-level";

    /** File name of the produced model. */
    public static final String FILE_NAME = "level.ofm";

    /** Half the map's playable extent, in world units. The map is 320x320. */
    public static final float HALF_EXTENT = 160.0f;

    /** Wall thickness, in world units. */
    public static final float WALL_THICKNESS = 6.0f;

    /** Perimeter wall height, in world units. */
    public static final float WALL_HEIGHT = 80.0f;

    /** Base platform height, in world units (above the ground slab). */
    public static final float BASE_PLATFORM_HEIGHT = 4.0f;

    /** Cover wall height (in lanes A and C), in world units. */
    public static final float COVER_WALL_HEIGHT = 48.0f;

    /** Texture edge, in texels. Power of two for the sampler's wrap. */
    public static final int TEXTURE_EDGE = 64;

    /** World units spanned by one repeat of a texture. */
    public static final float WORLD_UNITS_PER_TILE = 8.0f;

    private static final Logger LOG = LoggerFactory.getLogger(ExtractionMapBuilder.class);

    private ExtractionMapBuilder()
    {
        // entry point holder
    }

    /**
     * Builds the Extraction level model and writes it to the given directory.
     *
     * @param args {@code --out=<dir>} required, {@code --atlas=<path>} optional
     */
    public static void main(final String[] args)
    {
        final String out = option(args, "--out=");
        if (out == null)
        {
            LOG.error("usage: ExtractionMapBuilder --out=<directory>"
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
     * Returns the {@code .ofm} bytes for the Extraction level using the
     * procedural fallback (no Kenney atlas).
     *
     * @return the .ofm file image
     */
    public static byte[] build()
    {
        return build(null);
    }

    /**
     * Returns the {@code .ofm} bytes for the Extraction level.
     *
     * <p>Three submeshes (floor, walls, accents) with three textures.
     * The geometry is a flat ground slab, the long boulevard, two base
     * platforms (one per team), two cover walls flanking the boulevard,
     * two cut-through walls with a gap at x=160, perimeter walls, and
     * flagpole markers at each base.</p>
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
        final int floorTexture = builder.addTexture("extraction-floor", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, floorTexels));
        final int wallTexture = builder.addTexture("extraction-wall", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, wallTexels));
        final int accentTexture = builder.addTexture("extraction-accent", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, accentTexels));

        builder.beginSubmesh(floorTexture);
        addGroundSlab(builder);
        addBoulevard(builder);
        builder.endSubmesh();

        builder.beginSubmesh(wallTexture);
        addPerimeterWalls(builder);
        addBasePlatforms(builder);
        addCoverWalls(builder);
        addCutThroughWalls(builder);
        builder.endSubmesh();

        builder.beginSubmesh(accentTexture);
        addAccentGeometry(builder);
        builder.endSubmesh();

        return builder.toBytes();
    }

    /**
     * Returns the {@code .ofm} triangle count.
     *
     * <p>Stated as arithmetic: 1 ground slab (12) + 1 boulevard (12)
     * + 4 perimeter walls (48) + 2 base platforms (24) + 4 cover
     * walls (48) + 4 cut-through wall pieces (48) + 2 flagpoles (24)
     * = 216.</p>
     *
     * @return the level's triangle count
     */
    public static int triangleCount()
    {
        return (1 + 1 + 4 + 2 + 4 + 4 + 2) * 12;
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
     * The boulevard. A 320-long, 80-wide strip from x=-160 to
     * x=160, raised 2 units above the ground, running along z=120 to
     * z=200.
     */
    private static void addBoulevard(final ModelBuilder builder)
    {
        addBox(builder, -HALF_EXTENT, 0.0f, 120.0f, HALF_EXTENT, 2.0f, 200.0f);
    }

    /**
     * Four low perimeter walls. Half-height (40 units) so the bases
     * are still visible from across the map while the long sightlines
     * are not quite open.
     */
    private static void addPerimeterWalls(final ModelBuilder builder)
    {
        final float halfWallHeight = 40.0f;
        final float e = HALF_EXTENT;
        addBox(builder, -e, 0.0f, -e - WALL_THICKNESS, e, halfWallHeight, -e);
        addBox(builder, -e, 0.0f, e, e, halfWallHeight, e + WALL_THICKNESS);
        addBox(builder, -e - WALL_THICKNESS, 0.0f, -e, -e, halfWallHeight, e);
        addBox(builder, e, 0.0f, -e, e + WALL_THICKNESS, halfWallHeight, e);
    }

    /**
     * The two base platforms. Each is a 64x64 platform raised 4 units
     * above the ground. Red's base is at the south-west corner
     * (x=16..80, z=16..80), Blue's at the north-east corner
     * (x=240..304, z=240..304). The flag and capture point sit on
     * top of the platform.
     */
    private static void addBasePlatforms(final ModelBuilder builder)
    {
        addBox(builder, 16.0f, 0.0f, 16.0f, 80.0f, BASE_PLATFORM_HEIGHT, 80.0f);
        addBox(builder, 240.0f, 0.0f, 240.0f, 304.0f, BASE_PLATFORM_HEIGHT, 304.0f);
    }

    /**
     * The four cover walls in lanes A and C. Two at x=64, two at
     * x=256, each running along z for half the map's depth. They
     * provide flanking cover for defenders.
     */
    private static void addCoverWalls(final ModelBuilder builder)
    {
        // Lane A cover walls (north of the boulevard)
        addBox(builder, 56.0f, 0.0f, 16.0f, 72.0f, COVER_WALL_HEIGHT, 120.0f);
        addBox(builder, 248.0f, 0.0f, 16.0f, 264.0f, COVER_WALL_HEIGHT, 120.0f);
        // Lane C cover walls (south of the boulevard)
        addBox(builder, 56.0f, 0.0f, 200.0f, 72.0f, COVER_WALL_HEIGHT, 304.0f);
        addBox(builder, 248.0f, 0.0f, 200.0f, 264.0f, COVER_WALL_HEIGHT, 304.0f);
    }

    /**
     * The two cut-through walls. Each cut-through is a low east-west
     * wall with a gap at x=160: the north cut-through at z=120 and
     * the south cut-through at z=200. The gap allows lateral movement
     * between the lanes at the centre of the map.
     */
    private static void addCutThroughWalls(final ModelBuilder builder)
    {
        // North cut-through (z=120): two wall pieces with a gap at x=160
        addBox(builder, 16.0f, 0.0f, 114.0f, 154.0f, 32.0f, 126.0f);
        addBox(builder, 166.0f, 0.0f, 114.0f, 304.0f, 32.0f, 126.0f);
        // South cut-through (z=200): same pattern
        addBox(builder, 16.0f, 0.0f, 194.0f, 154.0f, 32.0f, 206.0f);
        addBox(builder, 166.0f, 0.0f, 194.0f, 304.0f, 32.0f, 206.0f);
    }

    /**
     * The accent geometry. Two flagpoles — one on each base platform,
     * marking the flag's position. Each is a thin (4 wide, 24 tall)
     * pole on top of the platform.
     */
    private static void addAccentGeometry(final ModelBuilder builder)
    {
        // Red flagpole (south-west base)
        addBox(builder, 30.0f, BASE_PLATFORM_HEIGHT, 30.0f, 34.0f,
            BASE_PLATFORM_HEIGHT + 24.0f, 34.0f);
        // Blue flagpole (north-east base)
        addBox(builder, 286.0f, BASE_PLATFORM_HEIGHT, 286.0f, 290.0f,
            BASE_PLATFORM_HEIGHT + 24.0f, 290.0f);
    }

    /**
     * Adds a closed axis-aligned box to the open submesh.
     */
    private static void addBox(final ModelBuilder builder, final float minX, final float minY,
        final float minZ, final float maxX, final float maxY, final float maxZ)
    {
        // +x face
        addFace(builder, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, maxX, minY, minZ);
        // -x face
        addFace(builder, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, minX, minY, maxZ);
        // +y face
        addFace(builder, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, minX, maxY, minZ);
        // -y face
        addFace(builder, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ);
        // +z face
        addFace(builder, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, minY, maxZ);
        // -z face
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
        final int shade = Rgba.pack(88, 92, 100, 255);
        final int[] out = new int[TEXTURE_EDGE * TEXTURE_EDGE];
        for (int y = 0; y < TEXTURE_EDGE; y++)
        {
            for (int x = 0; x < TEXTURE_EDGE; x++)
            {
                int colour = base;
                if ((x / 8 + y / 8) % 2 == 0)
                {
                    colour = shade;
                }
                out[y * TEXTURE_EDGE + x] = colour;
            }
        }
        return out;
    }

    private static int[] accentTexels()
    {
        final int colour = Rgba.pack(220, 48, 48, 255);
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
