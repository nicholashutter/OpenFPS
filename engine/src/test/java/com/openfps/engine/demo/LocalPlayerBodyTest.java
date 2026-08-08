/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.openfps.engine.render.adapter.Mat4;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the local player body's per-tic placement.
 *
 * <p>The transform that puts the arms at the eye is the only place in the
 * demo where the camera basis meets a world transform, and it is the one
 * piece of math that is easy to get wrong in a way that compiles. These
 * tests pin it down by transforming known view-space points and asserting
 * where they land in world space.</p>
 *
 * <p>The convention under test: view {@code +X} is the player's right,
 * view {@code +Y} is up, view {@code +Z} is <b>behind</b> the eye (a
 * part in front of the eye is at negative view Z). The local-to-world
 * matrix is the right-handed basis {@code (right, up, -forward)}, so the
 * matrix has positive determinant — the only form {@code Scene} accepts.</p>
 */
@DisplayName("LocalPlayerBody")
final class LocalPlayerBodyTest
{
    /** Floating-point slop for the transform assertions. */
    private static final float TOLERANCE = 1.0e-4f;

    @Nested
    @DisplayName("armsTransform")
    final class ArmsTransform
    {
        @Test
        @DisplayName("at zero yaw and pitch, view +X lands on world -X (player's right)")
        void shouldMapViewRightToWorldNegativeX()
        {
            // PlayerController's basis: facing +z at yaw 0, the player's
            // right is -x. The view model is built in +X = right, so the
            // transform must send a view-space +X to world -X.
            final Mat4 m = LocalPlayerBody.armsTransform(0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f);
            final float[] out = new float[4];
            m.transformPoint(1.0f, 0.0f, 0.0f, out, 0);

            assertThat(out[0]).isEqualTo(-1.0f, within(TOLERANCE));
            assertThat(out[1]).isEqualTo(0.0f, within(TOLERANCE));
            assertThat(out[2]).isEqualTo(0.0f, within(TOLERANCE));
        }

        @Test
        @DisplayName("at zero yaw and pitch, view +Y lands on world +Y (up is up)")
        void shouldMapViewUpToWorldUp()
        {
            final Mat4 m = LocalPlayerBody.armsTransform(0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f);
            final float[] out = new float[4];
            m.transformPoint(0.0f, 1.0f, 0.0f, out, 0);

            assertThat(out[0]).isEqualTo(0.0f, within(TOLERANCE));
            assertThat(out[1]).isEqualTo(1.0f, within(TOLERANCE));
            assertThat(out[2]).isEqualTo(0.0f, within(TOLERANCE));
        }

        @Test
        @DisplayName("at zero yaw and pitch, view +Z lands behind the eye (world -Z)")
        void shouldMapViewPositiveZToWorldNegativeZ()
        {
            // The model is in a right-handed view space where +Z is
            // behind the eye. A point at view (0, 0, 1) lands at world
            // (0, 0, -1) — behind the eye, where nothing the player can
            // see belongs.
            final Mat4 m = LocalPlayerBody.armsTransform(0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f);
            final float[] out = new float[4];
            m.transformPoint(0.0f, 0.0f, 1.0f, out, 0);

            assertThat(out[0]).isEqualTo(0.0f, within(TOLERANCE));
            assertThat(out[1]).isEqualTo(0.0f, within(TOLERANCE));
            assertThat(out[2]).isEqualTo(-1.0f, within(TOLERANCE));
        }

        @Test
        @DisplayName("at zero yaw and pitch, view -Z lands in front of the eye (world +Z)")
        void shouldMapViewNegativeZToWorldPositiveZ()
        {
            // The hands live at negative view Z and end up in front of
            // the eye in world space — that is the whole reason the
            // player can see them.
            final Mat4 m = LocalPlayerBody.armsTransform(0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f);
            final float[] out = new float[4];
            m.transformPoint(0.0f, 0.0f, -1.0f, out, 0);

            assertThat(out[0]).isEqualTo(0.0f, within(TOLERANCE));
            assertThat(out[1]).isEqualTo(0.0f, within(TOLERANCE));
            assertThat(out[2]).isEqualTo(1.0f, within(TOLERANCE));
        }

        @Test
        @DisplayName("at yaw PI/2, the view right vector follows the player round")
        void shouldRotateViewRightWithYaw()
        {
            // Yaw PI/2: player faces +x. groundRight = (-cos(PI/2), 0, sin(PI/2))
            // = (0, 0, 1) — the player's right is now +z. So view +X (right)
            // must map to world +Z.
            final Mat4 m = LocalPlayerBody.armsTransform(0.0f, 0.0f, 0.0f,
                (float) (Math.PI / 2.0), 0.0f, 0.0f);
            final float[] out = new float[4];
            m.transformPoint(1.0f, 0.0f, 0.0f, out, 0);

            assertThat(out[0]).isEqualTo(0.0f, within(TOLERANCE));
            assertThat(out[1]).isEqualTo(0.0f, within(TOLERANCE));
            assertThat(out[2]).isEqualTo(1.0f, within(TOLERANCE));
        }

        @Test
        @DisplayName("at positive pitch, the view -Z vector tilts up")
        void shouldTiltForwardWithPitch()
        {
            // The hands live at negative view Z. A point at view (0, 0, -1)
            // at yaw 0, pitch PI/4 lands at world (0, sin(PI/4), cos(PI/4))
            // = (0, 0.707, 0.707): the forward direction tilted up.
            final float pitch = (float) (Math.PI / 4.0);
            final Mat4 m = LocalPlayerBody.armsTransform(0.0f, 0.0f, 0.0f,
                0.0f, pitch, 0.0f);
            final float[] out = new float[4];
            m.transformPoint(0.0f, 0.0f, -1.0f, out, 0);

            assertThat(out[0]).isEqualTo(0.0f, within(TOLERANCE));
            assertThat(out[1]).isEqualTo((float) Math.sin(pitch), within(TOLERANCE));
            assertThat(out[2]).isEqualTo((float) Math.cos(pitch), within(TOLERANCE));
        }

        @Test
        @DisplayName("the eye translation is added to every world point")
        void shouldTranslateByEye()
        {
            // A non-zero eye position must shift every point by the same
            // amount. The arms are a rigid body, so the relative positions
            // are unchanged and the absolute positions move with the eye.
            final Mat4 m = LocalPlayerBody.armsTransform(100.0f, 50.0f, -200.0f,
                0.0f, 0.0f, 0.0f);
            final float[] out = new float[4];
            m.transformPoint(0.0f, 0.0f, 0.0f, out, 0);

            assertThat(out[0]).isEqualTo(100.0f, within(TOLERANCE));
            assertThat(out[1]).isEqualTo(50.0f, within(TOLERANCE));
            assertThat(out[2]).isEqualTo(-200.0f, within(TOLERANCE));
        }

        @Test
        @DisplayName("the bob is added to the world Y translation")
        void shouldAddBobToWorldY()
        {
            // The bob is a world-Y offset, applied after the rotation. So a
            // bob of +10 raises every point by 10 in world Y, regardless of
            // where the player is looking.
            final Mat4 without = LocalPlayerBody.armsTransform(0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 0.0f);
            final Mat4 with = LocalPlayerBody.armsTransform(0.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 10.0f);
            final float[] base = new float[4];
            final float[] bobbed = new float[4];
            // A point in front of the eye in view space (negative Z).
            without.transformPoint(0.5f, -0.4f, -1.5f, base, 0);
            with.transformPoint(0.5f, -0.4f, -1.5f, bobbed, 0);

            assertThat(bobbed[0]).isEqualTo(base[0], within(TOLERANCE));
            assertThat(bobbed[1]).isEqualTo(base[1] + 10.0f, within(TOLERANCE));
            assertThat(bobbed[2]).isEqualTo(base[2], within(TOLERANCE));
        }

        @Test
        @DisplayName("the matrix is orientation-preserving (positive determinant)")
        void shouldHavePositiveDeterminant()
        {
            // Scene rejects a non-affine or orientation-reversing transform
            // outright. The arms transform uses a right-handed basis
            // (right, up, -forward) so the determinant of its upper-left
            // 3x3 is +1. Pin it down: a sign flip here would render the
            // arms inside-out, which looks like a plausible shape rather
            // than like an error.
            final Mat4 m = LocalPlayerBody.armsTransform(0.0f, 41.0f, 0.0f,
                1.3f, -0.2f, 0.0f);
            final float m00 = m.get(0, 0);
            final float m01 = m.get(0, 1);
            final float m02 = m.get(0, 2);
            final float m10 = m.get(1, 0);
            final float m11 = m.get(1, 1);
            final float m12 = m.get(1, 2);
            final float m20 = m.get(2, 0);
            final float m21 = m.get(2, 1);
            final float m22 = m.get(2, 2);

            // det = m00*(m11*m22 - m12*m21)
            //     - m01*(m10*m22 - m12*m20)
            //     + m02*(m10*m21 - m11*m20)
            final float det = m00 * (m11 * m22 - m12 * m21)
                - m01 * (m10 * m22 - m12 * m20)
                + m02 * (m10 * m21 - m11 * m20);

            assertThat(det)
                .as("a negative determinant would mirror the arms")
                .isGreaterThan(0.0f);
        }
    }
}
