/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

/**
 * A flat, single-coloured, untextured quad as a {@link ModelFormat} file image
 * — the model the scene tests measure transforms with.
 *
 * <p><b>Why a quad rather than the cube.</b> A scene test has to assert where a
 * transformed instance lands on screen, against coordinates worked out from
 * {@code render/README.md} § 4 rather than against "it moved". That needs
 * geometry whose projection is an axis-aligned rectangle with edges a test can
 * read straight off the pixels, and whose colour is unambiguous.</p>
 *
 * <p><b>Untextured, deliberately.</b> A submesh with
 * {@link ModelFormat#NO_TEXTURE} takes {@code SoftwareRenderPort}'s flat-colour
 * path, which paints the triangle in its first vertex's baked colour — so every
 * covered pixel is exactly {@code colour} and no sampling, filtering or mip
 * selection stands between the transform and the assertion.</p>
 *
 * <p><b>Winding.</b> The two triangles are counter-clockwise seen from +z, the
 * glTF 2.0 front-face rule, so a quad in the z = 0 plane faces a camera on the
 * +z axis and survives {@link SoftwareRenderPort#BACKFACE_CULL_MODE}.</p>
 */
final class QuadFixture
{
    /** Triangles in a quad. */
    static final int TRIANGLES = 2;

    /** Slots per vertex in the interleaved block. */
    private static final int VERTEX_SLOTS = 6;

    private QuadFixture()
    {
        // fixture factory
    }

    /**
     * Builds a quad centred on the model origin in the z = 0 plane.
     *
     * @param halfWidth half the extent along x; must be positive
     * @param halfHeight half the extent along y; must be positive
     * @param colour packed RGBA8888 baked into every vertex
     * @return the file bytes, ready for {@code ModelFormat.read}
     */
    static byte[] build(final float halfWidth, final float halfHeight, final int colour)
    {
        final float[] corners =
        {
            -halfWidth, -halfHeight,
            halfWidth, -halfHeight,
            halfWidth, halfHeight,
            -halfWidth, halfHeight,
        };
        final float[] uv = {0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f};

        final int[] vertices = new int[4 * VERTEX_SLOTS];
        for (int corner = 0; corner < 4; corner++)
        {
            final int at = corner * VERTEX_SLOTS;
            vertices[at] = ModelFileFixture.bits(corners[corner * 2]);
            vertices[at + 1] = ModelFileFixture.bits(corners[corner * 2 + 1]);
            vertices[at + 2] = ModelFileFixture.bits(0.0f);
            vertices[at + 3] = ModelFileFixture.bits(uv[corner * 2]);
            vertices[at + 4] = ModelFileFixture.bits(uv[corner * 2 + 1]);
            vertices[at + 5] = colour;
        }

        return ModelFileFixture.build(vertices, new int[] {0, 1, 2, 0, 2, 3},
            new int[] {0, 6, ModelFormat.NO_TEXTURE, 0}, new int[0], new int[0],
            new float[] {-halfWidth, -halfHeight, 0.0f, halfWidth, halfHeight, 0.0f});
    }

    /**
     * Builds a square quad.
     *
     * @param halfEdge half the edge length
     * @param colour packed RGBA8888
     * @return the file bytes
     */
    static byte[] square(final float halfEdge, final int colour)
    {
        return build(halfEdge, halfEdge, colour);
    }

    /**
     * Builds a square quad that samples a solid texture of its own.
     *
     * <p><b>This is the fixture that catches a batched raster pass losing
     * track of which texture a triangle samples.</b> The untextured
     * {@link #square} takes the flat-colour path and would go on looking right
     * even if every instance in a frame shared one texture table entry;
     * distinct textures on distinct instances are the only way to see the
     * difference. The texture is solid, and 2x2 with two mip levels so that no
     * assertion depends on which level the sampler picks.</p>
     *
     * @param halfEdge half the edge length
     * @param bakedColour packed RGBA8888 baked into every vertex — what the
     *     quad would paint if it lost its texture
     * @param textureColour packed RGBA8888 filling every texel of every level
     * @return the file bytes, ready for {@code ModelFormat.read}
     */
    static byte[] texturedSquare(final float halfEdge, final int bakedColour,
        final int textureColour)
    {
        final float[] corners =
        {
            -halfEdge, -halfEdge,
            halfEdge, -halfEdge,
            halfEdge, halfEdge,
            -halfEdge, halfEdge,
        };
        final float[] uv = {0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f};

        final int[] vertices = new int[4 * VERTEX_SLOTS];
        for (int corner = 0; corner < 4; corner++)
        {
            final int at = corner * VERTEX_SLOTS;
            vertices[at] = ModelFileFixture.bits(corners[corner * 2]);
            vertices[at + 1] = ModelFileFixture.bits(corners[corner * 2 + 1]);
            vertices[at + 2] = ModelFileFixture.bits(0.0f);
            vertices[at + 3] = ModelFileFixture.bits(uv[corner * 2]);
            vertices[at + 4] = ModelFileFixture.bits(uv[corner * 2 + 1]);
            vertices[at + 5] = bakedColour;
        }

        // width, height, levelCount, first texel — then 2x2 plus its 1x1 mip.
        return ModelFileFixture.build(vertices, new int[] {0, 1, 2, 0, 2, 3},
            new int[] {0, 6, 0, 0}, new int[] {2, 2, 2, 0},
            new int[]
            {
                textureColour, textureColour, textureColour, textureColour, textureColour,
            },
            new float[] {-halfEdge, -halfEdge, 0.0f, halfEdge, halfEdge, 0.0f});
    }
}
