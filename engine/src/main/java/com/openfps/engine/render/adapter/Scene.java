/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import java.util.ArrayList;

/**
 * R_ What the renderer draws: model instances, each carrying its own
 * transform, in two lists that are rendered as two passes.
 *
 * Render adapter — immutable data over {@link ModelFormat} and {@link Mat4}.
 * It imports nothing from the core engine.
 *
 * <h2>The two lists</h2>
 *
 * <ul>
 *   <li><b>World instances</b> carry a {@code modelToWorld} transform and go
 *       model -&gt; world -&gt; view -&gt; clip. This is ordinary scene
 *       geometry.</li>
 *   <li><b>View instances</b> carry a {@code modelToView} transform: they are
 *       <i>already</i> expressed in the camera's own space, so they skip the
 *       view matrix entirely and take the projection alone. This is how a
 *       first-person weapon is drawn. A viewmodel is bolted to the camera, so
 *       "40 cm forward, 15 cm right, 20 cm down" is the exact statement of
 *       where it is; routing that through a world position and back out
 *       through the inverse camera would be a longer way of saying the same
 *       thing, with rounding.</li>
 * </ul>
 *
 * <p>{@link SoftwareRenderPort} renders the world list, <b>clears the depth
 * buffer</b>, and then renders the view list. That reset — and not a
 * compressed depth range — is what stops the weapon poking through a wall the
 * player is standing against: the viewmodel is compared only against itself.
 * Colour is not cleared in between, so the weapon composites over the world.</p>
 *
 * <h2>Transforms are validated on the way in</h2>
 *
 * <p>Two properties are checked when an instance is added, because both are
 * silent corruption rather than a visible failure if they are not:</p>
 *
 * <ul>
 *   <li><b>The bottom row must be {@code (0, 0, 0, 1)}.</b> The per-vertex path
 *       concatenates the transform into {@link Camera}'s packed three-row
 *       transform, which has no fourth row to carry a projective term. A
 *       transform with one would be silently ignored rather than applied.</li>
 *   <li><b>The determinant of the upper-left 3x3 must be positive.</b> A
 *       negative determinant — a mirror, or a negative scale on an odd number
 *       of axes — reverses triangle winding, and
 *       {@link SoftwareRenderPort#BACKFACE_CULL_MODE} is a fixed screen-space
 *       winding measured against a no-cull oracle ({@code render/README.md}
 *       § 7). Such an instance would render inside-out: back faces kept, front
 *       faces discarded, which looks like a plausible model rather than an
 *       error. The alternative to rejecting it is a per-instance cull flip,
 *       which means threading a second cull mode through the rasterizer's
 *       per-frame state for the sake of a transform no art pipeline emits.
 *       Rejected here instead, loudly. A zero determinant is rejected with it:
 *       it collapses the model into a plane and every triangle degenerates.</li>
 * </ul>
 *
 * <h2>Immutability and allocation</h2>
 *
 * <p>A built {@code Scene} is immutable and therefore safe to hand to the
 * render port from any thread. Building one allocates; <b>rendering one does
 * not</b>. Build a scene when its contents change, not every frame — and note
 * that changing where an instance <i>is</i> is a content change, so a moving
 * object does mean a new {@code Scene}. That is one small array of references
 * per rebuild, not per frame per instance.</p>
 */
public final class Scene
{
    /**
     * The scene with nothing in it. Renders a cleared frame and no geometry,
     * which is exactly what a renderer with no world loaded should show.
     */
    public static final Scene EMPTY = new Scene(new Instance[0], new Instance[0]);

    /** Row index of the transform row that must read {@code (0, 0, 0, 1)}. */
    private static final int BOTTOM_ROW = 3;

    private final Instance[] world;
    private final Instance[] view;
    private final int maxInstanceTriangles;

    // Takes ownership of two arrays the builder has already finished with.
    private Scene(final Instance[] worldInstances, final Instance[] viewInstances)
    {
        this.world = worldInstances;
        this.view = viewInstances;
        this.maxInstanceTriangles =
            Math.max(largestModel(worldInstances), largestModel(viewInstances));
    }

    /**
     * Returns a scene holding one world instance at the origin, untransformed.
     *
     * <p>This is what {@link SoftwareRenderPort#loadModel(ModelFormat)} builds:
     * the single-model behaviour the renderer had before scenes existed is one
     * instance with an identity transform, not a second code path.</p>
     *
     * @param model the model to draw; must not be null and must have triangles
     * @return a one-instance scene
     * @throws IllegalArgumentException if the model is null or has no triangles
     */
    public static Scene of(final ModelFormat model)
    {
        return builder().addWorldInstance(model, Mat4.identity()).build();
    }

    /**
     * Returns a new, empty builder.
     *
     * @return a builder that collects instances and freezes them into a scene
     */
    public static Builder builder()
    {
        return new Builder();
    }

    /** Returns how many world-space instances this scene holds. */
    public int worldInstanceCount()
    {
        return world.length;
    }

    /**
     * Returns one world instance's model.
     *
     * @param index instance index in {@code [0, worldInstanceCount())}
     * @return the model to draw
     */
    public ModelFormat worldModel(final int index)
    {
        return world[index].model;
    }

    /**
     * Returns one world instance's model-to-world transform.
     *
     * @param index instance index in {@code [0, worldInstanceCount())}
     * @return the transform, never null
     */
    public Mat4 worldTransform(final int index)
    {
        return world[index].transform;
    }

    /** Returns how many view-space instances this scene holds. */
    public int viewInstanceCount()
    {
        return view.length;
    }

    /**
     * Returns one view instance's model.
     *
     * @param index instance index in {@code [0, viewInstanceCount())}
     * @return the model to draw
     */
    public ModelFormat viewModel(final int index)
    {
        return view[index].model;
    }

    /**
     * Returns one view instance's model-to-view transform.
     *
     * <p>Already in view space: the camera's view matrix is not applied to it,
     * only the projection.</p>
     *
     * @param index instance index in {@code [0, viewInstanceCount())}
     * @return the transform, never null
     */
    public Mat4 viewTransform(final int index)
    {
        return view[index].transform;
    }

    /** Returns the total instance count across both passes. */
    public int instanceCount()
    {
        return world.length + view.length;
    }

    /**
     * Returns the triangle count of the largest single instance in the scene,
     * or zero if the scene is empty.
     *
     * <p>This is the figure the render port sizes its clip-space buffers by:
     * instances are transformed, clipped and rasterized one at a time, so the
     * geometry buffers have to hold the biggest one, not the sum.</p>
     *
     * @return the largest instance's triangle count
     */
    public int maxInstanceTriangles()
    {
        return maxInstanceTriangles;
    }

    /** Returns a debug rendering of the scene's contents. */
    @Override
    public String toString()
    {
        return "Scene{world=" + world.length + ", view=" + view.length
            + ", maxInstanceTriangles=" + maxInstanceTriangles + "}";
    }

    // The largest triangle count in one list, or zero for an empty one.
    private static int largestModel(final Instance[] instances)
    {
        // MUTABLE local — running maximum.
        int largest = 0;
        for (final Instance instance : instances)
        {
            largest = Math.max(largest, instance.model.triangleCount());
        }
        return largest;
    }

    /**
     * Collects instances and freezes them into an immutable {@link Scene}.
     *
     * <p>Not thread-safe, and not intended to be: build a scene on one thread,
     * then publish the finished immutable result. Every check a transform has
     * to pass happens here, at build time, so the per-frame path can assume
     * every instance is drawable.</p>
     */
    public static final class Builder
    {
        /** World instances collected so far. MUTABLE: appended by the caller. */
        private final ArrayList<Instance> world = new ArrayList<>();

        /** View instances collected so far. MUTABLE: appended by the caller. */
        private final ArrayList<Instance> view = new ArrayList<>();

        private Builder()
        {
            // Scene.builder()
        }

        /**
         * Adds a model to the world pass.
         *
         * @param model the model to draw; must not be null and must have at
         *     least one triangle
         * @param modelToWorld where it sits in the world; must not be null,
         *     must have bottom row {@code (0, 0, 0, 1)} and a positive
         *     upper-left determinant — see the class Javadoc for why a mirror
         *     is refused rather than corrected
         * @return this builder
         * @throws IllegalArgumentException if the model or the transform is
         *     unusable
         */
        public Builder addWorldInstance(final ModelFormat model, final Mat4 modelToWorld)
        {
            world.add(validated(model, modelToWorld, "modelToWorld"));
            return this;
        }

        /**
         * Adds a model to the view-space pass — a first-person viewmodel.
         *
         * <p>The transform maps model space directly to <b>view</b> space: +x
         * right, +y up, +z forward away from the eye ({@link Camera}). A
         * viewmodel a little forward, right and down of the eye is a
         * translation with a positive z, and it must be beyond the camera's
         * near plane like anything else.</p>
         *
         * @param model the model to draw; must not be null and must have at
         *     least one triangle
         * @param modelToView where it sits relative to the eye; same
         *     restrictions as {@link #addWorldInstance}
         * @return this builder
         * @throws IllegalArgumentException if the model or the transform is
         *     unusable
         */
        public Builder addViewInstance(final ModelFormat model, final Mat4 modelToView)
        {
            view.add(validated(model, modelToView, "modelToView"));
            return this;
        }

        /**
         * Freezes the collected instances into an immutable scene.
         *
         * <p>The builder may be reused afterwards; the returned scene does not
         * share storage with it.</p>
         *
         * @return the scene
         */
        public Scene build()
        {
            return new Scene(world.toArray(new Instance[0]), view.toArray(new Instance[0]));
        }

        // Every rule an instance has to satisfy, in one place.
        private static Instance validated(final ModelFormat model, final Mat4 transform,
            final String name)
        {
            if (model == null)
            {
                throw new IllegalArgumentException("model must not be null");
            }
            if (model.triangleCount() <= 0)
            {
                throw new IllegalArgumentException("model has no triangles");
            }
            if (transform == null)
            {
                throw new IllegalArgumentException(name + " must not be null");
            }
            requireAffine(transform, name);
            requireOrientationPreserving(transform, name);
            return new Instance(model, transform);
        }

        // The packed three-row transform has no fourth row, so a projective
        // one would be dropped rather than applied. Say so instead.
        private static void requireAffine(final Mat4 transform, final String name)
        {
            for (int column = 0; column < Mat4.ORDER; column++)
            {
                // MUTABLE local — the value this column's bottom entry must hold.
                float expected = 0.0f;
                if (column == BOTTOM_ROW)
                {
                    expected = 1.0f;
                }
                if (transform.get(BOTTOM_ROW, column) != expected)
                {
                    throw new IllegalArgumentException(name
                        + " must be affine: its bottom row must be (0, 0, 0, 1), but element ("
                        + BOTTOM_ROW + ", " + column + ") is "
                        + transform.get(BOTTOM_ROW, column));
                }
            }
        }

        // A negative determinant mirrors the model and inverts backface
        // culling; a zero one collapses it. See the class Javadoc.
        private static void requireOrientationPreserving(final Mat4 transform,
            final String name)
        {
            final float determinant = upperLeftDeterminant(transform);
            if (determinant > 0.0f)
            {
                return;
            }
            if (determinant == 0.0f)
            {
                throw new IllegalArgumentException(name + " is singular (determinant 0): it "
                    + "collapses the model onto a plane and every triangle degenerates");
            }
            throw new IllegalArgumentException(name + " has a negative determinant ("
                + determinant + "): a mirror or negative scale reverses triangle winding, "
                + "which inverts backface culling and renders the instance inside-out. "
                + "Mirror the model at build time instead.");
        }

        // The determinant of the linear part. That is the factor by which the
        // transform scales a signed volume, and its sign is exactly whether
        // winding survives.
        private static float upperLeftDeterminant(final Mat4 m)
        {
            return m.get(0, 0) * (m.get(1, 1) * m.get(2, 2) - m.get(1, 2) * m.get(2, 1))
                - m.get(0, 1) * (m.get(1, 0) * m.get(2, 2) - m.get(1, 2) * m.get(2, 0))
                + m.get(0, 2) * (m.get(1, 0) * m.get(2, 1) - m.get(1, 1) * m.get(2, 0));
        }
    }

    // One model and where it sits. Immutable, and never handed out: the scene
    // exposes the two fields by index so no caller can hold one and outlive
    // the scene's validation.
    private static final class Instance
    {
        private final ModelFormat model;
        private final Mat4 transform;

        Instance(final ModelFormat instanceModel, final Mat4 instanceTransform)
        {
            this.model = instanceModel;
            this.transform = instanceTransform;
        }
    }
}
