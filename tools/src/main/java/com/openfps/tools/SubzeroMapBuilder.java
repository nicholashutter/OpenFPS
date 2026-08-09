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
 * Builds the Subzero map's level model: a 320x320 polar research outpost
 * with three low sheet-metal buildings (the Generator Shed, the Operations
 * Trailer, and the Fuel Depot) at the corners of a 96x96 triangle, with
 * a system of snow-walled trenches connecting them.
 *
 * <p>Build-time only — this class is never on a runtime classpath. The
 * output is committed to {@code engine/src/main/resources/maps/arctic-hp/}
 * via {@code git add -f}, the same exception the overpass model uses.</p>
 *
 * <h2>Geometry</h2>
 *
 * <p>The level is a polar flat with three small sheet-metal buildings:
 * the Generator Shed at (64, 0, 64), the Operations Trailer at (160, 0,
 * 160), and the Fuel Depot at (64, 0, 256). Each building is 32 wide x
 * 32 deep x 24 tall, with a single 16-unit doorway on the side facing
 * the centre of the triangle. Two snow-walled trenches (the W trench
 * from z=64..192 along x=0..32, the E trench from z=128..256 along
 * x=0..32) connect the buildings at floor level. The trenches are 8
 * wide, with 8-tall snow walls on both sides.</p>
 *
 * <h2>Textures — Kenney Prototype Kit</h2>
 *
 * <p>Floor and wall tiles are sampled from the Kenney Prototype Kit's
 * colormap.png (CC0). The pack's neutral floor and wall swatches match
 * the polar palette. Without the atlas the builder falls back to a
 * procedural snow-tone generator (kept for clone-without-pack testing).</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 *   --out=&lt;dir&gt;     where the .ofm file goes; required
 *   --atlas=&lt;path&gt;  the Kenney Prototype Kit colormap.png; optional
 * </pre>
 */
public final class SubzeroMapBuilder
{
    /** Name of the produced model, written to {@code level.ofm}. */
    public static final String MODEL_NAME = "subzero-level";

    /** File name of the produced model. */
    public static final String FILE_NAME = "level.ofm";

    /** Half the map's playable extent, in world units. The map is 320x320. */
    public static final float HALF_EXTENT = 160.0f;

    /** Wall thickness, in world units. */
    public static final float WALL_THICKNESS = 6.0f;

    /** Building wall height (low sheet metal, matches the spec). */
    public static final float BUILDING_HEIGHT = 24.0f;

    /** Snow wall height (the trench walls). */
    public static final float SNOW_WALL_HEIGHT = 8.0f;

    /** Perimeter wall height, in world units. */
    public static final float PERIMETER_WALL_HEIGHT = 32.0f;

    /** Texture edge, in texels. Power of two for the sampler's wrap. */
    public static final int TEXTURE_EDGE = 64;

    /** World units spanned by one repeat of a texture. */
    public static final float WORLD_UNITS_PER_TILE = 8.0f;

    private static final Logger LOG = LoggerFactory.getLogger(SubzeroMapBuilder.class);

    private SubzeroMapBuilder()
    {
        // entry point holder
    }

    /**
     * Builds the Subzero level model and writes it to the given directory.
     *
     * @param args {@code --out=<dir>} required, {@code --atlas=<path>} optional
     */
    public static void main(final String[] args)
    {
        final String out = option(args, "--out=");

        if (out == null)
        {
            LOG.error("usage: SubzeroMapBuilder --out=<directory>"
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
     * Returns the {@code .ofm} bytes for the Subzero level, using the
     * procedural fallback (no Kenney atlas).
     *
     * @return the .ofm file image
     */
    public static byte[] build()
    {
        return build(null);
    }

    /**
     * Returns the {@code .ofm} bytes for the Subzero level.
     *
     * <p>Three submeshes (floor, walls, accent) with three textures. The
     * geometry is a flat snow floor, four perimeter walls, three small
     * sheet-metal buildings (each 32x32x24 with a single 16-unit
     * doorway on the side facing the centre), four snow-walled trench
     * segments (two W-trench walls and two E-trench walls), and a
     * small radar mast in the centre of the triangle (the accent
     * submesh).</p>
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

        final int floorTexture = builder.addTexture("subzero-floor", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, floorTexels));

        final int wallTexture = builder.addTexture("subzero-wall", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, wallTexels));

        final int accentTexture = builder.addTexture("subzero-accent", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, accentTexels));

        builder.beginSubmesh(floorTexture);

        addGroundSlab(builder);

        builder.endSubmesh();

        builder.beginSubmesh(wallTexture);

        addPerimeterWalls(builder);

        addGeneratorShed(builder);

        addOperationsTrailer(builder);

        addFuelDepot(builder);

        addWTrenchWalls(builder);

        addETrenchWalls(builder);

        builder.endSubmesh();

        builder.beginSubmesh(accentTexture);

        addAccentGeometry(builder);

        builder.endSubmesh();

        return builder.toBytes();
    }

    /**
     * Returns the {@code .ofm} triangle count.
     *
     * <p>Stated as arithmetic: 1 ground slab (12) + 4 perimeter walls
     * (48) + 3 buildings (each 4 walls with one gap = 12 walls = 144) +
     * 4 snow walls (48) = 252.</p>
     *
     * @return the level's triangle count
     */
    public static int triangleCount()
    {
        return (1 + 4 + 12 + 4) * 12;
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
     * The four perimeter walls. Short (32 units) so the open ground at
     * the centre of the triangle is the contested ground, not the
     * perimeter. The walls meet at the corners.
     */
    private static void addPerimeterWalls(final ModelBuilder builder)
    {
        final float e = HALF_EXTENT;

        addBox(builder, -e, 0.0f, -e - WALL_THICKNESS, e, PERIMETER_WALL_HEIGHT, -e);

        addBox(builder, -e, 0.0f, e, e, PERIMETER_WALL_HEIGHT, e + WALL_THICKNESS);

        addBox(builder, -e - WALL_THICKNESS, 0.0f, -e, -e, PERIMETER_WALL_HEIGHT, e);

        addBox(builder, e, 0.0f, -e, e + WALL_THICKNESS, PERIMETER_WALL_HEIGHT, e);
    }

    /**
     * The Generator Shed at (64, 0, 64). A 32x32x24 sheet-metal box
     * with a single 16-unit doorway on the south face (the side facing
     * the centre of the triangle).
     */
    private static void addGeneratorShed(final ModelBuilder builder)
    {
        addBuilding(builder, 48.0f, 48.0f, 80.0f, 80.0f, "south");
    }

    /**
     * The Operations Trailer at (160, 0, 160). A 32x32x24 sheet-metal
     * box with a single 16-unit doorway on the west face.
     */
    private static void addOperationsTrailer(final ModelBuilder builder)
    {
        addBuilding(builder, 144.0f, 144.0f, 176.0f, 176.0f, "west");
    }

    /**
     * The Fuel Depot at (64, 0, 256). A 32x32x24 sheet-metal box with
     * a single 16-unit doorway on the north face (the side facing the
     * centre of the triangle).
     */
    private static void addFuelDepot(final ModelBuilder builder)
    {
        addBuilding(builder, 48.0f, 240.0f, 80.0f, 272.0f, "north");
    }

    /**
     * Builds the four walls of one building, with a 16-unit gap on
     * the named face for the doorway.
     *
     * @param minX the building's west x
     * @param minZ the building's north z
     * @param maxX the building's east x
     * @param maxZ the building's south z
     * @param doorFace the face with the doorway: {@code "north"},
     *     {@code "south"}, {@code "east"} or {@code "west"}
     */
    private static void addBuilding(final ModelBuilder builder, final float minX, final float minZ,
        final float maxX, final float maxZ, final String doorFace)
    {
        final float yMax = BUILDING_HEIGHT;

        // South wall (the +z face) — doorway centred on x
        if ("south".equals(doorFace))
        {
            final float doorMin = (minX + maxX) / 2.0f - 8.0f;

            final float doorMax = (minX + maxX) / 2.0f + 8.0f;

            addBox(builder, minX, 0.0f, maxZ, doorMin, yMax, maxZ + WALL_THICKNESS);

            addBox(builder, doorMax, 0.0f, maxZ, maxX, yMax, maxZ + WALL_THICKNESS);
        }
        else
        {
            addBox(builder, minX, 0.0f, maxZ, maxX, yMax, maxZ + WALL_THICKNESS);
        }

        // North wall (the -z face) — doorway centred on x
        if ("north".equals(doorFace))
        {
            final float doorMin = (minX + maxX) / 2.0f - 8.0f;

            final float doorMax = (minX + maxX) / 2.0f + 8.0f;

            addBox(builder, minX, 0.0f, minZ - WALL_THICKNESS, doorMin, yMax, minZ);

            addBox(builder, doorMax, 0.0f, minZ - WALL_THICKNESS, maxX, yMax, minZ);
        }
        else
        {
            addBox(builder, minX, 0.0f, minZ - WALL_THICKNESS, maxX, yMax, minZ);
        }

        // East wall (the +x face) — doorway centred on z
        if ("east".equals(doorFace))
        {
            final float doorMin = (minZ + maxZ) / 2.0f - 8.0f;

            final float doorMax = (minZ + maxZ) / 2.0f + 8.0f;

            addBox(builder, maxX, 0.0f, minZ, maxX + WALL_THICKNESS, yMax, doorMin);

            addBox(builder, maxX, 0.0f, doorMax, maxX + WALL_THICKNESS, yMax, maxZ);
        }
        else
        {
            addBox(builder, maxX, 0.0f, minZ, maxX + WALL_THICKNESS, yMax, maxZ);
        }

        // West wall (the -x face) — doorway centred on z
        if ("west".equals(doorFace))
        {
            final float doorMin = (minZ + maxZ) / 2.0f - 8.0f;

            final float doorMax = (minZ + maxZ) / 2.0f + 8.0f;

            addBox(builder, minX - WALL_THICKNESS, 0.0f, minZ, minX, yMax, doorMin);

            addBox(builder, minX - WALL_THICKNESS, 0.0f, doorMax, minX, yMax, maxZ);
        }
        else
        {
            addBox(builder, minX - WALL_THICKNESS, 0.0f, minZ, minX, yMax, maxZ);
        }
    }

    /**
     * The W trench walls: two parallel snow walls running north-south
     * from the Generator Shed (z=64) to the Operations Trailer (z=192),
     * forming a 8-wide trench. The walls sit at x=0 and x=8, with the
     * trench floor between them.
     */
    private static void addWTrenchWalls(final ModelBuilder builder)
    {
        // West wall (x=0..4, z=64..192) — the outside wall
        addBox(builder, 0.0f, 0.0f, 64.0f, 4.0f, SNOW_WALL_HEIGHT, 192.0f);

        // East wall (x=4..8, z=64..192) — the inside wall
        addBox(builder, 4.0f, 0.0f, 64.0f, 8.0f, SNOW_WALL_HEIGHT, 192.0f);
    }

    /**
     * The E trench walls: two parallel snow walls running north-south
     * from the Operations Trailer (z=128) to the Fuel Depot (z=256).
     * The walls sit at x=0 and x=8, with the trench floor between
     * them.
     */
    private static void addETrenchWalls(final ModelBuilder builder)
    {
        // West wall (x=0..4, z=128..256)
        addBox(builder, 0.0f, 0.0f, 128.0f, 4.0f, SNOW_WALL_HEIGHT, 256.0f);

        // East wall (x=4..8, z=128..256)
        addBox(builder, 4.0f, 0.0f, 128.0f, 8.0f, SNOW_WALL_HEIGHT, 256.0f);
    }

    /**
     * The radar mast in the centre of the triangle — a 4-wide, 32-tall
     * column with a small dish on top. The mast sits at (96, 0, 160),
     * the centre of the triangle of buildings.
     */
    private static void addAccentGeometry(final ModelBuilder builder)
    {
        // Mast column
        addBox(builder, 94.0f, 0.0f, 158.0f, 98.0f, 32.0f, 162.0f);

        // Dish
        addBox(builder, 86.0f, 24.0f, 156.0f, 106.0f, 32.0f, 164.0f);
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
