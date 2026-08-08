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
 * Builds the Pipeline map's level model: a 320x320 pipeline pumping
 * station with three long east-west pipelines (z=64, z=160, z=256) and
 * three control valves at their centres. Three catwalks at y=64 run
 * north-south alongside the pipelines, and two east-west underpasses
 * (at x=-100 and x=100) cut through the pipelines.
 *
 * <p>Build-time only — this class is never on a runtime classpath. The
 * output is committed to {@code engine/src/main/resources/maps/pipeline/}
 * via {@code git add -f}, the same exception the tripoint model uses.</p>
 *
 * <h2>Geometry</h2>
 *
 * <p>The level is a flat industrial pad. The ground slab is a flat
 * 320x320 floor at y=0. Three pipeline runs sit on the slab at z=64,
 * z=160, and z=256 (each 16 wide, 16 tall, 320 long, centred on
 * x=160). Each pipeline has a 32x32x16 control valve at the centre
 * (x=144..176, z=flagZ-8..flagZ+8) that reads as the flag stand. Three
 * catwalks at y=64 run north-south at x=-80, x=0, and x=80 (each 8 wide,
 * 8 tall, 320 long, centred on z=160). Two east-west underpasses at
 * x=-100 and x=100 cut 64-wide gaps through the pipelines and the
 * catwalks. Four perimeter walls enclose the playable area. Eight
 * crates in the spaces between the pipelines provide cover. The
 * accent submesh is three red "valve handles" sitting on top of the
 * three control valves.</p>
 *
 * <h2>Textures — Kenney Prototype Kit</h2>
 *
 * <p>Floor and wall tiles are sampled from the Kenney Prototype Kit's
 * colormap.png (CC0). The pack's neutral floor and wall swatches
 * match the industrial palette. Without the atlas the builder falls
 * back to a procedural generator (kept for clone-without-pack
 * testing).</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 *   --out=&lt;dir&gt;     where the .ofm file goes; required
 *   --atlas=&lt;path&gt;  the Kenney Prototype Kit colormap.png; optional
 * </pre>
 */
public final class PipelineMapBuilder
{
    /** Name of the produced model, written to {@code level.ofm}. */
    public static final String MODEL_NAME = "pipeline-level";

    /** File name of the produced model. */
    public static final String FILE_NAME = "level.ofm";

    /** Half the map's playable extent, in world units. The map is 320x320. */
    public static final float HALF_EXTENT = 160.0f;

    /** Wall thickness, in world units. */
    public static final float WALL_THICKNESS = 6.0f;

    /** Perimeter wall height, in world units. */
    public static final float WALL_HEIGHT = 64.0f;

    /** Pipeline run width (the cross-section is square in the spec). */
    public static final float PIPELINE_WIDTH = 16.0f;

    /** Pipeline run height (above the ground slab). */
    public static final float PIPELINE_HEIGHT = 16.0f;

    /** Catwalk width (the cross-section is square in the spec). */
    public static final float CATWALK_WIDTH = 8.0f;

    /** Catwalk height (above the ground slab). */
    public static final float CATWALK_HEIGHT = 8.0f;

    /** Control-valve half-edge, in world units. The valve is square. */
    public static final float VALVE_HALF = 16.0f;

    /** East-west underpass half-width, in world units. */
    public static final float UNDERPASS_HALF_WIDTH = 32.0f;

    /** Crate edge, in world units. */
    public static final float CRATE_EDGE = 8.0f;

    /** Texture edge, in texels. Power of two for the sampler's wrap. */
    public static final int TEXTURE_EDGE = 64;

    /** World units spanned by one repeat of a texture. */
    public static final float WORLD_UNITS_PER_TILE = 8.0f;

    private static final Logger LOG = LoggerFactory.getLogger(PipelineMapBuilder.class);

    private PipelineMapBuilder()
    {
        // entry point holder
    }

    /**
     * Builds the Pipeline level model and writes it to the given directory.
     *
     * @param args {@code --out=<dir>} required, {@code --atlas=<path>} optional
     */
    public static void main(final String[] args)
    {
        final String out = option(args, "--out=");
        if (out == null)
        {
            LOG.error("usage: PipelineMapBuilder --out=<directory>"
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
     * Returns the {@code .ofm} bytes for the Pipeline level, using the
     * procedural fallback (no Kenney atlas).
     *
     * @return the .ofm file image
     */
    public static byte[] build()
    {
        return build(null);
    }

    /**
     * Returns the {@code .ofm} bytes for the Pipeline level.
     *
     * <p>Three submeshes (floor, walls, accents) with three textures.
     * The geometry is a flat ground slab, three pipeline runs with
     * underpass gaps, three control valves, three catwalks with
     * underpass gaps, four perimeter walls, eight crates, and three
     * red "valve handles" (the accent submesh).</p>
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
        final int floorTexture = builder.addTexture("pipeline-floor", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, floorTexels));
        final int wallTexture = builder.addTexture("pipeline-wall", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, wallTexels));
        final int accentTexture = builder.addTexture("pipeline-accent", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, accentTexels));

        builder.beginSubmesh(floorTexture);
        addGroundSlab(builder);
        builder.endSubmesh();

        builder.beginSubmesh(wallTexture);
        addPerimeterWalls(builder);
        addPipelines(builder);
        addControlValves(builder);
        addCatwalks(builder);
        addCrates(builder);
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
     * (48) + 3 pipelines (each split into 3 runs around the underpasses
     * = 9 runs = 108) + 3 control valves (36) + 3 catwalks (each split
     * into 3 runs around the underpasses = 9 runs = 108) + 8 crates
     * (96) + 3 valve handles (36) = 444.</p>
     *
     * @return the level's triangle count
     */
    public static int triangleCount()
    {
        return (1 + 4 + 9 + 3 + 9 + 8 + 3) * 12;
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
     * The four perimeter walls. Full-height (64 units) so the long
     * east-west sightline is broken at the edge of the map.
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
     * The three pipeline runs. Each runs the full 320-unit length
     * along x, with two 64-unit-wide underpass gaps at x=-100 and
     * x=+100 so a player can cross the pipeline without being shot
     * by a defender on the catwalk. The pipeline cross-section is
     * 16 wide (z) by 16 tall (y), centred on z=64, z=160, z=256.
     */
    private static void addPipelines(final ModelBuilder builder)
    {
        // North pipeline (FLAG_C, z=64): three runs around the gaps
        addPipelineRun(builder, 64.0f, -HALF_EXTENT, -100.0f);
        addPipelineRun(builder, 64.0f, -36.0f, 100.0f);
        addPipelineRun(builder, 64.0f, 164.0f, HALF_EXTENT);
        // Centre pipeline (FLAG_B, z=160)
        addPipelineRun(builder, 160.0f, -HALF_EXTENT, -100.0f);
        addPipelineRun(builder, 160.0f, -36.0f, 100.0f);
        addPipelineRun(builder, 160.0f, 164.0f, HALF_EXTENT);
        // South pipeline (FLAG_A, z=256)
        addPipelineRun(builder, 256.0f, -HALF_EXTENT, -100.0f);
        addPipelineRun(builder, 256.0f, -36.0f, 100.0f);
        addPipelineRun(builder, 256.0f, 164.0f, HALF_EXTENT);
    }

    /**
     * One run of a pipeline (a single rectangular box) between two
     * x coordinates.
     */
    private static void addPipelineRun(final ModelBuilder builder, final float z,
        final float minX, final float maxX)
    {
        final float halfW = PIPELINE_WIDTH / 2.0f;
        addBox(builder, minX, 0.0f, z - halfW, maxX, PIPELINE_HEIGHT, z + halfW);
    }

    /**
     * The three control valves. Each is a 32x32x16 box sitting on top
     * of the pipeline at the centre (x=144..176, z=flagZ-16..flagZ+16,
     * y=16..32). The valve reads as the flag stand for the player.
     */
    private static void addControlValves(final ModelBuilder builder)
    {
        // North valve (FLAG_C)
        addBox(builder, 144.0f, PIPELINE_HEIGHT, 48.0f, 176.0f,
            PIPELINE_HEIGHT + 16.0f, 80.0f);
        // Centre valve (FLAG_B)
        addBox(builder, 144.0f, PIPELINE_HEIGHT, 144.0f, 176.0f,
            PIPELINE_HEIGHT + 16.0f, 176.0f);
        // South valve (FLAG_A)
        addBox(builder, 144.0f, PIPELINE_HEIGHT, 240.0f, 176.0f,
            PIPELINE_HEIGHT + 16.0f, 272.0f);
    }

    /**
     * The three catwalks. Each runs the full 320-unit length along x
     * at y=64, with two 64-unit-wide underpass gaps at x=-100 and
     * x=+100 so the catwalks do not block the underpass below. The
     * catwalk cross-section is 8 wide (z) by 8 tall (y), centred on
     * z=-80, z=0, z=+80.
     */
    private static void addCatwalks(final ModelBuilder builder)
    {
        final float y0 = 64.0f;
        // North catwalk (alongside FLAG_C, z=-80): three runs
        addCatwalkRun(builder, -80.0f, y0, -HALF_EXTENT, -100.0f);
        addCatwalkRun(builder, -80.0f, y0, -36.0f, 100.0f);
        addCatwalkRun(builder, -80.0f, y0, 164.0f, HALF_EXTENT);
        // Centre catwalk (alongside FLAG_B, z=0)
        addCatwalkRun(builder, 0.0f, y0, -HALF_EXTENT, -100.0f);
        addCatwalkRun(builder, 0.0f, y0, -36.0f, 100.0f);
        addCatwalkRun(builder, 0.0f, y0, 164.0f, HALF_EXTENT);
        // South catwalk (alongside FLAG_A, z=80)
        addCatwalkRun(builder, 80.0f, y0, -HALF_EXTENT, -100.0f);
        addCatwalkRun(builder, 80.0f, y0, -36.0f, 100.0f);
        addCatwalkRun(builder, 80.0f, y0, 164.0f, HALF_EXTENT);
    }

    /**
     * One run of a catwalk (a single rectangular box) between two
     * x coordinates.
     */
    private static void addCatwalkRun(final ModelBuilder builder, final float z, final float y0,
        final float minX, final float maxX)
    {
        final float halfW = CATWALK_WIDTH / 2.0f;
        addBox(builder, minX, y0, z - halfW, maxX, y0 + CATWALK_HEIGHT, z + halfW);
    }

    /**
     * Eight cover crates scattered in the spaces between the three
     * pipelines. Each crate is an 8x8x8 box sitting on the ground
     * slab.
     */
    private static void addCrates(final ModelBuilder builder)
    {
        // Four crates between the north and centre pipelines
        addCrate(builder, -120.0f, 100.0f);
        addCrate(builder, -40.0f, 100.0f);
        addCrate(builder, 60.0f, 100.0f);
        addCrate(builder, 140.0f, 100.0f);
        // Four crates between the centre and south pipelines
        addCrate(builder, -120.0f, 200.0f);
        addCrate(builder, -40.0f, 200.0f);
        addCrate(builder, 60.0f, 200.0f);
        addCrate(builder, 140.0f, 200.0f);
    }

    /**
     * One cover crate.
     */
    private static void addCrate(final ModelBuilder builder, final float x, final float z)
    {
        addBox(builder, x, 0.0f, z, x + CRATE_EDGE, CRATE_EDGE, z + CRATE_EDGE);
    }

    /**
     * The accent geometry. Three red "valve handles" sitting on top of
     * the three control valves. Each handle is a thin 4-wide, 8-tall
     * box that reads as a wheel handle from the player's distance.
     */
    private static void addAccentGeometry(final ModelBuilder builder)
    {
        // FLAG_C handle
        addBox(builder, 158.0f, PIPELINE_HEIGHT + 16.0f, 62.0f, 162.0f,
            PIPELINE_HEIGHT + 24.0f, 66.0f);
        // FLAG_B handle
        addBox(builder, 158.0f, PIPELINE_HEIGHT + 16.0f, 158.0f, 162.0f,
            PIPELINE_HEIGHT + 24.0f, 162.0f);
        // FLAG_A handle
        addBox(builder, 158.0f, PIPELINE_HEIGHT + 16.0f, 254.0f, 162.0f,
            PIPELINE_HEIGHT + 24.0f, 258.0f);
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
        final int base = Rgba.pack(120, 122, 128, 255);
        final int line = Rgba.pack(96, 98, 104, 255);
        final int[] out = new int[TEXTURE_EDGE * TEXTURE_EDGE];
        for (int y = 0; y < TEXTURE_EDGE; y++)
        {
            for (int x = 0; x < TEXTURE_EDGE; x++)
            {
                int colour = base;
                if (y == TEXTURE_EDGE / 2)
                {
                    colour = line;
                }
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
        final int base = Rgba.pack(150, 152, 160, 255);
        final int shade = Rgba.pack(108, 110, 118, 255);
        final int rib = Rgba.pack(80, 82, 90, 255);
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
