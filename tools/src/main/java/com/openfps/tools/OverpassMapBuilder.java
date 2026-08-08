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
 * Builds the Overpass map's level model: a 320x320 highway interchange at
 * street level. Two parallel elevated overpasses running east-west, a
 * service road in the middle (low ground), and a control building
 * anchoring the south.
 *
 * <p>Build-time only — this class is never on a runtime classpath. The
 * output is committed to {@code engine/src/main/resources/maps/overpass/}
 * via {@code git add -f}, the same exception the cornerstone model uses.</p>
 *
 * <h2>Geometry</h2>
 *
 * <p>Two elevated decks (Overpass N at z=40, Overpass S at z=240) span
 * the full x range. Each deck is 16 wide, sitting at y=64 (the bridge
 * deck height). A service road runs along the centre of the map (z=120
 * to z=200, y=0). The control building is a 96x64 box at the south end
 * (x=128, z=296). Two east-west ramps connect the overpasses to the
 * service road at the far west and far east edges. A stairway at x=192
 * connects the south overpass to the control building.</p>
 *
 * <h2>Textures — Kenney Prototype Kit</h2>
 *
 * <p>Floor and wall tiles are sampled from the Kenney Prototype Kit's
 * colormap.png (CC0; {@code docs/ASSETS.md} § 3 records the provenance).
 * The pack has no "asphalt" or "guardrail" tile, so the road surface
 * uses the kit's neutral wall colour and the overpass underdeck uses
 * the column (deep blue) colour, with a procedural red trim for
 * accent geometry. Without the atlas the build falls back to the
 * pre-Pass 2 procedural generator.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 *   --out=&lt;dir&gt;     where the .ofm file goes; required
 *   --atlas=&lt;path&gt;  the Kenney Prototype Kit colormap.png; optional
 * </pre>
 */
public final class OverpassMapBuilder
{
    /** Name of the produced model, written to {@code level.ofm}. */
    public static final String MODEL_NAME = "overpass-level";

    /** File name of the produced model. */
    public static final String FILE_NAME = "level.ofm";

    /** Half the map's playable extent, in world units. The map is 320x320. */
    public static final float HALF_EXTENT = 160.0f;

    /** Wall thickness for perimeter and ramp walls, in world units. */
    public static final float WALL_THICKNESS = 6.0f;

    /** Perimeter wall height, in world units. */
    public static final float WALL_HEIGHT = 96.0f;

    /** The overpass deck's height above the service road, in world units. */
    public static final float OVERPASS_DECK_HEIGHT = 64.0f;

    /** The overpass deck's thickness (top of deck to bottom of deck), in world units. */
    public static final float OVERPASS_DECK_THICKNESS = 8.0f;

    /** Width of each overpass deck, in world units (along the z axis). */
    public static final float OVERPASS_DECK_WIDTH = 16.0f;

    /** Height of the control building, in world units. */
    public static final float CONTROL_BUILDING_HEIGHT = 80.0f;

    /** Texture edge, in texels. Power of two for the sampler's wrap. */
    public static final int TEXTURE_EDGE = 64;

    /** World units spanned by one repeat of a texture. */
    public static final float WORLD_UNITS_PER_TILE = 8.0f;

    private static final Logger LOG = LoggerFactory.getLogger(OverpassMapBuilder.class);

    private OverpassMapBuilder()
    {
        // entry point holder
    }

    /**
     * Builds the Overpass level model and writes it to the given directory.
     *
     * @param args {@code --out=<dir>} required, {@code --atlas=<path>} optional
     */
    public static void main(final String[] args)
    {
        final String out = option(args, "--out=");
        if (out == null)
        {
            LOG.error("usage: OverpassMapBuilder --out=<directory>"
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
     * Returns the {@code .ofm} bytes for the Overpass level using the
     * procedural fallback (no Kenney atlas).
     *
     * @return the .ofm file image
     */
    public static byte[] build()
    {
        return build(null);
    }

    /**
     * Returns the {@code .ofm} bytes for the Overpass level.
     *
     * <p>Three submeshes (floor, walls, accents) with three textures.
     * The geometry is a flat ground slab, two elevated overpass decks
     * with underdeck supports, two east-west ramps connecting the
     * decks to the ground, a control building, a stairway from the
     * south overpass to the control building, and four perimeter
     * walls (low — half-wall so the elevated overpasses read as
     * bridges). Concrete barriers line the service road for cover.</p>
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
        final int floorTexture = builder.addTexture("overpass-floor", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, floorTexels));
        final int wallTexture = builder.addTexture("overpass-wall", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, wallTexels));
        final int accentTexture = builder.addTexture("overpass-accent", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, accentTexels));

        builder.beginSubmesh(floorTexture);
        addGroundSlab(builder);
        addServiceRoad(builder);
        addOverpassDecks(builder);
        builder.endSubmesh();

        builder.beginSubmesh(wallTexture);
        addPerimeterWalls(builder);
        addUnderdeckSupports(builder);
        addRampWalls(builder);
        addControlBuilding(builder);
        addStairway(builder);
        addConcreteBarriers(builder);
        builder.endSubmesh();

        builder.beginSubmesh(accentTexture);
        addAccentGeometry(builder);
        builder.endSubmesh();

        return builder.toBytes();
    }

    /**
     * Returns the {@code .ofm} triangle count.
     *
     * <p>Stated as arithmetic: 1 ground slab (12) + 1 service road (12)
     * + 2 overpass decks (24) + 4 perimeter walls (48) + 8 underdeck
     * pillars (96) + 4 ramp walls (48) + 1 control building (12) + 1
     * stairway (12) + 6 concrete barriers (72) + 4 signposts (48) =
     * 384.</p>
     *
     * @return the level's triangle count
     */
    public static int triangleCount()
    {
        return (1 + 1 + 2 + 4 + 8 + 4 + 1 + 1 + 6 + 4) * 12;
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
     * The service road. A slightly raised (y=2) darker strip from
     * z=120 to z=200 spanning the full x range. Reads as the road
     * surface between the two overpasses; the height difference
     * (2 units) is too small to affect movement but gives a visible
     * edge in the rendered frame.
     */
    private static void addServiceRoad(final ModelBuilder builder)
    {
        addBox(builder, -HALF_EXTENT, 0.0f, 120.0f, HALF_EXTENT, 2.0f, 200.0f);
    }

    /**
     * The two overpass decks. Each deck is a 16-wide slab at the
     * overpass deck height (y=64), running the full x range. The deck
     * has thickness 8, so its top is at y=72 and its bottom at y=64.
     * A player on the deck stands on the top face (y=72) — slightly
     * above the deck's centre, but the difference is invisible to
     * the player model and keeps the deck's underdeck visible.
     */
    private static void addOverpassDecks(final ModelBuilder builder)
    {
        // North overpass: deck centred at z=40, width 16
        addBox(builder, -HALF_EXTENT, OVERPASS_DECK_HEIGHT, 40.0f - OVERPASS_DECK_WIDTH / 2.0f,
            HALF_EXTENT, OVERPASS_DECK_HEIGHT + OVERPASS_DECK_THICKNESS,
            40.0f + OVERPASS_DECK_WIDTH / 2.0f);
        // South overpass: deck centred at z=240, width 16
        addBox(builder, -HALF_EXTENT, OVERPASS_DECK_HEIGHT, 240.0f - OVERPASS_DECK_WIDTH / 2.0f,
            HALF_EXTENT, OVERPASS_DECK_HEIGHT + OVERPASS_DECK_THICKNESS,
            240.0f + OVERPASS_DECK_WIDTH / 2.0f);
    }

    /**
     * The four low perimeter walls. Half-height (48 units) so the
     * overpasses still read as elevated when a player looks from
     * the service road. The four walls meet at the corners.
     */
    private static void addPerimeterWalls(final ModelBuilder builder)
    {
        final float halfWallHeight = 48.0f;
        final float e = HALF_EXTENT;
        // North wall (z = -e)
        addBox(builder, -e, 0.0f, -e - WALL_THICKNESS, e, halfWallHeight, -e);
        // South wall (z = e) — but the control building sits on it, so
        // we leave a gap at x=128..224.
        addBox(builder, -e, 0.0f, e, -128.0f - WALL_THICKNESS / 2.0f, halfWallHeight,
            e + WALL_THICKNESS);
        addBox(builder, 224.0f + WALL_THICKNESS / 2.0f, 0.0f, e, e, halfWallHeight,
            e + WALL_THICKNESS);
        // West wall (x = -e)
        addBox(builder, -e - WALL_THICKNESS, 0.0f, -e, -e, halfWallHeight, e);
        // East wall (x = e)
        addBox(builder, e, 0.0f, -e, e + WALL_THICKNESS, halfWallHeight, e);
    }

    /**
     * Eight underdeck support pillars: one at each end of each
     * overpass deck. The pillars run from the ground to the bottom
     * of the deck (y=0 to y=64) and are 8 wide. They are
     * intentionally narrow so a player on the service road can see
     * past them.
     */
    private static void addUnderdeckSupports(final ModelBuilder builder)
    {
        final float[][] positions = {
            // North overpass
            {-140.0f, 40.0f}, {-44.0f, 40.0f}, {44.0f, 40.0f}, {140.0f, 40.0f},
            // South overpass
            {-140.0f, 240.0f}, {-44.0f, 240.0f}, {44.0f, 240.0f}, {140.0f, 240.0f}
        };
        for (final float[] pos : positions)
        {
            final float x = pos[0];
            final float z = pos[1];
            addBox(builder, x - 4.0f, 0.0f, z - 4.0f, x + 4.0f, OVERPASS_DECK_HEIGHT,
                z + 4.0f);
        }
    }

    /**
     * The two east-west ramp walls. Each ramp is a triangular
     * slope approximated by two boxes: one at the overpass end
     * (high) and one at the service road end (low). The two ramps
     * sit at x=16 and x=304, between the north and south overpasses.
     */
    private static void addRampWalls(final ModelBuilder builder)
    {
        // West ramp: two boxes stepping down from y=64 to y=0,
        // placed at x=16. The ramp surface is approximated by the
        // top faces of the two boxes.
        addBox(builder, 16.0f, 32.0f, 120.0f, 24.0f, 64.0f, 200.0f);
        addBox(builder, 16.0f, 0.0f, 120.0f, 24.0f, 32.0f, 200.0f);
        // East ramp: mirror at x=304..296
        addBox(builder, 296.0f, 32.0f, 120.0f, 304.0f, 64.0f, 200.0f);
        addBox(builder, 296.0f, 0.0f, 120.0f, 304.0f, 32.0f, 200.0f);
    }

    /**
     * The control building. A 96x64 box at the south end of the
     * map (x=128..224, z=296..320, y=0..80). The third hardpoint
     * zone is inside it.
     */
    private static void addControlBuilding(final ModelBuilder builder)
    {
        addBox(builder, 128.0f, 0.0f, 296.0f, 224.0f, CONTROL_BUILDING_HEIGHT, 320.0f);
    }

    /**
     * The stairway from the south overpass to the control building.
     * Two boxes stepping up from y=0 to y=64, placed at x=192 and
     * z=232..256.
     */
    private static void addStairway(final ModelBuilder builder)
    {
        addBox(builder, 184.0f, 0.0f, 232.0f, 200.0f, 32.0f, 240.0f);
        addBox(builder, 184.0f, 32.0f, 240.0f, 200.0f, 64.0f, 248.0f);
    }

    /**
     * Six concrete barriers on the service road. Each is a small
     * (16 wide, 24 tall, 8 deep) box placed in the middle of the
     * service road at x=-100, 0, +100, providing cover for players
     * crossing the low ground.
     */
    private static void addConcreteBarriers(final ModelBuilder builder)
    {
        addBox(builder, -108.0f, 0.0f, 152.0f, -92.0f, 24.0f, 168.0f);
        addBox(builder, -8.0f, 0.0f, 152.0f, 8.0f, 24.0f, 168.0f);
        addBox(builder, 92.0f, 0.0f, 152.0f, 108.0f, 24.0f, 168.0f);
        addBox(builder, -108.0f, 0.0f, 168.0f, -92.0f, 24.0f, 184.0f);
        addBox(builder, -8.0f, 0.0f, 168.0f, 8.0f, 24.0f, 184.0f);
        addBox(builder, 92.0f, 0.0f, 168.0f, 108.0f, 24.0f, 184.0f);
    }

    /**
     * The accent geometry. Four signposts at the overpass
     * transitions — small vertical pillars (4 wide, 32 tall) that
     * read as guard-rail posts.
     */
    private static void addAccentGeometry(final ModelBuilder builder)
    {
        // West signposts
        addBox(builder, 12.0f, 0.0f, 156.0f, 16.0f, 32.0f, 160.0f);
        addBox(builder, 12.0f, 0.0f, 200.0f, 16.0f, 32.0f, 204.0f);
        // East signposts
        addBox(builder, 304.0f, 0.0f, 156.0f, 308.0f, 32.0f, 160.0f);
        addBox(builder, 304.0f, 0.0f, 200.0f, 308.0f, 32.0f, 204.0f);
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
        final int colour = Rgba.pack(192, 64, 48, 255);
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
