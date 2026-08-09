/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The flat binary runtime model format: layout, versioning, and the reader.
 *
 * Render adapter — must not import from core engine packages.
 *
 * <h2>Why a second format exists at all</h2>
 *
 * glTF is <strong>not</strong> parsed at runtime ({@code docs/ASSETS.md} § 4).
 * That is architectural, not incidental: a software rasterizer's scarcest
 * resource is per-frame CPU and its cheapest is build-time CPU, so
 * triangulation, texture decode, mip generation, material flattening and
 * bounds computation all move offline into {@code GltfConverter}, which runs
 * on the build classpath and is never shipped. What reaches the engine is this
 * file: a fixed-size header, explicit section offsets, and arrays laid out
 * exactly as the renderer consumes them.
 *
 * <h2>Near-zero parsing</h2>
 *
 * Loading is a header decode, a fixed set of range checks, and four bulk
 * {@code IntBuffer.get} copies. There is no per-element decoding — no
 * varint, no string table, no attribute unpacking. The one per-element pass
 * that does happen is a bounds <em>compare</em> over the index array, which
 * is deliberate: an out-of-range index caught here is one clear message,
 * and the same index not caught here is an array store exception from inside
 * a worker thread three pipeline stages later.
 *
 * <h2>Byte layout</h2>
 *
 * Everything is <strong>little-endian</strong>, so a converted model is
 * byte-identical on every platform the engine targets. Every section holds
 * 4-byte elements and begins at a 4-byte-aligned offset; the reader enforces
 * both.
 *
 * <pre>
 * Header — 96 bytes
 *   0  u32  magic          'O','F','M','D' in file order
 *   4  u16  versionMajor   breaking layout changes
 *   6  u16  versionMinor   additive changes only
 *   8  u32  headerSize     96 in version 1.0; lets a later minor grow the header
 *  12  u32  fileSize       total file length; must equal the buffer length
 *  16  u32  vertexCount
 *  20  u32  vertexOffset
 *  24  u32  indexCount
 *  28  u32  indexOffset
 *  32  u32  submeshCount
 *  36  u32  submeshOffset
 *  40  u32  textureCount
 *  44  u32  textureOffset
 *  48  u32  texelCount     total texels across every texture and mip level
 *  52  u32  texelOffset
 *  56  f32  minX, minY, minZ, maxX, maxY, maxZ   (through offset 79)
 *  80  ---  16 reserved bytes, ignored by this version
 *
 * Vertex — 24 bytes, interleaved
 *   0  f32  x
 *   4  f32  y
 *   8  f32  z
 *  12  f32  u
 *  16  f32  v
 *  20  u32  colour         baked vertex colour, RGBA8888, see {@link Rgba}
 *
 * Index — u32, three per triangle
 *
 * Submesh — 16 bytes
 *   0  u32  firstIndex
 *   4  u32  indexCount
 *   8  i32  textureIndex   or {@link #NO_TEXTURE}
 *  12  u32  flags          reserved, zero
 *
 * Texture — 16 bytes
 *   0  u32  width          level 0, power of two
 *   4  u32  height         level 0, power of two
 *   8  u32  levelCount     mip levels present, at least one
 *  12  u32  firstTexel     index into the texel array
 *
 * Texel blob — u32 RGBA8888 each; per texture, levels concatenated level 0
 * first, each level row-major. This is exactly {@link MipChain}'s own layout.
 * </pre>
 *
 * <h2>Section ordering</h2>
 *
 * Header, vertices, indices, submeshes, textures, texels. Geometry comes
 * first because it is what a partial or streaming read wants first; the
 * fixed-size tables are tiny and therefore land within the first few
 * kilobytes; the texel blob is by far the largest section and goes last so
 * that everything needed to describe the model is contiguous ahead of it.
 * Header size 96 and vertex stride 24 are both multiples of 8, so the index
 * section is 8-byte aligned as a side effect; only a section following an odd
 * index count relies on the 4-byte guarantee.
 *
 * <h2>Interleaved, and held as {@code int[}{@code ]}</h2>
 *
 * Position, UV and colour are interleaved so that gathering one vertex's
 * attributes touches one 24-byte run rather than three distant arrays. Java
 * cannot bulk-load a mixed float/int record, so the whole block is copied in
 * one {@code IntBuffer.get} into an {@code int[}{@code ]} and the float
 * accessors reinterpret through {@link Float#intBitsToFloat}, which is an
 * intrinsic and compiles to a register move. The alternative — three planar
 * arrays — would bulk-load just as cheaply but costs three cache misses per
 * vertex in the attribute gather, and the vertex data is read far more often
 * than it is loaded.
 *
 * <h2>Versioning</h2>
 *
 * A major version this build does not know is refused outright, by name and
 * number. That is much cheaper than debugging a mis-parsed vertex array.
 * A higher <em>minor</em> version is accepted: minor bumps are additive only
 * and {@code headerSize} says how far the header now runs, so an older reader
 * skips what it does not recognise.
 *
 * <h2>Budgets are declared here and enforced by the converter</h2>
 *
 * {@link #MAX_TRIANGLES_PER_MODEL} and {@link #MAX_TEXTURE_DIMENSION} are the
 * {@code docs/ASSETS.md} § 5 caps. They live here because the format and the
 * converter must agree on one number, and the converter compiles against this
 * class. The reader deliberately does <strong>not</strong> enforce them: a
 * model over budget is a build failure to be fixed at import time, not a
 * corrupt file, and the reader's job is only to refuse what it cannot safely
 * address. Its own caps ({@link #MAX_VERTEX_COUNT} and friends) exist so a
 * corrupt header cannot ask for an absurd allocation.
 *
 * <h2>Sources</h2>
 *
 * The upstream format this is converted from is specified by
 * <a href="https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html">the
 * Khronos glTF 2.0 specification</a>. Nothing in this file derives from any
 * GPL renderer or editor implementation — see {@code docs/ASSETS.md} § 8.
 */
public final class ModelFormat
{
    /** File magic: the bytes {@code 'O'}, {@code 'F'}, {@code 'M'}, {@code 'D'} read little-endian. */
    public static final int MAGIC = 0x444D464F;

    /** Major version this build reads. A file with any other major version is refused. */
    public static final int VERSION_MAJOR = 1;

    /** Minor version this build writes. Higher minors are accepted on read. */
    public static final int VERSION_MINOR = 0;

    /** Size of the version 1.0 header in bytes. */
    public static final int HEADER_SIZE = 96;

    /** Number of {@code int} slots one vertex occupies in {@link #vertexData()}. */
    public static final int VERTEX_STRIDE_INTS = 6;

    /** Size of one vertex record in bytes. */
    public static final int VERTEX_STRIDE_BYTES = VERTEX_STRIDE_INTS * Integer.BYTES;

    /** Slot offset of the x coordinate within a vertex record. */
    public static final int VERTEX_POSITION_X = 0;

    /** Slot offset of the y coordinate within a vertex record. */
    public static final int VERTEX_POSITION_Y = 1;

    /** Slot offset of the z coordinate within a vertex record. */
    public static final int VERTEX_POSITION_Z = 2;

    /** Slot offset of the u texture coordinate within a vertex record. */
    public static final int VERTEX_TEXCOORD_U = 3;

    /** Slot offset of the v texture coordinate within a vertex record. */
    public static final int VERTEX_TEXCOORD_V = 4;

    /** Slot offset of the packed RGBA8888 vertex colour within a vertex record. */
    public static final int VERTEX_COLOUR = 5;

    /** Number of {@code int} slots one submesh record occupies. */
    public static final int SUBMESH_STRIDE_INTS = 4;

    /** Number of {@code int} slots one texture record occupies. */
    public static final int TEXTURE_STRIDE_INTS = 4;

    /** Texture index meaning "this submesh has no texture"; its vertex colour stands alone. */
    public static final int NO_TEXTURE = -1;

    /** Indices per triangle. The format stores triangle lists only — strips and fans are expanded offline. */
    public static final int INDICES_PER_TRIANGLE = 3;

    /**
     * Triangles per model, {@code docs/ASSETS.md} § 5. Tightened from 5,000 by
     * the § 2 benchmark: 60 Hz at 1080p affords roughly 10-20k triangles in the
     * whole scene, so a 5,000-triangle model meant two to four objects on
     * screen. Enforced by the converter, not by the reader.
     */
    public static final int MAX_TRIANGLES_PER_MODEL = 1500;

    /**
     * Largest texture edge accepted by the converter, {@code docs/ASSETS.md} § 5.
     * Kenney atlases are 256. Cache locality dominates the span loop, which is
     * what this cap is really protecting.
     */
    public static final int MAX_TEXTURE_DIMENSION = 512;

    /** Reader safety cap on vertex count, far above any legal asset. */
    public static final int MAX_VERTEX_COUNT = 1 << 20;

    /** Reader safety cap on index count, far above any legal asset. */
    public static final int MAX_INDEX_COUNT = 1 << 22;

    /** Reader safety cap on submesh count. */
    public static final int MAX_SUBMESH_COUNT = 1 << 12;

    /** Reader safety cap on texture count. */
    public static final int MAX_TEXTURE_COUNT = 1 << 10;

    /** Reader safety cap on total texels across every texture and level. */
    public static final int MAX_TEXEL_COUNT = 1 << 24;

    /**
     * Reader safety cap on a texture edge, matching {@link MipChain}'s own.
     * Well past {@link #MAX_TEXTURE_DIMENSION}; it exists so a corrupt header
     * cannot describe a chain whose flat texel index would overflow.
     */
    public static final int MAX_TEXTURE_EDGE = 1 << 16;

    // ---- Header field byte offsets -------------------------------------

    private static final int OFF_MAGIC = 0;
    private static final int OFF_VERSION_MAJOR = 4;
    private static final int OFF_VERSION_MINOR = 6;
    private static final int OFF_HEADER_SIZE = 8;
    private static final int OFF_FILE_SIZE = 12;
    private static final int OFF_VERTEX_COUNT = 16;
    private static final int OFF_VERTEX_OFFSET = 20;
    private static final int OFF_INDEX_COUNT = 24;
    private static final int OFF_INDEX_OFFSET = 28;
    private static final int OFF_SUBMESH_COUNT = 32;
    private static final int OFF_SUBMESH_OFFSET = 36;
    private static final int OFF_TEXTURE_COUNT = 40;
    private static final int OFF_TEXTURE_OFFSET = 44;
    private static final int OFF_TEXEL_COUNT = 48;
    private static final int OFF_TEXEL_OFFSET = 52;
    private static final int OFF_BOUNDS = 56;

    /** Number of floats in the axis-aligned bounding box: min xyz then max xyz. */
    private static final int BOUNDS_FLOATS = 6;

    /** Floats in one half of the bounding box, which is also the axis count. */
    private static final int BOUNDS_HALF = BOUNDS_FLOATS / 2;

    /** Slot offsets inside one submesh record. */
    private static final int SUBMESH_FIRST_INDEX = 0;
    private static final int SUBMESH_INDEX_COUNT = 1;
    private static final int SUBMESH_TEXTURE_INDEX = 2;

    /** Slot offsets inside one texture record. */
    private static final int TEXTURE_WIDTH = 0;
    private static final int TEXTURE_HEIGHT = 1;
    private static final int TEXTURE_LEVEL_COUNT = 2;
    private static final int TEXTURE_FIRST_TEXEL = 3;

    /** Mask widening a 16-bit header field read as a signed short. */
    private static final int U16_MASK = 0xFFFF;

    private final int versionMajor;
    private final int versionMinor;
    private final int[] vertexData;
    private final int[] indices;
    private final int[] submeshes;
    private final int[] textures;
    private final MipChain[] mipChains;
    private final float[] bounds;

    /**
     * Reads a model from a complete in-memory file image.
     *
     * <p>The array is not retained; every section is copied into its own
     * primitive array, so the caller may reuse the buffer immediately.</p>
     *
     * @param data the whole file, exactly as written by the converter
     * @return the parsed model
     * @throws ModelFormatException if the data is null, truncated, has the
     *     wrong magic, declares an unrecognised major version, or contains any
     *     offset, count or index that does not address inside the file
     */
    public static ModelFormat read(final byte[] data)
    {
        if (data == null)
        {
            throw new ModelFormatException("model data is null");
        }

        return new ModelFormat(data);
    }

    /**
     * Builds a model from geometry already in memory, with no file behind it.
     *
     * <h2>Why this exists beside a format whose whole point is being read</h2>
     *
     * <p>Some geometry has no author and never will. A tracer is a stretched
     * cube; a smoke puff is a box. Nobody is going to model those in Blender,
     * and routing them through the offline converter would mean shipping two
     * more {@code .ofm} files, a build step to produce them, and a staging
     * failure mode for art that is four lines of arithmetic. The converter
     * exists to move <i>expensive</i> work offline — triangulation, texture
     * decode, mip generation — and none of that applies to eight vertices.</p>
     *
     * <p><b>It is not a back door around validation.</b> Every invariant the
     * parser enforces on the sections it is given is enforced here, by the same
     * code: counts within the reader's safety caps, an index count that is a
     * multiple of {@link #INDICES_PER_TRIANGLE}, and every index addressing a
     * vertex that exists. The parser's remaining checks are all about a
     * <i>file</i> — magic, version, declared length, section offsets and
     * alignment — and there is no file here to get any of them wrong.</p>
     *
     * <p><b>No submeshes and no textures</b>, and that is the whole of the
     * material model rather than an omission. A model with an empty submesh
     * table has every triangle at {@link Rasterizer#NO_MATERIAL}, which is the
     * pre-existing path that shades a triangle flat from the baked colour of its
     * first vertex. So a caller colours geometry by colouring its vertices, and
     * the renderer needs no new case at all.</p>
     *
     * <p>The bounding box is <b>computed</b> from the positions rather than
     * declared, because a caller-supplied box is one more thing that can be
     * wrong and nothing downstream could tell. Both arrays are copied, so the
     * caller may reuse its scratch immediately.</p>
     *
     * @param vertexSlots interleaved vertex records, {@link #VERTEX_STRIDE_INTS}
     *     slots each, in the layout the {@code VERTEX_*} constants describe —
     *     see {@link #writeVertex}, which is how you fill it without
     *     reinterpreting floats by hand
     * @param triangleIndices three indices per triangle, each addressing a
     *     vertex in {@code vertexSlots}
     * @return the model
     * @throws ModelFormatException if either array is null, the vertex block is
     *     not a whole number of vertices, the index count is not a multiple of
     *     three, either count exceeds the reader's safety cap, or any index
     *     addresses outside the vertices present
     */
    public static ModelFormat ofGeometry(final int[] vertexSlots, final int[] triangleIndices)
    {
        if (vertexSlots == null)
        {
            throw new ModelFormatException("vertexSlots must not be null");
        }

        if (triangleIndices == null)
        {
            throw new ModelFormatException("triangleIndices must not be null");
        }

        if (vertexSlots.length % VERTEX_STRIDE_INTS != 0)
        {
            throw new ModelFormatException("vertex block is " + vertexSlots.length
                + " slots, not a multiple of the " + VERTEX_STRIDE_INTS + "-slot vertex stride");
        }

        final int vertexCount = vertexSlots.length / VERTEX_STRIDE_INTS;

        if (vertexCount > MAX_VERTEX_COUNT)
        {
            throw new ModelFormatException("vertexCount " + vertexCount
                + " is outside [0, " + MAX_VERTEX_COUNT + "]");
        }

        if (triangleIndices.length % INDICES_PER_TRIANGLE != 0)
        {
            throw new ModelFormatException("indexCount must be a multiple of "
                + INDICES_PER_TRIANGLE + ", got " + triangleIndices.length);
        }

        if (triangleIndices.length > MAX_INDEX_COUNT)
        {
            throw new ModelFormatException("indexCount " + triangleIndices.length
                + " is outside [0, " + MAX_INDEX_COUNT + "]");
        }

        return new ModelFormat(vertexSlots.clone(), triangleIndices.clone(), vertexCount);
    }

    /**
     * Writes one vertex into a block destined for {@link #ofGeometry}.
     *
     * <p>Exists so callers do not each grow their own copy of
     * {@link Float#floatToRawIntBits} and the slot arithmetic. The block holds
     * mixed float and int data in one {@code int[}{@code ]} — see the class
     * Javadoc on why — and hand-packing it is exactly the kind of duplication
     * that agrees with itself until one caller transposes u and v.</p>
     *
     * @param slots the vertex block being filled; must be long enough
     * @param vertex which vertex to write, from zero
     * @param x model-space x
     * @param y model-space y
     * @param z model-space z
     * @param u texture coordinate u; pass zero for untextured geometry
     * @param v texture coordinate v; pass zero for untextured geometry
     * @param colour baked vertex colour, packed RGBA8888 — see {@link Rgba}
     */
    public static void writeVertex(final int[] slots, final int vertex, final float x,
        final float y, final float z, final float u, final float v, final int colour)
    {
        final int base = vertex * VERTEX_STRIDE_INTS;

        slots[base + VERTEX_POSITION_X] = Float.floatToRawIntBits(x);

        slots[base + VERTEX_POSITION_Y] = Float.floatToRawIntBits(y);

        slots[base + VERTEX_POSITION_Z] = Float.floatToRawIntBits(z);

        slots[base + VERTEX_TEXCOORD_U] = Float.floatToRawIntBits(u);

        slots[base + VERTEX_TEXCOORD_V] = Float.floatToRawIntBits(v);

        slots[base + VERTEX_COLOUR] = colour;
    }

    // Takes ownership of two already-copied arrays and validates what a file
    // image would have had validated for it. Every field is assigned once, as
    // in the reading constructor.
    private ModelFormat(final int[] slots, final int[] triangleIndices, final int vertexCount)
    {
        this.versionMajor = VERSION_MAJOR;

        this.versionMinor = VERSION_MINOR;

        this.vertexData = slots;

        this.indices = triangleIndices;

        // Empty rather than absent: submeshCount() and textureCount() are
        // derived from these lengths, so an empty table IS "no submeshes" and
        // needs no null check anywhere downstream.
        this.submeshes = new int[0];

        this.textures = new int[0];

        this.mipChains = new MipChain[0];

        checkIndices(indices, vertexCount);

        this.bounds = boundsOf(slots, vertexCount);
    }

    // The axis-aligned bounding box of a vertex block, in the min-xyz then
    // max-xyz order the header stores it in.
    //
    // A model with no vertices gets an all-zero box rather than the empty
    // infinities, because the box is consumed by SoftwareRenderPort's orbit
    // camera, which subtracts min from max and would produce NaN from them.
    // Scene refuses an instance with no triangles anyway, so the degenerate box
    // is never framed in practice.
    private static float[] boundsOf(final int[] slots, final int vertexCount)
    {
        final float[] box = new float[BOUNDS_FLOATS];

        if (vertexCount == 0)
        {
            return box;
        }

        // `axis` is also the slot offset: VERTEX_POSITION_X, _Y and _Z are 0, 1
        // and 2, which is what lets one loop cover all three.
        for (int axis = 0; axis < BOUNDS_HALF; axis++)
        {
            // MUTABLE locals — the running extremes along one axis.
            float low = Float.intBitsToFloat(slots[axis]);

            float high = low;

            for (int vertex = 1; vertex < vertexCount; vertex++)
            {
                final float value =
                    Float.intBitsToFloat(slots[(vertex * VERTEX_STRIDE_INTS) + axis]);

                low = Math.min(low, value);

                high = Math.max(high, value);
            }

            box[axis] = low;

            box[axis + BOUNDS_HALF] = high;
        }

        return box;
    }

    // Reads and fully validates the file image. Every field is assigned once.
    private ModelFormat(final byte[] data)
    {
        final ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        checkHeader(buffer, data.length);

        this.versionMajor = buffer.getShort(OFF_VERSION_MAJOR) & U16_MASK;

        this.versionMinor = buffer.getShort(OFF_VERSION_MINOR) & U16_MASK;

        final int fileSize = data.length;

        final int headerSize = buffer.getInt(OFF_HEADER_SIZE);

        final int vertexCount = count(buffer, OFF_VERTEX_COUNT, MAX_VERTEX_COUNT, "vertexCount");

        final int indexCount = count(buffer, OFF_INDEX_COUNT, MAX_INDEX_COUNT, "indexCount");

        final int submeshCount = count(buffer, OFF_SUBMESH_COUNT, MAX_SUBMESH_COUNT, "submeshCount");

        final int textureCount = count(buffer, OFF_TEXTURE_COUNT, MAX_TEXTURE_COUNT, "textureCount");

        final int texelCount = count(buffer, OFF_TEXEL_COUNT, MAX_TEXEL_COUNT, "texelCount");

        if (indexCount % INDICES_PER_TRIANGLE != 0)
        {
            throw new ModelFormatException(
                "indexCount must be a multiple of " + INDICES_PER_TRIANGLE + ", got " + indexCount);
        }

        this.vertexData = readInts(buffer, OFF_VERTEX_OFFSET,
            (long) vertexCount * VERTEX_STRIDE_INTS, headerSize, fileSize, "vertex");

        this.indices = readInts(buffer, OFF_INDEX_OFFSET,
            indexCount, headerSize, fileSize, "index");

        this.submeshes = readInts(buffer, OFF_SUBMESH_OFFSET,
            (long) submeshCount * SUBMESH_STRIDE_INTS, headerSize, fileSize, "submesh");

        this.textures = readInts(buffer, OFF_TEXTURE_OFFSET,
            (long) textureCount * TEXTURE_STRIDE_INTS, headerSize, fileSize, "texture");

        final int[] texels = readInts(buffer, OFF_TEXEL_OFFSET,
            texelCount, headerSize, fileSize, "texel");

        this.bounds = new float[BOUNDS_FLOATS];

        for (int i = 0; i < BOUNDS_FLOATS; i++)
        {
            bounds[i] = buffer.getFloat(OFF_BOUNDS + (i * Float.BYTES));
        }

        checkIndices(indices, vertexCount);

        checkSubmeshes(submeshes, indexCount, textureCount);

        this.mipChains = buildMipChains(textures, texels);
    }

    // ---- Header -------------------------------------------------------

    // Rejects anything that is not a readable file of this major version.
    private static void checkHeader(final ByteBuffer buffer, final int length)
    {
        if (length < HEADER_SIZE)
        {
            throw new ModelFormatException(
                "model file is " + length + " bytes; the header alone needs " + HEADER_SIZE);
        }

        final int magic = buffer.getInt(OFF_MAGIC);

        if (magic != MAGIC)
        {
            throw new ModelFormatException("not a model file: magic 0x"
                + Integer.toHexString(magic) + ", expected 0x" + Integer.toHexString(MAGIC));
        }

        final int major = buffer.getShort(OFF_VERSION_MAJOR) & U16_MASK;

        final int minor = buffer.getShort(OFF_VERSION_MINOR) & U16_MASK;

        if (major != VERSION_MAJOR)
        {
            throw new ModelFormatException("unsupported model format version " + major + "."
                + minor + "; this build reads major version " + VERSION_MAJOR);
        }

        final int headerSize = buffer.getInt(OFF_HEADER_SIZE);

        if (headerSize < HEADER_SIZE || headerSize > length)
        {
            throw new ModelFormatException("headerSize " + headerSize + " is outside ["
                + HEADER_SIZE + ", " + length + "]");
        }

        final int declaredSize = buffer.getInt(OFF_FILE_SIZE);

        if (declaredSize != length)
        {
            throw new ModelFormatException("model file declares " + declaredSize
                + " bytes but is " + length + " — truncated or padded");
        }
    }

    // Reads an unsigned count field, rejecting negatives and absurd values alike.
    private static int count(final ByteBuffer buffer, final int fieldOffset, final int max,
        final String name)
    {
        final int value = buffer.getInt(fieldOffset);

        if (value < 0 || value > max)
        {
            throw new ModelFormatException(name + " " + Integer.toUnsignedString(value)
                + " is outside [0, " + max + "]");
        }

        return value;
    }

    // ---- Sections -----------------------------------------------------

    // Validates one section's offset and extent, then bulk-copies it out.
    private static int[] readInts(final ByteBuffer buffer, final int offsetField,
        final long elementCount, final int headerSize, final int fileSize, final String name)
    {
        final int offset = buffer.getInt(offsetField);

        if (offset < headerSize || offset > fileSize)
        {
            throw new ModelFormatException(name + " section offset " + Integer.toUnsignedString(offset)
                + " is outside [" + headerSize + ", " + fileSize + "]");
        }

        if (offset % Integer.BYTES != 0)
        {
            throw new ModelFormatException(name + " section offset " + offset
                + " is not " + Integer.BYTES + "-byte aligned");
        }

        final long end = offset + (elementCount * Integer.BYTES);

        if (end > fileSize)
        {
            throw new ModelFormatException(name + " section runs to byte " + end
                + " but the file is " + fileSize + " bytes");
        }

        final int[] out = new int[(int) elementCount];

        buffer.position(offset);

        buffer.asIntBuffer().get(out);

        return out;
    }

    // Every index must address a vertex. One compare each; no decoding.
    private static void checkIndices(final int[] index, final int vertexCount)
    {
        for (int i = 0; i < index.length; i++)
        {
            if (index[i] < 0 || index[i] >= vertexCount)
            {
                throw new ModelFormatException("index[" + i + "] = "
                    + Integer.toUnsignedString(index[i]) + " addresses outside the "
                    + vertexCount + " vertices present");
            }
        }
    }

    // Every submesh must name a real index range and a real texture, or none.
    private static void checkSubmeshes(final int[] table, final int indexCount,
        final int textureCount)
    {
        for (int base = 0; base < table.length; base += SUBMESH_STRIDE_INTS)
        {
            final int submesh = base / SUBMESH_STRIDE_INTS;

            final int first = table[base + SUBMESH_FIRST_INDEX];

            final int span = table[base + SUBMESH_INDEX_COUNT];

            final int texture = table[base + SUBMESH_TEXTURE_INDEX];

            if (first < 0 || span < 0 || (long) first + span > indexCount)
            {
                throw new ModelFormatException("submesh " + submesh + " covers indices ["
                    + Integer.toUnsignedString(first) + ", +" + Integer.toUnsignedString(span)
                    + ") outside the " + indexCount + " indices present");
            }

            if (span % INDICES_PER_TRIANGLE != 0)
            {
                throw new ModelFormatException("submesh " + submesh + " has " + span
                    + " indices, not a multiple of " + INDICES_PER_TRIANGLE);
            }

            if (texture != NO_TEXTURE && (texture < 0 || texture >= textureCount))
            {
                throw new ModelFormatException("submesh " + submesh + " names texture " + texture
                    + "; the file has " + textureCount);
            }
        }
    }

    // Slices the shared texel blob into one MipChain per texture record.
    private static MipChain[] buildMipChains(final int[] table, final int[] texels)
    {
        final MipChain[] chains = new MipChain[table.length / TEXTURE_STRIDE_INTS];

        for (int texture = 0; texture < chains.length; texture++)
        {
            final int base = texture * TEXTURE_STRIDE_INTS;

            final int width = table[base + TEXTURE_WIDTH];

            final int height = table[base + TEXTURE_HEIGHT];

            final int levelCount = table[base + TEXTURE_LEVEL_COUNT];

            final int firstTexel = table[base + TEXTURE_FIRST_TEXEL];

            checkTextureRecord(texture, width, height, levelCount, firstTexel);

            chains[texture] = new MipChain(width, height,
                sliceLevels(texture, width, height, levelCount, firstTexel, texels));
        }

        return chains;
    }

    // Rejects a texture descriptor the sampler could not address.
    private static void checkTextureRecord(final int texture, final int width, final int height,
        final int levelCount, final int firstTexel)
    {
        requirePowerOfTwo(texture, width, "width");

        requirePowerOfTwo(texture, height, "height");

        final int maxLevels = Integer.numberOfTrailingZeros(Math.max(width, height)) + 1;

        if (levelCount < 1 || levelCount > maxLevels)
        {
            throw new ModelFormatException("texture " + texture + " declares " + levelCount
                + " mip levels; " + width + "x" + height + " permits 1 to " + maxLevels);
        }

        if (firstTexel < 0)
        {
            throw new ModelFormatException("texture " + texture + " starts at texel "
                + Integer.toUnsignedString(firstTexel));
        }
    }

    // Copies each declared level out of the shared blob, checking extent as it goes.
    private static int[][] sliceLevels(final int texture, final int width, final int height,
        final int levelCount, final int firstTexel, final int[] texels)
    {
        final int[][] levels = new int[levelCount][];

        // MUTABLE local — running texel cursor within the shared blob.
        int cursor = firstTexel;

        for (int level = 0; level < levelCount; level++)
        {
            final int levelWidth = Math.max(1, width >> level);

            final int levelHeight = Math.max(1, height >> level);

            final int size = levelWidth * levelHeight;

            if ((long) cursor + size > texels.length)
            {
                throw new ModelFormatException("texture " + texture + " level " + level
                    + " runs to texel " + ((long) cursor + size) + " but the blob holds "
                    + texels.length);
            }

            levels[level] = new int[size];

            System.arraycopy(texels, cursor, levels[level], 0, size);

            cursor += size;
        }

        return levels;
    }

    // Power-of-two dimensions are what make the sampler's wrap a mask, not a
    // modulo. The upper bound matches MipChain's own, so that an absurd
    // dimension surfaces as a ModelFormatException naming the file's texture
    // rather than as an IllegalArgumentException from inside the chain.
    private static void requirePowerOfTwo(final int texture, final int value, final String name)
    {
        if (value <= 0 || value > MAX_TEXTURE_EDGE || (value & (value - 1)) != 0)
        {
            throw new ModelFormatException("texture " + texture + " " + name
                + " must be a power of two in [1, " + MAX_TEXTURE_EDGE + "], got " + value);
        }
    }

    // ---- Accessors ----------------------------------------------------

    /** Returns the major version the file declared; always {@link #VERSION_MAJOR}. */
    public int versionMajor()
    {
        return versionMajor;
    }

    /** Returns the minor version the file declared; may exceed {@link #VERSION_MINOR}. */
    public int versionMinor()
    {
        return versionMinor;
    }

    /** Returns the number of vertices. */
    public int vertexCount()
    {
        return vertexData.length / VERTEX_STRIDE_INTS;
    }

    /** Returns the number of indices, always a multiple of {@link #INDICES_PER_TRIANGLE}. */
    public int indexCount()
    {
        return indices.length;
    }

    /** Returns the number of triangles. */
    public int triangleCount()
    {
        return indices.length / INDICES_PER_TRIANGLE;
    }

    /** Returns the number of submeshes. */
    public int submeshCount()
    {
        return submeshes.length / SUBMESH_STRIDE_INTS;
    }

    /** Returns the number of textures. */
    public int textureCount()
    {
        return textures.length / TEXTURE_STRIDE_INTS;
    }

    /**
     * Returns the interleaved vertex block, {@link #VERTEX_STRIDE_INTS} slots
     * per vertex, in the layout the {@code VERTEX_*} constants describe.
     *
     * <p>Live array, not a copy — the rasterizer walks it directly and a
     * defensive copy every frame would defeat the point of the format. Treat
     * it as read-only.</p>
     */
    public int[] vertexData()
    {
        return vertexData;
    }

    /**
     * Returns the index array, three entries per triangle.
     *
     * <p>Live array, not a copy. Treat it as read-only.</p>
     */
    public int[] indices()
    {
        return indices;
    }

    /**
     * Returns a vertex x coordinate in model space.
     *
     * @param vertex vertex index in {@code [0, vertexCount())}
     */
    public float positionX(final int vertex)
    {
        return Float.intBitsToFloat(
            vertexData[(vertex * VERTEX_STRIDE_INTS) + VERTEX_POSITION_X]);
    }

    /**
     * Returns a vertex y coordinate in model space.
     *
     * @param vertex vertex index in {@code [0, vertexCount())}
     */
    public float positionY(final int vertex)
    {
        return Float.intBitsToFloat(
            vertexData[(vertex * VERTEX_STRIDE_INTS) + VERTEX_POSITION_Y]);
    }

    /**
     * Returns a vertex z coordinate in model space.
     *
     * @param vertex vertex index in {@code [0, vertexCount())}
     */
    public float positionZ(final int vertex)
    {
        return Float.intBitsToFloat(
            vertexData[(vertex * VERTEX_STRIDE_INTS) + VERTEX_POSITION_Z]);
    }

    /**
     * Returns a vertex u texture coordinate.
     *
     * @param vertex vertex index in {@code [0, vertexCount())}
     */
    public float texCoordU(final int vertex)
    {
        return Float.intBitsToFloat(
            vertexData[(vertex * VERTEX_STRIDE_INTS) + VERTEX_TEXCOORD_U]);
    }

    /**
     * Returns a vertex v texture coordinate.
     *
     * @param vertex vertex index in {@code [0, vertexCount())}
     */
    public float texCoordV(final int vertex)
    {
        return Float.intBitsToFloat(
            vertexData[(vertex * VERTEX_STRIDE_INTS) + VERTEX_TEXCOORD_V]);
    }

    /**
     * Returns a baked vertex colour, packed RGBA8888 — see {@link Rgba}.
     *
     * @param vertex vertex index in {@code [0, vertexCount())}
     */
    public int colour(final int vertex)
    {
        return vertexData[(vertex * VERTEX_STRIDE_INTS) + VERTEX_COLOUR];
    }

    /**
     * Returns the index of a submesh's first index.
     *
     * @param submesh submesh index in {@code [0, submeshCount())}
     */
    public int submeshFirstIndex(final int submesh)
    {
        return submeshes[(submesh * SUBMESH_STRIDE_INTS) + SUBMESH_FIRST_INDEX];
    }

    /**
     * Returns how many indices a submesh covers.
     *
     * @param submesh submesh index in {@code [0, submeshCount())}
     */
    public int submeshIndexCount(final int submesh)
    {
        return submeshes[(submesh * SUBMESH_STRIDE_INTS) + SUBMESH_INDEX_COUNT];
    }

    /**
     * Returns the texture a submesh samples, or {@link #NO_TEXTURE}.
     *
     * @param submesh submesh index in {@code [0, submeshCount())}
     */
    public int submeshTextureIndex(final int submesh)
    {
        return submeshes[(submesh * SUBMESH_STRIDE_INTS) + SUBMESH_TEXTURE_INDEX];
    }

    /**
     * Returns a texture's level 0 width in texels.
     *
     * @param texture texture index in {@code [0, textureCount())}
     */
    public int textureWidth(final int texture)
    {
        return textures[(texture * TEXTURE_STRIDE_INTS) + TEXTURE_WIDTH];
    }

    /**
     * Returns a texture's level 0 height in texels.
     *
     * @param texture texture index in {@code [0, textureCount())}
     */
    public int textureHeight(final int texture)
    {
        return textures[(texture * TEXTURE_STRIDE_INTS) + TEXTURE_HEIGHT];
    }

    /**
     * Returns a texture's mip level count, always at least one.
     *
     * @param texture texture index in {@code [0, textureCount())}
     */
    public int textureLevelCount(final int texture)
    {
        return textures[(texture * TEXTURE_STRIDE_INTS) + TEXTURE_LEVEL_COUNT];
    }

    /**
     * Returns a texture as the {@link MipChain} the sampler consumes.
     *
     * <p>Built once at load. Chains are shared, not copied per call.</p>
     *
     * @param texture texture index in {@code [0, textureCount())}
     */
    public MipChain mipChain(final int texture)
    {
        return mipChains[texture];
    }

    /** Returns the smallest x of the model-space bounding box. */
    public float minX()
    {
        return bounds[0];
    }

    /** Returns the smallest y of the model-space bounding box. */
    public float minY()
    {
        return bounds[1];
    }

    /** Returns the smallest z of the model-space bounding box. */
    public float minZ()
    {
        return bounds[2];
    }

    /** Returns the largest x of the model-space bounding box. */
    public float maxX()
    {
        return bounds[3];
    }

    /** Returns the largest y of the model-space bounding box. */
    public float maxY()
    {
        return bounds[4];
    }

    /** Returns the largest z of the model-space bounding box. */
    public float maxZ()
    {
        return bounds[5];
    }
}
