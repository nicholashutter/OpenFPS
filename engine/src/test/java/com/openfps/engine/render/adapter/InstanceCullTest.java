/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The per-instance frustum cull.
 *
 * <h2>Half of this file tests what is NOT culled, and that half is the point</h2>
 *
 * <p>A cull that removes too much is a hole in the world, visible from one angle
 * and not from another, and it looks like a level-design mistake rather than a
 * renderer bug. A cull that removes too little costs a few transforms. The two
 * failures are not comparable, so the tests are not balanced either: every plane
 * gets a "clearly outside, culled" case <b>and</b> a "straddles it, kept" case,
 * and the straddle cases are chosen to sit a hair on the inside rather than
 * safely inside.</p>
 *
 * <h2>The camera these tests use</h2>
 *
 * <p>Eye at the origin, looking along world +z, 90-degree vertical field of view,
 * square aspect. Those choices make the frustum arithmetic something a reader
 * can check without running anything: {@code w_clip} is world z, the vertical
 * scale is {@code 1 / tan(45) = 1}, and so a point is inside exactly when
 * {@code z > near}, {@code |x| <= z} and {@code |y| <= z}.</p>
 *
 * <p><b>World +x lands on view -x with this orientation</b>, because
 * {@code right = normalize(forward x up)} is {@code (-1, 0, 0)} here — see
 * {@link Camera}'s Javadoc, where the operand order is normative and has been
 * wrong once. Nothing below depends on which way round it is: the side-plane
 * cases are asserted for both signs.</p>
 */
@DisplayName("InstanceCull")
final class InstanceCullTest
{
    /** Vertical field of view: 90 degrees, so the projection scales are both 1. */
    private static final float FOV = (float) (Math.PI / 2.0);

    /** Near plane, matching the renderer's own default. */
    private static final float NEAR = 0.05f;

    /** Square aspect, so the horizontal half-angle is 45 degrees too. */
    private static final float ASPECT = 1.0f;

    /** World up. */
    private static final Vec3 UP = new Vec3(0.0f, 1.0f, 0.0f);

    /** Eye position: the origin, so world coordinates are view coordinates. */
    private static final Vec3 EYE = new Vec3(0.0f, 0.0f, 0.0f);

    /** Viewing direction: straight along world +z. */
    private static final Vec3 FORWARD = new Vec3(0.0f, 0.0f, 1.0f);

    /** Half-extent of the unit test box. */
    private static final float HALF = 1.0f;

    // The camera every case is tested against.
    private static Camera camera()
    {
        return Camera.create(EYE, FORWARD, UP, FOV, ASPECT, NEAR);
    }

    // A model-space axis-aligned box centred on a point, with a uniform
    // half-extent, in the min-then-max order ModelFormat and InstanceCull share.
    private static float[] box(final float centreX, final float centreY, final float centreZ,
        final float half)
    {
        return new float[]
        {
            centreX - half, centreY - half, centreZ - half,
            centreX + half, centreY + half, centreZ + half,
        };
    }

    // Whether the cull would drop a box placed by `placement`.
    private static boolean culled(final Mat4 placement, final float[] modelBox)
    {
        final float[] transform = new float[Camera.WORLD_TO_CLIP_FLOATS];
        camera().packModelToClip(placement, transform, 0);
        return InstanceCull.isOutsideFrustum(transform, 0, NEAR, modelBox, 0);
    }

    // The same, for a box already expressed in world coordinates.
    private static boolean culled(final float[] worldBox)
    {
        return culled(Mat4.identity(), worldBox);
    }

    @Nested
    @DisplayName("what it drops")
    final class Dropped
    {
        @Test
        @DisplayName("a box entirely behind the eye is culled")
        void behindTheEye()
        {
            assertThat(culled(box(0.0f, 0.0f, -5.0f, HALF))).isTrue();
        }

        @Test
        @DisplayName("a box entirely on the far side of the near plane is culled")
        void justBehindTheNearPlane()
        {
            // Spans z in [-0.05, 0.049...]: every point is at or before the near
            // plane, so the clipper would emit nothing from any triangle in it.
            assertThat(culled(box(0.0f, 0.0f, 0.0f, NEAR * 0.99f))).isTrue();
        }

        @Test
        @DisplayName("a box off to the left of the frustum is culled")
        void offToTheLeft()
        {
            assertThat(culled(box(-10.0f, 0.0f, 2.0f, HALF))).isTrue();
        }

        @Test
        @DisplayName("a box off to the right of the frustum is culled")
        void offToTheRight()
        {
            assertThat(culled(box(10.0f, 0.0f, 2.0f, HALF))).isTrue();
        }

        @Test
        @DisplayName("a box above the frustum is culled")
        void above()
        {
            assertThat(culled(box(0.0f, 10.0f, 2.0f, HALF))).isTrue();
        }

        @Test
        @DisplayName("a box below the frustum is culled")
        void below()
        {
            assertThat(culled(box(0.0f, -10.0f, 2.0f, HALF))).isTrue();
        }

        @Test
        @DisplayName("the placement is what decides it, not the model box")
        void thePlacementDecides()
        {
            final float[] atOrigin = box(0.0f, 0.0f, 0.0f, HALF);
            assertThat(culled(Mat4.translation(0.0f, 0.0f, -5.0f), atOrigin)).isTrue();
            assertThat(culled(Mat4.translation(0.0f, 0.0f, 5.0f), atOrigin)).isFalse();
        }
    }

    @Nested
    @DisplayName("what it must keep")
    final class Kept
    {
        @Test
        @DisplayName("a box in the middle of the view is kept")
        void inView()
        {
            assertThat(culled(box(0.0f, 0.0f, 5.0f, HALF))).isFalse();
        }

        @Test
        @DisplayName("a box straddling the near plane is kept")
        void straddlingNear()
        {
            // Half of it is behind the eye. The clipper's whole job is this case,
            // so the cull must hand it over rather than deciding for it.
            assertThat(culled(box(0.0f, 0.0f, 0.0f, HALF))).isFalse();
        }

        @Test
        @DisplayName("a box straddling the left frustum edge is kept")
        void straddlingLeft()
        {
            // Spans x in [-4, -2] and z in [1, 3]. The corner (-2, 3) satisfies
            // |x| <= z, so part of the box is inside and none of it may go.
            assertThat(culled(box(-3.0f, 0.0f, 2.0f, HALF))).isFalse();
        }

        @Test
        @DisplayName("a box straddling the right frustum edge is kept")
        void straddlingRight()
        {
            assertThat(culled(box(3.0f, 0.0f, 2.0f, HALF))).isFalse();
        }

        @Test
        @DisplayName("a box straddling the top frustum edge is kept")
        void straddlingTop()
        {
            assertThat(culled(box(0.0f, 3.0f, 2.0f, HALF))).isFalse();
        }

        @Test
        @DisplayName("a box straddling the bottom frustum edge is kept")
        void straddlingBottom()
        {
            assertThat(culled(box(0.0f, -3.0f, 2.0f, HALF))).isFalse();
        }

        @Test
        @DisplayName("a box the camera is inside is kept")
        void surroundingTheCamera()
        {
            // The demo room's floor and ceiling are exactly this: geometry whose
            // bounding box contains the eye. Culling one would delete the ground.
            assertThat(culled(box(0.0f, 0.0f, 0.0f, 1000.0f))).isFalse();
        }

        @Test
        @DisplayName("a wall crossing the whole view from one side to the other is kept")
        void spanningTheView()
        {
            // Outside the left plane on one end and the right plane on the other,
            // but outside NEITHER over the whole box. A cull that tested planes
            // jointly rather than one at a time would drop this and take a wall
            // with it.
            final float[] wall = {-50.0f, -1.0f, 4.0f, 50.0f, 1.0f, 5.0f};
            assertThat(culled(wall)).isFalse();
        }

        @Test
        @DisplayName("a box whose max is below its min inflates the test, never shrinks it")
        void degenerateBoxIsInflated()
        {
            // ModelFormat produces such a box only for a model with no vertices,
            // but a half-extent taken without a magnitude would be negative and
            // would subtract from the plane radius — the one arithmetic slip that
            // culls something visible. This box reaches into the frustum at
            // x = 0, z = 5 and must survive.
            final float[] inverted = {20.0f, 0.0f, 5.0f, -20.0f, 0.0f, 5.0f};
            assertThat(culled(inverted)).isFalse();
        }
    }

    @Nested
    @DisplayName("agreement with the rest of the pipeline")
    final class Agreement
    {
        @Test
        @DisplayName("the box layout is the one ModelFormat reports bounds in")
        void boxLayoutMatchesModelFormat()
        {
            assertThat(InstanceCull.BOX_FLOATS).isEqualTo(6);
        }

        @Test
        @DisplayName("five planes, and no far plane, because the pipeline has none")
        void fivePlanes()
        {
            assertThat(InstanceCull.PLANE_COUNT).isEqualTo(5);
        }

        @Test
        @DisplayName("the packed transform row width agrees with Camera's")
        void rowWidthMatchesCamera()
        {
            assertThat(InstanceCull.rowFloats() * Camera.CLIP_FLOATS)
                .isEqualTo(Camera.WORLD_TO_CLIP_FLOATS);
        }

        @Test
        @DisplayName("distance alone never culls: there is no far plane to hit")
        void nothingIsTooFarAway()
        {
            assertThat(culled(box(0.0f, 0.0f, 1.0e6f, HALF))).isFalse();
        }
    }
}
