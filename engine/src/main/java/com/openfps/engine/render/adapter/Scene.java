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
 *
 * <h2>Entity identity</h2>
 *
 * <p>A world instance may carry an <b>entity id</b>: an opaque positive
 * {@code int} the scene's builder assigns, or {@link #UNTAGGED} for ordinary
 * geometry. It exists for exactly one purpose — {@link OutlinePass} draws a
 * silhouette around tagged geometry, and it needs a per-pixel answer to "which
 * entity is this" that only the renderer can produce.</p>
 *
 * <p><b>Ids are not indices.</b> Nothing may assume they are dense, ordered,
 * or related to {@link #worldInstanceCount()}. Two instances may share an id —
 * a character built from several models is one entity — and that is exactly
 * what stops {@link OutlinePass} drawing a seam between an entity's own
 * parts.</p>
 *
 * <p><b>Hit detection does not come from here.</b> The per-pixel id buffer
 * would give pixel-exact hitscan for free and must not be used for it: peers
 * render at different resolutions and worker counts, so two clients would
 * disagree about who was hit, and under the lockstep model that is a desync.
 * Hits are resolved from a deterministic ray in {@code gameplay}.</p>
 *
 * <p><b>View instances are always {@link #UNTAGGED}.</b> There is no overload
 * that says otherwise. The held weapon fills a large part of the screen and
 * outlining it would be an unbroken band down one side of every frame.</p>
 */
public final class Scene
{
    /**
     * The entity id of geometry that belongs to no entity — walls, floors,
     * props, and every view-space instance. Zero, so a freshly cleared
     * {@link Framebuffer#entityIdBuffer()} already reads "nothing here".
     */
    public static final int UNTAGGED = 0;

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
    private final int worldTriangles;
    private final int viewTriangles;
    private final boolean tagged;

    // Takes ownership of two arrays the builder has already finished with.
    private Scene(final Instance[] worldInstances, final Instance[] viewInstances)
    {
        this.world = worldInstances;
        this.view = viewInstances;
        this.maxInstanceTriangles =
            Math.max(largestModel(worldInstances), largestModel(viewInstances));
        this.worldTriangles = totalTriangles(worldInstances);
        this.viewTriangles = totalTriangles(viewInstances);
        this.tagged = anyTagged(worldInstances);
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

    /**
     * Returns one world instance's entity id, or {@link #UNTAGGED}.
     *
     * <p>Opaque and positive when tagged. It is not an index into anything and
     * two instances may legitimately share one — see the class Javadoc.</p>
     *
     * @param index instance index in {@code [0, worldInstanceCount())}
     * @return the entity id, or {@link #UNTAGGED} for ordinary geometry
     */
    public int worldEntityId(final int index)
    {
        return world[index].entityId;
    }

    /**
     * Reports whether any world instance carries an entity id.
     *
     * <p>Computed once at build time so the render port can decide, before it
     * has done any work, whether this frame needs the per-pixel id buffer at
     * all. A scene with nothing tagged — which is every scene that has no
     * players in it — then pays no id clear, no per-pixel id write and no
     * {@link OutlinePass} dispatch. That gate is the entire reason this method
     * exists, and it is why the outline feature costs an untagged scene
     * nothing measurable.</p>
     *
     * @return true if at least one world instance is tagged
     */
    public boolean hasTaggedEntities()
    {
        return tagged;
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
     * <p>Descriptive rather than load-bearing: it is what the biggest single
     * model in the scene costs. {@link #maxPassTriangles()} is the figure the
     * render port sizes its buffers by, and the two stopped being the same
     * number when the world pass was batched.</p>
     *
     * @return the largest instance's triangle count
     */
    public int maxInstanceTriangles()
    {
        return maxInstanceTriangles;
    }

    /** Returns the triangle count of the whole world pass, summed over its instances. */
    public int worldTriangleCount()
    {
        return worldTriangles;
    }

    /** Returns the triangle count of the whole view pass, summed over its instances. */
    public int viewTriangleCount()
    {
        return viewTriangles;
    }

    /**
     * Returns the triangle count of the larger of the two passes.
     *
     * <p><b>This is the figure {@link SoftwareRenderPort} sizes its clip-space
     * buffers by.</b> A pass is transformed, clipped and rasterized as one
     * batch, so the geometry stream holds a whole pass at once — the sum over
     * its instances, not the largest of them. The two passes run one after the
     * other and share the stream, so the larger of the two is enough for
     * both.</p>
     *
     * @return the larger pass's triangle count, or zero for an empty scene
     */
    public int maxPassTriangles()
    {
        return Math.max(worldTriangles, viewTriangles);
    }

    /** Returns a debug rendering of the scene's contents. */
    @Override
    public String toString()
    {
        return "Scene{world=" + world.length + ", view=" + view.length
            + ", maxInstanceTriangles=" + maxInstanceTriangles
            + ", maxPassTriangles=" + maxPassTriangles()
            + ", tagged=" + tagged + "}";
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

    // Whether any instance in one list carries an entity id. Decided once, at
    // build time, so the per-frame path never walks the instance list to find
    // out — see hasTaggedEntities().
    private static boolean anyTagged(final Instance[] instances)
    {
        for (final Instance instance : instances)
        {
            if (instance.entityId != UNTAGGED)
            {
                return true;
            }
        }
        return false;
    }

    // Every triangle in one list. Duplicated models count once per instance:
    // each one is transformed and clipped separately, so each one occupies its
    // own region of the batched geometry stream.
    private static int totalTriangles(final Instance[] instances)
    {
        // MUTABLE local — running sum.
        int total = 0;
        for (final Instance instance : instances)
        {
            total += instance.model.triangleCount();
        }
        return total;
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
            return addWorldInstance(model, modelToWorld, UNTAGGED);
        }

        /**
         * Adds a model to the world pass, tagged as part of an entity.
         *
         * <p>Every pixel this instance wins the depth test at records
         * {@code entityId} in {@link Framebuffer#entityIdBuffer()}, and
         * {@link OutlinePass} draws a silhouette wherever that id borders a
         * different one. Passing {@link #UNTAGGED} is exactly the two-argument
         * overload and is not an error — it is how a caller that tags some of
         * its instances expresses "not this one" without branching.</p>
         *
         * <p>The id is <b>not</b> validated for uniqueness, and deliberately:
         * an entity made of several models shares one id across them, which is
         * what stops the outline drawing a seam through its own joints.</p>
         *
         * @param model the model to draw; same restrictions as
         *     {@link #addWorldInstance(ModelFormat, Mat4)}
         * @param modelToWorld where it sits in the world; same restrictions
         * @param entityId an opaque, positive entity id, or {@link #UNTAGGED}
         * @return this builder
         * @throws IllegalArgumentException if the model or the transform is
         *     unusable, or the id is negative
         */
        public Builder addWorldInstance(final ModelFormat model, final Mat4 modelToWorld,
            final int entityId)
        {
            if (entityId < UNTAGGED)
            {
                throw new IllegalArgumentException("entityId must be positive, or "
                    + UNTAGGED + " for untagged geometry; got " + entityId);
            }
            world.add(validated(model, modelToWorld, "modelToWorld", entityId));
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
            // UNTAGGED, and there is no overload that says otherwise: the
            // viewmodel must never be outlined. See the class Javadoc.
            view.add(validated(model, modelToView, "modelToView", UNTAGGED));
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
            final String name, final int entityId)
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
            return new Instance(model, transform, entityId);
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
        private final int entityId;

        Instance(final ModelFormat instanceModel, final Mat4 instanceTransform,
            final int instanceEntityId)
        {
            this.model = instanceModel;
            this.transform = instanceTransform;
            this.entityId = instanceEntityId;
        }
    }
}
