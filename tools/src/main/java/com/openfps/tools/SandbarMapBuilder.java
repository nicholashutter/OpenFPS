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
 * Builds the Sandbar map's level model: a 320x320 wide, shallow
 * canyon with three flat-topped sandstone buttes at z=64, z=160, and
 * z=256. Each butte is 32 tall, with a single 8-tread ramp on the
 * east side. A dry riverbed runs through the centre (y=-8, x=152..168,
 * z=-160..320). Four corner rocks, two cactus pairs, and two wash
 * channels provide cover.
 *
 * <p>Build-time only — this class is never on a runtime classpath. The
 * output is committed to {@code engine/src/main/resources/maps/sandbar/}
 * via {@code git add -f}, the same exception the tripoint model uses.</p>
 *
 * <h2>Geometry</h2>
 *
 * <p>The level is a flat desert pad. The ground slab is a flat 320x320
 * floor at y=0, with a 16-unit-wide, 8-unit-deep dry riverbed running
 * through the centre (x=152..168, y=-8..0). Three sandstone buttes sit
 * at z=64, z=160, and z=256 (each 96 wide x 32 tall x 96 deep,
 * x=112..208, y=0..32). Each butte has a single 8-tread ramp on the
 * east side (x=208..240, eight 4-tall steps climbing from y=0 to
 * y=32). Four corner rocks (16x12x16) and two cactus pairs (4x40x4)
 * provide cover. Two wash channels (32 wide, 8 deep) run east-west at
 * z=40 and z=280 to break up the long sightlines. Four perimeter
 * walls enclose the playable area.</p>
 *
 * <h2>Textures — Kenney Prototype Kit</h2>
 *
 * <p>Floor and wall tiles are sampled from the Kenney Prototype Kit's
 * colormap.png (CC0). The pack has no "sand" or "sandstone" tile, so
 * the floor and walls use the kit's neutral swatches. Without the
 * atlas the builder falls back to a procedural generator (kept for
 * clone-without-pack testing).</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 *   --out=&lt;dir&gt;     where the .ofm file goes; required
 *   --atlas=&lt;path&gt;  the Kenney Prototype Kit colormap.png; optional
 * </pre>
 */
public final class SandbarMapBuilder
{
    /** Name of the produced model, written to {@code level.ofm}. */
    public static final String MODEL_NAME = "sandbar-level";

    /** File name of the produced model. */
    public static final String FILE_NAME = "level.ofm";

    /** Half the map's playable extent, in world units. The map is 320x320. */
    public static final float HALF_EXTENT = 160.0f;

    /** Wall thickness, in world units. */
    public static final float WALL_THICKNESS = 6.0f;

    /** Perimeter wall height, in world units. */
    public static final float WALL_HEIGHT = 56.0f;

    /** Butte half-edge (the butte is square, 96 across). */
    public static final float BUTTE_HALF = 48.0f;

    /** Butte top elevation, in world units. */
    public static final float BUTTE_TOP_Y = 32.0f;

    /** Butte ramp width (E-W extent of each step's x). */
    public static final float RAMP_STEP_X = 4.0f;

    /** Butte ramp tread depth (N-S extent of each step's z). */
    public static final float RAMP_STEP_Z = 3.0f;

    /** Number of treads on each butte ramp. */
    public static final int RAMP_TREADS = 8;

    /** Number of corner rocks. */
    public static final int CORNER_ROCKS = 4;

    /** Number of cactus pairs. */
    public static final int CACTUS_PAIRS = 2;

    /** Texture edge, in texels. Power of two for the sampler's wrap. */
    public static final int TEXTURE_EDGE = 64;

    /** World units spanned by one repeat of a texture. */
    public static final float WORLD_UNITS_PER_TILE = 8.0f;

    private static final Logger LOG = LoggerFactory.getLogger(SandbarMapBuilder.class);

    private SandbarMapBuilder()
    {
        // entry point holder
    }

    /**
     * Builds the Sandbar level model and writes it to the given directory.
     *
     * @param args {@code --out=<dir>} required, {@code --atlas=<path>} optional
     */
    public static void main(final String[] args)
    {
        final String out = option(args, "--out=");

        if (out == null)
        {
            LOG.error("usage: SandbarMapBuilder --out=<directory>"
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
     * Returns the {@code .ofm} bytes for the Sandbar level, using the
     * procedural fallback (no Kenney atlas).
     *
     * @return the .ofm file image
     */
    public static byte[] build()
    {
        return build(null);
    }

    /**
     * Returns the {@code .ofm} bytes for the Sandbar level.
     *
     * <p>Three submeshes (floor, walls, accents) with three textures.
     * The geometry is a flat ground slab with a 16-wide, 8-deep
     * central dry riverbed, three sandstone buttes (each 96x32x96
     * with one 8-tread east-side ramp), four corner rocks, two cactus
     * pairs, two wash channels, four perimeter walls, and a butte-top
     * tile pattern in the accent submesh (a small 8x8x8 cap on each
     * butte that reads as a survey marker).</p>
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

        final int floorTexture = builder.addTexture("sandbar-floor", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, floorTexels));

        final int wallTexture = builder.addTexture("sandbar-wall", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, wallTexels));

        final int accentTexture = builder.addTexture("sandbar-accent", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, accentTexels));

        builder.beginSubmesh(floorTexture);

        addGroundSlab(builder);

        addDryRiverbed(builder);

        builder.endSubmesh();

        builder.beginSubmesh(wallTexture);

        addPerimeterWalls(builder);

        addButteN(builder);

        addButteCentre(builder);

        addButteS(builder);

        addRamps(builder);

        addWashChannels(builder);

        addCornerRocks(builder);

        addCactusPairs(builder);

        builder.endSubmesh();

        builder.beginSubmesh(accentTexture);

        addAccentGeometry(builder);

        builder.endSubmesh();

        return builder.toBytes();
    }

    /**
     * Returns the {@code .ofm} triangle count.
     *
     * <p>Stated as arithmetic: 1 ground slab (12) + 1 dry riverbed
     * (12) + 4 perimeter walls (48) + 3 buttes (36) + 3 ramps
     * (24 treads = 288) + 2 wash channels (24) + 4 corner rocks
     * (48) + 4 cacti (48) + 3 survey markers (36) = 552.</p>
     *
     * @return the level's triangle count
     */
    public static int triangleCount()
    {
        return (1 + 1 + 4 + 3 + RAMP_TREADS * 3 + 2 + CORNER_ROCKS + CACTUS_PAIRS * 2 + 3) * 12;
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
     * The dry riverbed. A 16-wide, 8-deep, 320-long depression
     * running through the centre of the map (x=152..168, y=-8..0,
     * z=-160..320). The riverbed floor sits at y=-8; the slab edges
     * are the river banks.
     */
    private static void addDryRiverbed(final ModelBuilder builder)
    {
        addBox(builder, 152.0f, -8.0f, -HALF_EXTENT, 168.0f, 0.0f, HALF_EXTENT);
    }

    /**
     * The four perimeter walls. Half-height (56 units) so the canyon
     * feel is preserved while still providing edge-of-map cover.
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
     * The north butte at z=64, x=112..208, y=0..32. The flag is
     * centred on (160, 32, 64).
     */
    private static void addButteN(final ModelBuilder builder)
    {
        addBox(builder, 112.0f, 0.0f, 16.0f, 208.0f, BUTTE_TOP_Y, 112.0f);
    }

    /**
     * The central butte at z=160, x=112..208, y=0..32. The flag is
     * centred on (160, 32, 160).
     */
    private static void addButteCentre(final ModelBuilder builder)
    {
        addBox(builder, 112.0f, 0.0f, 112.0f, 208.0f, BUTTE_TOP_Y, 208.0f);
    }

    /**
     * The south butte at z=256, x=112..208, y=0..32. The flag is
     * centred on (160, 32, 256).
     */
    private static void addButteS(final ModelBuilder builder)
    {
        addBox(builder, 112.0f, 0.0f, 208.0f, 208.0f, BUTTE_TOP_Y, 304.0f);
    }

    /**
     * The three butte ramps, one per butte. Each ramp is on the east
     * side of its butte, 8 treads climbing from y=0 to y=32 at
     * x=208..240.
     */
    private static void addRamps(final ModelBuilder builder)
    {
        addRamp(builder, 64.0f);

        addRamp(builder, 160.0f);

        addRamp(builder, 256.0f);
    }

    /**
     * One butte ramp. 8 treads, each (RAMP_STEP_X wide, 4 tall,
     * RAMP_STEP_Z deep), climbing from y=0 to y=32. The treads
     * advance south-to-north so the player climbs the east face of
     * the butte.
     */
    private static void addRamp(final ModelBuilder builder, final float butteZ)
    {
        final float rampX = 208.0f;

        for (int i = 0; i < RAMP_TREADS; i++)
        {
            final float yBottom = (float) i * 4.0f;

            final float yTop = yBottom + 4.0f;

            final float zMin = butteZ - BUTTE_HALF + (float) i * RAMP_STEP_Z;

            final float zMax = zMin + RAMP_STEP_Z;

            addBox(builder, rampX, yBottom, zMin, rampX + RAMP_STEP_X, yTop, zMax);
        }
    }

    /**
     * The two wash channels. Each is a 32-wide, 8-deep, 320-long
     * depression running east-west at z=40 (north wash) and z=280
     * (south wash).
     */
    private static void addWashChannels(final ModelBuilder builder)
    {
        addBox(builder, -HALF_EXTENT, -8.0f, 24.0f, HALF_EXTENT, 0.0f, 56.0f);

        addBox(builder, -HALF_EXTENT, -8.0f, 264.0f, HALF_EXTENT, 0.0f, 296.0f);
    }

    /**
     * The four corner rocks. Each is a 16x12x16 boulder at the
     * corners of the playable area. They are slightly inset so they
     * sit just inside the perimeter walls.
     */
    private static void addCornerRocks(final ModelBuilder builder)
    {
        // NW corner
        addBox(builder, -148.0f, 0.0f, -148.0f, -132.0f, 12.0f, -132.0f);

        // NE corner
        addBox(builder, 132.0f, 0.0f, -148.0f, 148.0f, 12.0f, -132.0f);

        // SW corner
        addBox(builder, -148.0f, 0.0f, 132.0f, -132.0f, 12.0f, 148.0f);

        // SE corner
        addBox(builder, 132.0f, 0.0f, 132.0f, 148.0f, 12.0f, 148.0f);
    }

    /**
     * The two cactus pairs. Each pair is two 4x40x4 cacti standing
     * on the canyon floor. The pairs sit at z=120 and z=200, on the
     * west side of the map.
     */
    private static void addCactusPairs(final ModelBuilder builder)
    {
        // Pair 1: z=120
        addBox(builder, -56.0f, 0.0f, 116.0f, -52.0f, 40.0f, 120.0f);

        addBox(builder, -36.0f, 0.0f, 124.0f, -32.0f, 40.0f, 128.0f);

        // Pair 2: z=200
        addBox(builder, -56.0f, 0.0f, 196.0f, -52.0f, 40.0f, 200.0f);

        addBox(builder, -36.0f, 0.0f, 204.0f, -32.0f, 40.0f, 208.0f);
    }

    /**
     * The accent geometry. Three small 8x8x8 survey markers sitting
     * on top of each butte. The markers are red and read as the
     * "you-are-here" pin from a player's distance.
     */
    private static void addAccentGeometry(final ModelBuilder builder)
    {
        // North butte marker
        addBox(builder, 156.0f, BUTTE_TOP_Y, 60.0f, 164.0f, BUTTE_TOP_Y + 8.0f, 68.0f);

        // Centre butte marker
        addBox(builder, 156.0f, BUTTE_TOP_Y, 156.0f, 164.0f, BUTTE_TOP_Y + 8.0f, 164.0f);

        // South butte marker
        addBox(builder, 156.0f, BUTTE_TOP_Y, 252.0f, 164.0f, BUTTE_TOP_Y + 8.0f, 260.0f);
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
        final int base = Rgba.pack(192, 168, 132, 255);

        final int streak = Rgba.pack(168, 144, 108, 255);

        final int[] out = new int[TEXTURE_EDGE * TEXTURE_EDGE];

        for (int y = 0; y < TEXTURE_EDGE; y++)
        {
            for (int x = 0; x < TEXTURE_EDGE; x++)
            {
                int colour = base;

                if ((x * 7 + y * 13) % 23 == 0)
                {
                    colour = streak;
                }

                out[y * TEXTURE_EDGE + x] = colour;
            }
        }

        return out;
    }

    private static int[] wallTexels()
    {
        final int base = Rgba.pack(168, 140, 100, 255);

        final int shade = Rgba.pack(140, 112, 76, 255);

        final int rib = Rgba.pack(112, 84, 52, 255);

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
        final int colour = Rgba.pack(200, 64, 48, 255);

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
