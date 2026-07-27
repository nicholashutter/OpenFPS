/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.tools.AssetBudgetException;

/**
 * Accumulates converted geometry and writes a {@link ModelFormat} file image.
 *
 * Build-time only — this type is never on a runtime classpath.
 *
 * <h2>Ownership</h2>
 *
 * {@code ModelFormat} owns the layout, the version field and the reader, and
 * explicitly does not produce the format; this class is the other half of that
 * split. It writes against {@code ModelFormat}'s own constants rather than
 * repeating the byte offsets, so the two cannot drift: a change to the stride
 * or the header size is a compile-time change here, not a silent mismatch that
 * surfaces as garbage geometry.
 *
 * <h2>Where the budget is enforced</h2>
 *
 * Texture budgets are checked at {@link #addTexture} — the moment the offending
 * image is known by name. The triangle budget is checked at {@link #toBytes},
 * not per triangle, so the message can report the model's true total rather
 * than the value at which it happened to cross the line. Both throw
 * {@link AssetBudgetException}, which fails the Gradle build.
 *
 * <h2>Mutability</h2>
 *
 * This is a builder, so it is mutable by construction — the one place in the
 * codebase where {@code STYLE.md} § 3.1's final-fields rule genuinely cannot
 * apply. Growable primitive arrays are used rather than boxed collections;
 * every mutable field carries the required marker.
 */
public final class ModelBuilder
{
    /** Initial slot capacity for each growable array. */
    private static final int INITIAL_CAPACITY = 256;

    /** Number of floats in the bounding box: min xyz then max xyz. */
    private static final int BOUNDS_FLOATS = 6;

    /** Name of the asset being built, used in every budget message. */
    private final String source;

    /** Interleaved vertex slots. MUTABLE: grown as vertices are added. */
    private int[] vertexData = new int[INITIAL_CAPACITY * ModelFormat.VERTEX_STRIDE_INTS];

    /** Slots used in {@link #vertexData}. MUTABLE. */
    private int vertexSlots;

    /** Triangle indices. MUTABLE: grown as triangles are added. */
    private int[] indices = new int[INITIAL_CAPACITY];

    /** Entries used in {@link #indices}. MUTABLE. */
    private int indexSlots;

    /** Submesh records. MUTABLE: grown as submeshes are closed. */
    private int[] submeshes = new int[INITIAL_CAPACITY * ModelFormat.SUBMESH_STRIDE_INTS];

    /** Slots used in {@link #submeshes}. MUTABLE. */
    private int submeshSlots;

    /** Texture records. MUTABLE: grown as textures are added. */
    private int[] textures = new int[INITIAL_CAPACITY * ModelFormat.TEXTURE_STRIDE_INTS];

    /** Slots used in {@link #textures}. MUTABLE. */
    private int textureSlots;

    /** The shared texel blob, every texture's every level. MUTABLE. */
    private int[] texels = new int[INITIAL_CAPACITY];

    /** Entries used in {@link #texels}. MUTABLE. */
    private int texelSlots;

    /** Model-space bounding box, min xyz then max xyz. MUTABLE: widened per vertex. */
    private final float[] bounds = new float[BOUNDS_FLOATS];

    /** Index at which the open submesh began, or -1 when none is open. MUTABLE. */
    private int openSubmeshFirstIndex = -1;

    /** Texture the open submesh samples. MUTABLE. */
    private int openSubmeshTexture = ModelFormat.NO_TEXTURE;

    /**
     * Creates a builder for one model.
     *
     * @param source name of the asset, quoted verbatim in budget failures
     */
    public ModelBuilder(final String source)
    {
        this.source = source;
        Arrays.fill(bounds, 0, 3, Float.POSITIVE_INFINITY);
        Arrays.fill(bounds, 3, BOUNDS_FLOATS, Float.NEGATIVE_INFINITY);
    }

    /**
     * Appends one vertex and returns its index.
     *
     * @param x model-space x
     * @param y model-space y
     * @param z model-space z
     * @param u texture coordinate u, glTF convention: origin top-left
     * @param v texture coordinate v, increasing downward
     * @param colour baked vertex colour, packed RGBA8888
     * @return the new vertex's index
     */
    public int addVertex(final float x, final float y, final float z, final float u,
        final float v, final int colour)
    {
        vertexData = grow(vertexData, vertexSlots + ModelFormat.VERTEX_STRIDE_INTS);
        vertexData[vertexSlots + ModelFormat.VERTEX_POSITION_X] = Float.floatToRawIntBits(x);
        vertexData[vertexSlots + ModelFormat.VERTEX_POSITION_Y] = Float.floatToRawIntBits(y);
        vertexData[vertexSlots + ModelFormat.VERTEX_POSITION_Z] = Float.floatToRawIntBits(z);
        vertexData[vertexSlots + ModelFormat.VERTEX_TEXCOORD_U] = Float.floatToRawIntBits(u);
        vertexData[vertexSlots + ModelFormat.VERTEX_TEXCOORD_V] = Float.floatToRawIntBits(v);
        vertexData[vertexSlots + ModelFormat.VERTEX_COLOUR] = colour;
        vertexSlots += ModelFormat.VERTEX_STRIDE_INTS;

        widenBounds(x, y, z);
        return (vertexSlots / ModelFormat.VERTEX_STRIDE_INTS) - 1;
    }

    /**
     * Appends one triangle.
     *
     * @param a first vertex index
     * @param b second vertex index
     * @param c third vertex index
     */
    public void addTriangle(final int a, final int b, final int c)
    {
        indices = grow(indices, indexSlots + ModelFormat.INDICES_PER_TRIANGLE);
        indices[indexSlots] = a;
        indices[indexSlots + 1] = b;
        indices[indexSlots + 2] = c;
        indexSlots += ModelFormat.INDICES_PER_TRIANGLE;
    }

    /**
     * Opens a submesh covering every triangle added until {@link #endSubmesh}.
     *
     * @param textureIndex texture the submesh samples, or {@link ModelFormat#NO_TEXTURE}
     */
    public void beginSubmesh(final int textureIndex)
    {
        openSubmeshFirstIndex = indexSlots;
        openSubmeshTexture = textureIndex;
    }

    /**
     * Closes the open submesh. A submesh that covered no triangles is dropped
     * rather than written, because an empty draw range is pure per-frame cost.
     */
    public void endSubmesh()
    {
        if (openSubmeshFirstIndex < 0)
        {
            return;
        }
        final int span = indexSlots - openSubmeshFirstIndex;
        if (span > 0)
        {
            submeshes = grow(submeshes, submeshSlots + ModelFormat.SUBMESH_STRIDE_INTS);
            submeshes[submeshSlots] = openSubmeshFirstIndex;
            submeshes[submeshSlots + 1] = span;
            submeshes[submeshSlots + 2] = openSubmeshTexture;
            submeshes[submeshSlots + 3] = 0;
            submeshSlots += ModelFormat.SUBMESH_STRIDE_INTS;
        }
        openSubmeshFirstIndex = -1;
        openSubmeshTexture = ModelFormat.NO_TEXTURE;
    }

    /**
     * Appends a texture and its pre-generated mip chain, enforcing the
     * {@code docs/ASSETS.md} § 5 texture budget.
     *
     * @param name image name, quoted verbatim in budget failures
     * @param width level 0 width in texels
     * @param height level 0 height in texels
     * @param levels every mip level, level 0 first, RGBA8888 packed, row-major
     * @return the new texture's index
     * @throws AssetBudgetException if a dimension exceeds
     *     {@link ModelFormat#MAX_TEXTURE_DIMENSION} or is not a power of two
     */
    public int addTexture(final String name, final int width, final int height,
        final int[][] levels)
    {
        checkTextureDimensions(name, width, height);
        checkLevels(name, width, height, levels);

        textures = grow(textures, textureSlots + ModelFormat.TEXTURE_STRIDE_INTS);
        textures[textureSlots] = width;
        textures[textureSlots + 1] = height;
        textures[textureSlots + 2] = levels.length;
        textures[textureSlots + 3] = texelSlots;
        textureSlots += ModelFormat.TEXTURE_STRIDE_INTS;

        for (final int[] level : levels)
        {
            texels = grow(texels, texelSlots + level.length);
            System.arraycopy(level, 0, texels, texelSlots, level.length);
            texelSlots += level.length;
        }
        return (textureSlots / ModelFormat.TEXTURE_STRIDE_INTS) - 1;
    }

    /** Returns how many triangles have been added so far. */
    public int triangleCount()
    {
        return indexSlots / ModelFormat.INDICES_PER_TRIANGLE;
    }

    /** Returns how many vertices have been added so far. */
    public int vertexCount()
    {
        return vertexSlots / ModelFormat.VERTEX_STRIDE_INTS;
    }

    /** Returns how many textures have been added so far. */
    public int textureCount()
    {
        return textureSlots / ModelFormat.TEXTURE_STRIDE_INTS;
    }

    /**
     * Enforces the remaining budgets and serialises the complete file image.
     *
     * <p>Section order is header, vertices, indices, submeshes, textures,
     * texels — geometry first, the largest blob last. Every section holds
     * 4-byte elements and the header is 96 bytes, so all offsets come out
     * naturally aligned with no padding.</p>
     *
     * @return the file image, ready to write to disk and to hand to
     *     {@link ModelFormat#read}
     * @throws AssetBudgetException if the model exceeds
     *     {@link ModelFormat#MAX_TRIANGLES_PER_MODEL}
     */
    public byte[] toBytes()
    {
        checkTriangleBudget();

        final int vertexOffset = ModelFormat.HEADER_SIZE;
        final int indexOffset = vertexOffset + (vertexSlots * Integer.BYTES);
        final int submeshOffset = indexOffset + (indexSlots * Integer.BYTES);
        final int textureOffset = submeshOffset + (submeshSlots * Integer.BYTES);
        final int texelOffset = textureOffset + (textureSlots * Integer.BYTES);
        final int fileSize = texelOffset + (texelSlots * Integer.BYTES);

        final ByteBuffer out = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(ModelFormat.MAGIC);
        out.putShort((short) ModelFormat.VERSION_MAJOR);
        out.putShort((short) ModelFormat.VERSION_MINOR);
        out.putInt(ModelFormat.HEADER_SIZE);
        out.putInt(fileSize);
        out.putInt(vertexCount());
        out.putInt(vertexOffset);
        out.putInt(indexSlots);
        out.putInt(indexOffset);
        out.putInt(submeshSlots / ModelFormat.SUBMESH_STRIDE_INTS);
        out.putInt(submeshOffset);
        out.putInt(textureCount());
        out.putInt(textureOffset);
        out.putInt(texelSlots);
        out.putInt(texelOffset);
        putBounds(out);

        writeSection(out, vertexOffset, vertexData, vertexSlots);
        writeSection(out, indexOffset, indices, indexSlots);
        writeSection(out, submeshOffset, submeshes, submeshSlots);
        writeSection(out, textureOffset, textures, textureSlots);
        writeSection(out, texelOffset, texels, texelSlots);
        return out.array();
    }

    // Writes the bounding box, collapsing an empty model's infinities to zero.
    private void putBounds(final ByteBuffer out)
    {
        if (vertexSlots == 0)
        {
            for (int i = 0; i < BOUNDS_FLOATS; i++)
            {
                out.putFloat(0.0f);
            }
            return;
        }
        for (int i = 0; i < BOUNDS_FLOATS; i++)
        {
            out.putFloat(bounds[i]);
        }
    }

    // Bulk-copies one section into place at its declared offset.
    private static void writeSection(final ByteBuffer out, final int offset, final int[] data,
        final int length)
    {
        out.position(offset);
        out.asIntBuffer().put(data, 0, length);
    }

    // Widens the model-space bounding box to contain one more vertex.
    private void widenBounds(final float x, final float y, final float z)
    {
        bounds[0] = Math.min(bounds[0], x);
        bounds[1] = Math.min(bounds[1], y);
        bounds[2] = Math.min(bounds[2], z);
        bounds[3] = Math.max(bounds[3], x);
        bounds[4] = Math.max(bounds[4], y);
        bounds[5] = Math.max(bounds[5], z);
    }

    // The docs/ASSETS.md section 5 triangle cap. Reported as a total, by name.
    private void checkTriangleBudget()
    {
        final int triangles = triangleCount();
        if (triangles > ModelFormat.MAX_TRIANGLES_PER_MODEL)
        {
            throw new AssetBudgetException(source + ": " + triangles
                + " triangles exceeds the budget of " + ModelFormat.MAX_TRIANGLES_PER_MODEL
                + " triangles per model (docs/ASSETS.md section 5)."
                + " A 60 Hz 1080p scene affords roughly 10-20k triangles in total,"
                + " so this model alone would be most of a frame."
                + " Decimate it upstream or choose a lower-poly source asset;"
                + " the converter will not silently reduce it.");
        }
        if (vertexCount() > ModelFormat.MAX_VERTEX_COUNT)
        {
            throw new AssetBudgetException(source + ": " + vertexCount()
                + " vertices exceeds the format limit of " + ModelFormat.MAX_VERTEX_COUNT + ".");
        }
    }

    /**
     * Enforces the {@code docs/ASSETS.md} § 5 texture cap and the power-of-two
     * rule the sampler's mask-instead-of-modulo wrapping depends on.
     *
     * <p>Public because the converter must reject an over-budget image
     * <em>before</em> spending time generating its mip pyramid, and because
     * the failure should name the image rather than the level that happened to
     * be built when the check fired. {@link #addTexture} repeats the check, so
     * calling this first is an optimisation, not a precondition.</p>
     *
     * @param name image name, quoted verbatim in the failure
     * @param width level 0 width in texels
     * @param height level 0 height in texels
     * @throws AssetBudgetException if a dimension is over budget or not a
     *     power of two
     */
    public void checkTextureDimensions(final String name, final int width, final int height)
    {
        if (width > ModelFormat.MAX_TEXTURE_DIMENSION || height > ModelFormat.MAX_TEXTURE_DIMENSION)
        {
            throw new AssetBudgetException(source + ": texture '" + name + "' is " + width + "x"
                + height + ", over the budget of " + ModelFormat.MAX_TEXTURE_DIMENSION
                + " squared (docs/ASSETS.md section 5)."
                + " Cache locality dominates the span loop, which is what that cap protects."
                + " Downsample the source image; the converter will not do it silently.");
        }
        requirePowerOfTwo(name, width, "width");
        requirePowerOfTwo(name, height, "height");
    }

    // Level k must be exactly max(1, w >> k) by max(1, h >> k) — MipChain's rule.
    private void checkLevels(final String name, final int width, final int height,
        final int[][] levels)
    {
        if (levels == null || levels.length == 0)
        {
            throw new AssetBudgetException(source + ": texture '" + name
                + "' has no mip levels. Mipmaps are required, not optional"
                + " (docs/ASSETS.md section 5).");
        }
        for (int level = 0; level < levels.length; level++)
        {
            final int expected = Math.max(1, width >> level) * Math.max(1, height >> level);
            if (levels[level] == null || levels[level].length != expected)
            {
                throw new AssetBudgetException(source + ": texture '" + name + "' mip level "
                    + level + " must hold " + expected + " texels.");
            }
        }
    }

    // Power-of-two dimensions are what make the sampler's wrap a mask.
    private void requirePowerOfTwo(final String name, final int value, final String dimension)
    {
        if (value <= 0 || (value & (value - 1)) != 0)
        {
            throw new AssetBudgetException(source + ": texture '" + name + "' " + dimension
                + " is " + value + ", not a power of two."
                + " The sampler wraps with an AND mask rather than a modulo,"
                + " which only works for power-of-two dimensions.");
        }
    }

    // Returns an array with room for at least `needed` slots, doubling to amortise.
    private static int[] grow(final int[] array, final int needed)
    {
        if (needed <= array.length)
        {
            return array;
        }
        return Arrays.copyOf(array, Math.max(array.length * 2, needed));
    }
}
