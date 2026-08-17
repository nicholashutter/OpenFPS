/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

/**
 * Camera and world-to-clip-space transform for the software rasterizer.
 *
 * Render adapter — pure math over primitives. It imports nothing from the core
 * engine, and nothing from outside {@code java.lang}.
 *
 * <p>This class produces clip-space vertices and <b>stops</b>. It does not
 * clip (that is {@link TriangleClipper}), it does not divide by w, and it does
 * not transform to the viewport (both of those belong to the rasterizer).
 * {@code render/README.md} § 1 makes the "does not own" column of the component
 * table as load-bearing as the "owns" column; this is that boundary.</p>
 *
 * <h2>Conventions — the next pipeline stage must match these exactly</h2>
 *
 * <p><b>View space is left-handed: +x right, +y up, +z forward.</b> Forward is
 * positive z, so {@code w_clip} is positive in front of the camera and the
 * near-plane test is the single comparison {@code w > near} with no sign flip
 * in the hottest branch of the pipeline ({@code render/README.md} § 4).</p>
 *
 * <p><b>The basis derivation is normative, and it is not the one an earlier
 * draft of {@code render/README.md} § 4 specified:</b></p>
 *
 * <pre>
 *   right = normalize(forward x up)
 *   up    = right x forward
 * </pre>
 *
 * <p><b>Do not swap the operands back.</b> § 4 used to say
 * {@code right = normalize(up x forward)}, and that is a horizontal mirror —
 * every model rendered flipped left-to-right, which is invisible on symmetric
 * geometry and looks entirely plausible on the rest. Work the reference case by
 * hand rather than trusting either version. A camera on the +z axis looking
 * back at the origin in a right-handed world has {@code forward = (0,0,-1)} and
 * {@code up = (0,1,0)}:</p>
 *
 * <pre>
 *   up x forward = (1*(-1) - 0*0,  0*0 - 0*(-1),  0*0 - 1*0) = (-1, 0, 0)   WRONG
 *   forward x up = (0*0 - (-1)*1,  (-1)*0 - 0*0,  0*1 - 0*0) = (+1, 0, 0)   RIGHT
 * </pre>
 *
 * <p>That is the standard front view: world +x must land on the right of the
 * screen, and only the second order puts it there. The mirror was found by
 * rendering a cube whose faces carry a marker at a known model coordinate and
 * observing it in the wrong corner — {@code SoftwareRenderPort}'s Javadoc has
 * the rest of that story, because the winding convention depends on this.</p>
 *
 * <p>Note what the corrected order does to handedness, since it is easy to
 * misread: {@code right x up == -forward}, so {@code (right, up, forward)} is a
 * <b>left-handed</b> triple in a right-handed world. That is exactly what "view
 * space is left-handed" means — +x right, +y up, +z <i>into</i> the screen —
 * and it is the property the old order silently lacked while claiming.</p>
 *
 * <p><b>The view matrix is the exact rigid inverse</b> of the camera's world
 * transform, {@code V = R^T . T(-eye)}, built directly from the orthonormal
 * basis. A general matrix inverse is never called.</p>
 *
 * <p><b>Clip space.</b> With {@code f = 1 / tan(fovY / 2)}:</p>
 * <pre>
 *   x_clip = x_view * f / aspect
 *   y_clip = y_view * f
 *   w_clip = z_view
 * </pre>
 *
 * <p><b>There is no z row, and no z_clip.</b> A GPU needs one because its
 * fixed-function depth unit consumes a window-space z in [0, 1]. This renderer
 * owns its depth unit and stores {@code 1/w}, which comes from {@code w_clip}
 * alone — so the z row is dead weight and is simply not computed. That is why
 * {@link #transformToClip} writes three floats, not four, and why there is no
 * {@code projectionMatrix()} accessor: the projection here is fully described
 * by the two scalars {@link #projectionScaleX()} and {@link #projectionScaleY()}.
 * Do not "restore" the missing row.</p>
 *
 * <p><b>What the rasterizer does next</b>, recorded here so the seam is
 * unambiguous ({@code render/README.md} § 4) — it is <em>not</em> done by this
 * class:</p>
 * <pre>
 *   invW = 1 / w_clip
 *   sx   = (x_clip * invW * 0.5 + 0.5) * width
 *   sy   = (0.5 - y_clip * invW * 0.5) * height
 * </pre>
 *
 * <p><b>Immutability and threading.</b> A {@code Camera} is immutable, so one
 * instance is safely shared by every tile worker without synchronisation.
 * Build one per frame; the per-vertex method {@link #transformToClip} allocates
 * nothing.</p>
 *
 * <p><b>Numeric policy.</b> {@code float}, not 16.16 fixed-point, and
 * {@code Math.tan} rather than {@code StrictMath.tan}. {@code Math}
 * transcendentals are permitted 1-2 ulp of error and are not required to be
 * reproducible between JVM implementations; that is acceptable here precisely
 * because rendering never feeds simulation state and so cannot desync lockstep.
 * Simulation code must use {@code StrictMath}. See {@code render/README.md}
 * § 2, JEP 306 (https://openjdk.org/jeps/306) and the {@code java.lang.Math}
 * javadoc.</p>
 */
public final class Camera
{
    /** Number of floats {@link #transformToClip} writes: x, y and w. No z. */
    public static final int CLIP_FLOATS = 3;

    /** Number of floats in the packed world-to-clip transform: 3 rows of 4. */
    public static final int WORLD_TO_CLIP_FLOATS = CLIP_FLOATS * 4;

    // Row offsets into the packed world-to-clip transform.
    private static final int ROW_X = 0;
    private static final int ROW_Y = 4;
    private static final int ROW_W = 8;

    private final Vec3 eye;
    private final Vec3 right;
    private final Vec3 up;
    private final Vec3 forward;
    private final float fovY;
    private final float aspect;
    private final float near;
    private final float scaleX;
    private final float scaleY;

    // The concatenated world -> clip transform, 3 rows of 4, row-major.
    // Private, written only in the constructor, never handed out by reference.
    // Concatenating here rather than composing per vertex is the whole point:
    // one vertex costs 12 multiplies instead of a 4x4 followed by two scales.
    private final float[] worldToClip;

    private Camera(final Vec3 eye, final Vec3 right, final Vec3 up, final Vec3 forward,
        final float fovY, final float aspect, final float near)
    {
        this.eye = eye;

        this.right = right;

        this.up = up;

        this.forward = forward;

        this.fovY = fovY;

        this.aspect = aspect;

        this.near = near;

        // f = 1 / tan(fovY / 2). Called once per camera, i.e. once per frame.
        final float focal = (float) (1.0 / Math.tan(fovY * 0.5f));

        this.scaleX = focal / aspect;

        this.scaleY = focal;

        // V = R^T . T(-eye): the rows of R^T are the basis vectors, and the
        // translation column is -(basis . eye).
        final float[] packed = new float[WORLD_TO_CLIP_FLOATS];

        packRow(packed, ROW_X, right, eye, scaleX);

        packRow(packed, ROW_Y, up, eye, scaleY);

        packRow(packed, ROW_W, forward, eye, 1.0f);

        this.worldToClip = packed;
    }

    // One row of V, pre-multiplied by its projection scale so the per-vertex
    // path needs no second pass.
    private static void packRow(final float[] packed, final int offset, final Vec3 axis,
        final Vec3 eye, final float scale)
    {
        packed[offset] = axis.x() * scale;

        packed[offset + 1] = axis.y() * scale;

        packed[offset + 2] = axis.z() * scale;

        packed[offset + 3] = -axis.dot(eye) * scale;
    }

    /**
     * Creates a camera from a position and a viewing direction.
     *
     * The basis is orthonormalised: {@code forward} is normalised, {@code right}
     * is derived as {@code normalize(forward x up)} — see the class Javadoc on
     * why that operand order and not the other — and the final {@code up} is
     * recomputed as {@code right x forward} so the three axes are exactly
     * orthogonal even when the caller's {@code up} is only approximate.
     *
     * @param eye the camera position in world space
     * @param forward the viewing direction; need not be unit length
     * @param up the approximate world up direction; need not be unit length or
     *     perpendicular to {@code forward}, but must not be parallel to it
     * @param fovYRadians the vertical field of view in radians, in (0, pi)
     * @param aspect the viewport aspect ratio, width / height; must be positive
     * @param near the near plane distance in view-space z units; must be
     *     positive, because {@code 1 / w} is evaluated at {@code w = near}
     * @return the camera
     * @throws IllegalArgumentException if any parameter is out of range, or if
     *     {@code forward} and {@code up} are parallel and so span no basis
     */
    public static Camera create(final Vec3 eye, final Vec3 forward, final Vec3 up,
        final float fovYRadians, final float aspect, final float near)
    {
        validateFrustum(fovYRadians, aspect, near);

        final Vec3 unitForward = forward.normalized();

        final Vec3 rightAxis = unitForward.cross(up);

        if (rightAxis.length() == 0.0f)
        {
            throw new IllegalArgumentException(
                "up is parallel to forward; the camera basis is undefined");
        }

        final Vec3 unitRight = rightAxis.normalized();

        final Vec3 unitUp = unitRight.cross(unitForward);

        return new Camera(eye, unitRight, unitUp, unitForward, fovYRadians, aspect, near);
    }

    /**
     * Creates a camera positioned at {@code eye} and aimed at {@code target}.
     *
     * Equivalent to {@link #create} with {@code forward = target - eye}.
     *
     * @param eye the camera position in world space
     * @param target the point to look at; must not coincide with {@code eye}
     * @param up the approximate world up direction
     * @param fovYRadians the vertical field of view in radians, in (0, pi)
     * @param aspect the viewport aspect ratio, width / height
     * @param near the near plane distance; must be positive
     * @return the camera
     * @throws IllegalArgumentException on the same conditions as {@link #create}
     */
    public static Camera lookingAt(final Vec3 eye, final Vec3 target, final Vec3 up,
        final float fovYRadians, final float aspect, final float near)
    {
        return create(eye, target.subtract(eye), up, fovYRadians, aspect, near);
    }

    // Frustum parameter range checks, shared by both factories.
    private static void validateFrustum(final float fovYRadians, final float aspect,
        final float near)
    {
        if (!(fovYRadians > 0.0f) || !(fovYRadians < (float) Math.PI))
        {
            throw new IllegalArgumentException(
                "fovY must be in (0, pi) radians, got " + fovYRadians);
        }

        if (!(aspect > 0.0f))
        {
            throw new IllegalArgumentException("aspect must be positive, got " + aspect);
        }

        if (!(near > 0.0f))
        {
            throw new IllegalArgumentException(
                "near must be positive so that 1/near is finite, got " + near);
        }
    }

    /**
     * Transforms a world-space point to clip space.
     *
     * Writes exactly {@link #CLIP_FLOATS} floats — {@code x_clip},
     * {@code y_clip}, {@code w_clip} — into a caller-supplied buffer. There is
     * no {@code z_clip}: see the class Javadoc and {@code render/README.md}
     * § 4. Nothing is allocated, so this is safe to call per vertex.
     *
     * <p>{@code w_clip} is the view-space depth. It is positive in front of the
     * camera, zero on the plane through the eye, and negative behind it — which
     * is exactly why {@link TriangleClipper} must run before anything divides
     * by it.</p>
     *
     * @param x the point's world x coordinate
     * @param y the point's world y coordinate
     * @param z the point's world z coordinate
     * @param out the destination buffer
     * @param outOffset index of the first of the three floats to write
     */
    public void transformToClip(final float x, final float y, final float z,
        final float[] out, final int outOffset)
    {
        transformToClip(worldToClip, 0, x, y, z, out, outOffset);
    }

    /**
     * Transforms a point through any packed three-row transform in this
     * class's layout — the camera's own, or a per-instance one built by
     * {@link #packModelToClip} or {@link #packViewToClip}.
     *
     * <p>Writes exactly {@link #CLIP_FLOATS} floats and allocates nothing.
     * <b>The per-vertex cost is the same twelve multiplies and nine adds
     * whichever transform is passed</b>, which is the whole reason a scene
     * concatenates {@code modelToWorld} into the packed rows once per instance
     * rather than applying a second matrix per vertex.</p>
     *
     * @param transform packed transform, {@link #WORLD_TO_CLIP_FLOATS} floats:
     *     the x row, the y row and the w row, row-major
     * @param transformOffset index of the transform's first float
     * @param x the point's x coordinate, in the transform's source space
     * @param y the point's y coordinate
     * @param z the point's z coordinate
     * @param out the destination buffer
     * @param outOffset index of the first of the three floats to write
     */
    public static void transformToClip(final float[] transform, final int transformOffset,
        final float x, final float y, final float z, final float[] out, final int outOffset)
    {
        final float[] t = transform;

        final int b = transformOffset;

        out[outOffset] = t[b + ROW_X] * x + t[b + ROW_X + 1] * y
            + t[b + ROW_X + 2] * z + t[b + ROW_X + 3];

        out[outOffset + 1] = t[b + ROW_Y] * x + t[b + ROW_Y + 1] * y
            + t[b + ROW_Y + 2] * z + t[b + ROW_Y + 3];

        out[outOffset + 2] = t[b + ROW_W] * x + t[b + ROW_W + 1] * y
            + t[b + ROW_W + 2] * z + t[b + ROW_W + 3];
    }

    /**
     * Concatenates an instance's model-to-world transform into this camera's
     * packed world-to-clip transform, giving one packed model-to-clip
     * transform.
     *
     * <p>Called <b>once per instance per frame</b>, not per vertex: 48
     * multiplies here buy a per-vertex cost identical to the untransformed
     * case. Composition is {@code clip = worldToClip . modelToWorld}, so the
     * model transform is applied to a point first.</p>
     *
     * <p>The result has no fourth row, so a {@code modelToWorld} whose bottom
     * row is not {@code (0, 0, 0, 1)} loses it. {@link Scene} refuses such a
     * transform rather than letting it be silently dropped.</p>
     *
     * @param modelToWorld the instance's placement in the world
     * @param dest the destination buffer
     * @param destOffset index of the first of {@link #WORLD_TO_CLIP_FLOATS}
     *     floats to write
     */
    public void packModelToClip(final Mat4 modelToWorld, final float[] dest,
        final int destOffset)
    {
        packConcatenatedRow(worldToClip, ROW_X, modelToWorld, dest, destOffset + ROW_X);

        packConcatenatedRow(worldToClip, ROW_Y, modelToWorld, dest, destOffset + ROW_Y);

        packConcatenatedRow(worldToClip, ROW_W, modelToWorld, dest, destOffset + ROW_W);
    }

    /**
     * Concatenates an instance's row-major 16-float model-to-world transform
     * into this camera's packed world-to-clip transform.
     *
     * <p>Companion to {@link #packModelToClip(Mat4, float[], int)} for the
     * per-tic hot path. The caller hands in a 16-float scratch that already
     * holds the placement (typically written by a placement helper that
     * does not wrap the values in a {@link Mat4}); this method reads the
     * 16 floats directly and skips the {@code Mat4.get(row, col)} indirect
     * call. The result is the same packed transform the wrapped overload
     * produces.</p>
     *
     * @param modelToWorld at least 16 floats in row-major order
     * @param modelOffset index of the first of the 16 floats to read
     * @param dest the destination buffer
     * @param destOffset index of the first of {@link #WORLD_TO_CLIP_FLOATS}
     *     floats to write
     */
    public void packModelToClip(final float[] modelToWorld, final int modelOffset,
        final float[] dest, final int destOffset)
    {
        packConcatenatedRow(worldToClip, ROW_X, modelToWorld, modelOffset, dest,
            destOffset + ROW_X);

        packConcatenatedRow(worldToClip, ROW_Y, modelToWorld, modelOffset, dest,
            destOffset + ROW_Y);

        packConcatenatedRow(worldToClip, ROW_W, modelToWorld, modelOffset, dest,
            destOffset + ROW_W);
    }

    // One row of (packed 3x4) . (Mat4), as four dot products against the
    // matrix's columns.
    private static void packConcatenatedRow(final float[] packed, final int row,
        final Mat4 rhs, final float[] dest, final int destOffset)
    {
        for (int column = 0; column < Mat4.ORDER; column++)
        {
            // MUTABLE local — accumulator for one dot product.
            float sum = 0.0f;

            for (int k = 0; k < Mat4.ORDER; k++)
            {
                sum += packed[row + k] * rhs.get(k, column);
            }

            dest[destOffset + column] = sum;
        }
    }

    // Same row multiply, reading the right-hand matrix from a raw 16-float
    // array instead of a Mat4. The matrix is laid out row-major, so the
    // (k, column) element lives at k * Mat4.ORDER + column.
    private static void packConcatenatedRow(final float[] packed, final int row,
        final float[] rhs, final int rhsOffset, final float[] dest, final int destOffset)
    {
        for (int column = 0; column < Mat4.ORDER; column++)
        {
            // MUTABLE local — accumulator for one dot product.
            float sum = 0.0f;

            for (int k = 0; k < Mat4.ORDER; k++)
            {
                sum += packed[row + k] * rhs[rhsOffset + k * Mat4.ORDER + column];
            }

            dest[destOffset + column] = sum;
        }
    }

    /**
     * Builds a packed clip transform for geometry that is <b>already in view
     * space</b> — a first-person viewmodel ({@link Scene}).
     *
     * <p>The view matrix is skipped entirely and only the projection is
     * applied, which for this pipeline is two scalars and an identity w row
     * ({@code render/README.md} § 4): {@code x_clip = x_view * scaleX},
     * {@code y_clip = y_view * scaleY}, {@code w_clip = z_view}. The result is
     * in the same packed layout as {@link #packModelToClip}, so the per-vertex
     * path cannot tell the two apart.</p>
     *
     * <p>Only the camera's frustum is used, never its position or orientation:
     * that is exactly what makes a viewmodel stay welded to the eye however
     * the player turns.</p>
     *
     * @param modelToView the viewmodel's placement relative to the eye
     * @param dest the destination buffer
     * @param destOffset index of the first of {@link #WORLD_TO_CLIP_FLOATS}
     *     floats to write
     */
    public void packViewToClip(final Mat4 modelToView, final float[] dest,
        final int destOffset)
    {
        packScaledRow(modelToView, 0, scaleX, dest, destOffset + ROW_X);

        packScaledRow(modelToView, 1, scaleY, dest, destOffset + ROW_Y);

        packScaledRow(modelToView, 2, 1.0f, dest, destOffset + ROW_W);
    }

    /**
     * Packs a viewmodel from a raw 16-float row-major transform.
     *
     * <p>Companion to {@link #packViewToClip(Mat4, float[], int)} for the
     * hot path. The viewmodel stays welded to the eye, so the camera's
     * frustum is the only thing the matrix needs scaled by. See
     * {@link #packModelToClip(float[], int, float[], int)} for the
     * row-major convention.</p>
     *
     * @param modelToView at least 16 floats in row-major order
     * @param modelOffset index of the first of the 16 floats to read
     * @param dest the destination buffer
     * @param destOffset index of the first of {@link #WORLD_TO_CLIP_FLOATS}
     *     floats to write
     */
    public void packViewToClip(final float[] modelToView, final int modelOffset,
        final float[] dest, final int destOffset)
    {
        packScaledRow(modelToView, 0, modelOffset, scaleX, dest, destOffset + ROW_X);

        packScaledRow(modelToView, 1, modelOffset, scaleY, dest, destOffset + ROW_Y);

        packScaledRow(modelToView, 2, modelOffset, 1.0f, dest, destOffset + ROW_W);
    }

    // One row of the source matrix, multiplied by its projection scale.
    private static void packScaledRow(final Mat4 source, final int row, final float scale,
        final float[] dest, final int destOffset)
    {
        for (int column = 0; column < Mat4.ORDER; column++)
        {
            dest[destOffset + column] = source.get(row, column) * scale;
        }
    }

    // Same as the Mat4 overload, but reading the source from a raw 16-float
    // row-major array. The (row, column) element lives at row * 4 + column.
    private static void packScaledRow(final float[] source, final int row, final int sourceOffset,
        final float scale, final float[] dest, final int destOffset)
    {
        final int base = sourceOffset + row * Mat4.ORDER;

        for (int column = 0; column < Mat4.ORDER; column++)
        {
            dest[destOffset + column] = source[base + column] * scale;
        }
    }

    /**
     * Returns the view matrix, the exact rigid inverse {@code R^T . T(-eye)} of
     * the camera's world transform.
     *
     * Rebuilt on each call; this is a diagnostic and test accessor, not part of
     * the per-vertex path, which uses the pre-concatenated transform instead.
     *
     * @return the world-to-view matrix
     */
    public Mat4 viewMatrix()
    {
        final float[] values = new float[Mat4.ELEMENTS];

        packViewRow(values, 0, right);

        packViewRow(values, 4, up);

        packViewRow(values, 8, forward);

        values[15] = 1.0f;

        return Mat4.ofRowMajor(values);
    }

    // One unscaled row of V = R^T . T(-eye).
    private void packViewRow(final float[] values, final int offset, final Vec3 axis)
    {
        values[offset] = axis.x();

        values[offset + 1] = axis.y();

        values[offset + 2] = axis.z();

        values[offset + 3] = -axis.dot(eye);
    }

    /**
     * Copies the packed world-to-clip transform into a caller-supplied buffer.
     *
     * Three rows of four, row-major: the x row, the y row and the w row. There
     * is no z row.
     *
     * @param dest the destination buffer
     * @param destOffset index of the first of {@link #WORLD_TO_CLIP_FLOATS}
     *     floats to write
     */
    public void copyWorldToClipInto(final float[] dest, final int destOffset)
    {
        System.arraycopy(worldToClip, 0, dest, destOffset, WORLD_TO_CLIP_FLOATS);
    }

    /** Returns the camera position in world space. */
    public Vec3 eye()
    {
        return eye;
    }

    /** Returns the unit view-space +x axis (right) expressed in world space. */
    public Vec3 right()
    {
        return right;
    }

    /** Returns the unit view-space +y axis (up) expressed in world space. */
    public Vec3 up()
    {
        return up;
    }

    /** Returns the unit view-space +z axis (forward) expressed in world space. */
    public Vec3 forward()
    {
        return forward;
    }

    /** Returns the vertical field of view in radians. */
    public float fovY()
    {
        return fovY;
    }

    /** Returns the viewport aspect ratio, width / height. */
    public float aspect()
    {
        return aspect;
    }

    /**
     * Returns the near plane distance, in view-space z units.
     *
     * This is the value {@link TriangleClipper} clips against: a vertex is
     * inside iff {@code w_clip > near}.
     *
     * @return the near plane distance, always positive
     */
    public float near()
    {
        return near;
    }

    /** Returns the projection x scale, {@code 1 / (tan(fovY / 2) * aspect)}. */
    public float projectionScaleX()
    {
        return scaleX;
    }

    /** Returns the projection y scale, {@code 1 / tan(fovY / 2)}. */
    public float projectionScaleY()
    {
        return scaleY;
    }

    /** Returns a debug rendering of the camera's placement and frustum. */
    @Override
    public String toString()
    {
        return "Camera{eye=" + eye + ", forward=" + forward + ", up=" + up
            + ", fovY=" + fovY + ", aspect=" + aspect + ", near=" + near + "}";
    }
}
