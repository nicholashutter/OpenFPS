/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.model;

import java.util.Arrays;

import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.engine.render.adapter.Rgba;
import com.openfps.tools.AssetBudgetException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip and budget tests for the build-time model writer.
 *
 * <p>{@code ModelFormatTest} in the engine reads bytes a human wrote, which
 * pins the layout. This suite closes the other half of the loop: what the
 * converter writes is what the engine reads. The two together mean a layout
 * change cannot pass unnoticed — it has to break one of them.</p>
 */
class ModelBuilderTest
{
    @Nested
    @DisplayName("round trip")
    class RoundTrip
    {
        @Test
        @DisplayName("geometry written here reads back identically through ModelFormat")
        void shouldRoundTripGeometry()
        {
            final ModelBuilder builder = new ModelBuilder("quad.glb");
            builder.beginSubmesh(ModelFormat.NO_TEXTURE);
            builder.addVertex(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, Rgba.pack(255, 0, 0, 255));
            builder.addVertex(2.0f, 0.0f, 0.0f, 1.0f, 0.0f, Rgba.pack(0, 255, 0, 255));
            builder.addVertex(2.0f, 3.0f, -1.0f, 1.0f, 1.0f, Rgba.pack(0, 0, 255, 255));
            builder.addTriangle(0, 1, 2);
            builder.endSubmesh();

            final ModelFormat model = ModelFormat.read(builder.toBytes());

            assertThat(model.vertexCount()).isEqualTo(3);
            assertThat(model.triangleCount()).isEqualTo(1);
            assertThat(model.indices()).containsExactly(0, 1, 2);
            assertThat(model.positionX(1)).isEqualTo(2.0f);
            assertThat(model.positionZ(2)).isEqualTo(-1.0f);
            assertThat(model.texCoordV(2)).isEqualTo(1.0f);
            assertThat(model.colour(0)).isEqualTo(Rgba.pack(255, 0, 0, 255));
        }

        @Test
        @DisplayName("the bounding box is computed from the vertices, not declared")
        void shouldComputeBounds()
        {
            final ModelBuilder builder = new ModelBuilder("bounds.glb");
            builder.beginSubmesh(ModelFormat.NO_TEXTURE);
            builder.addVertex(-4.0f, 1.0f, 0.5f, 0.0f, 0.0f, 0);
            builder.addVertex(2.0f, -7.0f, 9.0f, 0.0f, 0.0f, 0);
            builder.addVertex(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            builder.addTriangle(0, 1, 2);
            builder.endSubmesh();

            final ModelFormat model = ModelFormat.read(builder.toBytes());

            assertThat(model.minX()).isEqualTo(-4.0f);
            assertThat(model.minY()).isEqualTo(-7.0f);
            assertThat(model.minZ()).isEqualTo(0.0f);
            assertThat(model.maxX()).isEqualTo(2.0f);
            assertThat(model.maxY()).isEqualTo(1.0f);
            assertThat(model.maxZ()).isEqualTo(9.0f);
        }

        @Test
        @DisplayName("a model with no vertices writes a header-only file that still reads")
        void shouldWriteEmptyModel()
        {
            final byte[] image = new ModelBuilder("empty.glb").toBytes();

            assertThat(image).hasSize(ModelFormat.HEADER_SIZE);
            assertThat(ModelFormat.read(image).vertexCount()).isZero();
            assertThat(ModelFormat.read(image).minX()).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("each submesh keeps its own index range and texture")
        void shouldRoundTripSubmeshes()
        {
            final ModelBuilder builder = new ModelBuilder("two.glb");
            final int texture = builder.addTexture("atlas", 2, 2,
                MipGenerator.generate(2, 2, new int[4]));
            addTriangle(builder, texture);
            addTriangle(builder, ModelFormat.NO_TEXTURE);

            final ModelFormat model = ModelFormat.read(builder.toBytes());

            assertThat(model.submeshCount()).isEqualTo(2);
            assertThat(model.submeshFirstIndex(0)).isZero();
            assertThat(model.submeshIndexCount(0)).isEqualTo(3);
            assertThat(model.submeshTextureIndex(0)).isEqualTo(texture);
            assertThat(model.submeshFirstIndex(1)).isEqualTo(3);
            assertThat(model.submeshTextureIndex(1)).isEqualTo(ModelFormat.NO_TEXTURE);
        }

        @Test
        @DisplayName("a submesh that covered no triangles is dropped, not written empty")
        void shouldDropEmptySubmesh()
        {
            final ModelBuilder builder = new ModelBuilder("gap.glb");
            builder.beginSubmesh(ModelFormat.NO_TEXTURE);
            builder.endSubmesh();
            addTriangle(builder, ModelFormat.NO_TEXTURE);

            assertThat(ModelFormat.read(builder.toBytes()).submeshCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a texture and its whole mip chain survive the round trip")
        void shouldRoundTripTexture()
        {
            final int[] level0 =
            {
                Rgba.pack(255, 0, 0, 255), Rgba.pack(255, 0, 0, 255),
                Rgba.pack(255, 0, 0, 255), Rgba.pack(255, 0, 0, 255),
            };
            final ModelBuilder builder = new ModelBuilder("tex.glb");
            builder.addTexture("albedo", 2, 2, MipGenerator.generate(2, 2, level0));

            final ModelFormat model = ModelFormat.read(builder.toBytes());

            assertThat(model.textureCount()).isEqualTo(1);
            assertThat(model.textureWidth(0)).isEqualTo(2);
            assertThat(model.textureLevelCount(0)).isEqualTo(2);
            assertThat(model.mipChain(0).texel(0, 1, 1)).isEqualTo(Rgba.pack(255, 0, 0, 255));
            assertThat(model.mipChain(0).texel(1, 0, 0)).isEqualTo(Rgba.pack(255, 0, 0, 255));
        }

        @Test
        @DisplayName("several textures share one blob and each finds its own levels")
        void shouldRoundTripSeveralTextures()
        {
            final int[] red = new int[16];
            Arrays.fill(red, Rgba.pack(255, 0, 0, 255));
            final int[] blue = new int[4];
            Arrays.fill(blue, Rgba.pack(0, 0, 255, 255));

            final ModelBuilder builder = new ModelBuilder("multi.glb");
            builder.addTexture("red", 4, 4, MipGenerator.generate(4, 4, red));
            builder.addTexture("blue", 2, 2, MipGenerator.generate(2, 2, blue));

            final ModelFormat model = ModelFormat.read(builder.toBytes());

            assertThat(model.textureCount()).isEqualTo(2);
            assertThat(model.mipChain(0).texel(2, 0, 0)).isEqualTo(Rgba.pack(255, 0, 0, 255));
            assertThat(model.mipChain(1).texel(1, 0, 0)).isEqualTo(Rgba.pack(0, 0, 255, 255));
        }

        @Test
        @DisplayName("a model right at the triangle budget is written, not rejected")
        void shouldAcceptModelAtTheBudget()
        {
            final ModelBuilder builder = new ModelBuilder("dense.glb");
            builder.beginSubmesh(ModelFormat.NO_TEXTURE);
            builder.addVertex(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            builder.addVertex(1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            builder.addVertex(0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0);
            for (int i = 0; i < ModelFormat.MAX_TRIANGLES_PER_MODEL; i++)
            {
                builder.addTriangle(0, 1, 2);
            }
            builder.endSubmesh();

            assertThat(ModelFormat.read(builder.toBytes()).triangleCount())
                .isEqualTo(ModelFormat.MAX_TRIANGLES_PER_MODEL);
        }
    }

    @Nested
    @DisplayName("budget enforcement")
    class BudgetEnforcement
    {
        @Test
        @DisplayName("one triangle over budget fails, naming the file and the actual count")
        void shouldRejectTooManyTriangles()
        {
            final ModelBuilder builder = new ModelBuilder("overbudget.glb");
            builder.beginSubmesh(ModelFormat.NO_TEXTURE);
            builder.addVertex(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            builder.addVertex(1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
            builder.addVertex(0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0);
            for (int i = 0; i <= ModelFormat.MAX_TRIANGLES_PER_MODEL; i++)
            {
                builder.addTriangle(0, 1, 2);
            }
            builder.endSubmesh();

            assertThatThrownBy(builder::toBytes)
                .isInstanceOf(AssetBudgetException.class)
                .hasMessageContaining("overbudget.glb")
                .hasMessageContaining(Integer.toString(ModelFormat.MAX_TRIANGLES_PER_MODEL + 1))
                .hasMessageContaining(Integer.toString(ModelFormat.MAX_TRIANGLES_PER_MODEL));
        }

        @Test
        @DisplayName("an over-budget texture fails, naming the image and its size")
        void shouldRejectOversizedTexture()
        {
            final int edge = ModelFormat.MAX_TEXTURE_DIMENSION * 2;
            final ModelBuilder builder = new ModelBuilder("huge.glb");

            assertThatThrownBy(() -> builder.checkTextureDimensions("colormap", edge, edge))
                .isInstanceOf(AssetBudgetException.class)
                .hasMessageContaining("huge.glb")
                .hasMessageContaining("colormap")
                .hasMessageContaining(Integer.toString(edge))
                .hasMessageContaining(Integer.toString(ModelFormat.MAX_TEXTURE_DIMENSION));
        }

        @Test
        @DisplayName("a texture at exactly the budget is accepted")
        void shouldAcceptTextureAtTheBudget()
        {
            final ModelBuilder builder = new ModelBuilder("atbudget.glb");
            final int edge = ModelFormat.MAX_TEXTURE_DIMENSION;

            builder.checkTextureDimensions("colormap", edge, edge);

            assertThat(builder.textureCount()).isZero();
        }

        @Test
        @DisplayName("a non-power-of-two texture fails, explaining the mask wrapping")
        void shouldRejectNonPowerOfTwoTexture()
        {
            final ModelBuilder builder = new ModelBuilder("odd.glb");

            assertThatThrownBy(() -> builder.checkTextureDimensions("colormap", 100, 100))
                .isInstanceOf(AssetBudgetException.class)
                .hasMessageContaining("odd.glb")
                .hasMessageContaining("100")
                .hasMessageContaining("power of two");
        }

        @Test
        @DisplayName("a texture with no mip levels fails; mipmaps are required")
        void shouldRejectMissingMips()
        {
            final ModelBuilder builder = new ModelBuilder("flat.glb");

            assertThatThrownBy(() -> builder.addTexture("albedo", 2, 2, new int[0][]))
                .isInstanceOf(AssetBudgetException.class)
                .hasMessageContaining("required");
        }

        @Test
        @DisplayName("a mip level of the wrong size fails rather than reaching the runtime")
        void shouldRejectWrongSizedLevel()
        {
            final ModelBuilder builder = new ModelBuilder("bad.glb");
            final int[][] levels = {new int[4], new int[4]};

            assertThatThrownBy(() -> builder.addTexture("albedo", 2, 2, levels))
                .isInstanceOf(AssetBudgetException.class)
                .hasMessageContaining("mip level 1");
        }
    }

    // Adds one standalone triangle in its own submesh.
    private static void addTriangle(final ModelBuilder builder, final int texture)
    {
        final int base = builder.vertexCount();
        builder.beginSubmesh(texture);
        builder.addVertex(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
        builder.addVertex(1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0);
        builder.addVertex(0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0);
        builder.addTriangle(base, base + 1, base + 2);
        builder.endSubmesh();
    }
}
