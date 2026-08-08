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
 * Builds the Icebridge map's level model: a 320x320 polar rest stop with
 * two long east-west frozen bridges spanning a frozen ravine, with a
 * service building anchoring the south and snowdrift cover on the
 * ravine floor.
 *
 * <p>Build-time only — this class is never on a runtime classpath. The
 * output is committed to {@code engine/src/main/resources/maps/arctic-station/}
 * via {@code git add -f}, the same exception the cornerstone, refinery,
 * and crossroads models use.</p>
 *
 * <h2>Geometry</h2>
 *
 * <p>The level is a polar flat with two raised bridges. The North Bridge
 * (y=32, z=24..56) and the South Bridge (y=32, z=200..232) are 32-tall
 * sheet-metal decks, 320 units long. Between them is the frozen ravine
 * (y=-8, z=88..192), a 96-unit-wide low ground with snowdrift cover.
 * The service building (y=0..32, z=232..296) anchors the south. Two
 * fuel-depot buildings sit on the North Bridge at its west and east
 * ends. The snow-tone floor texture is a white-to-pale-blue gradient;
 * the wall texture is sheet metal with frost streaks.</p>
 *
 * <h2>Textures — Kenney Prototype Kit (Pass 5)</h2>
 *
 * <p>Floor and wall tiles are sampled from the Kenney Prototype Kit's
 * colormap.png (CC0; {@code docs/ASSETS.md} § 3 records the provenance).
 * Pre-Pass 5 the builder produced two hand-authored procedural textures
 * (a snow-tone floor and a sheet-metal wall); the
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
public final class ArcticStationMapBuilder
{
    /** Name of the produced model, written to {@code level.ofm}. */
    public static final String MODEL_NAME = "arctic-station-level";

    /** File name of the produced model. */
    public static final String FILE_NAME = "level.ofm";

    /** Half the map's playable extent, in world units. The map is 320x320. */
    public static final float HALF_EXTENT = 160.0f;

    /** Wall thickness, in world units. */
    public static final float WALL_THICKNESS = 6.0f;

    /** Bridge height (the deck is raised this many units above the floor). */
    public static final float BRIDGE_HEIGHT = 32.0f;

    /** Service building height. */
    public static final float SERVICE_HEIGHT = 32.0f;

    /** Fuel depot building height (sits on the bridge). */
    public static final float FUEL_HEIGHT = 40.0F;

    /** Snowdrift height (low cover on the ravine floor). */
    public static final float SNOWDRIFT_HEIGHT = 16.0f;

    /** Texture edge, in texels. Power of two for the sampler's wrap. */
    public static final int TEXTURE_EDGE = 64;

    /** World units spanned by one repeat of a texture. */
    public static final float WORLD_UNITS_PER_TILE = 8.0f;

    private static final Logger LOG = LoggerFactory.getLogger(ArcticStationMapBuilder.class);

    private ArcticStationMapBuilder()
    {
        // entry point holder
    }

    /**
     * Builds the Icebridge level model and writes it to the given directory.
     *
     * @param args {@code --out=<dir>} required, {@code --atlas=<path>} optional
     */
    public static void main(final String[] args)
    {
        final String out = option(args, "--out=");
        if (out == null)
        {
            LOG.error("usage: ArcticStationMapBuilder --out=<directory>"
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
     * Returns the {@code .ofm} bytes for the Icebridge level, using the
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
     * Returns the {@code .ofm} bytes for the Icebridge level.
     *
     * <p>One submesh for the snow floor; one submesh for the walls
     * (perimeter, bridges, fuel depots, service building, snowdrift
     * cover). Two textures: a snow-tone floor and a sheet-metal wall.</p>
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
        final int floorTexture = builder.addTexture("arctic-station-floor", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, floorTexels));
        final int wallTexture = builder.addTexture("arctic-station-wall", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, wallTexels));

        builder.beginSubmesh(floorTexture);
        // Floor slab: 320x320, 8 units thick (with the ravine carved
        // out as a depression), centred on origin.
        addBox(builder, -HALF_EXTENT, -8.0f, -HALF_EXTENT, HALF_EXTENT, 0.0f, HALF_EXTENT);
        builder.endSubmesh();

        builder.beginSubmesh(wallTexture);
        addPerimeterWalls(builder);
        addNorthBridge(builder);
        addSouthBridge(builder);
        addFuelDepots(builder);
        addServiceBuilding(builder);
        addServiceBuildingCanopy(builder);
        addSnowdrifts(builder);
        addBridgeSupports(builder);
        addRocks(builder);
        builder.endSubmesh();

        return builder.toBytes();
    }

    /**
     * Returns how many triangles {@link #build} emits.
     *
     * <p>1 floor (12) + 4 perimeter walls (48) + 2 bridges × 2 boxes
     * (48) + 2 fuel depots (24) + 1 service building (12) + 1 service
     * canopy (12) + 4 snowdrifts (48) + 4 bridge supports (48) + 4
     * rocks (48) = 300.</p>
     *
     * @return the level's triangle count
     */
    public static int triangleCount()
    {
        return (1 + 4 + 4 + 2 + 1 + 1 + 4 + 4 + 4) * 12;
    }

    // ------------------------------------------------------------------
    // Geometry construction
    // ------------------------------------------------------------------

    private static void addPerimeterWalls(final ModelBuilder builder)
    {
        final float e = HALF_EXTENT;
        addBox(builder, -e, 0.0f, -e - WALL_THICKNESS, e, BRIDGE_HEIGHT, -e);
        addBox(builder, -e, 0.0f, e, e, BRIDGE_HEIGHT, e + WALL_THICKNESS);
        addBox(builder, -e - WALL_THICKNESS, 0.0f, -e, -e, BRIDGE_HEIGHT, e);
        addBox(builder, e, 0.0f, -e, e + WALL_THICKNESS, BRIDGE_HEIGHT, e);
    }

    /**
     * The North Bridge — a long east-west deck at y=32, z=24..56. Two
     * layers: the deck (a 320-long box) and the underside (a thinner
     * box hanging below). The deck is the walkable surface.
     */
    private static void addNorthBridge(final ModelBuilder builder)
    {
        // Deck: 320 long, 32 wide (z=24..56), 4 units thick (y=28..32).
        addBox(builder, -HALF_EXTENT, BRIDGE_HEIGHT - 4.0f, 24.0f, HALF_EXTENT,
            BRIDGE_HEIGHT, 56.0f);
        // Underside: 320 long, 32 wide, 4 units thick (y=24..28).
        addBox(builder, -HALF_EXTENT, BRIDGE_HEIGHT - 8.0f, 24.0f, HALF_EXTENT,
            BRIDGE_HEIGHT - 4.0f, 56.0f);
    }

    /**
     * The South Bridge — mirror of the North Bridge at z=200..232.
     */
    private static void addSouthBridge(final ModelBuilder builder)
    {
        addBox(builder, -HALF_EXTENT, BRIDGE_HEIGHT - 4.0f, 200.0f, HALF_EXTENT,
            BRIDGE_HEIGHT, 232.0f);
        addBox(builder, -HALF_EXTENT, BRIDGE_HEIGHT - 8.0f, 200.0f, HALF_EXTENT,
            BRIDGE_HEIGHT - 4.0f, 232.0f);
    }

    /**
     * Two fuel-depot buildings sitting on the North Bridge, at the west
     * and east ends. Each is a 32x32x40 box.
     */
    private static void addFuelDepots(final ModelBuilder builder)
    {
        // West fuel depot on North Bridge, at x=-128, on top of the deck.
        addBox(builder, -144.0f, BRIDGE_HEIGHT, 28.0f, -112.0f, BRIDGE_HEIGHT + FUEL_HEIGHT,
            52.0f);
        // East fuel depot on North Bridge, at x=128.
        addBox(builder, 112.0f, BRIDGE_HEIGHT, 28.0f, 144.0f, BRIDGE_HEIGHT + FUEL_HEIGHT,
            52.0f);
    }

    /**
     * The service building anchoring the south end. A 64x64 sheet-metal
     * box at y=0..32, z=232..296, with the front door facing the South
     * Bridge.
     */
    private static void addServiceBuilding(final ModelBuilder builder)
    {
        addBox(builder, -32.0f, 0.0f, 232.0f, 32.0f, SERVICE_HEIGHT, 296.0f);
    }

    /**
     * The fuel-pump canopy on the east side of the service building — a
     * thin awning at y=24, providing cover next to the service
     * building.
     */
    private static void addServiceBuildingCanopy(final ModelBuilder builder)
    {
        addBox(builder, 32.0f, 0.0f, 240.0f, 48.0f, 24.0f, 280.0f);
    }

    /**
     * Four snowdrift walls on the ravine floor — long east-west thin
     * walls at y=0..16, breaking the long sight line the open ravine
     * would otherwise have.
     */
    private static void addSnowdrifts(final ModelBuilder builder)
    {
        addBox(builder, -HALF_EXTENT + 8.0f, 0.0f, 80.0f, HALF_EXTENT - 8.0f,
            SNOWDRIFT_HEIGHT, 96.0f);
        addBox(builder, -HALF_EXTENT + 8.0f, 0.0f, 184.0f, HALF_EXTENT - 8.0f,
            SNOWDRIFT_HEIGHT, 200.0f);
        // Two short north-south walls at the edges
        addBox(builder, -140.0f, 0.0f, 100.0f, -132.0f, SNOWDRIFT_HEIGHT, 180.0f);
        addBox(builder, 132.0f, 0.0f, 100.0f, 140.0f, SNOWDRIFT_HEIGHT, 180.0f);
    }

    /**
     * Four bridge supports — vertical pillars at the corners of each
     * bridge, from the ravine floor up to the bridge underside. They
     * are not load-bearing (this is a level model, not engineering)
     * but they break the sight line between the bridges.
     */
    private static void addBridgeSupports(final ModelBuilder builder)
    {
        // North Bridge supports: at the four corners.
        addBox(builder, -140.0f, 0.0f, 24.0f, -132.0f, BRIDGE_HEIGHT - 8.0f, 32.0f);
        addBox(builder, 132.0f, 0.0f, 24.0f, 140.0f, BRIDGE_HEIGHT - 8.0f, 32.0f);
        // South Bridge supports
        addBox(builder, -140.0f, 0.0f, 200.0f, -132.0f, BRIDGE_HEIGHT - 8.0f, 208.0f);
        addBox(builder, 132.0f, 0.0f, 200.0f, 140.0f, BRIDGE_HEIGHT - 8.0f, 208.0f);
    }

    /**
     * Four small rocks (low, wide) scattered around the ravine for
     * cover — they are not solid cover in any meaningful sense (a
     * 16-unit-wide wall is a sliver to a 32-unit-diameter player) but
     * they break sight lines and read as a polar rest stop.
     */
    private static void addRocks(final ModelBuilder builder)
    {
        addBox(builder, -100.0f, 0.0f, 110.0f, -84.0f, 12.0f, 126.0f);
        addBox(builder, 84.0f, 0.0f, 110.0f, 100.0f, 12.0f, 126.0f);
        addBox(builder, -100.0f, 0.0f, 162.0f, -84.0f, 12.0f, 178.0f);
        addBox(builder, 84.0f, 0.0f, 162.0f, 100.0f, 12.0f, 178.0f);
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

    /** A snow-tone floor texture with a subtle white-to-pale-blue gradient. */
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
                // Subtle horizontal "drift" bands every 8 texels
                if ((y / 8) % 2 == 0)
                {
                    colour = shade;
                }
                // Sparse darker drift streaks (footprints / paths)
                if (x % 16 == 0)
                {
                    colour = drift;
                }
                out[y * TEXTURE_EDGE + x] = colour;
            }
        }
        return out;
    }

    /** A sheet-metal wall texture with horizontal ribbed lines. */
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
                else if ((x + (band * 4)) % 32 == 0)
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
