/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.gltf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.google.gson.JsonObject;

/**
 * Decodes glTF accessors into flat primitive arrays.
 *
 * Build-time only — this type is never on a runtime classpath.
 *
 * <h2>What an accessor is</h2>
 *
 * glTF stores vertex attributes as a three-level indirection: an accessor
 * describes element count, element type and component type; it points at a
 * buffer view, which describes a byte range and an optional stride; the view
 * points at a buffer, which is the actual bytes. Resolving all three is
 * exactly the work {@code docs/ASSETS.md} § 4 moves offline, so that the
 * runtime sees one flat interleaved array instead.
 *
 * <h2>Reading the bytes</h2>
 *
 * {@link ByteBuffer} with an explicit little-endian order is used rather than
 * the engine's {@code LittleEndian} helper: that class is scoped to WAD data
 * by its own documentation and offers no 32-bit float or unsigned 32-bit read,
 * which are two of the six component types glTF defines. Sharing half an
 * implementation would be worse than sharing none.
 *
 * <h2>Not implemented, and refused loudly</h2>
 *
 * Sparse accessors, and accessors of matrix type. Both are legal glTF and
 * neither appears in the low-poly sources {@code docs/ASSETS.md} § 3 accepts.
 * They fail with a message naming the accessor rather than silently producing
 * wrong geometry.
 *
 * Source — Khronos Group, glTF 2.0 Specification, § 3.6 (accessors) and
 * § 5.1 (accessor schema) — https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html
 */
public final class GltfAccessor
{
    /** Component type {@code BYTE}, one signed byte. */
    private static final int COMPONENT_BYTE = 5120;

    /** Component type {@code UNSIGNED_BYTE}. */
    private static final int COMPONENT_UNSIGNED_BYTE = 5121;

    /** Component type {@code SHORT}, one signed 16-bit integer. */
    private static final int COMPONENT_SHORT = 5122;

    /** Component type {@code UNSIGNED_SHORT}. */
    private static final int COMPONENT_UNSIGNED_SHORT = 5123;

    /** Component type {@code UNSIGNED_INT}. */
    private static final int COMPONENT_UNSIGNED_INT = 5125;

    /** Component type {@code FLOAT}, IEEE 754 single precision. */
    private static final int COMPONENT_FLOAT = 5126;

    /** Largest value of a signed byte, the normalisation divisor for BYTE. */
    private static final float BYTE_SCALE = 127.0f;

    /** Largest value of an unsigned byte. */
    private static final float UNSIGNED_BYTE_SCALE = 255.0f;

    /** Largest value of a signed short. */
    private static final float SHORT_SCALE = 32767.0f;

    /** Largest value of an unsigned short. */
    private static final float UNSIGNED_SHORT_SCALE = 65535.0f;

    private GltfAccessor()
    {
        // utility class
    }

    /**
     * Returns how many components one element of the given accessor type has.
     *
     * @param type a glTF accessor type, for example {@code "VEC3"}
     * @return the component count
     * @throws GltfException if the type is a matrix type or unrecognised
     */
    public static int componentCount(final String type)
    {
        switch (type)
        {
            case "SCALAR":
                return 1;
            case "VEC2":
                return 2;
            case "VEC3":
                return 3;
            case "VEC4":
                return 4;
            default:
                throw new GltfException("accessor type " + type + " is not supported;"
                    + " only SCALAR, VEC2, VEC3 and VEC4 appear in mesh attributes");
        }
    }

    /**
     * Returns the number of elements an accessor describes.
     *
     * @param asset the asset the accessor belongs to
     * @param accessorIndex index into the document's {@code accessors} array
     * @return the element count
     */
    public static int count(final GltfAsset asset, final int accessorIndex)
    {
        return asset.item("accessors", accessorIndex).get("count").getAsInt();
    }

    /**
     * Reads an accessor as floats, {@code components} per element.
     *
     * <p>Integer component types are converted per the specification: when the
     * accessor is marked {@code normalized}, unsigned types map onto
     * {@code [0, 1]} and signed types onto {@code [-1, 1]}; otherwise the
     * integer value is widened as-is.</p>
     *
     * @param asset the asset the accessor belongs to
     * @param accessorIndex index into the document's {@code accessors} array
     * @param components components expected per element, 1 to 4
     * @return {@code count * components} floats, element-major
     * @throws GltfException if the accessor is sparse, has the wrong component
     *     count, or addresses outside its buffer
     */
    public static float[] readFloats(final GltfAsset asset, final int accessorIndex,
        final int components)
    {
        final JsonObject accessor = asset.item("accessors", accessorIndex);

        final int actual = componentCount(accessor.get("type").getAsString());

        if (actual != components)
        {
            throw new GltfException(asset.name() + ": accessor " + accessorIndex + " is "
                + accessor.get("type").getAsString() + ", expected " + components + " components");
        }

        final int componentType = accessor.get("componentType").getAsInt();

        final boolean normalized = GltfAsset.optionalBoolean(accessor, "normalized", false);

        final int elements = accessor.get("count").getAsInt();

        final float[] out = new float[Math.multiplyExact(elements, components)];

        final Reader reader = readerFor(asset, accessorIndex, accessor, components);

        for (int element = 0; element < elements; element++)
        {
            for (int component = 0; component < components; component++)
            {
                out[(element * components) + component] = toFloat(
                    reader.raw(element, component), componentType, normalized);
            }
        }

        return out;
    }

    /**
     * Reads a SCALAR accessor as integers — the form vertex indices take.
     *
     * @param asset the asset the accessor belongs to
     * @param accessorIndex index into the document's {@code accessors} array
     * @return one integer per element
     * @throws GltfException if the accessor is not SCALAR, is sparse, or
     *     addresses outside its buffer
     */
    public static int[] readScalarInts(final GltfAsset asset, final int accessorIndex)
    {
        final JsonObject accessor = asset.item("accessors", accessorIndex);

        if (componentCount(accessor.get("type").getAsString()) != 1)
        {
            throw new GltfException(asset.name() + ": accessor " + accessorIndex + " is "
                + accessor.get("type").getAsString() + ", expected SCALAR");
        }

        final int elements = accessor.get("count").getAsInt();

        final int[] out = new int[elements];

        final Reader reader = readerFor(asset, accessorIndex, accessor, 1);

        for (int element = 0; element < elements; element++)
        {
            out[element] = (int) reader.raw(element, 0);
        }

        return out;
    }

    // ---- Internals ------------------------------------------------------

    // Binds an accessor to its buffer view and validates that every element
    // it claims actually lies inside the buffer.
    private static Reader readerFor(final GltfAsset asset, final int accessorIndex,
        final JsonObject accessor, final int components)
    {
        if (accessor.has("sparse"))
        {
            throw new GltfException(asset.name() + ": accessor " + accessorIndex
                + " is sparse; sparse accessors are not implemented");
        }

        final int componentType = accessor.get("componentType").getAsInt();

        final int componentBytes = componentSize(componentType);

        final int elements = accessor.get("count").getAsInt();

        final int elementBytes = componentBytes * components;

        if (!accessor.has("bufferView"))
        {
            // Specification: an accessor without a buffer view reads as zeros.
            return new Reader(ByteBuffer.allocate(Math.max(1, elementBytes))
                .order(ByteOrder.LITTLE_ENDIAN), componentType, 0, 0, componentBytes, true);
        }

        final JsonObject view = asset.item("bufferViews", accessor.get("bufferView").getAsInt());

        final byte[] data = asset.buffer(view.get("buffer").getAsInt());

        final int viewOffset = GltfAsset.optionalInt(view, "byteOffset", 0);

        final int declaredStride = GltfAsset.optionalInt(view, "byteStride", 0);

        final int accessorOffset = GltfAsset.optionalInt(accessor, "byteOffset", 0);

        // MUTABLE local — a stride of zero or absent means tightly packed.
        int stride = declaredStride;

        if (stride == 0)
        {
            stride = elementBytes;
        }

        final long start = (long) viewOffset + accessorOffset;

        final long end = start + ((long) (elements - 1) * stride) + elementBytes;

        if (elements > 0 && (start < 0 || end > data.length))
        {
            throw new GltfException(asset.name() + ": accessor " + accessorIndex + " reads to byte "
                + end + " of a " + data.length + "-byte buffer");
        }

        return new Reader(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN), componentType,
            (int) start, stride, componentBytes, false);
    }

    // Widens one raw component to long, sign-extending only signed types.
    private static long rawAt(final ByteBuffer data, final int componentType, final int offset)
    {
        switch (componentType)
        {
            case COMPONENT_BYTE:
                return data.get(offset);
            case COMPONENT_UNSIGNED_BYTE:
                return Byte.toUnsignedLong(data.get(offset));
            case COMPONENT_SHORT:
                return data.getShort(offset);
            case COMPONENT_UNSIGNED_SHORT:
                return Short.toUnsignedLong(data.getShort(offset));
            case COMPONENT_UNSIGNED_INT:
                return Integer.toUnsignedLong(data.getInt(offset));
            case COMPONENT_FLOAT:
                return Float.floatToRawIntBits(data.getFloat(offset));
            default:
                throw new GltfException("componentType " + componentType + " is not a glTF type");
        }
    }

    // Converts a raw component to float, applying the specification's
    // normalisation rules when the accessor asked for them.
    private static float toFloat(final long raw, final int componentType, final boolean normalized)
    {
        if (componentType == COMPONENT_FLOAT)
        {
            return Float.intBitsToFloat((int) raw);
        }

        if (!normalized)
        {
            return raw;
        }

        switch (componentType)
        {
            case COMPONENT_BYTE:
                return Math.max(raw / BYTE_SCALE, -1.0f);
            case COMPONENT_UNSIGNED_BYTE:
                return raw / UNSIGNED_BYTE_SCALE;
            case COMPONENT_SHORT:
                return Math.max(raw / SHORT_SCALE, -1.0f);
            case COMPONENT_UNSIGNED_SHORT:
                return raw / UNSIGNED_SHORT_SCALE;
            default:
                throw new GltfException("componentType " + componentType
                    + " cannot be normalized");
        }
    }

    // Bytes occupied by one component of the given type.
    private static int componentSize(final int componentType)
    {
        switch (componentType)
        {
            case COMPONENT_BYTE:
            case COMPONENT_UNSIGNED_BYTE:
                return 1;
            case COMPONENT_SHORT:
            case COMPONENT_UNSIGNED_SHORT:
                return 2;
            case COMPONENT_UNSIGNED_INT:
            case COMPONENT_FLOAT:
                return 4;
            default:
                throw new GltfException("componentType " + componentType + " is not a glTF type");
        }
    }

    /**
     * A bound accessor: a buffer, a start offset, and a stride.
     *
     * Private to the decoding path; exists so the element loop above stays one
     * arithmetic expression rather than re-resolving the view per component.
     */
    private static final class Reader
    {
        private final ByteBuffer data;
        private final int componentType;
        private final int start;
        private final int stride;
        private final int componentBytes;
        private final boolean zeroFilled;

        private Reader(final ByteBuffer data, final int componentType, final int start,
            final int stride, final int componentBytes, final boolean zeroFilled)
        {
            this.data = data;

            this.componentType = componentType;

            this.start = start;

            this.stride = stride;

            this.componentBytes = componentBytes;

            this.zeroFilled = zeroFilled;
        }

        // Returns one raw component, widened to long.
        private long raw(final int element, final int component)
        {
            if (zeroFilled)
            {
                return 0L;
            }

            return rawAt(data, componentType,
                start + (element * stride) + (component * componentBytes));
        }
    }
}
