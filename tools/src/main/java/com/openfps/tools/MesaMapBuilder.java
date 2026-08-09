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
 * Builds the Mesa map's level model: a 320x320 desert plateau with a
 * single easy ramp on the south face and a switchback stair on the
 * north face. The mesa top is the contested ground; the two mesa-top
 * HP zones (HP_B and HP_C) sit on it. The third zone (HP_A) is a
 * canyon-floor cave to the south, the round's opening zone.
 *
 * <p>Build-time only — this class is never on a runtime classpath. The
 * output is committed to {@code engine/src/main/resources/maps/mesa/}
 * via {@code git add -f}, the same exception the overpass model uses.</p>
 *
 * <h2>Geometry</h2>
 *
 * <p>The level is a 320x320 desert flat with a raised mesa top (y=32)
 * covering the centre (x=80..240, z=64..192) and a cave (a low-roofed
 * sandstone chamber) at the south end (z=240..304). The mesa rim is
 * a 4-tall sandstone lip at the edge of the top. The mesa has two
 * rim gaps at x=160 on the east and west sides. The south ramp runs
 * from the desert floor at (32, 0, 256) to the mesa top at (32, 32,
 * 192) at 16% grade; the north switchback climbs the same vertical
 * from (32, 0, 96) to (32, 32, 96) in eight zig-zag treads.</p>
 *
 * <h2>Textures — Kenney Prototype Kit</h2>
 *
 * <p>Floor and wall tiles are sampled from the Kenney Prototype Kit's
 * colormap.png (CC0). The pack has no "sand" or "sandstone" tile, so
 * the look matches the prototype-kit's neutral floor and wall — the
 * Kenney grey fits a generic desert plateau palette well enough that
 * the missing swatch is not worth a custom image. Without the atlas
 * the builder falls back to procedural textures.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 *   --out=&lt;dir&gt;     where the .ofm file goes; required
 *   --atlas=&lt;path&gt;  the Kenney Prototype Kit colormap.png; optional
 * </pre>
 */
public final class MesaMapBuilder
{
    /** Name of the produced model, written to {@code level.ofm}. */
    public static final String MODEL_NAME = "mesa-level";

    /** File name of the produced model. */
    public static final String FILE_NAME = "level.ofm";

    /** Half the map's playable extent, in world units. The map is 320x320. */
    public static final float HALF_EXTENT = 160.0f;

    /** Wall thickness, in world units. */
    public static final float WALL_THICKNESS = 6.0f;

    /** Mesa top height above the surrounding desert floor. */
    public static final float MESA_HEIGHT = 32.0f;

    /** Cave wall height (low-roofed, matches the spec). */
    public static final float CAVE_HEIGHT = 24.0f;

    /** Perimeter wall height, in world units. */
    public static final float PERIMETER_WALL_HEIGHT = 56.0f;

    /** Mesa rim height (the lip on top of the mesa). */
    public static final float RIM_HEIGHT = 4.0f;

    /** Texture edge, in texels. Power of two for the sampler's wrap. */
    public static final int TEXTURE_EDGE = 64;

    /** World units spanned by one repeat of a texture. */
    public static final float WORLD_UNITS_PER_TILE = 8.0f;

    private static final Logger LOG = LoggerFactory.getLogger(MesaMapBuilder.class);

    private MesaMapBuilder()
    {
        // entry point holder
    }

    /**
     * Builds the Mesa level model and writes it to the given directory.
     *
     * @param args {@code --out=<dir>} required, {@code --atlas=<path>} optional
     */
    public static void main(final String[] args)
    {
        final String out = option(args, "--out=");

        if (out == null)
        {
            LOG.error("usage: MesaMapBuilder --out=<directory>"
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
     * Returns the {@code .ofm} bytes for the Mesa level, using the
     * procedural fallback (no Kenney atlas).
     *
     * @return the .ofm file image
     */
    public static byte[] build()
    {
        return build(null);
    }

    /**
     * Returns the {@code .ofm} bytes for the Mesa level.
     *
     * <p>Three submeshes (floor, walls, accent) with three textures. The
     * geometry is a flat desert floor, a raised mesa top covering the
     * centre of the map, a low-roofed cave at the south end, the mesa
     * rim with two rim gaps, the south ramp, the north switchback
     * (eight zig-zag treads), four perimeter walls, two cactus pairs,
     * two wash channels, and four corner rock formations.</p>
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

        final int floorTexture = builder.addTexture("mesa-floor", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, floorTexels));

        final int wallTexture = builder.addTexture("mesa-wall", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, wallTexels));

        final int accentTexture = builder.addTexture("mesa-accent", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, accentTexels));

        builder.beginSubmesh(floorTexture);

        addGroundSlab(builder);

        addMesaTop(builder);

        addCaveFloor(builder);

        builder.endSubmesh();

        builder.beginSubmesh(wallTexture);

        addPerimeterWalls(builder);

        addMesaSides(builder);

        addCave(builder);

        addSouthRamp(builder);

        addNorthSwitchback(builder);

        addMesaRim(builder);

        addCacti(builder);

        addRocks(builder);

        addWashChannels(builder);

        builder.endSubmesh();

        builder.beginSubmesh(accentTexture);

        addAccentGeometry(builder);

        builder.endSubmesh();

        return builder.toBytes();
    }

    /**
     * Returns the {@code .ofm} triangle count.
     *
     * <p>Stated as arithmetic: 1 ground slab (12) + 1 mesa top (12) +
     * 1 cave floor (12) + 4 perimeter walls (48) + 4 mesa sides (48)
     * + 4 cave walls (48) + 1 south ramp (8 boxes, 96) + 1 north
     * switchback (8 boxes, 96) + 4 mesa rim (48) + 4 cacti (48) +
     * 4 rocks (48) + 4 wash walls (48) = 564.</p>
     *
     * @return the level's triangle count
     */
    public static int triangleCount()
    {
        return (1 + 1 + 1 + 4 + 4 + 4 + 8 + 8 + 4 + 4 + 4 + 4) * 12;
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
     * The mesa top: a raised 160x128 slab at y=32..36 covering the
     * centre of the map (x=80..240, z=64..192). Two rim gaps at
     * x=160 (east and west) leave the mesa top flush with the floor
     * for an 8-unit-wide corridor; the gaps are modelled by leaving
     * the gaps empty (no fill) rather than subtracting from the slab.
     */
    private static void addMesaTop(final ModelBuilder builder)
    {
        addBox(builder, 80.0f, MESA_HEIGHT - 4.0f, 64.0f, 240.0f, MESA_HEIGHT, 192.0f);
    }

    /**
     * The cave floor: a slightly raised (y=2) slab inside the cave,
     * signalling the change of surface from the surrounding desert
     * floor to the cave's patterned floor. The 2-unit height is too
     * small to affect movement but gives a visible edge.
     */
    private static void addCaveFloor(final ModelBuilder builder)
    {
        addBox(builder, 96.0f, 0.0f, 248.0f, 224.0f, 2.0f, 304.0f);
    }

    /**
     * The four perimeter walls. Short (56 units) so the open desert
     * reads as a desert, not a fort. The walls meet at the corners.
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
     * The four mesa sides: a 4-unit-thick sandstone slope from the
     * desert floor to the mesa top. The east, west and north sides
     * are full; the south side is broken by the south ramp and the
     * rim gap.
     */
    private static void addMesaSides(final ModelBuilder builder)
    {
        // North side: full
        addBox(builder, 80.0f, 0.0f, 60.0f, 240.0f, MESA_HEIGHT, 64.0f);

        // South side: split by the south ramp (x=24..40) and the
        // rim gap (x=156..164). Two pieces: west (x=80..152) and
        // east (x=168..240).
        addBox(builder, 80.0f, 0.0f, 192.0f, 152.0f, MESA_HEIGHT, 196.0f);

        addBox(builder, 168.0f, 0.0f, 192.0f, 240.0f, MESA_HEIGHT, 196.0f);

        // East side: full
        addBox(builder, 240.0f, 0.0f, 64.0f, 244.0f, MESA_HEIGHT, 192.0f);

        // West side: split by the rim gap (x=156..164)
        addBox(builder, 76.0f, 0.0f, 64.0f, 80.0f, MESA_HEIGHT, 192.0f);
    }

    /**
     * The cave: a low-roofed sandstone chamber at the south end. Four
     * walls (north, south, east, west) up to y=24, with a single
     * south-facing opening on the +z face (the cave mouth).
     */
    private static void addCave(final ModelBuilder builder)
    {
        // North wall
        addBox(builder, 96.0f, 0.0f, 244.0f, 224.0f, CAVE_HEIGHT, 248.0f);

        // South wall (the +z face), with a 64-unit opening at x=128..192
        addBox(builder, 96.0f, 0.0f, 304.0f, 128.0f, CAVE_HEIGHT, 308.0f);

        addBox(builder, 192.0f, 0.0f, 304.0f, 224.0f, CAVE_HEIGHT, 308.0f);

        // East wall
        addBox(builder, 224.0f, 0.0f, 248.0f, 228.0f, CAVE_HEIGHT, 304.0f);

        // West wall
        addBox(builder, 92.0f, 0.0f, 248.0f, 96.0f, CAVE_HEIGHT, 304.0f);
    }

    /**
     * The south ramp: a 16% grade from the desert floor at (32, 0,
     * 256) to the mesa top at (32, 32, 192). Modelled as 8 boxes
     * stepping up from y=0 to y=32, each 4 units tall and 8 wide.
     * The ramp surface is the top face of each box.
     */
    private static void addSouthRamp(final ModelBuilder builder)
    {
        for (int i = 0; i < 8; i++)
        {
            final float yMin = i * 4.0f;

            final float yMax = (i + 1) * 4.0f;

            final float zMin = 256.0f - i * 8.0f;

            final float zMax = zMin + 8.0f;

            addBox(builder, 24.0f, yMin, zMin, 40.0f, yMax, zMax);
        }
    }

    /**
     * The north switchback: eight zig-zag treads from the desert floor
     * at (32, 0, 96) to the mesa top at (32, 32, 96), alternating
     * between x=-16 and x=+16. Each tread is 4 wide and 4 tall, with
     * 8-unit landings at the top and bottom.
     */
    private static void addNorthSwitchback(final ModelBuilder builder)
    {
        // 4 ascending treads alternating between x=-16 and x=+16
        addBox(builder, -20.0f, 0.0f, 92.0f, -12.0f, 8.0f, 100.0f);

        addBox(builder, 12.0f, 8.0f, 92.0f, 20.0f, 16.0f, 100.0f);

        addBox(builder, -20.0f, 16.0f, 92.0f, -12.0f, 24.0f, 100.0f);

        addBox(builder, 12.0f, 24.0f, 92.0f, 20.0f, 32.0f, 100.0f);

        // 4 descending treads, mirrored, climbing back up to the rim
        addBox(builder, 12.0f, 0.0f, 60.0f, 20.0f, 8.0f, 68.0f);

        addBox(builder, -20.0f, 8.0f, 60.0f, -12.0f, 16.0f, 68.0f);

        addBox(builder, 12.0f, 16.0f, 60.0f, 20.0f, 24.0f, 68.0f);

        addBox(builder, -20.0f, 24.0f, 60.0f, -12.0f, 32.0f, 68.0f);
    }

    /**
     * The mesa rim: a 4-tall sandstone lip at the edge of the mesa
     * top, sitting on top of the mesa sides. The rim runs around the
     * full mesa perimeter except for the two rim gaps (8-unit-wide
     * openings at x=160 on the east and west sides) and the south
     * ramp landing.
     */
    private static void addMesaRim(final ModelBuilder builder)
    {
        final float yRim = MESA_HEIGHT;

        final float yRimTop = yRim + RIM_HEIGHT;

        // North rim: full
        addBox(builder, 80.0f, yRim, 60.0f, 240.0f, yRimTop, 64.0f);

        // South rim: split by the south ramp landing (x=24..40) and
        // the rim gap (x=156..164)
        addBox(builder, 40.0f, yRim, 192.0f, 152.0f, yRimTop, 196.0f);

        addBox(builder, 168.0f, yRim, 192.0f, 240.0f, yRimTop, 196.0f);

        // East rim: split by the rim gap (x=156..164)
        addBox(builder, 240.0f, yRim, 64.0f, 244.0f, yRimTop, 152.0f);

        addBox(builder, 240.0f, yRim, 168.0f, 244.0f, yRimTop, 192.0f);

        // West rim: split by the rim gap (x=156..164)
        addBox(builder, 76.0f, yRim, 64.0f, 80.0f, yRimTop, 152.0f);

        addBox(builder, 76.0f, yRim, 168.0f, 80.0f, yRimTop, 192.0f);
    }

    /**
     * Four cacti on the canyon floor: tall thin (4-wide, 40-tall)
     * sandstone columns at z=120 and z=200, two per side.
     */
    private static void addCacti(final ModelBuilder builder)
    {
        addCactusAt(builder, -100.0f, 120.0f);

        addCactusAt(builder, 100.0f, 120.0f);

        addCactusAt(builder, -100.0f, 200.0f);

        addCactusAt(builder, 100.0f, 200.0f);
    }

    private static void addCactusAt(final ModelBuilder builder, final float x, final float z)
    {
        addBox(builder, x - 2.0f, 0.0f, z - 2.0f, x + 2.0f, 40.0f, z + 2.0f);
    }

    /**
     * Four corner rocks (low, wide) scattered around the desert floor
     * for cover. The rocks are 16 wide x 12 tall x 16 deep.
     */
    private static void addRocks(final ModelBuilder builder)
    {
        addBox(builder, -100.0f, 0.0f, 16.0f, -84.0f, 12.0f, 32.0f);

        addBox(builder, 84.0f, 0.0f, 16.0f, 100.0f, 12.0f, 32.0f);

        addBox(builder, -100.0f, 0.0f, 232.0f, -84.0f, 12.0f, 248.0f);

        addBox(builder, 84.0f, 0.0f, 232.0f, 100.0f, 12.0f, 248.0f);
    }

    /**
     * Four wash channel walls — long east-west thin walls at varying
     * z, simulating dry riverbeds. They break the long cross-map
     * sight lines that the desert would otherwise have.
     */
    private static void addWashChannels(final ModelBuilder builder)
    {
        // Two channels in the north half, two in the south half
        addBox(builder, -HALF_EXTENT + 6.0f, 0.0f, 40.0f, HALF_EXTENT - 6.0f, 8.0f, 46.0f);

        addBox(builder, -HALF_EXTENT + 6.0f, 0.0f, 220.0f, HALF_EXTENT - 6.0f, 8.0f, 226.0f);

        // Two short north-south walls at the edges
        addBox(builder, -140.0f, 0.0f, 100.0f, -134.0f, 16.0f, 220.0f);

        addBox(builder, 134.0f, 0.0f, 100.0f, 140.0f, 16.0f, 220.0f);
    }

    /**
     * Four signposts at the south ramp and north switchback — small
     * vertical pillars (4 wide, 32 tall) that read as trail markers
     * on the canyon floor.
     */
    private static void addAccentGeometry(final ModelBuilder builder)
    {
        addBox(builder, 28.0f, 0.0f, 252.0f, 32.0f, 32.0f, 256.0f);

        addBox(builder, 32.0f, 0.0f, 92.0f, 36.0f, 32.0f, 96.0f);

        addBox(builder, -100.0f, 0.0f, 64.0f, -96.0f, 32.0f, 68.0f);

        addBox(builder, 100.0f, 0.0f, 232.0f, 104.0f, 32.0f, 236.0f);
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
        final int base = Rgba.pack(212, 188, 144, 255);

        final int shade = Rgba.pack(188, 162, 116, 255);

        final int line = Rgba.pack(160, 132, 88, 255);

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
                    colour = line;
                }

                out[y * TEXTURE_EDGE + x] = colour;
            }
        }

        return out;
    }

    private static int[] wallTexels()
    {
        final int base = Rgba.pack(208, 176, 124, 255);

        final int shade = Rgba.pack(180, 148, 96, 255);

        final int mortar = Rgba.pack(140, 108, 64, 255);

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

                out[y * TEXTURE_EDGE + x] = colour;
            }
        }

        return out;
    }

    private static int[] accentTexels()
    {
        final int colour = Rgba.pack(192, 96, 48, 255);

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
