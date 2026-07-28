/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.model;

import com.openfps.engine.render.adapter.ModelFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for the generated greybox room.
 *
 * <p>The property worth testing is <b>winding</b>, and it is not testable by
 * eye. A room whose faces are wound inward renders as an empty void under
 * backface culling, and a room whose faces are wound outward renders as a room
 * — but both produce identical vertex and triangle counts, identical bounds,
 * and identical file sizes. The signed-volume check below separates them: for
 * a closed mesh wound counter-clockwise as seen from outside, the divergence
 * theorem gives a positive volume, and for the same mesh wound the other way it
 * gives exactly the negative of it.</p>
 */
class ProceduralRoomTest
{
    /** Half of the divisor in the signed-volume sum over tetrahedra. */
    private static final float SIXTH = 1.0f / 6.0f;

    /**
     * Volume of the five boxes: a 16 x 0.5 x 16 floor slab, two 17 x 4 x 0.5
     * walls spanning the full outer extent, and two 0.5 x 4 x 16 walls filling
     * between them.
     */
    private static final float EXPECTED_VOLUME = 128.0f + 34.0f + 34.0f + 32.0f + 32.0f;

    /** Tolerance for the accumulated float sum. */
    private static final float VOLUME_EPSILON = 0.01f;

    @Nested
    @DisplayName("geometry")
    class Geometry
    {
        @Test
        @DisplayName("every face is wound counter-clockwise as seen from outside")
        void shouldWindOutward()
        {
            final ModelFormat room = ModelFormat.read(ProceduralRoom.build());
            assertThat(signedVolume(room)).isCloseTo(EXPECTED_VOLUME, within(VOLUME_EPSILON));
        }

        @Test
        @DisplayName("five closed boxes cost sixty triangles")
        void shouldCountTriangles()
        {
            final ModelFormat room = ModelFormat.read(ProceduralRoom.build());
            assertThat(room.triangleCount()).isEqualTo(ProceduralRoom.triangleCount());
            assertThat(room.triangleCount()).isEqualTo(60);
            assertThat(room.triangleCount()).isLessThan(ModelFormat.MAX_TRIANGLES_PER_MODEL);
        }

        @Test
        @DisplayName("the bounds enclose the walls, not just the floor")
        void shouldReportBounds()
        {
            final ModelFormat room = ModelFormat.read(ProceduralRoom.build());
            final float outer = ProceduralRoom.INTERIOR_HALF_EXTENT
                + ProceduralRoom.SLAB_THICKNESS;
            assertThat(room.minX()).isEqualTo(-outer);
            assertThat(room.maxX()).isEqualTo(outer);
            assertThat(room.minZ()).isEqualTo(-outer);
            assertThat(room.maxZ()).isEqualTo(outer);
            assertThat(room.minY()).isEqualTo(-ProceduralRoom.SLAB_THICKNESS);
            assertThat(room.maxY()).isEqualTo(ProceduralRoom.WALL_HEIGHT);
        }
    }

    @Nested
    @DisplayName("materials")
    class Materials
    {
        @Test
        @DisplayName("floor and wall are separate textures in separate submeshes")
        void shouldSplitByMaterial()
        {
            final ModelFormat room = ModelFormat.read(ProceduralRoom.build());
            assertThat(room.textureCount()).isEqualTo(2);
            assertThat(room.submeshCount()).isEqualTo(2);
            assertThat(room.submeshTextureIndex(0))
                .isNotEqualTo(room.submeshTextureIndex(1));
        }

        @Test
        @DisplayName("both textures are power-of-two with a full mip pyramid")
        void shouldCarryMips()
        {
            final ModelFormat room = ModelFormat.read(ProceduralRoom.build());
            for (int texture = 0; texture < room.textureCount(); texture++)
            {
                assertThat(room.textureWidth(texture)).isEqualTo(ProceduralRoom.TEXTURE_EDGE);
                assertThat(room.textureHeight(texture)).isEqualTo(ProceduralRoom.TEXTURE_EDGE);
                assertThat(room.textureLevelCount(texture)).isEqualTo(
                    MipGenerator.levelCount(ProceduralRoom.TEXTURE_EDGE,
                        ProceduralRoom.TEXTURE_EDGE));
            }
        }
    }

    // The divergence theorem over a triangle soup: each triangle contributes
    // the signed volume of the tetrahedron it forms with the origin, and for a
    // closed outward-wound mesh those sum to the enclosed volume.
    private static float signedVolume(final ModelFormat model)
    {
        final int[] indices = model.indices();
        // MUTABLE local — the running sum.
        float total = 0.0f;
        for (int at = 0; at < indices.length; at += ModelFormat.INDICES_PER_TRIANGLE)
        {
            final int a = indices[at];
            final int b = indices[at + 1];
            final int c = indices[at + 2];

            final float bx = model.positionX(b);
            final float by = model.positionY(b);
            final float bz = model.positionZ(b);
            final float cx = model.positionX(c);
            final float cy = model.positionY(c);
            final float cz = model.positionZ(c);

            final float crossX = (by * cz) - (bz * cy);
            final float crossY = (bz * cx) - (bx * cz);
            final float crossZ = (bx * cy) - (by * cx);

            total += ((model.positionX(a) * crossX) + (model.positionY(a) * crossY)
                + (model.positionZ(a) * crossZ)) * SIXTH;
        }
        return total;
    }
}
