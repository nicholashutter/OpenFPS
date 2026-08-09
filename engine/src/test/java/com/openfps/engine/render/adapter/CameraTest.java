/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import static com.openfps.engine.render.adapter.RenderTestEpsilon.EPSILON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Camera}.
 *
 * <p>Reference camera for most cases: at the world origin, looking down +z with
 * +y up, a 90 degree vertical field of view and a square viewport. That makes
 * {@code f = 1 / tan(45 deg) = 1} and both projection scales exactly 1, so the
 * clip coordinates equal the view coordinates and every expected value can be
 * read off by hand rather than recomputed by a second implementation of the
 * same formula.</p>
 */
@DisplayName("Camera")
class CameraTest
{
    private static final float FOV_90 = (float) (Math.PI / 2.0);
    private static final float FOV_60 = (float) (Math.PI / 3.0);
    private static final Vec3 WORLD_UP = new Vec3(0.0f, 1.0f, 0.0f);
    private static final Vec3 FORWARD_Z = new Vec3(0.0f, 0.0f, 1.0f);
    private static final float NEAR = 1.0f;

    private static Camera reference()
    {
        return Camera.create(Vec3.ZERO, FORWARD_Z, WORLD_UP, FOV_90, 1.0f, NEAR);
    }

    private static float[] clip(final Camera camera, final float x, final float y, final float z)
    {
        final float[] out = new float[Camera.CLIP_FLOATS];

        camera.transformToClip(x, y, z, out, 0);

        return out;
    }

    @Nested
    @DisplayName("conventions")
    class Conventions
    {
        @Test
        @DisplayName("derives the basis as right = forward x up, not up x forward")
        void shouldDeriveBasisFromForwardCrossUpWhenLookingDownWorldZ()
        {
            // THE OPERAND ORDER IS THE WHOLE TEST. Getting it backwards is a
            // horizontal mirror: every model renders flipped left-to-right,
            // which is invisible on symmetric geometry. Work it by hand.
            //
            // The clearest case is the standard front view — a camera on the
            // +z axis looking back at the origin, so forward = (0,0,-1),
            // up = (0,1,0), in a right-handed world:
            //
            //   up x forward = (1*(-1) - 0*0, 0*0 - 0*(-1), 0*0 - 1*0) = (-1,0,0)
            //   forward x up = (0*0 - (-1)*1, (-1)*0 - 0*0, 0*1 - 0*0) = (+1,0,0)
            //
            // World +x must be on the right of a front view, so forward x up
            // is the correct one and up x forward is the mirror.
            final Camera front = Camera.create(
                Vec3.ZERO, new Vec3(0.0f, 0.0f, -1.0f), WORLD_UP, FOV_90, 1.0f, NEAR);

            assertThat(front.right().x()).isCloseTo(1.0f, within(EPSILON));

            // The reference camera looks the OTHER way, down +z, which is the
            // back view — so world +x is on the left and right.x is -1. Both
            // cases are asserted because a sign error that happened to please
            // one of them would fail the other.
            final Camera camera = reference();

            assertThat(camera.forward().z()).isCloseTo(1.0f, within(EPSILON));

            assertThat(camera.right().x()).isCloseTo(-1.0f, within(EPSILON));

            assertThat(camera.up().y()).isCloseTo(1.0f, within(EPSILON));
        }

        @Test
        @DisplayName("keeps the basis orthonormal and left-handed: right x up == -forward")
        void shouldKeepBasisOrthonormalWhenUpIsNotPerpendicularToForward()
        {
            // up leans out of the plane; the camera must re-orthogonalise it.
            final Camera camera = Camera.create(
                Vec3.ZERO, FORWARD_Z, new Vec3(0.0f, 1.0f, 1.0f), FOV_90, 1.0f, NEAR);

            final Vec3 right = camera.right();

            final Vec3 up = camera.up();

            final Vec3 forward = camera.forward();

            assertThat(right.length()).isCloseTo(1.0f, within(EPSILON));

            assertThat(up.length()).isCloseTo(1.0f, within(EPSILON));

            assertThat(forward.length()).isCloseTo(1.0f, within(EPSILON));

            assertThat(right.dot(up)).isCloseTo(0.0f, within(EPSILON));

            assertThat(up.dot(forward)).isCloseTo(0.0f, within(EPSILON));

            assertThat(forward.dot(right)).isCloseTo(0.0f, within(EPSILON));

            // -1, not +1. "View space is left-handed" (+x right, +y up, +z INTO
            // the screen) is precisely the statement that right x up points
            // BACKWARDS along forward. The old +1 here described a right-handed
            // triple, which is the mirror this test now guards against.
            final Vec3 cross = right.cross(up);

            assertThat(cross.dot(forward)).isCloseTo(-1.0f, within(EPSILON));
        }

        @Test
        @DisplayName("gives positive w in front of the camera and negative behind")
        void shouldGivePositiveWInFrontWhenPointIsAheadOfTheCamera()
        {
            final Camera camera = reference();

            // Forward is +z, so w_clip = z_view with no sign flip. This is the
            // property the near-plane test `w > near` depends on.
            assertThat(clip(camera, 0.0f, 0.0f, 10.0f)[2]).isCloseTo(10.0f, within(EPSILON));

            assertThat(clip(camera, 0.0f, 0.0f, 0.0f)[2]).isCloseTo(0.0f, within(EPSILON));

            assertThat(clip(camera, 0.0f, 0.0f, -10.0f)[2]).isCloseTo(-10.0f, within(EPSILON));
        }

        @Test
        @DisplayName("puts world +x on the right of a front view and the left of a back view")
        void shouldPutWorldRightOnScreenRightWhenLookingDownNegativeWorldZ()
        {
            // ndcX = x_clip / w_clip; positive is the right half of the screen.
            //
            // Front view: on the +z axis looking back at the origin. This is
            // the orientation a person means by "looking at the model", and
            // world +x has to come out on the right.
            final Camera front = Camera.create(
                new Vec3(0.0f, 0.0f, 20.0f), new Vec3(0.0f, 0.0f, -1.0f), WORLD_UP,
                FOV_90, 1.0f, NEAR);

            final float[] ahead = clip(front, 3.0f, 0.0f, 10.0f);

            assertThat(ahead[0] / ahead[2]).isCloseTo(0.3f, within(EPSILON));

            // The reference camera faces the other way, so the same world point
            // is on the left. Not a quirk — walking round behind an object does
            // put its right-hand side on your left.
            final float[] behind = clip(reference(), 3.0f, 0.0f, 10.0f);

            assertThat(behind[0] / behind[2]).isCloseTo(-0.3f, within(EPSILON));
        }
    }

    @Nested
    @DisplayName("projection")
    class Projection
    {
        @Test
        @DisplayName("maps the frustum edges to ndc +/-1 at a 90 degree fov")
        void shouldMapFrustumEdgesToUnitNdcWhenFovIsNinetyDegrees()
        {
            final Camera camera = reference();

            // f = 1, so at depth z the visible extent is exactly +/- z.
            // The reference camera looks down +z, which is the back view, so
            // world +x sits at ndc -1. What this test is about is the
            // magnitude: the frustum edge maps to the edge of the screen.
            final float[] rightEdge = clip(camera, 10.0f, 0.0f, 10.0f);

            final float[] topEdge = clip(camera, 0.0f, 10.0f, 10.0f);

            final float[] centre = clip(camera, 0.0f, 0.0f, 10.0f);

            assertThat(rightEdge[0] / rightEdge[2]).isCloseTo(-1.0f, within(EPSILON));

            assertThat(topEdge[1] / topEdge[2]).isCloseTo(1.0f, within(EPSILON));

            assertThat(centre[0]).isCloseTo(0.0f, within(EPSILON));

            assertThat(centre[1]).isCloseTo(0.0f, within(EPSILON));
        }

        @Test
        @DisplayName("computes f as 1 / tan(fovY / 2)")
        void shouldComputeFocalScaleWhenFovIsSixtyDegrees()
        {
            final Camera camera = Camera.create(
                Vec3.ZERO, FORWARD_Z, WORLD_UP, FOV_60, 1.0f, NEAR);

            final float expected = (float) (1.0 / Math.tan(Math.PI / 6.0));

            assertThat(camera.projectionScaleY()).isCloseTo(expected, within(EPSILON));

            assertThat(camera.projectionScaleX()).isCloseTo(expected, within(EPSILON));

            // The top frustum edge at depth z sits at y = z * tan(fovY / 2).
            final float depth = 8.0f;

            final float edgeY = depth * (float) Math.tan(Math.PI / 6.0);

            final float[] out = clip(camera, 0.0f, edgeY, depth);

            assertThat(out[1] / out[2]).isCloseTo(1.0f, within(EPSILON));
        }

        @Test
        @DisplayName("divides only the x scale by the aspect ratio")
        void shouldDivideOnlyXByAspectWhenViewportIsWide()
        {
            final Camera camera = Camera.create(
                Vec3.ZERO, FORWARD_Z, WORLD_UP, FOV_90, 2.0f, NEAR);

            assertThat(camera.projectionScaleX()).isCloseTo(0.5f, within(EPSILON));

            assertThat(camera.projectionScaleY()).isCloseTo(1.0f, within(EPSILON));

            // Twice as wide, so the same world x lands at half the ndc x, while
            // ndc y is untouched. Negative because the reference direction is
            // the back view; the aspect divide is what this asserts.
            final float[] out = clip(camera, 10.0f, 10.0f, 10.0f);

            assertThat(out[0] / out[2]).isCloseTo(-0.5f, within(EPSILON));

            assertThat(out[1] / out[2]).isCloseTo(1.0f, within(EPSILON));
        }

        @Test
        @DisplayName("writes exactly three clip floats — there is no z row")
        void shouldWriteOnlyThreeFloatsWhenTransformingToClip()
        {
            // render/README.md § 4: the projection has no third row, because
            // the pipeline stores 1/w for depth and needs no window-space z.
            // A "helpfully" restored z row would show up as a fourth write.
            assertThat(Camera.CLIP_FLOATS).isEqualTo(3);

            final float[] out = new float[6];

            Arrays.fill(out, Float.NaN);

            reference().transformToClip(1.0f, 2.0f, 3.0f, out, 1);

            assertThat(out[0]).isNaN();

            assertThat(out[1]).isNotNaN();

            assertThat(out[2]).isNotNaN();

            assertThat(out[3]).isNotNaN();

            assertThat(out[4]).isNaN();

            assertThat(out[5]).isNaN();
        }

        @Test
        @DisplayName("packs the world-to-clip transform as three rows of four")
        void shouldPackTransformAsThreeRowsOfFourWhenCopiedOut()
        {
            assertThat(Camera.WORLD_TO_CLIP_FLOATS).isEqualTo(12);

            final float[] packed = new float[Camera.WORLD_TO_CLIP_FLOATS];

            reference().copyWorldToClipInto(packed, 0);

            // Reference camera: at the origin looking down +z, both scales 1.
            // The x row is negated because that is the back view — right is
            // world -x there. See the basis test above.
            assertThat(packed).containsExactly(new float[] {
                -1.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f, 0.0f,
            }, within(EPSILON));
        }
    }

    @Nested
    @DisplayName("view matrix")
    class ViewMatrix
    {
        @Test
        @DisplayName("maps the eye to the view-space origin")
        void shouldMapEyeToOriginWhenCameraIsTranslated()
        {
            final Vec3 eye = new Vec3(3.0f, -4.0f, 12.0f);

            final Camera camera = Camera.create(eye, FORWARD_Z, WORLD_UP, FOV_90, 1.0f, NEAR);

            final float[] out = new float[Mat4.ORDER];

            camera.viewMatrix().transformPoint(eye.x(), eye.y(), eye.z(), out, 0);

            assertThat(out[0]).isCloseTo(0.0f, within(EPSILON));

            assertThat(out[1]).isCloseTo(0.0f, within(EPSILON));

            assertThat(out[2]).isCloseTo(0.0f, within(EPSILON));

            assertThat(out[3]).isEqualTo(1.0f);
        }

        @Test
        @DisplayName("equals R transpose times T(-eye)")
        void shouldEqualRigidInverseCompositionWhenBuiltDirectly()
        {
            // render/README.md § 4: V = R^T . T(-eye), built directly, never via
            // a general inverse. Compose it the long way and compare.
            final Vec3 eye = new Vec3(-2.0f, 5.0f, 1.5f);

            final Camera camera = Camera.create(
                eye, new Vec3(1.0f, 0.0f, 1.0f), WORLD_UP, FOV_60, 1.5f, NEAR);

            final Vec3 r = camera.right();

            final Vec3 u = camera.up();

            final Vec3 f = camera.forward();

            final Mat4 rotationTranspose = Mat4.ofRowMajor(new float[] {
                r.x(), r.y(), r.z(), 0.0f,
                u.x(), u.y(), u.z(), 0.0f,
                f.x(), f.y(), f.z(), 0.0f,
                0.0f, 0.0f, 0.0f, 1.0f,
            });

            final Mat4 composed =
                rotationTranspose.multiply(Mat4.translation(-eye.x(), -eye.y(), -eye.z()));

            final Mat4 direct = camera.viewMatrix();

            for (int row = 0; row < Mat4.ORDER; row++)
            {
                for (int column = 0; column < Mat4.ORDER; column++)
                {
                    assertThat(direct.get(row, column))
                        .isCloseTo(composed.get(row, column), within(EPSILON));
                }
            }
        }

        @Test
        @DisplayName("moves a world point by the camera offset along forward")
        void shouldOffsetDepthByEyePositionWhenCameraIsPulledBack()
        {
            final Camera camera = Camera.create(
                new Vec3(0.0f, 0.0f, -5.0f), FORWARD_Z, WORLD_UP, FOV_90, 1.0f, NEAR);

            // The origin is 5 units in front of a camera sitting at z = -5.
            assertThat(clip(camera, 0.0f, 0.0f, 0.0f)[2]).isCloseTo(5.0f, within(EPSILON));

            assertThat(clip(camera, 0.0f, 0.0f, -5.0f)[2]).isCloseTo(0.0f, within(EPSILON));

            assertThat(clip(camera, 0.0f, 0.0f, -6.0f)[2]).isCloseTo(-1.0f, within(EPSILON));
        }

        @Test
        @DisplayName("rotates so that looking down +x puts world +z on the right")
        void shouldRotateBasisWhenLookingDownWorldX()
        {
            final Camera camera = Camera.create(
                Vec3.ZERO, new Vec3(1.0f, 0.0f, 0.0f), WORLD_UP, FOV_90, 1.0f, NEAR);

            // right = forward x up = (1,0,0) x (0,1,0) = (0,0,1).
            //
            // Physically: in a right-handed world with +x east and +y up, +z is
            // south (point east, curl up, the thumb goes south). Face east and
            // your right hand points south. So right = +z is the answer a
            // person standing there would give.
            assertThat(camera.right().z()).isCloseTo(1.0f, within(EPSILON));

            final float[] ahead = clip(camera, 10.0f, 0.0f, 0.0f);

            assertThat(ahead[2]).isCloseTo(10.0f, within(EPSILON));

            assertThat(ahead[0] / ahead[2]).isCloseTo(0.0f, within(EPSILON));

            final float[] offset = clip(camera, 10.0f, 0.0f, -3.0f);

            assertThat(offset[0] / offset[2]).isCloseTo(-0.3f, within(EPSILON));
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        @DisplayName("lookingAt matches create with forward = target - eye")
        void shouldMatchCreateWhenAimedAtATarget()
        {
            final Vec3 eye = new Vec3(1.0f, 2.0f, 3.0f);

            final Vec3 target = new Vec3(4.0f, 2.0f, 9.0f);

            final Camera aimed = Camera.lookingAt(eye, target, WORLD_UP, FOV_60, 1.25f, NEAR);

            final Camera direct = Camera.create(
                eye, target.subtract(eye), WORLD_UP, FOV_60, 1.25f, NEAR);

            final float[] a = new float[Camera.WORLD_TO_CLIP_FLOATS];

            final float[] b = new float[Camera.WORLD_TO_CLIP_FLOATS];

            aimed.copyWorldToClipInto(a, 0);

            direct.copyWorldToClipInto(b, 0);

            assertThat(a).containsExactly(b, within(EPSILON));
        }

        @Test
        @DisplayName("ignores the magnitude of forward and up")
        void shouldIgnoreInputMagnitudesWhenBasisIsNormalized()
        {
            final Camera unit = reference();

            final Camera scaled = Camera.create(
                Vec3.ZERO, FORWARD_Z.scale(500.0f), WORLD_UP.scale(0.001f),
                FOV_90, 1.0f, NEAR);

            final float[] a = new float[Camera.WORLD_TO_CLIP_FLOATS];

            final float[] b = new float[Camera.WORLD_TO_CLIP_FLOATS];

            unit.copyWorldToClipInto(a, 0);

            scaled.copyWorldToClipInto(b, 0);

            assertThat(a).containsExactly(b, within(EPSILON));
        }

        @Test
        @DisplayName("exposes the frustum parameters it was given")
        void shouldExposeFrustumParametersWhenConstructed()
        {
            final Vec3 eye = new Vec3(7.0f, 8.0f, 9.0f);

            final Camera camera = Camera.create(eye, FORWARD_Z, WORLD_UP, FOV_60, 1.75f, 0.25f);

            assertThat(camera.fovY()).isEqualTo(FOV_60);

            assertThat(camera.aspect()).isEqualTo(1.75f);

            assertThat(camera.near()).isEqualTo(0.25f);

            assertThat(camera.eye().x()).isEqualTo(7.0f);

            assertThat(camera.toString()).contains("near=0.25");
        }

        @Test
        @DisplayName("rejects a field of view outside (0, pi)")
        void shouldRejectWhenFieldOfViewIsOutOfRange()
        {
            assertThatThrownBy(
                () -> Camera.create(Vec3.ZERO, FORWARD_Z, WORLD_UP, 0.0f, 1.0f, NEAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fovY");

            assertThatThrownBy(
                () -> Camera.create(Vec3.ZERO, FORWARD_Z, WORLD_UP, (float) Math.PI, 1.0f, NEAR))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(
                () -> Camera.create(Vec3.ZERO, FORWARD_Z, WORLD_UP, Float.NaN, 1.0f, NEAR))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects a non-positive aspect ratio")
        void shouldRejectWhenAspectIsNotPositive()
        {
            assertThatThrownBy(
                () -> Camera.create(Vec3.ZERO, FORWARD_Z, WORLD_UP, FOV_90, 0.0f, NEAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aspect");

            assertThatThrownBy(
                () -> Camera.create(Vec3.ZERO, FORWARD_Z, WORLD_UP, FOV_90, -1.0f, NEAR))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects a non-positive near plane so that 1/near stays finite")
        void shouldRejectWhenNearIsNotPositive()
        {
            assertThatThrownBy(
                () -> Camera.create(Vec3.ZERO, FORWARD_Z, WORLD_UP, FOV_90, 1.0f, 0.0f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("near");

            assertThatThrownBy(
                () -> Camera.create(Vec3.ZERO, FORWARD_Z, WORLD_UP, FOV_90, 1.0f, -0.5f))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects a degenerate or ambiguous basis")
        void shouldRejectWhenBasisIsDegenerate()
        {
            assertThatThrownBy(
                () -> Camera.create(Vec3.ZERO, Vec3.ZERO, WORLD_UP, FOV_90, 1.0f, NEAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero-length");

            assertThatThrownBy(
                () -> Camera.create(Vec3.ZERO, WORLD_UP, WORLD_UP, FOV_90, 1.0f, NEAR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parallel");
        }
    }
}
