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
 * Builds the Foundry map's level model: a 320x320 heavy-machinery foundry
 * with three large machine halls (cast-metal shop, assembly floor, cooling
 * room) and a system of mid-level gantries that connect them.
 *
 * <p>Build-time only — this class is never on a runtime classpath. The
 * output is committed to {@code engine/src/main/resources/maps/foundry/}
 * via {@code git add -f}, the same exception the overpass model uses.</p>
 *
 * <h2>Geometry</h2>
 *
 * <p>The level is a 320x320 industrial hall. Three large machine halls sit
 * along the central spine (x=160, z=40/160/270). The two horizontal
 * gantries (the casting-gantry at y=64, z=80 and the foundry spine at
 * z=160) plus a vertical gantry at x=0 connect the three halls at
 * mid-height. The catwalk ring at y=64 lets a player who has climbed a
 * stairway rotate between halls without coming down.</p>
 *
 * <h2>Textures — Kenney Prototype Kit</h2>
 *
 * <p>Floor and wall tiles are sampled from the Kenney Prototype Kit's
 * colormap.png (CC0; {@code docs/ASSETS.md} § 3 records the provenance).
 * The kit's neutral floor and wall swatches match the foundry palette.
 * Without the atlas the builder falls back to a procedural generator
 * (kept for clone-without-pack testing).</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 *   --out=&lt;dir&gt;     where the .ofm file goes; required
 *   --atlas=&lt;path&gt;  the Kenney Prototype Kit colormap.png; optional
 * </pre>
 */
public final class FoundryMapBuilder
{
    /** Name of the produced model, written to {@code level.ofm}. */
    public static final String MODEL_NAME = "foundry-level";

    /** File name of the produced model. */
    public static final String FILE_NAME = "level.ofm";

    /** Half the map's playable extent, in world units. The map is 320x320. */
    public static final float HALF_EXTENT = 160.0f;

    /** Wall thickness, in world units. */
    public static final float WALL_THICKNESS = 6.0f;

    /** Hall wall height (low-ceiling, matches the spec). */
    public static final float HALL_HEIGHT = 64.0f;

    /** Gantry height (the mid-level ring). */
    public static final float GANTRY_HEIGHT = 64.0f;

    /** Gantry deck thickness. */
    public static final float GANTRY_THICKNESS = 4.0f;

    /** Perimeter wall height, in world units. */
    public static final float PERIMETER_WALL_HEIGHT = 96.0f;

    /** Texture edge, in texels. Power of two for the sampler's wrap. */
    public static final int TEXTURE_EDGE = 64;

    /** World units spanned by one repeat of a texture. */
    public static final float WORLD_UNITS_PER_TILE = 8.0f;

    private static final Logger LOG = LoggerFactory.getLogger(FoundryMapBuilder.class);

    private FoundryMapBuilder()
    {
        // entry point holder
    }

    /**
     * Builds the Foundry level model and writes it to the given directory.
     *
     * @param args {@code --out=<dir>} required, {@code --atlas=<path>} optional
     */
    public static void main(final String[] args)
    {
        final String out = option(args, "--out=");

        if (out == null)
        {
            LOG.error("usage: FoundryMapBuilder --out=<directory>"
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
     * Returns the {@code .ofm} bytes for the Foundry level, using the
     * procedural fallback (no Kenney atlas).
     *
     * @return the .ofm file image
     */
    public static byte[] build()
    {
        return build(null);
    }

    /**
     * Returns the {@code .ofm} bytes for the Foundry level.
     *
     * <p>Three submeshes (floor, walls, accent) with three textures. The
     * geometry is a flat floor, four perimeter walls, three machine
     * halls (cast-metal shop at z=270, assembly floor at z=160, cooling
     * room at z=40), a vertical gantry at x=0 connecting the halls at
     * mid-height, two horizontal gantries (the casting-gantry at z=80
     * and the foundry spine at z=160) at y=64, four corner stairways,
     * and a row of crates at the cast-metal shop and the cooling room
     * for cover.</p>
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

        final int floorTexture = builder.addTexture("foundry-floor", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, floorTexels));

        final int wallTexture = builder.addTexture("foundry-wall", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, wallTexels));

        final int accentTexture = builder.addTexture("foundry-accent", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, accentTexels));

        builder.beginSubmesh(floorTexture);

        addGroundSlab(builder);

        addHallFloors(builder);

        builder.endSubmesh();

        builder.beginSubmesh(wallTexture);

        addPerimeterWalls(builder);

        addHalls(builder);

        addGantries(builder);

        addStairways(builder);

        addCrates(builder);

        addPillars(builder);

        builder.endSubmesh();

        builder.beginSubmesh(accentTexture);

        addAccentGeometry(builder);

        builder.endSubmesh();

        return builder.toBytes();
    }

    /**
     * Returns the {@code .ofm} triangle count.
     *
     * <p>Stated as arithmetic: 1 ground slab (12) + 3 hall floors (36)
     * + 4 perimeter walls (48) + 3 halls (each 5 wall pieces — the
     * south face is split into two around the doorway — total 15 wall
     * pieces = 180) + 3 gantries (3 decks = 36) + 4 stairways (48) +
     * 8 crates (96) + 4 pillars (48) + 4 signposts (48) = 552.</p>
     *
     * @return the level's triangle count
     */
    public static int triangleCount()
    {
        return (1 + 3 + 4 + 15 + 3 + 4 + 8 + 4 + 4) * 12;
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
     * The three hall floors. Each is a slightly raised (y=2) slab
     * inside its hall, signalling the change of surface from the
     * surrounding concrete to the hall's patterned floor. The 2-unit
     * height is too small to affect movement but gives a visible edge
     * in the rendered frame.
     */
    private static void addHallFloors(final ModelBuilder builder)
    {
        // Cast-metal shop floor (HP_A), at z=270
        addBox(builder, -64.0f, 0.0f, 240.0f, 64.0f, 2.0f, 304.0f);

        // Assembly floor (HP_B), at z=160
        addBox(builder, -64.0f, 0.0f, 128.0f, 64.0f, 2.0f, 192.0f);

        // Cooling room floor (HP_C), at z=40
        addBox(builder, -64.0f, 0.0f, 8.0f, 64.0f, 2.0f, 72.0f);
    }

    /**
     * The four perimeter walls. Half-height (96 units, matching the
     * spec's perimeter-wall rule) so the elevated gantries still read
     * as gantries when a player looks from the floor.
     */
    private static void addPerimeterWalls(final ModelBuilder builder)
    {
        final float e = HALF_EXTENT;

        // North wall (z = -e)
        addBox(builder, -e, 0.0f, -e - WALL_THICKNESS, e, PERIMETER_WALL_HEIGHT, -e);

        // South wall (z = e)
        addBox(builder, -e, 0.0f, e, e, PERIMETER_WALL_HEIGHT, e + WALL_THICKNESS);

        // West wall (x = -e)
        addBox(builder, -e - WALL_THICKNESS, 0.0f, -e, -e, PERIMETER_WALL_HEIGHT, e);

        // East wall (x = e)
        addBox(builder, e, 0.0f, -e, e + WALL_THICKNESS, PERIMETER_WALL_HEIGHT, e);
    }

    /**
     * The three machine halls. Each hall is 128 wide (x=-64..64) and
     * 64 deep, with walls on all four sides up to mid-level (y=64).
     * The two exits (one on the south face for the cooling room and
     * the cast-metal shop, one on the east face for the assembly
     * floor) are 32 units wide.
     */
    private static void addHalls(final ModelBuilder builder)
    {
        // Cast-metal shop (HP_A) at z=270, with a south exit at z=304
        addHallWalls(builder, 240.0f, 304.0f);

        // Assembly floor (HP_B) at z=160, with two side exits
        addHallWalls(builder, 128.0f, 192.0f);

        // Cooling room (HP_C) at z=40, with a north exit at z=8
        addHallWalls(builder, 8.0f, 72.0f);
    }

    /**
     * Builds the four walls of one hall, with a 32-unit gap on the
     * +z face to give the spec's "two exits leading to the
     * cooling-gantry" or "two exits leading to the casting-gantry"
     * layout. The two side walls are unbroken.
     *
     * @param minZ the z of the south face of the hall
     * @param maxZ the z of the north face of the hall
     */
    private static void addHallWalls(final ModelBuilder builder, final float minZ, final float maxZ)
    {
        final float hallY = HALL_HEIGHT;

        // South wall (the +z face), with a 32-unit gap at x=0
        addBox(builder, -64.0f, 0.0f, maxZ, -16.0f, hallY, maxZ + WALL_THICKNESS);

        addBox(builder, 16.0f, 0.0f, maxZ, 64.0f, hallY, maxZ + WALL_THICKNESS);

        // North wall (the -z face), full width
        addBox(builder, -64.0f, 0.0f, minZ - WALL_THICKNESS, 64.0f, hallY, minZ);

        // East wall (the +x face), full depth
        addBox(builder, 64.0f, 0.0f, minZ, 64.0f + WALL_THICKNESS, hallY, maxZ);

        // West wall (the -x face), full depth
        addBox(builder, -64.0f - WALL_THICKNESS, 0.0f, minZ, -64.0f, hallY, maxZ);
    }

    /**
     * The two horizontal gantries and one vertical gantry. The two
     * horizontal gantries (the casting-gantry at z=80 and the foundry
     * spine at z=160) are 8 wide, sitting at y=64 (the deck is at
     * y=68, so a player stands at y=68). The vertical gantry at x=0
     * is 8 wide, running from the cooling room (z=40) to the
     * cast-metal shop (z=270).
     */
    private static void addGantries(final ModelBuilder builder)
    {
        // Casting-gantry at z=80, deck y=64..68
        addBox(builder, -HALF_EXTENT, GANTRY_HEIGHT, 76.0f, HALF_EXTENT,
            GANTRY_HEIGHT + GANTRY_THICKNESS, 84.0f);

        // Foundry spine at z=160, deck y=64..68
        addBox(builder, -HALF_EXTENT, GANTRY_HEIGHT, 156.0f, HALF_EXTENT,
            GANTRY_HEIGHT + GANTRY_THICKNESS, 164.0f);

        // Vertical cooling-gantry at x=0, running from z=8 to z=304
        addBox(builder, -4.0f, GANTRY_HEIGHT, 8.0f, 4.0f,
            GANTRY_HEIGHT + GANTRY_THICKNESS, 304.0f);
    }

    /**
     * Four corner stairways connecting the floor to the gantries. The
     * stairways are at the four corners of the foundry spine: NW
     * (x=-100, z=120), NE (x=100, z=120), SW (x=-100, z=200), SE
     * (x=100, z=200). Each is 10x64x10.
     */
    private static void addStairways(final ModelBuilder builder)
    {
        // SW stairway
        addBox(builder, -100.0f, 0.0f, 200.0f, -90.0f, GANTRY_HEIGHT, 210.0f);

        // SE stairway
        addBox(builder, 90.0f, 0.0f, 200.0f, 100.0f, GANTRY_HEIGHT, 210.0f);

        // NW stairway
        addBox(builder, -100.0f, 0.0f, 120.0f, -90.0f, GANTRY_HEIGHT, 130.0f);

        // NE stairway
        addBox(builder, 90.0f, 0.0f, 120.0f, 100.0f, GANTRY_HEIGHT, 130.0f);
    }

    /**
     * Eight crates scattered around the halls for cover. The crates
     * are 16 wide x 32 tall x 16 deep, providing cover in the centre
     * of each hall.
     */
    private static void addCrates(final ModelBuilder builder)
    {
        // Cast-metal shop (HP_A): two crates at the north and south ends
        addBox(builder, -48.0f, 0.0f, 248.0f, -32.0f, 32.0f, 264.0f);

        addBox(builder, 32.0f, 0.0f, 280.0f, 48.0f, 32.0f, 296.0f);

        // Assembly floor (HP_B): two crates flanking the centre
        addBox(builder, -48.0f, 0.0f, 136.0f, -32.0f, 32.0f, 152.0f);

        addBox(builder, 32.0f, 0.0f, 168.0f, 48.0f, 32.0f, 184.0f);

        // Cooling room (HP_C): two crates at the north and south ends
        addBox(builder, -48.0f, 0.0f, 16.0f, -32.0f, 32.0f, 32.0f);

        addBox(builder, 32.0f, 0.0f, 48.0f, 48.0f, 32.0f, 64.0f);

        // Two extra crates in the floor between halls
        addBox(builder, -16.0f, 0.0f, 200.0f, 0.0f, 32.0f, 216.0f);

        addBox(builder, 0.0f, 0.0f, 104.0f, 16.0f, 32.0f, 120.0f);
    }

    /**
     * Four pillars supporting the foundry spine at its mid-point. The
     * pillars run from the floor to the underside of the gantry deck
     * (y=0..64) and are 8 wide, evenly spaced.
     */
    private static void addPillars(final ModelBuilder builder)
    {
        // Two pairs flanking the cooling-gantry vertical (x=0)
        addBox(builder, -28.0f, 0.0f, 156.0f, -20.0f, GANTRY_HEIGHT, 164.0f);

        addBox(builder, 20.0f, 0.0f, 156.0f, 28.0f, GANTRY_HEIGHT, 164.0f);

        addBox(builder, -28.0f, 0.0f, 76.0f, -20.0f, GANTRY_HEIGHT, 84.0f);

        addBox(builder, 20.0f, 0.0f, 76.0f, 28.0f, GANTRY_HEIGHT, 84.0f);
    }

    /**
     * Four signposts at the gantry transitions — small vertical pillars
     * (4 wide, 32 tall) that read as guard-rail posts. Two on the
     * casting-gantry and two on the foundry spine.
     */
    private static void addAccentGeometry(final ModelBuilder builder)
    {
        // Casting-gantry signposts
        addBox(builder, -150.0f, GANTRY_HEIGHT, 76.0f, -146.0f, GANTRY_HEIGHT + 32.0f, 84.0f);

        addBox(builder, 146.0f, GANTRY_HEIGHT, 76.0f, 150.0f, GANTRY_HEIGHT + 32.0f, 84.0f);

        // Foundry spine signposts
        addBox(builder, -150.0f, GANTRY_HEIGHT, 156.0f, -146.0f, GANTRY_HEIGHT + 32.0f,
            164.0f);

        addBox(builder, 146.0f, GANTRY_HEIGHT, 156.0f, 150.0f, GANTRY_HEIGHT + 32.0f,
            164.0f);
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
     * Adds one face to the open submesh, with UVs scaled to the
     * texture tile size.
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
        final int base = Rgba.pack(72, 76, 80, 255);

        final int shade = Rgba.pack(52, 56, 60, 255);

        final int line = Rgba.pack(120, 124, 132, 255);

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

    private static int[] wallTexels()
    {
        final int base = Rgba.pack(104, 100, 96, 255);

        final int shade = Rgba.pack(76, 72, 68, 255);

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
        final int colour = Rgba.pack(200, 88, 56, 255);

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
