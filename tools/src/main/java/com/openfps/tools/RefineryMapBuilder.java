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
 * Builds the Refinery map's level model: a 320x320 industrial complex with
 * three lanes, multi-level geometry (floor, mid-level catwalks, tall tank
 * tops), and four named landmarks.
 *
 * <p>Build-time only — this class is never on a runtime classpath. The output
 * is committed to {@code engine/src/main/resources/maps/refinery/} via
 * {@code git add -f}, the same exception the {@code cornerstone} model uses.</p>
 *
 * <h2>Geometry</h2>
 *
 * <p>The level is a square block centered on the world origin. Three
 * conceptual lanes run north-south: <b>lane A</b> (the tank row) is a
 * line of three tall distillation columns on a raised catwalk,
 * <b>lane B</b> (the process hall) is a large open building with
 * internal pipework at mid-height, and <b>lane C</b> (the boiler row)
 * is three wide low structures that block the south sightlines. Two
 * east-west cut-throughs and one north-south cut-through connect the
 * lanes; a mid-level catwalk ring (y=64) lets a player who has climbed
 * a stairway rotate between lanes without coming down.</p>
 *
 * <h2>Textures — Kenney Prototype Kit (Pass 5)</h2>
 *
 * <p>Floor and wall tiles are sampled from the Kenney Prototype Kit's
 * colormap.png (CC0; {@code docs/ASSETS.md} § 3 records the provenance).
 * Pre-Pass 5 the builder produced two hand-authored procedural textures
 * (a worn-concrete floor and a rusted-steel wall); the
 * {@code --atlas=<colormap.png>} flag swaps those for the kit's
 * neutral floor and wall swatches. Without the atlas the builder falls
 * back to the pre-Pass 5 procedural generator, so a clone without the
 * pack can still build the level.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 *   --out=&lt;dir&gt;     where the .ofm file goes; required
 *   --atlas=&lt;path&gt;  the Kenney Prototype Kit colormap.png; optional
 * </pre>
 */
public final class RefineryMapBuilder
{
    /** Name of the produced model, written to {@code level.ofm}. */
    public static final String MODEL_NAME = "refinery-level";

    /** File name of the produced model. */
    public static final String FILE_NAME = "level.ofm";

    /** Half the map's playable extent, in world units. The map is 320x320. */
    public static final float HALF_EXTENT = 160.0f;

    /** Wall thickness, in world units. */
    public static final float WALL_THICKNESS = 6.0f;

    /** Perimeter wall height, in world units. */
    public static final float WALL_HEIGHT = 96.0f;

    /** Catwalk height (mid-level), in world units. */
    public static final float CATWALK_HEIGHT = 64.0f;

    /** Tank height (tall landmark), in world units. */
    public static final float TANK_HEIGHT = 128.0f;

    /** Boiler height (low landmark), in world units. */
    public static final float BOILER_HEIGHT = 40.0f;

    /** Pipe radius (small landmark), in world units. */
    public static final float PIPE_RADIUS = 3.0f;

    /** Texture edge, in texels. Power of two for the sampler's wrap. */
    public static final int TEXTURE_EDGE = 64;

    /** World units spanned by one repeat of a texture. */
    public static final float WORLD_UNITS_PER_TILE = 8.0f;

    private static final Logger LOG = LoggerFactory.getLogger(RefineryMapBuilder.class);

    private RefineryMapBuilder()
    {
        // entry point holder
    }

    /**
     * Builds the Refinery level model and writes it to the given directory.
     *
     * @param args {@code --out=<dir>} required, {@code --atlas=<path>} optional
     */
    public static void main(final String[] args)
    {
        final String out = option(args, "--out=");
        if (out == null)
        {
            LOG.error("usage: RefineryMapBuilder --out=<directory>"
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
     * Returns the {@code .ofm} bytes for the Refinery level, using the
     * pre-Pass 5 procedural texture generator (no Kenney atlas).
     *
     * <p>Convenience overload for callers without a Kenney atlas staged;
     * tests and CI use this. Production builds go through
     * {@link #build(Path)}.</p>
     *
     * @return the .ofm file image
     */
    public static byte[] build()
    {
        return build(null);
    }

    /**
     * Returns the {@code .ofm} bytes for the Refinery level.
     *
     * <p>Two submeshes (floor and walls) with two textures. Geometry is a
     * flat floor, four perimeter walls, three tall tanks with catwalks,
     * a process hall with internal pipework, three boiler structures,
     * and four stairways.</p>
     *
     * <p>When {@code atlasPath} is non-null, the floor and wall textures
     * are sampled from the Kenney Prototype Kit's colormap.png. When the
     * path is null, the pre-Pass 5 procedural generator is used — kept so
     * a clone without the pack can still build the level.</p>
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
        final int floorTexture = builder.addTexture("refinery-floor", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, floorTexels));
        final int wallTexture = builder.addTexture("refinery-wall", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, wallTexels));

        builder.beginSubmesh(floorTexture);
        // Floor slab: 320x320, 4 units thick, centered on origin.
        addBox(builder, -HALF_EXTENT, -4.0f, -HALF_EXTENT, HALF_EXTENT, 0.0f, HALF_EXTENT);
        builder.endSubmesh();

        builder.beginSubmesh(wallTexture);
        addPerimeterWalls(builder);
        addTanks(builder);
        addProcessHall(builder);
        addBoilers(builder);
        addCatwalks(builder);
        addPipes(builder);
        addStairways(builder);
        addInternalWalls(builder);
        addProps(builder);
        builder.endSubmesh();

        return builder.toBytes();
    }

    /**
     * Returns how many triangles {@link #build} emits.
     *
     * <p>Stated as arithmetic: 1 floor (12) + 4 perimeter walls (48) +
     * 3 tanks (36) + 4 process hall walls (48) + 3 boilers (36) +
     * 2 catwalks (24) + 4 catwalk supports (48) + 4 stairways (48) +
     * 8 pipe segments (96) + 4 internal walls (48) + 6 props (72) =
     * 516.</p>
     *
     * @return the level's triangle count
     */
    public static int triangleCount()
    {
        return (1 + 4 + 3 + 4 + 3 + 2 + 4 + 4 + 8 + 4 + 6) * 12;
    }

    // ------------------------------------------------------------------
    // Geometry construction
    // ------------------------------------------------------------------

    private static void addPerimeterWalls(final ModelBuilder builder)
    {
        final float e = HALF_EXTENT;
        addBox(builder, -e, 0.0f, -e - WALL_THICKNESS, e, WALL_HEIGHT, -e);
        addBox(builder, -e, 0.0f, e, e, WALL_HEIGHT, e + WALL_THICKNESS);
        addBox(builder, -e - WALL_THICKNESS, 0.0f, -e, -e, WALL_HEIGHT, e);
        addBox(builder, e, 0.0f, -e, e + WALL_THICKNESS, WALL_HEIGHT, e);
    }

    /**
     * Three tall distillation tanks anchoring lane A. The tanks are at
     * the north end of the map (z=40), evenly spaced across the x axis.
     * Each is a tall narrow column visible from across the map.
     */
    private static void addTanks(final ModelBuilder builder)
    {
        // Tank positions: x = -100, 0, +100, z = 40
        for (int i = 0; i < 3; i++)
        {
            final float x = -100.0f + i * 100.0f;
            // The tank itself: 16 wide, TANK_HEIGHT tall, 16 deep
            addBox(builder, x - 8.0f, 0.0f, 32.0f, x + 8.0f, TANK_HEIGHT, 48.0f);
            // A small platform on top of the tank
            addBox(builder, x - 12.0f, TANK_HEIGHT, 28.0f, x + 12.0f,
                TANK_HEIGHT + 4.0f, 52.0f);
        }
    }

    /**
     * The process hall in lane B: a large open building with walls
     * around the perimeter and an open top. The hall runs from
     * z=110 to z=210, with walls on the four sides up to mid-level
     * (y=64). The top is open so a player on the catwalk can shoot
     * down into the hall.
     */
    private static void addProcessHall(final ModelBuilder builder)
    {
        final float inner = 110.0f;
        final float outer = 210.0f;
        final float sideIn = -130.0f;
        final float sideOut = 130.0f;
        // North wall (z=110) with one cut-through at x=0
        addBox(builder, sideIn, 0.0f, inner - WALL_THICKNESS, -8.0f,
            CATWALK_HEIGHT, inner);
        addBox(builder, 8.0f, 0.0f, inner - WALL_THICKNESS, sideOut,
            CATWALK_HEIGHT, inner);
        // South wall (z=210) with one cut-through at x=0
        addBox(builder, sideIn, 0.0f, outer, -8.0f, CATWALK_HEIGHT,
            outer + WALL_THICKNESS);
        addBox(builder, 8.0f, 0.0f, outer, sideOut, CATWALK_HEIGHT,
            outer + WALL_THICKNESS);
        // East wall (x=130) with one cut-through at z=160
        addBox(builder, sideOut, 0.0f, inner, sideOut + WALL_THICKNESS,
            CATWALK_HEIGHT, 152.0f);
        addBox(builder, sideOut, 0.0f, 168.0f, sideOut + WALL_THICKNESS,
            CATWALK_HEIGHT, outer);
        // West wall (x=-130) with one cut-through at z=160
        addBox(builder, sideIn - WALL_THICKNESS, 0.0f, inner, sideIn,
            CATWALK_HEIGHT, 152.0f);
        addBox(builder, sideIn - WALL_THICKNESS, 0.0f, 168.0f, sideIn,
            CATWALK_HEIGHT, outer);
    }

    /**
     * Three wide low boiler structures anchoring lane C. The boilers
     * are at the south end of the map (z=270), with two cut-throughs
     * between them at x=-50 and x=50.
     */
    private static void addBoilers(final ModelBuilder builder)
    {
        // Three boilers at x = -100, 0, +100, z = 270
        addBox(builder, -120.0f, 0.0f, 250.0f, -80.0f, BOILER_HEIGHT, 290.0f);
        addBox(builder, -20.0f, 0.0f, 250.0f, 20.0f, BOILER_HEIGHT, 290.0f);
        addBox(builder, 80.0f, 0.0f, 250.0f, 120.0f, BOILER_HEIGHT, 290.0f);
    }

    /**
     * The mid-level catwalks. Two segments: a long east-west catwalk
     * crossing the process hall (x=-130 to 130, z=160, y=64) and a
     * short north-south catwalk connecting the tanks to the process
     * hall (x=0, z=0 to 160, y=64). Four pillars support them.
     */
    private static void addCatwalks(final ModelBuilder builder)
    {
        // East-west catwalk across the process hall
        addBox(builder, -130.0f, CATWALK_HEIGHT, 156.0f, 130.0f,
            CATWALK_HEIGHT + 4.0f, 164.0f);
        // North-south catwalk from the tanks to the process hall
        addBox(builder, -4.0f, CATWALK_HEIGHT, 0.0f, 4.0f, CATWALK_HEIGHT + 4.0f, 160.0f);
        // Four pillars (one in each corner of the east-west catwalk)
        addBox(builder, -128.0f, 0.0f, 156.0f, -124.0f, CATWALK_HEIGHT, 164.0f);
        addBox(builder, 124.0f, 0.0f, 156.0f, 128.0f, CATWALK_HEIGHT, 164.0f);
    }

    /**
     * Internal pipework at mid-height in the process hall. Eight
     * segments running north-south, hanging just under the catwalk
     * (y=56, slightly below the catwalk's y=64 so they look like
     * pipes in the ceiling space). The pipes run from z=120 to z=200
     * at eight evenly-spaced x positions across the hall.
     */
    private static void addPipes(final ModelBuilder builder)
    {
        for (int i = 0; i < 8; i++)
        {
            final float x = -112.0f + i * 32.0f;
            addBox(builder, x - PIPE_RADIUS, 56.0f, 120.0f, x + PIPE_RADIUS, 62.0f, 200.0f);
        }
    }

    /**
     * Four stairways connecting the floor to the mid-level catwalks.
     * The stairways are at the four corners of the east-west catwalk:
     * two on the red-spawn side, two on the blue-spawn side.
     */
    private static void addStairways(final ModelBuilder builder)
    {
        // SW stairway
        addBox(builder, -100.0f, 0.0f, 100.0f, -90.0f, CATWALK_HEIGHT, 110.0f);
        // SE stairway
        addBox(builder, 90.0f, 0.0f, 100.0f, 100.0f, CATWALK_HEIGHT, 110.0f);
        // NW stairway
        addBox(builder, -100.0f, 0.0f, 200.0f, -90.0f, CATWALK_HEIGHT, 210.0f);
        // NE stairway
        addBox(builder, 90.0f, 0.0f, 200.0f, 100.0f, CATWALK_HEIGHT, 210.0f);
    }

    /**
     * Internal walls breaking up the process hall. Four short
     * segments placed to create cover and choke points inside the
     * hall. The walls are waist-height so a player on the catwalk
     * can still see over them.
     */
    private static void addInternalWalls(final ModelBuilder builder)
    {
        // West side: two L-shaped walls
        addBox(builder, -110.0f, 0.0f, 130.0f, -100.0f, 32.0f, 140.0f);
        addBox(builder, -110.0f, 0.0f, 180.0f, -100.0f, 32.0f, 190.0f);
        // East side: two L-shaped walls (mirror)
        addBox(builder, 100.0f, 0.0f, 130.0f, 110.0f, 32.0f, 140.0f);
        addBox(builder, 100.0f, 0.0f, 180.0f, 110.0f, 32.0f, 190.0f);
    }

    /**
     * Six small props scattered around the map: crates on the floor
     * and a small control room in the centre of the process hall.
     */
    private static void addProps(final ModelBuilder builder)
    {
        // Four crates in lane A and lane C, at the spawn areas
        addBox(builder, -130.0f, 0.0f, 50.0f, -120.0f, 32.0f, 60.0f);
        addBox(builder, 120.0f, 0.0f, 50.0f, 130.0f, 32.0f, 60.0f);
        addBox(builder, -130.0f, 0.0f, 250.0f, -120.0f, 32.0f, 260.0f);
        addBox(builder, 120.0f, 0.0f, 250.0f, 130.0f, 32.0f, 260.0f);
        // Control room: a small enclosed box in the centre of the hall
        addBox(builder, -16.0f, 0.0f, 144.0f, 16.0f, 48.0f, 176.0f);
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

    /** A worn concrete floor texture with a 4x4 grid pattern. */
    private static int[] floorTexels()
    {
        final int base = Rgba.pack(76, 80, 84, 255);
        final int shade = Rgba.pack(56, 60, 64, 255);
        final int line = Rgba.pack(124, 130, 138, 255);
        final int cell = TEXTURE_EDGE / 4;
        final int[] out = new int[TEXTURE_EDGE * TEXTURE_EDGE];
        for (int y = 0; y < TEXTURE_EDGE; y++)
        {
            for (int x = 0; x < TEXTURE_EDGE; x++)
            {
                int colour = base;
                final int cellX = x / cell;
                final int cellY = y / cell;
                if ((cellX + cellY) % 2 == 1)
                {
                    colour = shade;
                }
                if (x % cell == 0 || y % cell == 0)
                {
                    colour = line;
                }
                out[y * TEXTURE_EDGE + x] = colour;
            }
        }
        return out;
    }

    /** A rusted steel wall texture with horizontal banding and rust spots. */
    private static int[] wallTexels()
    {
        final int base = Rgba.pack(110, 92, 76, 255);
        final int shade = Rgba.pack(80, 66, 56, 255);
        final int rust = Rgba.pack(140, 72, 48, 255);
        final int bandHeight = TEXTURE_EDGE / 6;
        final int[] out = new int[TEXTURE_EDGE * TEXTURE_EDGE];
        for (int y = 0; y < TEXTURE_EDGE; y++)
        {
            final int band = y / bandHeight;
            final boolean isBandLine = (y % bandHeight) == 0;
            for (int x = 0; x < TEXTURE_EDGE; x++)
            {
                int colour = base;
                if (band % 2 != 0)
                {
                    colour = shade;
                }
                if (isBandLine)
                {
                    colour = shade;
                }
                // Rust spots: sparse dark patches that read as corrosion
                if ((x + (band * 7)) % 11 == 0 && (y * 3) % 13 == 0)
                {
                    colour = rust;
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
