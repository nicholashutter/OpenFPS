/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.openfps.engine.render.adapter.ModelFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the generated stand-in carbine.
 *
 * <p>Only one property here is really worth asserting, and it is not the shape:
 * <b>the substitute must occupy the real model's box and point the same way</b>.
 * Three constants elsewhere are solved against {@code blaster-p.ofm}'s geometry —
 * the world scale, the muzzle flip, and the distance from the weapon's origin to
 * its muzzle, which is where a tracer and a puff of smoke are born. Every one of
 * them keeps working against either model only while this holds, and the failure
 * mode of it not holding is smoke coming out of the wrong end of a gun on the
 * machines that never staged the art.</p>
 */
@DisplayName("BlockCarbine")
final class BlockCarbineTest
{
    /** Model-space slop allowed against the real carbine's measured bounds. */
    private static final float TOLERANCE = 1.0e-4f;

    @Test
    @DisplayName("occupies exactly blaster-p's model-space box")
    void shouldMatchTheRealCarbinesExtent()
    {
        final ModelFormat carbine = BlockCarbine.model();

        assertThat(carbine.minX()).isEqualTo(-BlockCarbine.HALF_WIDTH, within(TOLERANCE));

        assertThat(carbine.maxX()).isEqualTo(BlockCarbine.HALF_WIDTH, within(TOLERANCE));

        assertThat(carbine.minY()).isEqualTo(-BlockCarbine.HALF_HEIGHT, within(TOLERANCE));

        assertThat(carbine.maxY()).isEqualTo(BlockCarbine.HALF_HEIGHT, within(TOLERANCE));

        assertThat(carbine.minZ()).isEqualTo(-BlockCarbine.HALF_LENGTH, within(TOLERANCE));

        assertThat(carbine.maxZ()).isEqualTo(BlockCarbine.HALF_LENGTH, within(TOLERANCE));
    }

    @Test
    @DisplayName("is a flat-shaded mesh with no submeshes and no textures")
    void shouldTakeTheFlatPath()
    {
        // The same contract DemoEffects' box and sphere use: no submesh table
        // means every triangle takes the flat path and gets its baked vertex
        // colour. A texture table would need an atlas, which is the whole thing
        // being worked around.
        final ModelFormat carbine = BlockCarbine.model();

        assertThat(carbine.submeshCount()).isZero();

        assertThat(carbine.textureCount()).isZero();

        assertThat(carbine.triangleCount())
            .isEqualTo(BlockCarbine.partCount() * 12);

        assertThat(carbine.triangleCount())
            .as("a stand-in must not cost more than the model it stands in for")
            .isLessThan(ModelFormat.MAX_TRIANGLES_PER_MODEL);
    }

    @Test
    @DisplayName("has nine parts: the original six plus muzzle, handguard, scope")
    void shouldHaveNineParts()
    {
        // Six base parts (barrel, receiver, stock, magazine, grip, sight) plus
        // three accents (muzzle device, handguard shell, scope tube) added so
        // the gun reads as a weapon at across-the-room distances, not just as
        // a long dark shape. The accents stay within the same +-HALF_* box as
        // the real model — see the "occupies exactly blaster-p's model-space
        // box" assertion, which is the contract that makes adding more parts
        // safe.
        assertThat(BlockCarbine.partCount())
            .as("six base parts plus three accents")
            .isEqualTo(9);
    }

    @Test
    @DisplayName("the muzzle end is the thin end")
    void shouldBeShapedLikeAWeapon()
    {
        // A cube would have satisfied the extent assertion above and would read as
        // a crate in a bot's hands. What makes this a carbine at across-the-room
        // distances is that the -z end is a thin barrel and the +z end is a
        // shoulder-width stock, so the silhouette tapers toward the muzzle.
        final ModelFormat carbine = BlockCarbine.model();

        final float atMuzzle = widthAt(carbine, -BlockCarbine.HALF_LENGTH + TOLERANCE);

        final float atStock = widthAt(carbine, BlockCarbine.HALF_LENGTH - TOLERANCE);

        assertThat(atMuzzle).isPositive();

        assertThat(atMuzzle)
            .as("the muzzle end is as thick as the stock, so this reads as a plank")
            .isLessThan(atStock);
    }

    // The widest the model gets across x among vertices at or beyond a z depth,
    // measuring toward whichever end that depth is nearer.
    private static float widthAt(final ModelFormat model, final float z)
    {
        // MUTABLE local — the widest span found so far at that depth.
        float widest = 0.0f;

        for (int vertex = 0; vertex < model.vertexCount(); vertex++)
        {
            final boolean towardMuzzle = z < 0.0f;

            final boolean reaches = towardMuzzle && model.positionZ(vertex) <= z
                || !towardMuzzle && model.positionZ(vertex) >= z;

            if (reaches)
            {
                widest = Math.max(widest, Math.abs(model.positionX(vertex)));
            }
        }

        return widest;
    }
}
