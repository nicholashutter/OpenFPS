/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

/**
 * Model transforms for the scene tests, built as raw row-major elements.
 *
 * <p>{@link Mat4} deliberately offers only {@link Mat4#identity()} and
 * {@link Mat4#translation} — its documented scope is holding and composing a
 * rigid view transform, and the engine has no caller for a rotation or scale
 * factory yet ({@code STYLE.md} § 13: do not add a service before something
 * needs it). The tests do need them, so they are spelled out here, in one
 * place, as literal matrices rather than as calls into the class under test.</p>
 */
final class TransformFixture
{
    private TransformFixture()
    {
        // fixture factory
    }

    /**
     * Returns a rotation about the z axis, counter-clockwise looking down -z.
     *
     * @param radians the rotation angle
     * @return the rotation matrix; determinant +1
     */
    static Mat4 rotationZ(final float radians)
    {
        final float cos = (float) Math.cos(radians);

        final float sin = (float) Math.sin(radians);

        return Mat4.ofRowMajor(new float[]
        {
            cos, -sin, 0.0f, 0.0f,
            sin, cos, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f,
        });
    }

    /**
     * Returns a uniform scale about the model origin.
     *
     * @param factor the scale factor; positive to keep winding
     * @return the scale matrix; determinant {@code factor} cubed
     */
    static Mat4 uniformScale(final float factor)
    {
        return Mat4.ofRowMajor(new float[]
        {
            factor, 0.0f, 0.0f, 0.0f,
            0.0f, factor, 0.0f, 0.0f,
            0.0f, 0.0f, factor, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f,
        });
    }

    /**
     * Returns a mirror in the yz plane — the transform {@link Scene} refuses,
     * because it reverses winding and so inverts backface culling.
     *
     * @return a matrix with determinant -1
     */
    static Mat4 mirrorX()
    {
        return Mat4.ofRowMajor(new float[]
        {
            -1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f,
        });
    }

    /**
     * Returns a matrix that flattens everything onto the z = 0 plane.
     *
     * @return a singular matrix; determinant 0
     */
    static Mat4 flattenZ()
    {
        return Mat4.ofRowMajor(new float[]
        {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f,
        });
    }

    /**
     * Returns an identity matrix with a projective bottom row — affine in
     * every respect the packed three-row transform can carry, and not in the
     * one it cannot.
     *
     * @return a matrix whose bottom row is not {@code (0, 0, 0, 1)}
     */
    static Mat4 projective()
    {
        return Mat4.ofRowMajor(new float[]
        {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.25f, 1.0f,
        });
    }
}
