/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

/**
 * A closed, six-coloured cube as a {@link ModelFormat} file image.
 *
 * <p><b>Closed is the point.</b> A z-buffer resolves a closed mesh correctly
 * with no culling at all, so {@link Rasterizer.CullMode#NONE} is an oracle for
 * what the image must be that assumes nothing whatsoever about winding. Giving
 * every face its own solid colour turns "which faces are on screen" into
 * something a test can read off the pixels: with correct culling only the three
 * near faces appear, and with the convention inverted only the three far ones
 * do — the inside-out failure {@code render/README.md} § 7 warns looks like a
 * plausible model rather than an error.</p>
 *
 * <p><b>Winding.</b> Every face is counter-clockwise seen from outside — the
 * glTF 2.0 front-face rule, equivalently {@code (v1 - v0) x (v2 - v0)} pointing
 * along the outward normal. Positions are in the model's own coordinates with
 * no handedness conversion applied, which is exactly what {@code GltfConverter}
 * emits today, so this cube stands in faithfully for a converted asset.</p>
 *
 * <p>Textures are 1x1, one solid texel each. That is deliberate: bilinear
 * filtering over a uniform texture returns that texel exactly, whatever the
 * UVs or the mip level, so a face's colour on screen is unambiguous and the
 * test is measuring culling rather than sampling.</p>
 */
final class CubeFixture
{
    /** Faces on a cube. */
    static final int FACE_COUNT = 6;

    /** Half the cube's edge; it spans -1 to +1 on every axis. */
    static final float HALF_EDGE = 1.0f;

    /** Colour of the +x face. */
    static final int PLUS_X = 0xFF2020FF;

    /** Colour of the -x face. */
    static final int MINUS_X = 0x20FFFFFF;

    /** Colour of the +y face. */
    static final int PLUS_Y = 0x20FF20FF;

    /** Colour of the -y face. */
    static final int MINUS_Y = 0xFF20FFFF;

    /** Colour of the +z face. */
    static final int PLUS_Z = 0x2020FFFF;

    /** Colour of the -z face. */
    static final int MINUS_Z = 0xFFFF20FF;

    /** Face colours in the order the faces are built: +x, -x, +y, -y, +z, -z. */
    static final int[] FACE_COLORS = {PLUS_X, MINUS_X, PLUS_Y, MINUS_Y, PLUS_Z, MINUS_Z};

    /** Corners per face. */
    private static final int FACE_CORNERS = 4;

    /** Slots per vertex in the interleaved block. */
    private static final int VERTEX_SLOTS = 6;

    /** Slots per submesh record. */
    private static final int SUBMESH_SLOTS = 4;

    /** Slots per texture record. */
    private static final int TEXTURE_SLOTS = 4;

    /** Indices per face: two triangles. */
    private static final int FACE_INDICES = 6;

    /**
     * Face corner positions, six faces of four corners of three components,
     * counter-clockwise seen from outside. Each row was checked against
     * {@code (v1 - v0) x (v2 - v0)} pointing outward.
     */
    private static final float[] FACES =
    {
        1, -1, 1,   1, -1, -1,   1, 1, -1,   1, 1, 1,
        -1, -1, -1,  -1, -1, 1,  -1, 1, 1,  -1, 1, -1,
        -1, 1, 1,   1, 1, 1,   1, 1, -1,  -1, 1, -1,
        -1, -1, -1,   1, -1, -1,   1, -1, 1,  -1, -1, 1,
        -1, -1, 1,   1, -1, 1,   1, 1, 1,  -1, 1, 1,
        1, -1, -1,  -1, -1, -1,  -1, 1, -1,   1, 1, -1,
    };

    /** Texture coordinates for the four corners, in the order {@link #FACES} lists them. */
    private static final float[] FACE_UV = {0, 1, 1, 1, 1, 0, 0, 0};

    private CubeFixture()
    {
        // fixture factory
    }

    /**
     * Builds the cube as a complete {@link ModelFormat} file image.
     *
     * @return the file bytes, ready for {@code ModelFormat.read}
     */
    static byte[] build()
    {
        final int[] vertices = new int[FACE_COUNT * FACE_CORNERS * VERTEX_SLOTS];

        final int[] indices = new int[FACE_COUNT * FACE_INDICES];

        final int[] submeshes = new int[FACE_COUNT * SUBMESH_SLOTS];

        final int[] textures = new int[FACE_COUNT * TEXTURE_SLOTS];

        final int[] texels = new int[FACE_COUNT];

        for (int face = 0; face < FACE_COUNT; face++)
        {
            writeFaceVertices(vertices, face);

            writeFaceIndices(indices, face);

            submeshes[face * SUBMESH_SLOTS] = face * FACE_INDICES;

            submeshes[face * SUBMESH_SLOTS + 1] = FACE_INDICES;

            submeshes[face * SUBMESH_SLOTS + 2] = face;

            submeshes[face * SUBMESH_SLOTS + 3] = 0;

            // 1x1, one level, one texel — see the class Javadoc.
            textures[face * TEXTURE_SLOTS] = 1;

            textures[face * TEXTURE_SLOTS + 1] = 1;

            textures[face * TEXTURE_SLOTS + 2] = 1;

            textures[face * TEXTURE_SLOTS + 3] = face;

            texels[face] = FACE_COLORS[face];
        }

        return ModelFileFixture.build(vertices, indices, submeshes, textures, texels,
            new float[] {-HALF_EDGE, -HALF_EDGE, -HALF_EDGE, HALF_EDGE, HALF_EDGE, HALF_EDGE});
    }

    private static void writeFaceVertices(final int[] vertices, final int face)
    {
        for (int corner = 0; corner < FACE_CORNERS; corner++)
        {
            final int source = (face * FACE_CORNERS + corner) * 3;

            final int at = (face * FACE_CORNERS + corner) * VERTEX_SLOTS;

            vertices[at] = ModelFileFixture.bits(FACES[source] * HALF_EDGE);

            vertices[at + 1] = ModelFileFixture.bits(FACES[source + 1] * HALF_EDGE);

            vertices[at + 2] = ModelFileFixture.bits(FACES[source + 2] * HALF_EDGE);

            vertices[at + 3] = ModelFileFixture.bits(FACE_UV[corner * 2]);

            vertices[at + 4] = ModelFileFixture.bits(FACE_UV[corner * 2 + 1]);

            vertices[at + 5] = FACE_COLORS[face];
        }
    }

    // Two triangles fanned from corner 0, which preserves the winding.
    private static void writeFaceIndices(final int[] indices, final int face)
    {
        final int first = face * FACE_CORNERS;

        final int at = face * FACE_INDICES;

        indices[at] = first;

        indices[at + 1] = first + 1;

        indices[at + 2] = first + 2;

        indices[at + 3] = first;

        indices[at + 4] = first + 2;

        indices[at + 5] = first + 3;
    }
}
