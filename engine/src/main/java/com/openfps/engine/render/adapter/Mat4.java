/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

/**
 * Immutable 4x4 single-precision matrix, stored row-major.
 *
 * Render adapter — pure math over primitives. It imports nothing from the core
 * engine, and nothing from outside {@code java.lang}.
 *
 * <p>Element {@code (row, column)} lives at index {@code row * 4 + column}, so
 * a row is contiguous. That is the layout the transform wants: every transform
 * here is a sequence of row-times-column dot products, and a contiguous row is
 * one cache line's worth of sequential reads.</p>
 *
 * <p><b>Scope.</b> This exists to hold and compose the camera's <em>view</em>
 * matrix, which is a rigid transform. It deliberately has no inverse, no
 * determinant and no decomposition: {@code render/README.md} § 4 states that
 * the view matrix is built directly as {@code V = R^T . T(-eye)} and that a
 * general matrix inverse must never be called. It also has no projection
 * factory — the projection in this pipeline is not a 4x4 matrix at all; see
 * {@link Camera}.</p>
 *
 * <p>Instances are immutable and therefore safe to share across the render
 * worker threads without synchronisation.</p>
 */
public final class Mat4
{
    /** Number of rows, and of columns. */
    public static final int ORDER = 4;

    /** Number of elements in a 4x4 matrix. */
    public static final int ELEMENTS = ORDER * ORDER;

    // Row-major storage. Private, written only in the constructor, and never
    // handed out by reference — every accessor copies. The class is immutable.
    private final float[] m;

    // Takes ownership of an array the caller has already finished with.
    //
    // Package-private so the renderer's per-instance override slots can wrap
    // a shared 16-float backing array without copying it. The wrapper's
    // m is the same reference as the caller's array, so a write to either
    // is visible to both — the whole point of the slot's no-alloc update.
    Mat4(final float[] owned)
    {
        this.m = owned;
    }

    /**
     * Returns the 4x4 identity matrix.
     *
     * @return a matrix that maps every point to itself
     */
    public static Mat4 identity()
    {
        final float[] values = new float[ELEMENTS];

        values[0] = 1.0f;

        values[5] = 1.0f;

        values[10] = 1.0f;

        values[15] = 1.0f;

        return new Mat4(values);
    }

    /**
     * Creates a matrix from 16 row-major elements.
     *
     * The array is copied, so later mutation by the caller cannot reach into
     * the matrix.
     *
     * @param values exactly {@link #ELEMENTS} elements, row-major
     * @return the matrix holding those elements
     * @throws IllegalArgumentException if {@code values} is not 16 elements long
     */
    public static Mat4 ofRowMajor(final float[] values)
    {
        if (values.length != ELEMENTS)
        {
            throw new IllegalArgumentException(
                "a 4x4 matrix needs " + ELEMENTS + " elements, got " + values.length);
        }

        final float[] copy = new float[ELEMENTS];

        System.arraycopy(values, 0, copy, 0, ELEMENTS);

        return new Mat4(copy);
    }

    /**
     * Returns a pure translation matrix.
     *
     * @param tx translation along x
     * @param ty translation along y
     * @param tz translation along z
     * @return a matrix mapping {@code p} to {@code p + (tx, ty, tz)}
     */
    public static Mat4 translation(final float tx, final float ty, final float tz)
    {
        final float[] values = new float[ELEMENTS];

        values[0] = 1.0f;

        values[5] = 1.0f;

        values[10] = 1.0f;

        values[15] = 1.0f;

        values[3] = tx;

        values[7] = ty;

        values[11] = tz;

        return new Mat4(values);
    }

    /**
     * Returns the matrix product {@code this * rhs}.
     *
     * Composition reads right to left, as the algebra does: the transform in
     * {@code rhs} is applied to a point first.
     *
     * @param rhs the right-hand operand
     * @return a new matrix, {@code this * rhs}
     */
    public Mat4 multiply(final Mat4 rhs)
    {
        final float[] out = new float[ELEMENTS];

        for (int row = 0; row < ORDER; row++)
        {
            for (int column = 0; column < ORDER; column++)
            {
                // MUTABLE local — accumulator for one dot product.
                float sum = 0.0f;

                for (int k = 0; k < ORDER; k++)
                {
                    sum += m[row * ORDER + k] * rhs.m[k * ORDER + column];
                }

                out[row * ORDER + column] = sum;
            }
        }

        return new Mat4(out);
    }

    /**
     * Returns one element.
     *
     * @param row the row index, 0 to 3
     * @param column the column index, 0 to 3
     * @return the element at that position
     */
    public float get(final int row, final int column)
    {
        return m[row * ORDER + column];
    }

    /**
     * Transforms a point, treating it as the homogeneous point
     * {@code (x, y, z, 1)}.
     *
     * Writes four floats — x, y, z, w — into a caller-supplied buffer, so a
     * caller in a per-vertex loop allocates nothing.
     *
     * @param x the point's x coordinate
     * @param y the point's y coordinate
     * @param z the point's z coordinate
     * @param out the destination buffer
     * @param outOffset index of the first of the four floats to write
     */
    public void transformPoint(final float x, final float y, final float z,
        final float[] out, final int outOffset)
    {
        for (int row = 0; row < ORDER; row++)
        {
            final int base = row * ORDER;

            out[outOffset + row] = m[base] * x + m[base + 1] * y + m[base + 2] * z + m[base + 3];
        }
    }

    /**
     * Copies this matrix's 16 row-major elements into a caller-supplied buffer.
     *
     * @param dest the destination buffer
     * @param destOffset index of the first element to write
     */
    public void copyRowMajorInto(final float[] dest, final int destOffset)
    {
        System.arraycopy(m, 0, dest, destOffset, ELEMENTS);
    }

    /** Returns a debug rendering, one row per line. */
    @Override
    public String toString()
    {
        final StringBuilder text = new StringBuilder("Mat4{");

        for (int row = 0; row < ORDER; row++)
        {
            text.append("\n  [");

            for (int column = 0; column < ORDER; column++)
            {
                if (column > 0)
                {
                    text.append(", ");
                }

                text.append(m[row * ORDER + column]);
            }

            text.append(']');
        }

        return text.append("\n}").toString();
    }

    /**
     * Tests whether another object holds the same 16 elements in the same order.
     *
     * <p>Value equality. Two matrices are equal iff each of the 16 row-major
     * floats is bit-identical — {@code Float.floatToRawIntBits}, not a tolerance,
     * because the matrices here are built from the same arithmetic every tic
     * and a "near enough" comparison would mask real divergence.</p>
     *
     * <p>The package-private {@code Mat4(float[])} constructor aliases the
     * caller's array, so two {@link Mat4}s over the same backing array are
     * equal by this definition; that is the property that lets tests compare
     * a renderer's per-instance override slot against
     * {@link com.openfps.engine.demo.DemoEffects#HIDDEN} by value, which is
     * what the no-allocation rewrite of the per-tic publish path depends on.</p>
     */
    @Override
    public boolean equals(final Object other)
    {
        if (this == other)
        {
            return true;
        }

        if (!(other instanceof Mat4))
        {
            return false;
        }

        final Mat4 rhs = (Mat4) other;

        for (int i = 0; i < ELEMENTS; i++)
        {
            if (Float.floatToRawIntBits(m[i]) != Float.floatToRawIntBits(rhs.m[i]))
            {
                return false;
            }
        }

        return true;
    }

    /**
     * Returns a hash consistent with {@link #equals}.
     *
     * <p>Built from the same 16 floats as equals, so two matrices that compare
     * equal produce the same hash, as the {@link Object#hashCode} contract
     * requires. The cost is one xor-fold over 16 ints; this class does not
     * use {@code Mat4} as a hash key, so the cost is paid only when a test
     * asks for it.</p>
     */
    @Override
    public int hashCode()
    {
        int hash = 1;

        for (int i = 0; i < ELEMENTS; i++)
        {
            hash = 31 * hash + Float.floatToRawIntBits(m[i]);
        }

        return hash;
    }
}
