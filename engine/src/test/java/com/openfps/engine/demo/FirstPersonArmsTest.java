/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.openfps.engine.render.adapter.ModelFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the procedurally generated first-person arms.
 *
 * <p>The arms are flat-shaded geometry — no submeshes, no textures, no
 * normals — and that contract is the only thing about the model that is
 * worth asserting as a unit. The placement is a separate question, and
 * {@code LocalPlayerBodyTest} covers it.</p>
 */
@DisplayName("FirstPersonArms")
final class FirstPersonArmsTest
{
    /** Floating-point slop for the constant-only assertions. */
    private static final float TOLERANCE = 1.0e-4f;

    @Nested
    @DisplayName("the model")
    final class Model
    {
        @Test
        @DisplayName("is a flat-shaded mesh with no submeshes and no textures")
        void shouldTakeTheFlatPath()
        {
            // The same contract BlockCarbine and DemoEffects' box use: no
            // submesh table means every triangle takes the flat path and
            // gets its baked vertex colour. A texture table would need an
            // atlas, which is the whole thing being worked around.
            final ModelFormat arms = FirstPersonArms.model();

            assertThat(arms.submeshCount()).isZero();

            assertThat(arms.textureCount()).isZero();

            assertThat(arms.triangleCount())
                .isEqualTo(FirstPersonArms.partCount() * 12);

            assertThat(arms.triangleCount())
                .as("a stand-in must not cost more than the model it stands in for")
                .isLessThan(ModelFormat.MAX_TRIANGLES_PER_MODEL);
        }

        @Test
        @DisplayName("sits in front of the eye, not behind it")
        void shouldReachForwardOfTheEye()
        {
            // The model is in a right-handed view space where +Z is
            // behind the eye, so every part has a non-positive z — a
            // part at z > 0 would end up behind the camera in world
            // space and would suggest a transposed sign in the
            // authoring. The chest piece touches z = -0.1, which is
            // the tightest gap before the model ends behind the eye.
            final ModelFormat arms = FirstPersonArms.model();

            for (int vertex = 0; vertex < arms.vertexCount(); vertex++)
            {
                assertThat(arms.positionZ(vertex))
                    .as("vertex %d z is non-positive (in front of the eye)", vertex)
                    .isLessThanOrEqualTo(TOLERANCE);
            }
        }

        @Test
        @DisplayName("extends from below the eye to just past the held weapon")
        void shouldReachTheWeapon()
        {
            // The blaster viewmodel is at (0.92, -0.38, 1.85) in view space
            // and its grip end is around z = 1.45. The hands are at view
            // z = -1.36 (right) and -1.50 (left), so minZ in the model is
            // around -1.68. A model that did not reach forward enough
            // would have minZ closer to 0.
            final ModelFormat arms = FirstPersonArms.model();

            assertThat(arms.minZ())
                .as("the hands must reach at least the grip end of the viewmodel")
                .isLessThan(-1.4f);
        }
    }

    @Nested
    @DisplayName("part count")
    final class PartCount
    {
        @Test
        @DisplayName("is at least six: two forearms, two hands, two cuffs")
        void shouldHaveAtLeastSixParts()
        {
            // Six is the minimum for "two arms" — a forearm and a hand for
            // each side, with an optional cuff or chest piece on top. The
            // actual count is seven (with the chest wedge), but the minimum
            // the test cares about is six: dropping below that means one of
            // the hands or forearms is gone, and the player is holding the
            // gun with one hand or no hands.
            assertThat(FirstPersonArms.partCount()).isGreaterThanOrEqualTo(6);
        }
    }
}
