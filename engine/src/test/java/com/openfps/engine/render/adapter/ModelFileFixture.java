/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Hand-built {@link ModelFormat} file images for the reader's tests.
 *
 * <p>Every offset here is written as a <b>literal</b>, not read from
 * {@code ModelFormat}'s constants. That is the point: if the reader and its
 * fixture both derived the layout from the same symbols, a change to either
 * would move both together and the test would keep passing while the format
 * silently changed underneath every already-converted asset. Writing the
 * offsets out by hand makes the file layout itself the thing under test.</p>
 *
 * <p>The converter lives in the build-time {@code :tools} module and cannot be
 * reached from here, which is exactly the isolation the project wants. Round
 * tripping against the real writer is covered there; this file covers the
 * reader against bytes nothing but a text editor produced.</p>
 */
final class ModelFileFixture
{
    /** File magic, {@code 'O'}, {@code 'F'}, {@code 'M'}, {@code 'D'} little-endian. */
    static final int MAGIC = 0x444D464F;

    /** Size of the version 1.0 header. */
    static final int HEADER_BYTES = 96;

    /** Byte offset of the magic. */
    static final int AT_MAGIC = 0;

    /** Byte offset of the major version. */
    static final int AT_VERSION_MAJOR = 4;

    /** Byte offset of the minor version. */
    static final int AT_VERSION_MINOR = 6;

    /** Byte offset of the header size. */
    static final int AT_HEADER_SIZE = 8;

    /** Byte offset of the declared file size. */
    static final int AT_FILE_SIZE = 12;

    /** Byte offset of the vertex count. */
    static final int AT_VERTEX_COUNT = 16;

    /** Byte offset of the vertex section offset. */
    static final int AT_VERTEX_OFFSET = 20;

    /** Byte offset of the index count. */
    static final int AT_INDEX_COUNT = 24;

    /** Byte offset of the index section offset. */
    static final int AT_INDEX_OFFSET = 28;

    /** Byte offset of the submesh count. */
    static final int AT_SUBMESH_COUNT = 32;

    /** Byte offset of the submesh section offset. */
    static final int AT_SUBMESH_OFFSET = 36;

    /** Byte offset of the texture count. */
    static final int AT_TEXTURE_COUNT = 40;

    /** Byte offset of the texture section offset. */
    static final int AT_TEXTURE_OFFSET = 44;

    /** Byte offset of the texel count. */
    static final int AT_TEXEL_COUNT = 48;

    /** Byte offset of the texel section offset. */
    static final int AT_TEXEL_OFFSET = 52;

    /** Byte offset of the first bounding-box float. */
    static final int AT_BOUNDS = 56;

    private ModelFileFixture()
    {
        // fixture factory
    }

    // The canonical model: a unit quad as two triangles, split across two
    // submeshes so the submesh table is exercised, with one 2x2 texture
    // carrying a two-level mip chain.
    static byte[] valid()
    {
        return build(quadVertices(), new int[] {0, 1, 2, 0, 2, 3},
            new int[] {0, 3, 0, 0, 3, 3, ModelFormat.NO_TEXTURE, 0},
            new int[] {2, 2, 2, 0},
            new int[] {0xFF0000FF, 0x00FF00FF, 0x0000FFFF, 0xFFFFFFFF, 0x7F7F7FFF},
            new float[] {0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f});
    }

    // A structurally valid model with nothing in it. Every section offset
    // collapses onto the end of the header, which the reader must accept.
    static byte[] empty()
    {
        return build(new int[0], new int[0], new int[0], new int[0], new int[0], new float[6]);
    }

    // Assembles a file image from raw section contents, laying out every
    // section back to back after the header.
    static byte[] build(final int[] vertices, final int[] indices, final int[] submeshes,
        final int[] textures, final int[] texels, final float[] bounds)
    {
        final int vertexOffset = HEADER_BYTES;
        final int indexOffset = vertexOffset + (vertices.length * Integer.BYTES);
        final int submeshOffset = indexOffset + (indices.length * Integer.BYTES);
        final int textureOffset = submeshOffset + (submeshes.length * Integer.BYTES);
        final int texelOffset = textureOffset + (textures.length * Integer.BYTES);
        final int fileSize = texelOffset + (texels.length * Integer.BYTES);

        final ByteBuffer out = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(AT_MAGIC, MAGIC);
        out.putShort(AT_VERSION_MAJOR, (short) 1);
        out.putShort(AT_VERSION_MINOR, (short) 0);
        out.putInt(AT_HEADER_SIZE, HEADER_BYTES);
        out.putInt(AT_FILE_SIZE, fileSize);
        out.putInt(AT_VERTEX_COUNT, vertices.length / 6);
        out.putInt(AT_VERTEX_OFFSET, vertexOffset);
        out.putInt(AT_INDEX_COUNT, indices.length);
        out.putInt(AT_INDEX_OFFSET, indexOffset);
        out.putInt(AT_SUBMESH_COUNT, submeshes.length / 4);
        out.putInt(AT_SUBMESH_OFFSET, submeshOffset);
        out.putInt(AT_TEXTURE_COUNT, textures.length / 4);
        out.putInt(AT_TEXTURE_OFFSET, textureOffset);
        out.putInt(AT_TEXEL_COUNT, texels.length);
        out.putInt(AT_TEXEL_OFFSET, texelOffset);
        for (int i = 0; i < 6; i++)
        {
            out.putFloat(AT_BOUNDS + (i * Float.BYTES), bounds[i]);
        }

        putSection(out, vertexOffset, vertices);
        putSection(out, indexOffset, indices);
        putSection(out, submeshOffset, submeshes);
        putSection(out, textureOffset, textures);
        putSection(out, texelOffset, texels);
        return out.array();
    }

    // Four vertices of a unit quad in the z = 0 plane, interleaved
    // x, y, z, u, v, colour with the floats stored as raw bits.
    static int[] quadVertices()
    {
        return new int[]
        {
            bits(0.0f), bits(0.0f), bits(0.0f), bits(0.0f), bits(0.0f), 0xFF0000FF,
            bits(1.0f), bits(0.0f), bits(0.0f), bits(1.0f), bits(0.0f), 0x00FF00FF,
            bits(1.0f), bits(1.0f), bits(0.0f), bits(1.0f), bits(1.0f), 0x0000FFFF,
            bits(0.0f), bits(1.0f), bits(0.0f), bits(0.0f), bits(1.0f), 0xFFFFFFFF,
        };
    }

    // Reinterprets a float as the int the format stores.
    static int bits(final float value)
    {
        return Float.floatToRawIntBits(value);
    }

    // Overwrites a little-endian 32-bit field in place.
    static void putInt(final byte[] data, final int offset, final int value)
    {
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
    }

    // Overwrites a little-endian 16-bit field in place.
    static void putShort(final byte[] data, final int offset, final int value)
    {
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, (short) value);
    }

    // Reads a little-endian 32-bit field.
    static int getInt(final byte[] data, final int offset)
    {
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(offset);
    }

    // Copies one section into place at an absolute byte offset.
    private static void putSection(final ByteBuffer out, final int offset, final int[] data)
    {
        for (int i = 0; i < data.length; i++)
        {
            out.putInt(offset + (i * Integer.BYTES), data[i]);
        }
    }
}
