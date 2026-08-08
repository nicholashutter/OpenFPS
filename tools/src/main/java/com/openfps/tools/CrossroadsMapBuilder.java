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
 * Builds the Crossroads map's level model: a 320x320 desert town at a
 * four-way crossroads. Three lanes, a central plaza, low sandstone
 * buildings, weathered wood structures, and sparse cover.
 *
 * <p>Build-time only — this class is never on a runtime classpath. The
 * output is committed to {@code engine/src/main/resources/maps/crossroads/}
 * via {@code git add -f}, the same exception the cornerstone and refinery
 * models use.</p>
 *
 * <h2>Geometry</h2>
 *
 * <p>The level is a flat desert town. The central plaza at the
 * four-way crossroads is a 64x64 open space; the four corners of the
 * plaza are the named chokepoints (Cafe Corner NW, Sheriff's NE,
 * Wells Fargo SW, Trading Post SE). North of the plaza is a row of
 * smaller buildings (the "shacks"), south is a row of larger
 * buildings (the "warehouses"). Sandstone colour palette; weathered
 * wood structures; some cacti and rocks for cover.</p>
 *
 * <h2>Textures — Kenney Prototype Kit (Pass 5)</h2>
 *
 * <p>Floor and wall tiles are sampled from the Kenney Prototype Kit's
 * colormap.png (CC0; {@code docs/ASSETS.md} § 3 records the provenance).
 * Pre-Pass 5 the builder produced two hand-authored procedural textures
 * (a sand-tone floor and a sandstone wall); the
 * {@code --atlas=<colormap.png>} flag swaps those for the kit's
 * neutral floor and wall swatches. Without the atlas the builder falls
 * back to the pre-Pass 5 procedural generator.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 *   --out=&lt;dir&gt;     where the .ofm file goes; required
 *   --atlas=&lt;path&gt;  the Kenney Prototype Kit colormap.png; optional
 * </pre>
 */
public final class CrossroadsMapBuilder
{
    /** Name of the produced model, written to {@code level.ofm}. */
    public static final String MODEL_NAME = "crossroads-level";

    /** File name of the produced model. */
    public static final String FILE_NAME = "level.ofm";

    /** Half the map's playable extent, in world units. The map is 320x320. */
    public static final float HALF_EXTENT = 160.0f;

    /** Wall thickness, in world units. */
    public static final float WALL_THICKNESS = 6.0f;

    /** Building wall height (low sandstone). */
    public static final float BUILDING_HEIGHT = 56.0f;

    /** Plaza wall height (lower, for visual cover around the open centre). */
    public static final float PLAZA_WALL_HEIGHT = 24.0f;

    /** Cactus height, in world units. */
    public static final float CACTUS_HEIGHT = 40.0f;

    /** Texture edge, in texels. Power of two for the sampler's wrap. */
    public static final int TEXTURE_EDGE = 64;

    /** World units spanned by one repeat of a texture. */
    public static final float WORLD_UNITS_PER_TILE = 8.0f;

    private static final Logger LOG = LoggerFactory.getLogger(CrossroadsMapBuilder.class);

    private CrossroadsMapBuilder()
    {
        // entry point holder
    }

    /**
     * Builds the Crossroads level model and writes it to the given directory.
     *
     * @param args {@code --out=<dir>} required, {@code --atlas=<path>} optional
     */
    public static void main(final String[] args)
    {
        final String out = option(args, "--out=");
        if (out == null)
        {
            LOG.error("usage: CrossroadsMapBuilder --out=<directory>"
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
     * Returns the {@code .ofm} bytes for the Crossroads level, using the
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
     * Returns the {@code .ofm} bytes for the Crossroads level.
     *
     * <p>One submesh for the sandy floor; one submesh for the walls
     * (buildings, plaza walls, cacti, rocks). Two textures: a sand
     * floor and a sandstone wall.</p>
     *
     * <p>When {@code atlasPath} is non-null, the floor and wall textures
     * are sampled from the Kenney Prototype Kit's colormap.png. When the
     * path is null, the pre-Pass 5 procedural generator is used.</p>
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
        final int floorTexture = builder.addTexture("crossroads-floor", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, floorTexels));
        final int wallTexture = builder.addTexture("crossroads-wall", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, wallTexels));

        builder.beginSubmesh(floorTexture);
        // Floor slab: 320x320, 4 units thick, centred on origin.
        addBox(builder, -HALF_EXTENT, -4.0f, -HALF_EXTENT, HALF_EXTENT, 0.0f, HALF_EXTENT);
        builder.endSubmesh();

        builder.beginSubmesh(wallTexture);
        addPerimeterWalls(builder);
        addNorthShacks(builder);
        addPlaza(builder);
        addSouthWarehouses(builder);
        addCacti(builder);
        addRocks(builder);
        addWellsAndPumps(builder);
        addWashChannels(builder);
        addProps(builder);
        builder.endSubmesh();

        return builder.toBytes();
    }

    /**
     * Returns how many triangles {@link #build} emits.
     *
     * <p>1 floor (12) + 4 perimeter walls (48) + 4 north shacks (48)
     * + 4 plaza walls (48) + 4 south warehouses (48) + 6 cacti (72) +
     * 4 rocks (48) + 2 wells (24) + 4 wash walls (48) + 4 props (48) =
     * 444.</p>
     *
     * @return the level's triangle count
     */
    public static int triangleCount()
    {
        return (1 + 4 + 4 + 4 + 4 + 6 + 4 + 2 + 4 + 4) * 12;
    }

    // ------------------------------------------------------------------
    // Geometry construction
    // ------------------------------------------------------------------

    private static void addPerimeterWalls(final ModelBuilder builder)
    {
        final float e = HALF_EXTENT;
        addBox(builder, -e, 0.0f, -e - WALL_THICKNESS, e, BUILDING_HEIGHT, -e);
        addBox(builder, -e, 0.0f, e, e, BUILDING_HEIGHT, e + WALL_THICKNESS);
        addBox(builder, -e - WALL_THICKNESS, 0.0f, -e, -e, BUILDING_HEIGHT, e);
        addBox(builder, e, 0.0f, -e, e + WALL_THICKNESS, BUILDING_HEIGHT, e);
    }

    /**
     * Four small "shack" buildings anchoring the north side of the
     * map (z=24). Each is a 32x32x32 low sandstone block with a
     * single cut-through. The shacks are north-side cover; the
     * empty space between them is the cut-through that connects
     * lane A to lane B.
     */
    private static void addNorthShacks(final ModelBuilder builder)
    {
        // Four shacks at x = -112, -32, 32, 112, z = 24
        for (int i = 0; i < 4; i++)
        {
            final float x = -112.0f + i * 80.0f;
            addBox(builder, x - 16.0f, 0.0f, 8.0f, x + 16.0f, BUILDING_HEIGHT, 40.0f);
        }
    }

    /**
     * The central plaza at the four-way crossroads (z=160, x=0).
     * Four low walls on the corners mark the named chokepoints; the
     * centre is open. The walls are PLAZA_WALL_HEIGHT (24) — waist
     * height — so a player can see over them and the plaza reads as
     * an open contested ground.
     */
    private static void addPlaza(final ModelBuilder builder)
    {
        // Four corner walls, leaving cut-throughs on the four sides
        // of the plaza. The cut-throughs are 24 units wide.
        final float plazaMin = -64.0f;
        final float plazaMax = 64.0f;
        final float wallY = PLAZA_WALL_HEIGHT;
        // NW corner: blocks (plazaMin, plazaMin) to (-40, 32)
        addBox(builder, plazaMin, 0.0f, plazaMin, plazaMin + 24.0f, wallY, plazaMin + 96.0f);
        // NE corner
        addBox(builder, plazaMax - 24.0f, 0.0f, plazaMin, plazaMax, wallY, plazaMin + 96.0f);
        // SW corner
        addBox(builder, plazaMin, 0.0f, plazaMax - 96.0f, plazaMin + 24.0f, wallY, plazaMax);
        // SE corner
        addBox(builder, plazaMax - 24.0f, 0.0f, plazaMax - 96.0f, plazaMax, wallY, plazaMax);
    }

    /**
     * Four larger "warehouse" buildings anchoring the south side
     * of the map (z=296). Each is a 48x48x64 sandstone block —
     * taller and wider than the shacks, marking the south
     * landmarks.
     */
    private static void addSouthWarehouses(final ModelBuilder builder)
    {
        // Four warehouses at x = -112, -32, 32, 112, z = 296
        for (int i = 0; i < 4; i++)
        {
            final float x = -112.0f + i * 80.0f;
            addBox(builder, x - 24.0f, 0.0f, 272.0f, x + 24.0f, BUILDING_HEIGHT + 16.0f, 320.0f);
        }
    }

    /**
     * Six tall thin "cactus" landmarks scattered around the map.
     * Each is a 4-wide, 40-tall column. The cacti are not solid
     * cover in any meaningful sense (a 4-unit-wide wall is a sliver
     * to a 32-unit-diameter player) but they break sight lines and
     * read as a desert.
     */
    private static void addCacti(final ModelBuilder builder)
    {
        addCactusAt(builder, -100.0f, 80.0f);
        addCactusAt(builder, -40.0f, 200.0f);
        addCactusAt(builder, 40.0f, 80.0f);
        addCactusAt(builder, 100.0f, 200.0f);
        addCactusAt(builder, -100.0f, 240.0f);
        addCactusAt(builder, 100.0f, 240.0f);
    }

    private static void addCactusAt(final ModelBuilder builder, final float x, final float z)
    {
        addBox(builder, x - 2.0f, 0.0f, z - 2.0f, x + 2.0f, CACTUS_HEIGHT, z + 2.0f);
    }

    /**
     * Four "rocks" (low, wide) scattered around the plaza for cover.
     */
    private static void addRocks(final ModelBuilder builder)
    {
        addBox(builder, -84.0f, 0.0f, 80.0f, -68.0f, 16.0f, 96.0f);
        addBox(builder, 68.0f, 0.0f, 80.0f, 84.0f, 16.0f, 96.0f);
        addBox(builder, -84.0f, 0.0f, 224.0f, -68.0f, 16.0f, 240.0f);
        addBox(builder, 68.0f, 0.0f, 224.0f, 84.0f, 16.0f, 240.0f);
    }

    /**
     * Two wooden wells in the plaza, marking the centre.
     */
    private static void addWellsAndPumps(final ModelBuilder builder)
    {
        addBox(builder, -20.0f, 0.0f, 140.0f, -12.0f, 24.0f, 148.0f);
        addBox(builder, 12.0f, 0.0f, 172.0f, 20.0f, 24.0f, 180.0f);
    }

    /**
     * Four low "wash channel" walls — long east-west thin walls at
     * varying z, simulating dry riverbeds. They break the long
     * cross-map sight lines that the desert would otherwise have.
     */
    private static void addWashChannels(final ModelBuilder builder)
    {
        // Two channels in the north half, two in the south half
        addBox(builder, -HALF_EXTENT + 6.0f, 0.0f, 70.0f, HALF_EXTENT - 6.0f, 8.0f, 76.0f);
        addBox(builder, -HALF_EXTENT + 6.0f, 0.0f, 250.0f, HALF_EXTENT - 6.0f, 8.0f, 256.0f);
        // Two short north-south walls at the edges
        addBox(builder, -140.0f, 0.0f, 100.0f, -134.0f, 16.0f, 220.0f);
        addBox(builder, 134.0f, 0.0f, 100.0f, 140.0f, 16.0f, 220.0f);
    }

    /**
     * Four small props: wooden crates near the shacks and the
     * warehouses, providing initial safety at the spawns.
     */
    private static void addProps(final ModelBuilder builder)
    {
        addBox(builder, -120.0f, 0.0f, 60.0f, -112.0f, 24.0f, 68.0f);
        addBox(builder, 112.0f, 0.0f, 60.0f, 120.0f, 24.0f, 68.0f);
        addBox(builder, -120.0f, 0.0f, 260.0f, -112.0f, 24.0f, 268.0f);
        addBox(builder, 112.0f, 0.0f, 260.0f, 120.0f, 24.0f, 268.0f);
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

    /** A sandy floor texture with subtle dune ripples. */
    private static int[] floorTexels()
    {
        final int base = Rgba.pack(218, 198, 158, 255);
        final int shade = Rgba.pack(196, 174, 130, 255);
        final int line = Rgba.pack(170, 144, 96, 255);
        final int[] out = new int[TEXTURE_EDGE * TEXTURE_EDGE];
        for (int y = 0; y < TEXTURE_EDGE; y++)
        {
            for (int x = 0; x < TEXTURE_EDGE; x++)
            {
                int colour = base;
                // Horizontal "dune" bands every 16 texels, alternating
                if ((y / 8) % 2 == 0)
                {
                    colour = shade;
                }
                // Sparse darker streaks (paths/footprints)
                if (x % 16 == 0)
                {
                    colour = line;
                }
                out[y * TEXTURE_EDGE + x] = colour;
            }
        }
        return out;
    }

    /** A sandstone wall texture with horizontal block lines. */
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
                else if ((x + (band * 4)) % 16 == 0)
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
