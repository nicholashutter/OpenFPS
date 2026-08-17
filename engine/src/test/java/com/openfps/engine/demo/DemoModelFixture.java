/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Minimal but genuinely valid {@code .ofm} files for the demo's tests.
 *
 * <p>Hand-built rather than produced by the converter, for the same reason
 * {@code ModelFileFixture} is: the converter lives in the build-time
 * {@code :tools} module and {@code :engine} cannot reach it, which is the
 * isolation the project wants. These bytes come from nothing but this file.</p>
 *
 * <p>Two triangles, no textures, one submesh. That is the smallest thing
 * {@link com.openfps.engine.render.adapter.Scene} will accept — it requires at
 * least one triangle — and nothing these tests assert depends on the geometry
 * being anything in particular.</p>
 */
public final class DemoModelFixture
{
    /** File magic, {@code 'O'}, {@code 'F'}, {@code 'M'}, {@code 'D'} little-endian. */
    private static final int MAGIC = 0x444D464F;

    /** Size of the version 1.0 header. */
    private static final int HEADER_BYTES = 96;

    /** Ints per vertex: x, y, z, u, v, colour. */
    private static final int VERTEX_INTS = 6;

    /** Marker for a submesh with no texture. */
    private static final int NO_TEXTURE = -1;

    /** Opaque white, so nothing modulates a colour these tests never look at. */
    private static final int COLOUR = 0xFFFFFFFF;

    private DemoModelFixture()
    {
        // fixture factory
    }

    /**
     * Writes a valid model file at a path, creating parent directories.
     *
     * @param path where to write
     * @throws IOException if the file cannot be written
     */
    public static void write(final Path path) throws IOException
    {
        if (path.getParent() != null)
        {
            Files.createDirectories(path.getParent());
        }

        Files.write(path, quad());
    }

    /**
     * Returns a valid two-triangle model spanning the unit square in y = 0.
     *
     * @return the {@code .ofm} bytes
     */
    public static byte[] quad()
    {
        final int[] vertices =
        {
            bits(0.0f), bits(0.0f), bits(0.0f), bits(0.0f), bits(0.0f), COLOUR,
            bits(1.0f), bits(0.0f), bits(0.0f), bits(1.0f), bits(0.0f), COLOUR,
            bits(1.0f), bits(0.0f), bits(1.0f), bits(1.0f), bits(1.0f), COLOUR,
            bits(0.0f), bits(0.0f), bits(1.0f), bits(0.0f), bits(1.0f), COLOUR,
        };

        final int[] indices = {0, 1, 2, 0, 2, 3};

        final int[] submeshes = {0, indices.length, NO_TEXTURE, 0};

        final float[] bounds = {0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f};

        return build(vertices, indices, submeshes, bounds);
    }

    // Lays every section out back to back after the header.
    private static byte[] build(final int[] vertices, final int[] indices,
        final int[] submeshes, final float[] bounds)
    {
        final int vertexOffset = HEADER_BYTES;

        final int indexOffset = vertexOffset + (vertices.length * Integer.BYTES);

        final int submeshOffset = indexOffset + (indices.length * Integer.BYTES);

        final int textureOffset = submeshOffset + (submeshes.length * Integer.BYTES);

        final int fileSize = textureOffset;

        final ByteBuffer out = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN);

        out.putInt(0, MAGIC);

        out.putShort(4, (short) 1);

        out.putShort(6, (short) 0);

        out.putInt(8, HEADER_BYTES);

        out.putInt(12, fileSize);

        out.putInt(16, vertices.length / VERTEX_INTS);

        out.putInt(20, vertexOffset);

        out.putInt(24, indices.length);

        out.putInt(28, indexOffset);

        out.putInt(32, submeshes.length / 4);

        out.putInt(36, submeshOffset);

        out.putInt(40, 0);

        out.putInt(44, textureOffset);

        out.putInt(48, 0);

        out.putInt(52, textureOffset);

        for (int i = 0; i < bounds.length; i++)
        {
            out.putFloat(56 + (i * Float.BYTES), bounds[i]);
        }

        putSection(out, vertexOffset, vertices);

        putSection(out, indexOffset, indices);

        putSection(out, submeshOffset, submeshes);

        return out.array();
    }

    private static void putSection(final ByteBuffer out, final int offset, final int[] data)
    {
        for (int i = 0; i < data.length; i++)
        {
            out.putInt(offset + (i * Integer.BYTES), data[i]);
        }
    }

    private static int bits(final float value)
    {
        return Float.floatToRawIntBits(value);
    }
}
