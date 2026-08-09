/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.model;

import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.engine.render.adapter.Rgba;

/**
 * A generated greybox room — floor slab plus four walls — in
 * {@link ModelFormat}, for standing inside when no upstream level kit is
 * available.
 *
 * Build-time only — this type is never on a runtime classpath.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code docs/ASSETS.md} § 3 names Kenney's Prototype Kit as the intended
 * source of level geometry, and § 6 keeps that art out of git. A clone with no
 * pack downloaded therefore has no floor to stand on, and a first-person view
 * of a void is not a view of anything. This class is the floor of last resort:
 * it owes nothing to any third party, so it is always available, and it goes
 * through the same {@link ModelBuilder} and {@link MipGenerator} path as a
 * converted glTF asset — which means it also exercises that path.</p>
 *
 * <p>It is a <b>fallback, not a substitute.</b> {@code DemoAssetsMain} emits it
 * only when no glTF was staged, and says so at {@code WARN} when it does.
 * Shipping generated geometry while implying it is Kenney art would be a
 * provenance lie, which is the exact failure {@code docs/ASSETS.md} § 7
 * exists to prevent.</p>
 *
 * <h2>Why solid boxes rather than inward-facing quads</h2>
 *
 * <p>Every piece is a <b>closed box</b>, wound counter-clockwise as seen from
 * outside, which is the glTF 2.0 front-face rule the rest of the pipeline
 * already obeys. A room built from single-sided quads turned inward would be
 * invisible under the same backface culling that makes a converted Kenney model
 * render correctly — the room would have to disable culling, and a test scene
 * that needs different render state from the real art tests the wrong thing.
 * A wall with thickness has its interior-facing side as a genuine outward face,
 * so the room reads correctly from inside with the shipping cull mode
 * untouched.</p>
 *
 * <h2>Texture coordinates</h2>
 *
 * <p>UVs are world position divided by {@link #WORLD_UNITS_PER_TILE} rather
 * than the usual per-face {@code 0..1}, so the grid holds one constant texel
 * density across pieces of different sizes and tiles seamlessly where they
 * meet. This relies on the sampler's repeat wrap, which is why the texture edge
 * is a power of two.</p>
 */
public final class ProceduralRoom
{
    /** Edge of each generated texture, in texels. A power of two, per the sampler's mask wrap. */
    public static final int TEXTURE_EDGE = 64;

    /** World units spanned by one repeat of a texture. */
    public static final float WORLD_UNITS_PER_TILE = 2.0f;

    /** Half the interior floor span, in world units; the room is 16 by 16. */
    public static final float INTERIOR_HALF_EXTENT = 8.0f;

    /** Thickness of the floor slab and of each wall, in world units. */
    public static final float SLAB_THICKNESS = 0.5f;

    /** Height of the walls above the floor surface, in world units. */
    public static final float WALL_HEIGHT = 4.0f;

    /** Faces on a box. */
    public static final int FACE_COUNT = 6;

    /** Corners per face. */
    private static final int FACE_CORNERS = 4;

    /** Components in a position. */
    private static final int AXES = 3;

    /** Boxes in the room: one floor slab and four walls. */
    private static final int BOX_COUNT = 5;

    /**
     * Corner selectors, six faces of four corners of three axes: {@code 0}
     * takes the box minimum on that axis and {@code 1} takes the maximum.
     *
     * <p>Face order is {@code +x, -x, +y, -y, +z, -z}, and each face's corners
     * are listed counter-clockwise as seen from outside — checked face by face
     * against {@code (v1 - v0) x (v2 - v0)} pointing along the outward
     * normal.</p>
     */
    private static final int[] FACE_CORNER_SELECTORS =
    {
        // +x
        1, 0, 1,   1, 0, 0,   1, 1, 0,   1, 1, 1,
        // -x
        0, 0, 0,   0, 0, 1,   0, 1, 1,   0, 1, 0,
        // +y
        0, 1, 1,   1, 1, 1,   1, 1, 0,   0, 1, 0,
        // -y
        0, 0, 0,   1, 0, 0,   1, 0, 1,   0, 0, 1,
        // +z
        0, 0, 1,   1, 0, 1,   1, 1, 1,   0, 1, 1,
        // -z
        1, 0, 0,   0, 0, 0,   0, 1, 0,   1, 1, 0,
    };

    /**
     * The world axis each face's {@code u} then {@code v} follows, indexed by
     * face in the order {@link #FACE_CORNER_SELECTORS} lists them. The pair is
     * always the two axes the face actually spans, so no face is textured
     * along its own normal — which would collapse the whole face to one line of
     * texels.
     */
    private static final int[] FACE_UV_AXES = {2, 1,   2, 1,   0, 2,   0, 2,   0, 1,   0, 1};

    /** Baked vertex colour: opaque white, so the texture arrives unmodulated. */
    private static final int SURFACE_COLOUR = Rgba.pack(255, 255, 255, 255);

    /** Width of the grid line along a tile's leading edges, in texels. */
    private static final int LINE_WIDTH = 3;

    /** Edge of one checker square within a tile, in texels. */
    private static final int CHECKER = TEXTURE_EDGE / 2;

    /** Floor tile fill. */
    private static final int FLOOR_BASE = Rgba.pack(58, 62, 70, 255);

    /** Floor tile alternate checker square. */
    private static final int FLOOR_SHADE = Rgba.pack(45, 49, 56, 255);

    /** Floor grid line. */
    private static final int FLOOR_LINE = Rgba.pack(104, 112, 126, 255);

    /** Wall tile fill. */
    private static final int WALL_BASE = Rgba.pack(94, 90, 84, 255);

    /** Wall tile alternate checker square. */
    private static final int WALL_SHADE = Rgba.pack(78, 75, 70, 255);

    /** Wall grid line. */
    private static final int WALL_LINE = Rgba.pack(142, 136, 126, 255);

    private ProceduralRoom()
    {
        // model factory
    }

    /**
     * Builds the room as a complete {@link ModelFormat} file image.
     *
     * <p>Two submeshes, floor and walls, so the two textures are one material
     * switch apart rather than interleaved — the draw-sorting point
     * {@code docs/ASSETS.md} § 9 measures at 17% of the span loop.</p>
     *
     * @return the {@code .ofm} bytes, ready for {@code ModelFormat.read}
     */
    public static byte[] build()
    {
        final ModelBuilder builder = new ModelBuilder("generated-room");

        final int floorTexture = builder.addTexture("room-floor", TEXTURE_EDGE, TEXTURE_EDGE,
            MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE,
                gridTexels(FLOOR_BASE, FLOOR_SHADE, FLOOR_LINE)));

        final int wallTexture = builder.addTexture("room-wall", TEXTURE_EDGE, TEXTURE_EDGE,
            MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE,
                gridTexels(WALL_BASE, WALL_SHADE, WALL_LINE)));

        final float inner = INTERIOR_HALF_EXTENT;

        final float outer = INTERIOR_HALF_EXTENT + SLAB_THICKNESS;

        builder.beginSubmesh(floorTexture);

        addBox(builder, -inner, -SLAB_THICKNESS, -inner, inner, 0.0f, inner);

        builder.endSubmesh();

        // The two z walls run the full outer span and the two x walls fill
        // between them, so the four meet with no gap at the corners.
        builder.beginSubmesh(wallTexture);

        addBox(builder, -outer, 0.0f, -outer, outer, WALL_HEIGHT, -inner);

        addBox(builder, -outer, 0.0f, inner, outer, WALL_HEIGHT, outer);

        addBox(builder, -outer, 0.0f, -inner, -inner, WALL_HEIGHT, inner);

        addBox(builder, inner, 0.0f, -inner, outer, WALL_HEIGHT, inner);

        builder.endSubmesh();

        return builder.toBytes();
    }

    /**
     * Returns how many triangles {@link #build} emits.
     *
     * <p>Stated as arithmetic rather than a literal so it cannot drift from
     * the geometry above.</p>
     *
     * @return the room's triangle count
     */
    public static int triangleCount()
    {
        return BOX_COUNT * FACE_COUNT * 2;
    }

    // Emits one axis-aligned box: six faces, four vertices each, two triangles
    // per face fanned from corner 0, which preserves the corner order's
    // counter-clockwise winding.
    private static void addBox(final ModelBuilder builder, final float minX, final float minY,
        final float minZ, final float maxX, final float maxY, final float maxZ)
    {
        final float[] min = {minX, minY, minZ};

        final float[] max = {maxX, maxY, maxZ};

        for (int face = 0; face < FACE_COUNT; face++)
        {
            addFace(builder, face, min, max);
        }
    }

    // One face of a box, textured along the two axes it spans.
    private static void addFace(final ModelBuilder builder, final int face, final float[] min,
        final float[] max)
    {
        final int uAxis = FACE_UV_AXES[face * 2];

        final int vAxis = FACE_UV_AXES[(face * 2) + 1];

        // MUTABLE locals — the corner being built and the index of the first
        // one, which the two triangles below both fan from.
        final float[] corner = new float[AXES];

        int first = 0;

        for (int index = 0; index < FACE_CORNERS; index++)
        {
            final int at = (((face * FACE_CORNERS) + index) * AXES);

            for (int axis = 0; axis < AXES; axis++)
            {
                corner[axis] = min[axis];

                if (FACE_CORNER_SELECTORS[at + axis] != 0)
                {
                    corner[axis] = max[axis];
                }
            }

            final int vertex = builder.addVertex(corner[0], corner[1], corner[2],
                corner[uAxis] / WORLD_UNITS_PER_TILE, corner[vAxis] / WORLD_UNITS_PER_TILE,
                SURFACE_COLOUR);

            if (index == 0)
            {
                first = vertex;
            }
        }

        builder.addTriangle(first, first + 1, first + 2);

        builder.addTriangle(first, first + 2, first + 3);
    }

    // Builds one tile's level-0 texels: a two-tone checker with a brighter
    // line along the tile's leading edges, which is what reads as a grid once
    // the world-space UVs tile it across a surface.
    private static int[] gridTexels(final int base, final int shade, final int line)
    {
        final int[] texels = new int[TEXTURE_EDGE * TEXTURE_EDGE];

        for (int y = 0; y < TEXTURE_EDGE; y++)
        {
            for (int x = 0; x < TEXTURE_EDGE; x++)
            {
                texels[(y * TEXTURE_EDGE) + x] = texelAt(x, y, base, shade, line);
            }
        }

        return texels;
    }

    // The colour of one texel: grid line first, then the checker.
    private static int texelAt(final int x, final int y, final int base, final int shade,
        final int line)
    {
        if (x < LINE_WIDTH || y < LINE_WIDTH)
        {
            return line;
        }

        if (((x / CHECKER) + (y / CHECKER)) % 2 == 0)
        {
            return base;
        }

        return shade;
    }
}
