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
 * Builds the Tripoint map's level model: a 320x320 three-way
 * intersection at street level. A roundabout in the centre, three
 * approach streets (north, south-east, south-west) leading to the
 * three flags, and a back-alley cut-through connecting the two
 * southern approach streets.
 *
 * <p>Build-time only — this class is never on a runtime classpath. The
 * output is committed to {@code engine/src/main/resources/maps/tripoint/}
 * via {@code git add -f}, the same exception the cornerstone model
 * uses.</p>
 *
 * <h2>Geometry</h2>
 *
 * <p>The level is a flat urban block. A roundabout in the centre
 * (z=140..180, x=120..200) is the contested ground; three approach
 * streets lead out of it to the three flags. FLAG_A is to the north
 * (z=48, x=160); FLAG_C_SE is to the south-east (x=240, z=240);
 * FLAG_C_SW is to the south-west (x=80, z=240). A back-alley
 * cut-through at z=200 connects the south-east and south-west
 * approaches behind the flag positions.</p>
 *
 * <h2>Textures — Kenney Prototype Kit</h2>
 *
 * <p>Floor and wall tiles are sampled from the Kenney Prototype Kit's
 * colormap.png. The pack has no "roundabout kerb" or "flag stand" tile,
 * so the kerbs use the kit's column colour (deep blue) and the flag
 * stands use the accent red. Without the atlas the build falls back to
 * the pre-Pass 2 procedural generator.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 *   --out=&lt;dir&gt;     where the .ofm file goes; required
 *   --atlas=&lt;path&gt;  the Kenney Prototype Kit colormap.png; optional
 * </pre>
 */
public final class TripointMapBuilder
{
    /** Name of the produced model, written to {@code level.ofm}. */
    public static final String MODEL_NAME = "tripoint-level";

    /** File name of the produced model. */
    public static final String FILE_NAME = "level.ofm";

    /** Half the map's playable extent, in world units. The map is 320x320. */
    public static final float HALF_EXTENT = 160.0f;

    /** Wall thickness, in world units. */
    public static final float WALL_THICKNESS = 6.0f;

    /** Perimeter wall height, in world units. */
    public static final float WALL_HEIGHT = 64.0f;

    /** Approach road width, in world units. */
    public static final float APPROACH_ROAD_WIDTH = 32.0f;

    /** Roundabout outer radius, in world units. */
    public static final float ROUNDABOUT_RADIUS = 40.0f;

    /** Roundabout kerb height, in world units (above the ground slab). */
    public static final float ROUNDABOUT_KERB_HEIGHT = 4.0f;

    /** Flag stand height, in world units. */
    public static final float FLAG_STAND_HEIGHT = 16.0f;

    /** Texture edge, in texels. Power of two for the sampler's wrap. */
    public static final int TEXTURE_EDGE = 64;

    /** World units spanned by one repeat of a texture. */
    public static final float WORLD_UNITS_PER_TILE = 8.0f;

    private static final Logger LOG = LoggerFactory.getLogger(TripointMapBuilder.class);

    private TripointMapBuilder()
    {
        // entry point holder
    }

    /**
     * Builds the Tripoint level model and writes it to the given directory.
     *
     * @param args {@code --out=<dir>} required, {@code --atlas=<path>} optional
     */
    public static void main(final String[] args)
    {
        final String out = option(args, "--out=");

        if (out == null)
        {
            LOG.error("usage: TripointMapBuilder --out=<directory>"
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
     * Returns the {@code .ofm} bytes for the Tripoint level using the
     * procedural fallback (no Kenney atlas).
     *
     * @return the .ofm file image
     */
    public static byte[] build()
    {
        return build(null);
    }

    /**
     * Returns the {@code .ofm} bytes for the Tripoint level.
     *
     * <p>Three submeshes (floor, walls, accents) with three textures.
     * The geometry is a flat ground slab, three approach roads, a
     * roundabout kerb, three flag stands, a back-alley cut-through,
     * low perimeter walls, and a few streetlight bollards. The
     * approach roads are slightly darker (raised 2 units) than the
     * ground slab to read as a road surface.</p>
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

        final int floorTexture = builder.addTexture("tripoint-floor", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, floorTexels));

        final int wallTexture = builder.addTexture("tripoint-wall", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, wallTexels));

        final int accentTexture = builder.addTexture("tripoint-accent", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, accentTexels));

        builder.beginSubmesh(floorTexture);

        addGroundSlab(builder);

        addApproachRoads(builder);

        addRoundabout(builder);

        builder.endSubmesh();

        builder.beginSubmesh(wallTexture);

        addPerimeterWalls(builder);

        addBackAlley(builder);

        addFlagStands(builder);

        addStreetlightBollards(builder);

        builder.endSubmesh();

        builder.beginSubmesh(accentTexture);

        addAccentGeometry(builder);

        builder.endSubmesh();

        return builder.toBytes();
    }

    /**
     * Returns the {@code .ofm} triangle count.
     *
     * <p>Stated as arithmetic: 1 ground slab (12) + 3 approach roads
     * (36) + 1 roundabout kerb (12) + 4 perimeter walls (48) + 1 back
     * alley wall (12) + 3 flag stands (36) + 4 bollards (48) + 3
     * flag markers (36) = 240.</p>
     *
     * @return the level's triangle count
     */
    public static int triangleCount()
    {
        return (1 + 3 + 1 + 4 + 1 + 3 + 4 + 3) * 12;
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
     * The three approach roads. Each is a 32-wide strip, raised
     * 2 units above the ground, leading from the roundabout to one
     * of the three flags.
     */
    private static void addApproachRoads(final ModelBuilder builder)
    {
        // North approach: from the roundabout (z=180) up to FLAG_A
        // (z=48), at x=144..176.
        addBox(builder, 144.0f, 0.0f, 48.0f, 176.0f, 2.0f, 200.0f);

        // South-east approach: from the roundabout to FLAG_C_SE
        // (x=240, z=240). The road is a 32-wide strip running
        // diagonally; approximated as a 32-wide strip at the SE
        // quadrant.
        addBox(builder, 200.0f, 0.0f, 144.0f, 264.0f, 2.0f, 200.0f);

        // South-west approach: from the roundabout to FLAG_C_SW
        // (x=80, z=240).
        addBox(builder, 56.0f, 0.0f, 144.0f, 120.0f, 2.0f, 200.0f);
    }

    /**
     * The roundabout kerb. An 80x80 box (40 radius) at the centre
     * of the map (x=120..200, z=140..220), raised 4 units above the
     * ground. Reads as the kerb around the central capture zone.
     * A 16x16 flat top sits on the kerb; the kerb is hollow in
     * concept (a circle) but the box approximation reads correctly
     * from the player's distance.
     */
    private static void addRoundabout(final ModelBuilder builder)
    {
        addBox(builder, 120.0f, 0.0f, 140.0f, 200.0f, ROUNDABOUT_KERB_HEIGHT, 220.0f);
    }

    /**
     * Four low perimeter walls. Half-height (32 units) so the open
     * feel of the intersection is preserved while still providing
     * edge-of-map cover.
     */
    private static void addPerimeterWalls(final ModelBuilder builder)
    {
        final float halfWallHeight = 32.0f;

        final float e = HALF_EXTENT;

        addBox(builder, -e, 0.0f, -e - WALL_THICKNESS, e, halfWallHeight, -e);

        addBox(builder, -e, 0.0f, e, e, halfWallHeight, e + WALL_THICKNESS);

        addBox(builder, -e - WALL_THICKNESS, 0.0f, -e, -e, halfWallHeight, e);

        addBox(builder, e, 0.0f, -e, e + WALL_THICKNESS, halfWallHeight, e);
    }

    /**
     * The back-alley cut-through. A short east-west wall (z=200,
     * x=80..240) at half-height, with a gap in the middle for
     * access. The wall is on the alley itself, so what we add is
     * actually the bordering low walls: two short east-west
     * segments at z=200 with a gap at x=140..180.
     */
    private static void addBackAlley(final ModelBuilder builder)
    {
        addBox(builder, 80.0f, 0.0f, 196.0f, 140.0f, 24.0f, 204.0f);

        addBox(builder, 180.0f, 0.0f, 196.0f, 240.0f, 24.0f, 204.0f);
    }

    /**
     * The three flag stands. Each is a small box (8 wide, 16 tall)
     * marking a flag position. The three stands are at the
     * documented flag coordinates: FLAG_A at (160, 0, 48),
     * FLAG_C_SE at (240, 0, 240), FLAG_C_SW at (80, 0, 240).
     */
    private static void addFlagStands(final ModelBuilder builder)
    {
        // FLAG_A: north
        addBox(builder, 156.0f, 0.0f, 44.0f, 164.0f, FLAG_STAND_HEIGHT, 52.0f);

        // FLAG_C_SE: south-east
        addBox(builder, 236.0f, 0.0f, 236.0f, 244.0f, FLAG_STAND_HEIGHT, 244.0f);

        // FLAG_C_SW: south-west
        addBox(builder, 76.0f, 0.0f, 236.0f, 84.0f, FLAG_STAND_HEIGHT, 244.0f);
    }

    /**
     * Four streetlight bollards. Small square pillars (8 wide, 32
     * tall) at the four corners of the central intersection, just
     * outside the roundabout kerb.
     */
    private static void addStreetlightBollards(final ModelBuilder builder)
    {
        // NW corner
        addBox(builder, 104.0f, 0.0f, 124.0f, 112.0f, 32.0f, 132.0f);

        // NE corner
        addBox(builder, 208.0f, 0.0f, 124.0f, 216.0f, 32.0f, 132.0f);

        // SW corner
        addBox(builder, 104.0f, 0.0f, 228.0f, 112.0f, 32.0f, 236.0f);

        // SE corner
        addBox(builder, 208.0f, 0.0f, 228.0f, 216.0f, 32.0f, 236.0f);
    }

    /**
     * The accent geometry. Three small flag markers sitting on top
     * of the flag stands. The markers are thin vertical slats
     * (2 wide, 8 tall, 4 deep) that read as flagpoles when
     * rendered.
     */
    private static void addAccentGeometry(final ModelBuilder builder)
    {
        // FLAG_A pole
        addBox(builder, 159.0f, FLAG_STAND_HEIGHT, 47.0f, 161.0f,
            FLAG_STAND_HEIGHT + 8.0f, 49.0f);

        // FLAG_C_SE pole
        addBox(builder, 239.0f, FLAG_STAND_HEIGHT, 239.0f, 241.0f,
            FLAG_STAND_HEIGHT + 8.0f, 241.0f);

        // FLAG_C_SW pole
        addBox(builder, 79.0f, FLAG_STAND_HEIGHT, 239.0f, 81.0f,
            FLAG_STAND_HEIGHT + 8.0f, 241.0f);
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
