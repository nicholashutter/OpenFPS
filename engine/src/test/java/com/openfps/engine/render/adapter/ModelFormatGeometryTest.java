/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The in-memory {@link ModelFormat} factory — geometry with no file behind it.
 *
 * <p>Separate from {@code ModelFormatTest}, which is entirely about parsing a
 * byte image: magic, versions, section offsets, alignment and truncation. None
 * of those exist here, and the point of these tests is the half that does — the
 * invariants that are about the <b>geometry</b> rather than about the file, and
 * which therefore have to hold on both paths or the reader is not the only way
 * to get a bad model into the renderer.</p>
 */
@DisplayName("ModelFormat.ofGeometry")
final class ModelFormatGeometryTest
{
    /** A colour distinguishable from anything the fixtures use. */
    private static final int COLOUR = Rgba.pack(12, 34, 56, 255);

    /** Another one. */
    private static final int OTHER_COLOUR = Rgba.pack(200, 100, 50, 255);

    // A triangle with three distinct positions, so a transposed slot shows up.
    private static int[] triangleVertices()
    {
        final int[] vertices = new int[3 * ModelFormat.VERTEX_STRIDE_INTS];

        ModelFormat.writeVertex(vertices, 0, -1.0f, -2.0f, -3.0f, 0.0f, 0.0f, COLOUR);

        ModelFormat.writeVertex(vertices, 1, 4.0f, -2.0f, -3.0f, 1.0f, 0.0f, OTHER_COLOUR);

        ModelFormat.writeVertex(vertices, 2, 4.0f, 5.0f, 6.0f, 1.0f, 1.0f, COLOUR);

        return vertices;
    }

    private static ModelFormat triangle()
    {
        return ModelFormat.ofGeometry(triangleVertices(), new int[] {0, 1, 2});
    }

    @Nested
    @DisplayName("what it builds")
    final class Built
    {
        @Test
        @DisplayName("counts vertices, indices and triangles from the arrays it was given")
        void countsFollowTheArrays()
        {
            final ModelFormat model = triangle();

            assertThat(model.vertexCount()).isEqualTo(3);

            assertThat(model.indexCount()).isEqualTo(3);

            assertThat(model.triangleCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("every attribute reads back exactly, including the packed colour")
        void attributesSurvive()
        {
            final ModelFormat model = triangle();

            // Exact, not approximate: the factory stores the caller's float
            // bits and reinterprets them, so anything but equality would mean a
            // slot had been transposed rather than that arithmetic had drifted.
            assertThat(model.positionX(0)).isEqualTo(-1.0f);

            assertThat(model.positionY(0)).isEqualTo(-2.0f);

            assertThat(model.positionZ(0)).isEqualTo(-3.0f);

            assertThat(model.texCoordU(1)).isEqualTo(1.0f);

            assertThat(model.texCoordV(2)).isEqualTo(1.0f);

            assertThat(model.colour(0)).isEqualTo(COLOUR);

            assertThat(model.colour(1))
                .as("a per-vertex colour must not be flattened to the first one")
                .isEqualTo(OTHER_COLOUR);
        }

        @Test
        @DisplayName("computes the bounding box from the positions rather than taking one")
        void boundsAreComputed()
        {
            final ModelFormat model = triangle();

            assertThat(model.minX()).isEqualTo(-1.0f);

            assertThat(model.minY()).isEqualTo(-2.0f);

            assertThat(model.minZ()).isEqualTo(-3.0f);

            assertThat(model.maxX()).isEqualTo(4.0f);

            assertThat(model.maxY()).isEqualTo(5.0f);

            assertThat(model.maxZ()).isEqualTo(6.0f);
        }

        @Test
        @DisplayName("has no submeshes and no textures, which is what puts it on the flat path")
        void carriesNoMaterials()
        {
            final ModelFormat model = triangle();

            // Not an omission: an empty submesh table means every triangle is
            // NO_MATERIAL, which is the pre-existing path that shades from the
            // baked vertex colour. See the factory's Javadoc.
            assertThat(model.submeshCount()).isZero();

            assertThat(model.textureCount()).isZero();
        }

        @Test
        @DisplayName("declares the version this build writes")
        void versionIsThisBuild()
        {
            final ModelFormat model = triangle();

            assertThat(model.versionMajor()).isEqualTo(ModelFormat.VERSION_MAJOR);

            assertThat(model.versionMinor()).isEqualTo(ModelFormat.VERSION_MINOR);
        }

        @Test
        @DisplayName("copies both arrays, so the caller may reuse its scratch")
        void arraysAreCopied()
        {
            final int[] vertices = triangleVertices();

            final int[] indices = {0, 1, 2};

            final ModelFormat model = ModelFormat.ofGeometry(vertices, indices);

            vertices[ModelFormat.VERTEX_COLOUR] = 0;

            indices[0] = 2;

            assertThat(model.colour(0))
                .as("the model must not alias the caller's vertex block")
                .isEqualTo(COLOUR);

            assertThat(model.indices()[0])
                .as("nor its index array")
                .isZero();
        }

        @Test
        @DisplayName("an empty model is legal here and gets a zero box, not infinities")
        void emptyIsLegal()
        {
            final ModelFormat model = ModelFormat.ofGeometry(new int[0], new int[0]);

            assertThat(model.vertexCount()).isZero();

            assertThat(model.triangleCount()).isZero();

            // The orbit camera subtracts min from max; the empty-box infinities
            // would make that NaN. Scene refuses a model with no triangles, so
            // this box is never actually framed — but it must not be poison.
            assertThat(model.maxX() - model.minX()).isZero();
        }
    }

    @Nested
    @DisplayName("geometry it refuses")
    final class Refuses
    {
        @Test
        @DisplayName("a null vertex block or index array")
        void nullArrays()
        {
            assertThatThrownBy(() -> ModelFormat.ofGeometry(null, new int[0]))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("vertexSlots");

            assertThatThrownBy(() -> ModelFormat.ofGeometry(new int[0], null))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("triangleIndices");
        }

        @Test
        @DisplayName("a vertex block that is not a whole number of vertices")
        void raggedVertexBlock()
        {
            assertThatThrownBy(() ->
                ModelFormat.ofGeometry(new int[ModelFormat.VERTEX_STRIDE_INTS + 1], new int[0]))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("vertex stride");
        }

        @Test
        @DisplayName("an index count that is not a multiple of three — the parser's own rule")
        void indicesMustBeWholeTriangles()
        {
            assertThatThrownBy(() ->
                ModelFormat.ofGeometry(triangleVertices(), new int[] {0, 1}))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("multiple of " + ModelFormat.INDICES_PER_TRIANGLE);
        }

        @Test
        @DisplayName("an index that addresses past the last vertex")
        void indexOutOfRange()
        {
            assertThatThrownBy(() ->
                ModelFormat.ofGeometry(triangleVertices(), new int[] {0, 1, 3}))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("addresses outside");
        }

        @Test
        @DisplayName("a negative index, which is what an unsigned overflow looks like here")
        void negativeIndex()
        {
            assertThatThrownBy(() ->
                ModelFormat.ofGeometry(triangleVertices(), new int[] {0, 1, -1}))
                .isInstanceOf(ModelFormatException.class)
                .hasMessageContaining("addresses outside");
        }

        @Test
        @DisplayName("an index into an empty model, rather than reporting no triangles later")
        void indicesWithNoVertices()
        {
            assertThatThrownBy(() -> ModelFormat.ofGeometry(new int[0], new int[] {0, 0, 0}))
                .isInstanceOf(ModelFormatException.class);
        }

        @Test
        @DisplayName("geometry at the edge of legality is still accepted")
        void degenerateButAddressableIsFine()
        {
            // Three indices all naming vertex 0 is a zero-area triangle. That is
            // NOT this class's business to refuse: the rasterizer rejects a
            // degenerate triangle on its screen area, which is the only place
            // that can be decided, and hiding an instance behind a degenerate
            // transform depends on it.
            assertThatCode(() ->
                ModelFormat.ofGeometry(triangleVertices(), new int[] {0, 0, 0}))
                .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("agreement with the reader")
    final class AgreesWithTheParser
    {
        @Test
        @DisplayName("the same geometry through both paths produces the same model")
        void bothPathsAgree()
        {
            final int[] vertices = triangleVertices();

            final int[] indices = {0, 1, 2};

            final ModelFormat built = ModelFormat.ofGeometry(vertices, indices);

            final ModelFormat parsed = ModelFormat.read(ModelFileFixture.build(vertices, indices,
                new int[0], new int[0], new int[0],
                new float[] {-1.0f, -2.0f, -3.0f, 4.0f, 5.0f, 6.0f}));

            assertThat(built.vertexData()).containsExactly(parsed.vertexData());

            assertThat(built.indices()).containsExactly(parsed.indices());

            assertThat(built.triangleCount()).isEqualTo(parsed.triangleCount());

            // The bounds are declared on one path and computed on the other,
            // which is exactly the pair worth comparing.
            assertThat(built.minZ()).isEqualTo(parsed.minZ());

            assertThat(built.maxZ()).isEqualTo(parsed.maxZ());
        }
    }
}
