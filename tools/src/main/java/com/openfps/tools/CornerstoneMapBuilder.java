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
 * Builds the Cornerstone map's level model: a 320x320 urban block with three
 * lanes, perimeter walls, four "buildings" (large boxes) as landmarks, and a
 * few crates scattered around the chokepoints.
 *
 * <p>Build-time only — this class is never on a runtime classpath. The output
 * is committed to {@code engine/src/main/resources/maps/cornerstone/} via
 * {@code git add -f} (the {@code *.ofm} pattern in {@code .gitignore} is
 * overridden here as a deliberate small test fixture, exactly as the existing
 * generated-room model is committed).</p>
 *
 * <h2>Geometry</h2>
 *
 * <p>The level is a square block centered on the world origin. Three
 * conceptual lanes run north-south (along +z), separated by east-west wall
 * segments with gaps forming the cut-throughs. Four tall buildings mark the
 * chokepoints: "Cafe" and "Library" anchor lane A, "Atrium" anchors lane B,
 * "Storefront" anchors lane C. A row of crates along lane B's central axis
 * is the main cover in the middle lane, and four short walls near the spawn
 * areas provide initial safety.</p>
 *
 * <h2>Textures — Kenney Prototype Kit, swatches sampled from the atlas</h2>
 *
 * <p>The textures are sampled from the Kenney Prototype Kit's
 * {@code colormap.png} (CC0; {@code docs/ASSETS.md} § 3 records the
 * provenance). Floor and wall tiles read the kit's swatches, so the look
 * matches a Kenney-converted level without committing the whole
 * {@code wall.ofm} / {@code floor-square.ofm} pack here. The geometry stays
 * procedural because a 320x320 map built out of 1x1 Kenney grid cells would
 * be a 5x5 grid of cubes and would not fit the spec's callout structure.</p>
 *
 * <p><b>With no atlas, the builder falls back to the previous procedural
 * texture generator.</b> The CLI accepts {@code --atlas=<path>}; without it
 * the build tool uses a hand-rolled grey-and-brick pair, which is what Pass 1
 * shipped. The default Gradle task wires the prototype-kit atlas so the
 * committed model uses Kenney colours; the procedural fallback is kept for
 * test isolation and the rare clone without the pack staged.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>
 *   --out=&lt;dir&gt;     where the .ofm file goes; required
 *   --atlas=&lt;path&gt;  the Kenney Prototype Kit colormap.png; optional
 * </pre>
 *
 * <p>With no arguments the program prints the usage and exits non-zero.</p>
 */
public final class CornerstoneMapBuilder
{
    /** Name of the produced model, written to {@code level.ofm}. */
    public static final String MODEL_NAME = "cornerstone-level";

    /** File name of the produced model. */
    public static final String FILE_NAME = "level.ofm";

    /** Half the map's playable extent, in world units. The map is 320x320. */
    public static final float HALF_EXTENT = 160.0f;

    /** Wall thickness for perimeter and internal walls, in world units. */
    public static final float WALL_THICKNESS = 6.0f;

    /** Perimeter wall height, in world units. */
    public static final float WALL_HEIGHT = 96.0f;

    /** Height of the tall landmark buildings, in world units. */
    public static final float BUILDING_HEIGHT = 128.0f;

    /** Height of a crate, in world units. */
    public static final float CRATE_HEIGHT = 56.0f;

    /** Texture edge, in texels. Power of two for the sampler's wrap. */
    public static final int TEXTURE_EDGE = 64;

    /** World units spanned by one repeat of a texture. */
    public static final float WORLD_UNITS_PER_TILE = 8.0f;

    private static final Logger LOG = LoggerFactory.getLogger(CornerstoneMapBuilder.class);

    /** Exit status used when an argument, an asset, or a budget is wrong. */
    private static final int EXIT_FAILURE = 1;

    private CornerstoneMapBuilder()
    {
        // entry point holder
    }

    /**
     * Builds the Cornerstone level model and writes it to the given directory.
     *
     * @param args {@code --out=<dir>} required, {@code --atlas=<path>} optional
     */
    public static void main(final String[] args)
    {
        final String out = option(args, "--out=");

        if (out == null)
        {
            LOG.error("usage: CornerstoneMapBuilder --out=<directory>"
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

        // Read it back through the runtime's reader to make sure the bytes
        // are valid. This is the same defensive check DemoAssetsMain runs.
        final ModelFormat parsed = ModelFormat.read(bytes);

        LOG.info("Wrote {} ({} triangles, {} vertices, {} textures)",
            outFile, parsed.indexCount() / 3, parsed.vertexCount(), parsed.textureCount());
    }

    /**
     * Returns the {@code .ofm} bytes for the Cornerstone level, using the
     * procedural texture generator (no Kenney atlas).
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
     * Returns the {@code .ofm} bytes for the Cornerstone level.
     *
     * <p>Three submeshes (floor, walls, accents) with three textures.
     * Geometry is a flat floor, four perimeter walls, two internal
     * east-west walls with cut-throughs, four tall landmark buildings,
     * a row of crates, and a small set of accent geometry (streetlights
     * and trim).</p>
     *
     * <p>When {@code atlasPath} is non-null, the floor and wall textures
     * are sampled from the Kenney Prototype Kit's colormap.png; the
     * accent texture is always procedural (the pack has no accent tile
     * that maps cleanly to a callout). When the path is null, the
     * pre-Kenney procedural generator is used — kept so a clone without
     * the pack can still build the level.</p>
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

        final int floorTexture = builder.addTexture("cornerstone-floor", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, floorTexels));

        final int wallTexture = builder.addTexture("cornerstone-wall", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, wallTexels));

        final int accentTexture = builder.addTexture("cornerstone-accent", TEXTURE_EDGE,
            TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, accentTexels));

        builder.beginSubmesh(floorTexture);

        // Floor slab: 320x320, 4 units thick, centered on origin.
        addBox(builder, -HALF_EXTENT, -4.0f, -HALF_EXTENT, HALF_EXTENT, 0.0f, HALF_EXTENT);

        builder.endSubmesh();

        builder.beginSubmesh(wallTexture);

        addPerimeterWalls(builder);

        addInternalWalls(builder);

        addLandmarkBuildings(builder);

        addCrateRow(builder);

        builder.endSubmesh();

        builder.beginSubmesh(accentTexture);

        addAccentGeometry(builder);

        builder.endSubmesh();

        return builder.toBytes();
    }

    /**
     * Adds the accent geometry: small streetlight bollards along the
     * spawn-side pavements and small corner-trim pieces on the
     * landmark buildings.
     *
     * <p>Bollards are short square pillars (8 wide, 32 tall) that
     * provide a visual scale reference and break up the long sightlines
     * along the spawn edges without blocking fire. Corner trims are
     * small L-shaped pieces that sit on top of each landmark building's
     * corner to read as a rooftop detail.</p>
     */
    private static void addAccentGeometry(final ModelBuilder builder)
    {
        // 8 bollards along the west and east pavements, between the
        // spawns. 4 per side, evenly spaced.
        for (int i = 0; i < 4; i++)
        {
            final float z = 48.0f + i * 48.0f;

            // West side
            addBox(builder, -150.0f, 0.0f, z, -142.0f, 32.0f, z + 8.0f);

            // East side
            addBox(builder, 142.0f, 0.0f, z, 150.0f, 32.0f, z + 8.0f);
        }

        // 4 corner trim pieces, one on each landmark building's roof
        // corner — a small visible band that reads as architecture.
        // Cafe (west lane A)
        addBox(builder, 48.0f, BUILDING_HEIGHT - 8.0f, 8.0f, 80.0f, BUILDING_HEIGHT, 12.0f);

        addBox(builder, 76.0f, BUILDING_HEIGHT - 8.0f, 8.0f, 80.0f, BUILDING_HEIGHT, 40.0f);

        // Library (east lane A)
        addBox(builder, 176.0f, BUILDING_HEIGHT - 8.0f, 8.0f, 208.0f, BUILDING_HEIGHT, 12.0f);

        addBox(builder, 176.0f, BUILDING_HEIGHT - 8.0f, 36.0f, 180.0f, BUILDING_HEIGHT, 40.0f);
    }

    /**
     * Returns how many triangles {@link #build} emits.
     *
     * <p>Stated as arithmetic rather than a literal so it cannot drift from
     * the geometry: 1 floor box (12 tri), 4 perimeter walls (48), 2
     * internal walls (24), 4 buildings (48), 8 crates (96), 8 bollards
     * (96), 4 corner trim pieces (48). Total: 348. The Pass 1 build had
     * 228 triangles; Pass 2 adds 8 bollards and 4 corner trims to the
     * accent submesh (12 boxes x 12 tri = 120 new triangles).</p>
     *
     * @return the level's triangle count
     */
    public static int triangleCount()
    {
        return (1 + 4 + 2 + 4 + 8 + 8 + 4) * 12;
    }

    // ------------------------------------------------------------------
    // Geometry construction
    // ------------------------------------------------------------------

    /**
     * Adds the four perimeter walls to the open submesh.
     *
     * <p>Each wall is one slab spanning the full side. The walls meet at the
     * corners — a separate "corner" box is not needed and would double the
     * count without adding geometry.</p>
     */
    private static void addPerimeterWalls(final ModelBuilder builder)
    {
        final float e = HALF_EXTENT;

        // South wall (z = -e)
        addBox(builder, -e, 0.0f, -e - WALL_THICKNESS, e, WALL_HEIGHT, -e);

        // North wall (z = e)
        addBox(builder, -e, 0.0f, e, e, WALL_HEIGHT, e + WALL_THICKNESS);

        // West wall (x = -e), between south and north walls
        addBox(builder, -e - WALL_THICKNESS, 0.0f, -e, -e, WALL_HEIGHT, e);

        // East wall (x = e)
        addBox(builder, e, 0.0f, -e, e + WALL_THICKNESS, WALL_HEIGHT, e);
    }

    /**
     * Adds the two east-west internal walls that separate the three lanes.
     *
     * <p>The walls are at z = 100 (the A/B boundary) and z = 220 (the B/C
     * boundary). Each has gaps for the cut-throughs. The gap positions are
     * what the design spec calls out: a gap at x=96 in the z=100 wall
     * connects the Cafe cut-through; a gap at x=224 in the z=100 wall is
     * the Library cut-through; the z=220 wall has a single gap at x=160
     * that connects the central Market cut-through.</p>
     */
    private static void addInternalWalls(final ModelBuilder builder)
    {
        final float wallY = WALL_HEIGHT;

        final float southEdge = 100.0f - WALL_THICKNESS / 2.0f;

        final float northEdge = 100.0f + WALL_THICKNESS / 2.0f;

        // z = 100 wall, three pieces around two gaps.
        // Left piece: x in [-160, 96 - 6]
        addBox(builder, -160.0f, 0.0f, southEdge, 90.0f, wallY, northEdge);

        // Middle piece: x in [96 + 6, 224 - 6] (a long central piece)
        addBox(builder, 102.0f, 0.0f, southEdge, 218.0f, wallY, northEdge);

        // Right piece: x in [224 + 6, 160]
        addBox(builder, 230.0f, 0.0f, southEdge, 160.0f, wallY, northEdge);

        // z = 220 wall, two pieces around one gap.
        final float cSouth = 220.0f - WALL_THICKNESS / 2.0f;

        final float cNorth = 220.0f + WALL_THICKNESS / 2.0f;

        // Left piece: x in [-160, 160 - 6]
        addBox(builder, -160.0f, 0.0f, cSouth, 154.0f, wallY, cNorth);

        // Right piece: x in [160 + 6, 160]
        addBox(builder, 166.0f, 0.0f, cSouth, 160.0f, wallY, cNorth);
    }

    /**
     * Adds the four tall "buildings" that mark the named chokepoints.
     *
     * <p>Each building is a large box positioned at a chokepoint from the
     * design spec. The boxes are tall enough to provide vertical cover and
     * the player can see them from across the map.</p>
     */
    private static void addLandmarkBuildings(final ModelBuilder builder)
    {
        // "Cafe" at (64, 0, 24) — west end of lane A
        addBox(builder, 48.0f, 0.0f, 8.0f, 80.0f, BUILDING_HEIGHT, 40.0f);

        // "Library" at (192, 0, 24) — east end of lane A
        addBox(builder, 176.0f, 0.0f, 8.0f, 208.0f, BUILDING_HEIGHT, 40.0f);

        // "Atrium" at (256, 0, 160) — east end of lane B
        addBox(builder, 240.0f, 0.0f, 144.0f, 272.0f, BUILDING_HEIGHT, 176.0f);

        // "Storefront" at (64, 0, 296) — west end of lane C
        addBox(builder, 48.0f, 0.0f, 280.0f, 80.0f, BUILDING_HEIGHT, 312.0f);
    }

    /**
     * Adds eight crates along lane B as the main mid-map cover.
     *
     * <p>Lane B is the risk/reward middle lane, and the player needs
     * something to hide behind while crossing it. Eight crates in a 2x4
     * grid form two "stacks" with walking gaps between them.</p>
     */
    private static void addCrateRow(final ModelBuilder builder)
    {
        // Two stacks, one at x=80 and one at x=240, with 4 crates in each.
        for (int row = 0; row < 4; row++)
        {
            final float z = 128.0f + row * 16.0f;

            // West stack
            addBox(builder, 72.0f, 0.0f, z, 88.0f, CRATE_HEIGHT, z + 16.0f);

            // East stack
            addBox(builder, 232.0f, 0.0f, z, 248.0f, CRATE_HEIGHT, z + 16.0f);
        }
    }

    /**
     * Adds a closed axis-aligned box to the open submesh.
     *
     * <p>Six faces, each wound counter-clockwise as seen from outside, the
     * same convention the rest of the pipeline obeys. UVs are world position
     * divided by {@link #WORLD_UNITS_PER_TILE} so the texture tiles
     * seamlessly across pieces of different sizes.</p>
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
     * Adds one face to the open submesh, with UVs scaled to the texture
     * tile size.
     *
     * @param ax face corner ax; @param ay face corner ay; @param az face corner az
     * @param bx face corner bx; @param by face corner by; @param bz face corner bz
     * @param cx face corner cx; @param cy face corner cy; @param cz face corner cz
     * @param dx face corner dx; @param dy face corner dy; @param dz face corner dz
     */
    private static void addFace(final ModelBuilder builder, final float ax, final float ay,
        final float az, final float bx, final float by, final float bz, final float cx,
        final float cy, final float cz, final float dx, final float dy, final float dz)
    {
        final float uScale = 1.0f / WORLD_UNITS_PER_TILE;

        // Two triangles (a, b, c) and (a, c, d), UVs scaled to the tile.
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
    // Textures — small generated tiles
    // ------------------------------------------------------------------

    /** A concrete-tone floor texture with a 2x2 checker pattern. */
    private static int[] floorTexels()
    {
        final int base = Rgba.pack(82, 84, 88, 255);

        final int shade = Rgba.pack(64, 66, 70, 255);

        final int line = Rgba.pack(126, 130, 138, 255);

        final int half = TEXTURE_EDGE / 2;

        final int[] out = new int[TEXTURE_EDGE * TEXTURE_EDGE];

        for (int y = 0; y < TEXTURE_EDGE; y++)
        {
            for (int x = 0; x < TEXTURE_EDGE; x++)
            {
                int colour = base;

                if (x < half)
                {
                    if (y < half)
                    {
                        colour = base;
                    }
                    else
                    {
                        colour = shade;
                    }
                }
                else
                {
                    if (y < half)
                    {
                        colour = shade;
                    }
                    else
                    {
                        colour = base;
                    }
                }

                // Grid line at the boundaries
                if (x == 0 || y == 0 || x == TEXTURE_EDGE - 1 || y == TEXTURE_EDGE - 1
                    || x == half || y == half)
                {
                    colour = line;
                }

                out[y * TEXTURE_EDGE + x] = colour;
            }
        }

        return out;
    }

    /** A brick-tone wall texture with horizontal banding. */
    private static int[] wallTexels()
    {
        final int base = Rgba.pack(126, 100, 84, 255);

        final int shade = Rgba.pack(94, 72, 60, 255);

        final int mortar = Rgba.pack(60, 50, 44, 255);

        final int[] out = new int[TEXTURE_EDGE * TEXTURE_EDGE];

        final int bandHeight = TEXTURE_EDGE / 8;

        for (int y = 0; y < TEXTURE_EDGE; y++)
        {
            final int band = y / bandHeight;

            final boolean isMortar = (y % bandHeight) == 0;

            for (int x = 0; x < TEXTURE_EDGE; x++)
            {
                int colour = base;

                if (band % 2 != 0)
                {
                    colour = shade;
                }

                if (isMortar)
                {
                    colour = mortar;
                }
                // Vertical mortar offset between rows for a brick pattern
                else if ((x + (band * 4)) % 16 == 0)
                {
                    colour = mortar;
                }

                out[y * TEXTURE_EDGE + x] = colour;
            }
        }

        return out;
    }

    /**
     * A solid red accent texture for the bollards and corner trim. Always
     * procedural, because the Kenney Prototype Kit has no swatch that
     * reads as "streetlight / trim" — the pack's swatches are floor and
     * wall and a handful of saturated colours, none of which are
     * recognisably an accent.
     *
     * <p>Hand-authored rather than sampled, and the same single colour
     * everywhere: the bollards and trims are visual punctuation, not
     * surface detail, and a solid colour reads as "painted metal" the way
     * a textured one would not.</p>
     *
     * @return the accent tile
     */
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

    @SuppressWarnings("unused")
    private static boolean flag(final String[] args, final String name)
    {
        for (final String arg : args)
        {
            if (name.equals(arg))
            {
                return true;
            }
        }

        return false;
    }
}
