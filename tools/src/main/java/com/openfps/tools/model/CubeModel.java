/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.model;

import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.engine.render.adapter.Rgba;

/**
 * A hand-built textured cube in {@link ModelFormat}, for verifying the
 * renderer without any third-party asset.
 *
 * Build-time only — this type is never on a runtime classpath.
 *
 * <h2>Why a cube specifically</h2>
 *
 * <p>A cube is the cheapest <b>closed</b> mesh, and closedness is the whole
 * point: with correct backface culling no interior face is ever visible, so
 * "the model renders inside-out" — the failure mode
 * {@code render/README.md} § 7 warns is indistinguishable from a plausible
 * model — becomes a visible, assertable difference. Each of the six faces gets
 * its own texture in a distinct hue, so which faces are on screen can be read
 * off the pixels rather than guessed at from a silhouette.</p>
 *
 * <h2>Winding</h2>
 *
 * <p>Every face is wound <b>counter-clockwise as seen from outside</b>, which
 * is the glTF 2.0 front-face rule ("the winding order determines front- versus
 * back-facing... counter-clockwise is front-facing"). Equivalently
 * {@code (v1 - v0) x (v2 - v0)} points along the outward normal, which is how
 * the face table below is checked. That makes this cube a faithful stand-in
 * for a converted Kenney model as far as the cull test is concerned.</p>
 *
 * <h2>Texture coordinates</h2>
 *
 * <p>glTF's convention: origin top-left, {@code v} increasing downward. The
 * four corners of each face carry {@code (0,1), (1,1), (1,0), (0,0)} in the
 * same order the positions are listed, so the checker pattern is upright on
 * every face and a rotated or mirrored sampler shows up as a visibly wrong
 * corner marker.</p>
 */
public final class CubeModel
{
    /** Edge length of each face texture, in texels. A power of two, per the sampler's mask wrap. */
    public static final int TEXTURE_EDGE = 32;

    /** Checker square size in texels. */
    public static final int CHECKER = 8;

    /** Half the cube's edge length; the model spans -1 to +1 on every axis. */
    public static final float HALF_EDGE = 1.0f;

    /** Faces on a cube. */
    public static final int FACE_COUNT = 6;

    /** Corners per face. */
    private static final int FACE_CORNERS = 4;

    /**
     * Face corner positions in units of {@link #HALF_EDGE}, six faces of four
     * corners of three components, wound counter-clockwise seen from outside.
     * Verified face by face against {@code (v1 - v0) x (v2 - v0)}.
     */
    private static final float[] FACES =
    {
        // +x
        1, -1, 1,   1, -1, -1,   1, 1, -1,   1, 1, 1,
        // -x
        -1, -1, -1,  -1, -1, 1,  -1, 1, 1,  -1, 1, -1,
        // +y
        -1, 1, 1,   1, 1, 1,   1, 1, -1,  -1, 1, -1,
        // -y
        -1, -1, -1,   1, -1, -1,   1, -1, 1,  -1, -1, 1,
        // +z
        -1, -1, 1,   1, -1, 1,   1, 1, 1,  -1, 1, 1,
        // -z
        1, -1, -1,  -1, -1, -1,  -1, 1, -1,   1, 1, -1,
    };

    /** Texture coordinates for the four corners, in the order {@link #FACES} lists them. */
    private static final float[] FACE_UV = {0, 1, 1, 1, 1, 0, 0, 0};

    /** The light square of each face's checker, one per face: +x, -x, +y, -y, +z, -z. */
    private static final int[] FACE_LIGHT =
    {
        Rgba.pack(230, 70, 70, 255),
        Rgba.pack(70, 220, 220, 255),
        Rgba.pack(90, 220, 90, 255),
        Rgba.pack(220, 80, 220, 255),
        Rgba.pack(90, 140, 240, 255),
        Rgba.pack(240, 210, 80, 255),
    };

    /** How much darker the alternate checker square is, in eighths. */
    private static final int DARK_NUMERATOR = 3;

    /** Denominator of {@link #DARK_NUMERATOR}. */
    private static final int DARK_DENOMINATOR = 8;

    /** Size of the orientation marker in the face's (0,0) texel corner, in texels. */
    private static final int MARKER = 6;

    /** The orientation marker's colour — white, so it reads on every hue. */
    private static final int MARKER_COLOR = Rgba.pack(255, 255, 255, 255);

    private CubeModel()
    {
        // model factory
    }

    /**
     * Builds the cube as a complete {@link ModelFormat} file image.
     *
     * @return the {@code .ofm} bytes, ready for {@code ModelFormat.read}
     */
    public static byte[] build()
    {
        final ModelBuilder builder = new ModelBuilder("cube");

        for (int face = 0; face < FACE_COUNT; face++)
        {
            final int texture = builder.addTexture("cube-face-" + face, TEXTURE_EDGE,
                TEXTURE_EDGE, MipGenerator.generate(TEXTURE_EDGE, TEXTURE_EDGE, faceTexels(face)));

            builder.beginSubmesh(texture);

            addFace(builder, face);

            builder.endSubmesh();
        }

        return builder.toBytes();
    }

    // One face: four vertices, two triangles fanned from corner 0, which
    // preserves the counter-clockwise winding the corner order carries.
    private static void addFace(final ModelBuilder builder, final int face)
    {
        final int base = face * FACE_CORNERS * 3;

        // MUTABLE local — the index of this face's first vertex.
        int first = 0;

        for (int corner = 0; corner < FACE_CORNERS; corner++)
        {
            final int at = base + corner * 3;

            final int index = builder.addVertex(
                FACES[at] * HALF_EDGE, FACES[at + 1] * HALF_EDGE, FACES[at + 2] * HALF_EDGE,
                FACE_UV[corner * 2], FACE_UV[corner * 2 + 1], FACE_LIGHT[face]);

            if (corner == 0)
            {
                first = index;
            }
        }

        builder.addTriangle(first, first + 1, first + 2);

        builder.addTriangle(first, first + 2, first + 3);
    }

    /**
     * Builds one face's level-0 texels: a two-tone checker in the face's hue,
     * with a white marker in the {@code (0, 0)} corner.
     *
     * <p>The marker is what turns "the texture looks fine" into an actual
     * check. A sampler that flipped {@code v}, or a converter that mirrored the
     * UVs, still produces a perfectly plausible checkerboard; it cannot produce
     * the marker in the right corner.</p>
     *
     * @param face face index in {@code [0, FACE_COUNT)}
     * @return {@link #TEXTURE_EDGE} squared RGBA8888 texels, row-major
     */
    public static int[] faceTexels(final int face)
    {
        final int light = FACE_LIGHT[face];

        final int dark = darken(light);

        final int[] texels = new int[TEXTURE_EDGE * TEXTURE_EDGE];

        for (int y = 0; y < TEXTURE_EDGE; y++)
        {
            for (int x = 0; x < TEXTURE_EDGE; x++)
            {
                texels[y * TEXTURE_EDGE + x] = texelAt(x, y, light, dark);
            }
        }

        return texels;
    }

    private static int texelAt(final int x, final int y, final int light, final int dark)
    {
        if (x < MARKER && y < MARKER)
        {
            return MARKER_COLOR;
        }

        if (((x / CHECKER) + (y / CHECKER)) % 2 == 0)
        {
            return light;
        }

        return dark;
    }

    // Scales every colour channel down, leaving alpha alone.
    private static int darken(final int rgba)
    {
        return Rgba.pack(
            Rgba.red(rgba) * DARK_NUMERATOR / DARK_DENOMINATOR,
            Rgba.green(rgba) * DARK_NUMERATOR / DARK_DENOMINATOR,
            Rgba.blue(rgba) * DARK_NUMERATOR / DARK_DENOMINATOR,
            Rgba.alpha(rgba));
    }
}
