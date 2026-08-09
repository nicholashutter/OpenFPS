/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

/**
 * Immutable three-component single-precision vector.
 *
 * Render adapter — pure math over primitives. It imports nothing from the core
 * engine, and nothing from outside {@code java.lang}.
 *
 * <p><b>This is not a general-purpose linear algebra type.</b> It carries only
 * the operations the render pipeline actually needs: the handful used to build
 * a camera basis ({@link #cross}, {@link #normalized}, {@link #dot}) and to
 * position it ({@link #subtract}). Resist growing it; the per-vertex hot path
 * uses flat {@code float[]} buffers, not objects.</p>
 *
 * <p><b>Numeric policy.</b> {@code float}, not 16.16 fixed-point. The renderer
 * never feeds simulation state, so it cannot desync lockstep, and 16.16 has
 * neither the range nor the precision a projective pipeline needs. See
 * {@code render/README.md} § 2 and JEP 306 (Java 17 floating point is
 * always-strict IEEE 754): https://openjdk.org/jeps/306</p>
 */
public final class Vec3
{
    /** The zero vector. */
    public static final Vec3 ZERO = new Vec3(0.0f, 0.0f, 0.0f);

    private final float x;
    private final float y;
    private final float z;

    /**
     * Creates a vector from its three components.
     *
     * @param x the x component
     * @param y the y component
     * @param z the z component
     */
    public Vec3(final float x, final float y, final float z)
    {
        this.x = x;

        this.y = y;

        this.z = z;
    }

    /** Returns the x component. */
    public float x()
    {
        return x;
    }

    /** Returns the y component. */
    public float y()
    {
        return y;
    }

    /** Returns the z component. */
    public float z()
    {
        return z;
    }

    /**
     * Returns the component-wise sum of this vector and another.
     *
     * @param other the vector to add
     * @return a new vector, {@code this + other}
     */
    public Vec3 add(final Vec3 other)
    {
        return new Vec3(x + other.x, y + other.y, z + other.z);
    }

    /**
     * Returns the component-wise difference of this vector and another.
     *
     * @param other the vector to subtract
     * @return a new vector, {@code this - other}
     */
    public Vec3 subtract(final Vec3 other)
    {
        return new Vec3(x - other.x, y - other.y, z - other.z);
    }

    /**
     * Returns this vector scaled by a scalar.
     *
     * @param factor the scale factor
     * @return a new vector, {@code this * factor}
     */
    public Vec3 scale(final float factor)
    {
        return new Vec3(x * factor, y * factor, z * factor);
    }

    /**
     * Returns the dot product of this vector and another.
     *
     * @param other the right-hand operand
     * @return {@code this . other}
     */
    public float dot(final Vec3 other)
    {
        return x * other.x + y * other.y + z * other.z;
    }

    /**
     * Returns the cross product of this vector and another.
     *
     * The result is perpendicular to both operands. Which way it points is a
     * property of the operand order alone and is independent of any handedness
     * convention; {@link Camera} fixes the handedness by choosing that order.
     *
     * @param other the right-hand operand
     * @return a new vector, {@code this x other}
     */
    public Vec3 cross(final Vec3 other)
    {
        return new Vec3(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x);
    }

    /**
     * Returns the Euclidean length of this vector.
     *
     * {@code Math.sqrt} is exactly rounded and therefore reproducible on every
     * conforming JVM (unlike the transcendentals — see {@code render/README.md}
     * § 2), so no {@code StrictMath} call is warranted here.
     *
     * @return the length, always non-negative
     */
    public float length()
    {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    /**
     * Returns this vector scaled to unit length.
     *
     * @return a new unit-length vector in the same direction
     * @throws IllegalArgumentException if the vector has zero length, which has
     *     no direction to preserve
     */
    public Vec3 normalized()
    {
        final float len = length();

        if (len == 0.0f)
        {
            throw new IllegalArgumentException("cannot normalize a zero-length vector");
        }

        final float inv = 1.0f / len;

        return new Vec3(x * inv, y * inv, z * inv);
    }

    /** Returns a debug rendering of the three components. */
    @Override
    public String toString()
    {
        return "Vec3{" + x + ", " + y + ", " + z + "}";
    }
}
