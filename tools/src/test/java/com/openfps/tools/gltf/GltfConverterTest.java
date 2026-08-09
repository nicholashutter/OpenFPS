/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.tools.gltf;

import java.nio.charset.StandardCharsets;

import com.google.gson.JsonObject;
import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.engine.render.adapter.Rgba;
import com.openfps.tools.AssetBudgetException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

/**
 * Tests for the build-time glTF to {@link ModelFormat} converter.
 *
 * <h2>What this covers, and what it does not</h2>
 *
 * <p>Every fixture is assembled in the test process by {@link GltfFixtures},
 * so the suite is hermetic: it needs no {@code fetchAssets} run, no network
 * and no Kenney payload, which matters because {@code fetchAssets} is
 * deliberately not wired into {@code build}. That covers the container
 * handling, accessor decoding, scene-graph flattening, triangulation, texture
 * decode, colour baking and every budget rejection.</p>
 *
 * <p><b>It does not cover a real upstream asset.</b> Nothing here proves that
 * a Kenney Blaster Kit GLB converts, or that the result looks right — that
 * needs the published {@code assets-v1} release, which does not exist yet
 * ({@code docs/ASSETS.md} § 9), and it needs a rasterizer to draw the output,
 * which is a different lane. The first real conversion is likely to surface
 * something no synthetic fixture predicted; this suite is what makes that a
 * short debugging session rather than a long one.</p>
 */
class GltfConverterTest
{
    /** Three vertices in the z = 0 plane; the smallest thing with an area. */
    private static final float[] TRIANGLE =
    {
        0.0f, 0.0f, 0.0f,
        1.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
    };

    /** Matching texture coordinates. */
    private static final float[] TRIANGLE_UV =
    {
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
    };

    @Nested
    @DisplayName("containers")
    class Containers
    {
        @Test
        @DisplayName("a minimal .gltf with an embedded buffer converts and reads back")
        void shouldConvertEmbeddedGltf()
        {
            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("tri.gltf", triangleFixture().gltf()));

            assertThat(model.vertexCount()).isEqualTo(3);

            assertThat(model.triangleCount()).isEqualTo(1);

            assertThat(model.submeshCount()).isEqualTo(1);

            assertThat(model.indices()).containsExactly(0, 1, 2);

            assertThat(model.positionX(1)).isEqualTo(1.0f);

            assertThat(model.positionY(2)).isEqualTo(1.0f);
        }

        @Test
        @DisplayName("the GLB form of the same asset produces byte-identical output")
        void shouldConvertGlbIdentically()
        {
            final byte[] fromGltf = GltfConverter.convert("tri.gltf", triangleFixture().gltf());

            final byte[] fromGlb = GltfConverter.convert("tri.glb", triangleFixture().glb());

            assertThat(fromGlb).isEqualTo(fromGltf);
        }

        @Test
        @DisplayName("the container is detected from the magic, not the file extension")
        void shouldDetectContainerFromMagic()
        {
            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("misnamed.gltf", triangleFixture().glb()));

            assertThat(model.vertexCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("a GLB of an unknown container version is refused")
        void shouldRejectGlbVersion()
        {
            final byte[] glb = triangleFixture().glb();

            glb[4] = 9;

            assertThatThrownBy(() -> GltfConverter.convert("bad.glb", glb))
                .isInstanceOf(GltfException.class)
                .hasMessageContaining("version 9");
        }

        @Test
        @DisplayName("a GLB whose declared length disagrees with the file is refused")
        void shouldRejectGlbLength()
        {
            final byte[] glb = triangleFixture().glb();

            glb[8] = 0;

            assertThatThrownBy(() -> GltfConverter.convert("bad.glb", glb))
                .isInstanceOf(GltfException.class)
                .hasMessageContaining("truncated or padded");
        }

        @Test
        @DisplayName("a GLB with a chunk running past the end of the file is refused")
        void shouldRejectGlbChunkOverrun()
        {
            final byte[] glb = triangleFixture().glb();

            glb[13] = (byte) 0xFF;

            glb[14] = (byte) 0xFF;

            assertThatThrownBy(() -> GltfConverter.convert("bad.glb", glb))
                .isInstanceOf(GltfException.class)
                .hasMessageContaining("past the end");
        }

        @Test
        @DisplayName("a GLB with no JSON chunk is refused")
        void shouldRejectGlbWithoutJson()
        {
            final byte[] glb = GltfFixtures.binOnlyGlb();

            assertThatThrownBy(() -> GltfConverter.convert("empty.glb", glb))
                .isInstanceOf(GltfException.class)
                .hasMessageContaining("no JSON chunk");
        }

        @Test
        @DisplayName("a file that is neither GLB nor JSON is refused")
        void shouldRejectGarbage()
        {
            assertThatThrownBy(() -> GltfConverter.convert("junk.bin", new byte[] {1, 2, 3, 4}))
                .isInstanceOf(GltfException.class);
        }

        @Test
        @DisplayName("a glTF 1.0 document is refused rather than half-understood")
        void shouldRejectGltfVersionOne()
        {
            final byte[] source = triangleFixture().version("1.0").gltf();

            assertThatThrownBy(() -> GltfConverter.convert("old.gltf", source))
                .isInstanceOf(GltfException.class)
                .hasMessageContaining("2.x");
        }

        @Test
        @DisplayName("JSON without an asset block is refused")
        void shouldRejectNonGltfJson()
        {
            final byte[] source = "{\"hello\":1}".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> GltfConverter.convert("plain.json", source))
                .isInstanceOf(GltfException.class)
                .hasMessageContaining("asset.version");
        }
    }

    @Nested
    @DisplayName("scene flattening")
    class SceneFlattening
    {
        @Test
        @DisplayName("a node's translation is baked into its vertices")
        void shouldBakeTranslation()
        {
            final GltfFixtures fixture = new GltfFixtures();

            final int mesh = simpleMesh(fixture, -1);

            fixture.scene(fixture.node(mesh, new float[] {10.0f, 20.0f, 30.0f}, null, null));

            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("moved.gltf", fixture.gltf()));

            assertThat(model.positionX(0)).isEqualTo(10.0f);

            assertThat(model.positionY(0)).isEqualTo(20.0f);

            assertThat(model.positionZ(0)).isEqualTo(30.0f);

            assertThat(model.positionX(1)).isEqualTo(11.0f);
        }

        @Test
        @DisplayName("a node's scale is baked in")
        void shouldBakeScale()
        {
            final GltfFixtures fixture = new GltfFixtures();

            final int mesh = simpleMesh(fixture, -1);

            fixture.scene(fixture.node(mesh, null, null, new float[] {3.0f, 4.0f, 5.0f}));

            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("scaled.gltf", fixture.gltf()));

            assertThat(model.positionX(1)).isEqualTo(3.0f);

            assertThat(model.positionY(2)).isEqualTo(4.0f);
        }

        @Test
        @DisplayName("a quarter turn about z maps +x onto +y")
        void shouldBakeRotation()
        {
            final float half = (float) Math.sqrt(0.5);

            final GltfFixtures fixture = new GltfFixtures();

            final int mesh = simpleMesh(fixture, -1);

            fixture.scene(fixture.node(mesh, null, new float[] {0.0f, 0.0f, half, half}, null));

            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("turned.gltf", fixture.gltf()));

            assertThat(model.positionX(1)).isCloseTo(0.0f, offset(1.0e-6f));

            assertThat(model.positionY(1)).isCloseTo(1.0f, offset(1.0e-6f));
        }

        @Test
        @DisplayName("an explicit column-major matrix is transposed correctly into row-major")
        void shouldBakeExplicitMatrix()
        {
            final float[] columnMajor =
            {
                2.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 2.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 2.0f, 0.0f,
                5.0f, 6.0f, 7.0f, 1.0f,
            };

            final GltfFixtures fixture = new GltfFixtures();

            final int mesh = simpleMesh(fixture, -1);

            fixture.scene(fixture.matrixNode(mesh, columnMajor));

            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("matrix.gltf", fixture.gltf()));

            assertThat(model.positionX(0)).isEqualTo(5.0f);

            assertThat(model.positionY(0)).isEqualTo(6.0f);

            assertThat(model.positionZ(0)).isEqualTo(7.0f);

            assertThat(model.positionX(1)).isEqualTo(7.0f);
        }

        @Test
        @DisplayName("a child node composes its transform with its parent's")
        void shouldComposeParentAndChild()
        {
            final GltfFixtures fixture = new GltfFixtures();

            final int mesh = simpleMesh(fixture, -1);

            final int child = fixture.node(mesh, new float[] {1.0f, 0.0f, 0.0f}, null, null);

            final int parent = fixture.node(-1, new float[] {10.0f, 0.0f, 0.0f}, null, null);

            fixture.children(parent, child);

            fixture.scene(parent);

            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("nested.gltf", fixture.gltf()));

            assertThat(model.positionX(0)).isEqualTo(11.0f);
        }

        @Test
        @DisplayName("a node that is its own ancestor is refused, not followed forever")
        void shouldRejectNodeCycle()
        {
            final GltfFixtures fixture = new GltfFixtures();

            final int mesh = simpleMesh(fixture, -1);

            final int node = fixture.node(mesh, null, null, null);

            fixture.children(node, node);

            fixture.scene(node);

            assertThatThrownBy(() -> GltfConverter.convert("cycle.gltf", fixture.gltf()))
                .isInstanceOf(GltfException.class)
                .hasMessageContaining("own ancestor");
        }

        @Test
        @DisplayName("a document with no scene still converts every mesh it has")
        void shouldConvertMeshesWithoutAScene()
        {
            final GltfFixtures fixture = new GltfFixtures();

            simpleMesh(fixture, -1);

            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("sceneless.gltf", fixture.gltf()));

            assertThat(model.triangleCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("the bounding box is computed from the baked, not the source, positions")
        void shouldComputeBoundsAfterBaking()
        {
            final GltfFixtures fixture = new GltfFixtures();

            final int mesh = simpleMesh(fixture, -1);

            fixture.scene(fixture.node(mesh, new float[] {5.0f, 5.0f, 5.0f}, null, null));

            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("bounds.gltf", fixture.gltf()));

            assertThat(model.minX()).isEqualTo(5.0f);

            assertThat(model.maxX()).isEqualTo(6.0f);

            assertThat(model.maxY()).isEqualTo(6.0f);

            assertThat(model.minZ()).isEqualTo(5.0f);
        }
    }

    @Nested
    @DisplayName("topology")
    class Topology
    {
        @Test
        @DisplayName("a triangle strip expands into a triangle list with alternating winding")
        void shouldExpandTriangleStrip()
        {
            final GltfFixtures fixture = new GltfFixtures();

            final int positions = fixture.floats("VEC3",
                0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f);

            final int indices = fixture.indices(GltfFixtures.UNSIGNED_SHORT, 0, 1, 2, 3);

            fixture.mesh(GltfFixtures.primitive(positions, -1, -1, indices, -1, 5));

            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("strip.gltf", fixture.gltf()));

            assertThat(model.triangleCount()).isEqualTo(2);

            assertThat(model.indices()).containsExactly(0, 1, 2, 2, 1, 3);
        }

        @Test
        @DisplayName("a triangle fan expands with every triangle pivoting on vertex zero")
        void shouldExpandTriangleFan()
        {
            final GltfFixtures fixture = new GltfFixtures();

            final int positions = fixture.floats("VEC3",
                0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f);

            final int indices = fixture.indices(GltfFixtures.UNSIGNED_SHORT, 0, 1, 2, 3);

            fixture.mesh(GltfFixtures.primitive(positions, -1, -1, indices, -1, 6));

            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("fan.gltf", fixture.gltf()));

            assertThat(model.triangleCount()).isEqualTo(2);

            assertThat(model.indices()).containsExactly(0, 1, 2, 0, 2, 3);
        }

        @Test
        @DisplayName("a line or point primitive is refused; there is nothing to rasterize")
        void shouldRejectNonSurfaceTopology()
        {
            final GltfFixtures fixture = new GltfFixtures();

            final int positions = fixture.floats("VEC3", TRIANGLE);

            fixture.mesh(GltfFixtures.primitive(positions, -1, -1, -1, -1, 1));

            assertThatThrownBy(() -> GltfConverter.convert("lines.gltf", fixture.gltf()))
                .isInstanceOf(GltfException.class)
                .hasMessageContaining("mode 1");
        }

        @Test
        @DisplayName("a primitive with no index accessor uses an implicit 0..n-1 sequence")
        void shouldSynthesiseIndices()
        {
            final GltfFixtures fixture = new GltfFixtures();

            final int positions = fixture.floats("VEC3", TRIANGLE);

            fixture.mesh(GltfFixtures.primitive(positions, -1, -1, -1, -1, 4));

            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("implicit.gltf", fixture.gltf()));

            assertThat(model.indices()).containsExactly(0, 1, 2);
        }

        @Test
        @DisplayName("each primitive becomes its own submesh")
        void shouldMapPrimitivesToSubmeshes()
        {
            final GltfFixtures fixture = new GltfFixtures();

            final int positions = fixture.floats("VEC3", TRIANGLE);

            fixture.mesh(GltfFixtures.primitive(positions, -1, -1, -1, -1, 4),
                GltfFixtures.primitive(positions, -1, -1, -1, -1, 4));

            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("two.gltf", fixture.gltf()));

            assertThat(model.submeshCount()).isEqualTo(2);

            assertThat(model.vertexCount()).isEqualTo(6);

            assertThat(model.submeshFirstIndex(1)).isEqualTo(3);

            assertThat(model.indices()).containsExactly(0, 1, 2, 3, 4, 5);
        }

        @Test
        @DisplayName("an index addressing a vertex the primitive does not have is refused")
        void shouldRejectOutOfRangeIndex()
        {
            final GltfFixtures fixture = new GltfFixtures();

            final int positions = fixture.floats("VEC3", TRIANGLE);

            final int indices = fixture.indices(GltfFixtures.UNSIGNED_SHORT, 0, 1, 99);

            fixture.mesh(GltfFixtures.primitive(positions, -1, -1, indices, -1, 4));

            assertThatThrownBy(() -> GltfConverter.convert("bad.gltf", fixture.gltf()))
                .isInstanceOf(GltfException.class)
                .hasMessageContaining("99");
        }

        @Test
        @DisplayName("a primitive with no POSITION is refused")
        void shouldRejectMissingPosition()
        {
            final GltfFixtures fixture = new GltfFixtures();

            final JsonObject primitive = new JsonObject();

            primitive.add("attributes", new JsonObject());

            fixture.mesh(primitive);

            assertThatThrownBy(() -> GltfConverter.convert("nopos.gltf", fixture.gltf()))
                .isInstanceOf(GltfException.class)
                .hasMessageContaining("POSITION");
        }
    }

    @Nested
    @DisplayName("accessors")
    class Accessors
    {
        @Test
        @DisplayName("byte, short and int index accessors all decode to the same indices")
        void shouldDecodeEveryIndexWidth()
        {
            assertThat(indicesOf(GltfFixtures.UNSIGNED_BYTE)).containsExactly(2, 1, 0);

            assertThat(indicesOf(GltfFixtures.UNSIGNED_SHORT)).containsExactly(2, 1, 0);

            assertThat(indicesOf(GltfFixtures.UNSIGNED_INT)).containsExactly(2, 1, 0);
        }

        @Test
        @DisplayName("an interleaved buffer view with a byte stride is decoded correctly")
        void shouldHonourByteStride()
        {
            // Six floats per vertex: position then UV then two bytes of padding.
            final float[] interleaved =
            {
                0.0f, 0.0f, 0.0f, 0.25f, 0.75f, 0.0f,
                1.0f, 0.0f, 0.0f, 0.50f, 0.50f, 0.0f,
                0.0f, 1.0f, 0.0f, 0.75f, 0.25f, 0.0f,
            };

            final GltfFixtures fixture = new GltfFixtures();

            final int view = fixture.stridedView(GltfFixtures.floatBytes(interleaved), 24);

            final int positions = fixture.accessor(view, GltfFixtures.FLOAT, "VEC3", 3);

            final int uvs = fixture.accessor(view, GltfFixtures.FLOAT, "VEC2", 3);

            fixture.accessorOffset(uvs, 12);

            fixture.mesh(GltfFixtures.primitive(positions, uvs, -1, -1, -1, 4));

            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("strided.gltf", fixture.gltf()));

            assertThat(model.positionX(1)).isEqualTo(1.0f);

            assertThat(model.texCoordU(0)).isEqualTo(0.25f);

            assertThat(model.texCoordV(2)).isEqualTo(0.25f);
        }

        @Test
        @DisplayName("a sparse accessor is refused rather than silently misread")
        void shouldRejectSparseAccessor()
        {
            final GltfFixtures fixture = new GltfFixtures();

            final int positions = fixture.floats("VEC3", TRIANGLE);

            fixture.markSparse(positions);

            fixture.mesh(GltfFixtures.primitive(positions, -1, -1, -1, -1, 4));

            assertThatThrownBy(() -> GltfConverter.convert("sparse.gltf", fixture.gltf()))
                .isInstanceOf(GltfException.class)
                .hasMessageContaining("sparse");
        }

        @Test
        @DisplayName("an accessor reading past its buffer is refused")
        void shouldRejectAccessorOverrun()
        {
            final GltfFixtures fixture = new GltfFixtures();

            final int view = fixture.bufferView(GltfFixtures.floatBytes(TRIANGLE));

            final int positions = fixture.accessor(view, GltfFixtures.FLOAT, "VEC3", 99);

            fixture.mesh(GltfFixtures.primitive(positions, -1, -1, -1, -1, 4));

            assertThatThrownBy(() -> GltfConverter.convert("short.gltf", fixture.gltf()))
                .isInstanceOf(GltfException.class)
                .hasMessageContaining("buffer");
        }
    }

    @Nested
    @DisplayName("materials, textures and colour")
    class MaterialsAndColour
    {
        @Test
        @DisplayName("a base colour texture is decoded, mipped and attached to its submesh")
        void shouldDecodeBaseColourTexture()
        {
            final GltfFixtures fixture = new GltfFixtures();

            final int image = fixture.image("colormap",
                GltfFixtures.solidPng(4, 4, Rgba.pack(200, 100, 50, 255)));

            final int material = fixture.material(fixture.texture(image), null);

            simpleMesh(fixture, material);

            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("textured.gltf", fixture.gltf()));

            assertThat(model.textureCount()).isEqualTo(1);

            assertThat(model.textureWidth(0)).isEqualTo(4);

            assertThat(model.textureLevelCount(0)).isEqualTo(3);

            assertThat(model.submeshTextureIndex(0)).isZero();

            assertThat(model.mipChain(0).texel(0, 2, 3)).isEqualTo(Rgba.pack(200, 100, 50, 255));

            assertThat(model.mipChain(0).texel(2, 0, 0)).isEqualTo(Rgba.pack(200, 100, 50, 255));
        }

        @Test
        @DisplayName("two materials sharing one image produce one texture, not two")
        void shouldDeduplicateTextures()
        {
            final GltfFixtures fixture = new GltfFixtures();

            final int image = fixture.image("colormap",
                GltfFixtures.solidPng(2, 2, Rgba.pack(1, 2, 3, 255)));

            final int first = fixture.material(fixture.texture(image), null);

            final int second = fixture.material(fixture.texture(image), null);

            final int positions = fixture.floats("VEC3", TRIANGLE);

            fixture.mesh(GltfFixtures.primitive(positions, -1, -1, -1, first, 4),
                GltfFixtures.primitive(positions, -1, -1, -1, second, 4));

            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("shared.gltf", fixture.gltf()));

            assertThat(model.textureCount()).isEqualTo(1);

            assertThat(model.submeshTextureIndex(0)).isZero();

            assertThat(model.submeshTextureIndex(1)).isZero();
        }

        @Test
        @DisplayName("a material with no texture leaves its submesh untextured")
        void shouldLeaveUntexturedSubmesh()
        {
            final GltfFixtures fixture = new GltfFixtures();

            simpleMesh(fixture, fixture.material(-1, new float[] {1.0f, 1.0f, 1.0f, 1.0f}));

            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("flat.gltf", fixture.gltf()));

            assertThat(model.textureCount()).isZero();

            assertThat(model.submeshTextureIndex(0)).isEqualTo(ModelFormat.NO_TEXTURE);
        }

        @Test
        @DisplayName("normal and emissive maps are dropped; there is no lighting to feed")
        void shouldDropNonAlbedoTextures()
        {
            final GltfFixtures fixture = new GltfFixtures();

            final int albedo = fixture.image("colormap",
                GltfFixtures.solidPng(2, 2, Rgba.OPAQUE_BLACK));

            final int normal = fixture.image("normalmap",
                GltfFixtures.solidPng(2, 2, Rgba.OPAQUE_BLACK));

            final int material = fixture.material(fixture.texture(albedo), null);

            fixture.materialSlot(material, "normalTexture", fixture.texture(normal));

            fixture.materialSlot(material, "emissiveTexture", fixture.texture(normal));

            simpleMesh(fixture, material);

            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("pbr.gltf", fixture.gltf()));

            assertThat(model.textureCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("with no colour input at all the vertex colour is opaque white")
        void shouldDefaultToWhite()
        {
            final GltfFixtures fixture = new GltfFixtures();

            simpleMesh(fixture, -1);

            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("plain.gltf", fixture.gltf()));

            assertThat(model.colour(0)).isEqualTo(Rgba.pack(255, 255, 255, 255));
        }

        @Test
        @DisplayName("baseColorFactor is baked into the vertex colour, sRGB-encoded not linear")
        void shouldBakeBaseColourFactor()
        {
            final GltfFixtures fixture = new GltfFixtures();

            simpleMesh(fixture, fixture.material(-1, new float[] {0.5f, 0.0f, 1.0f, 1.0f}));

            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("tinted.gltf", fixture.gltf()));

            // Linear 0.5 encodes to about 188, not to 128. Asserting the
            // encoded value is what stops a future "simplification" back to a
            // straight multiply by 255, which would darken every flat-shaded
            // surface in the game by a visible amount.
            assertThat(Rgba.red(model.colour(0))).isBetween(186, 190);

            assertThat(Rgba.green(model.colour(0))).isZero();

            assertThat(Rgba.blue(model.colour(0))).isEqualTo(255);

            assertThat(Rgba.alpha(model.colour(0))).isEqualTo(255);
        }

        @Test
        @DisplayName("COLOR_0 multiplies with baseColorFactor rather than replacing it")
        void shouldMultiplyVertexColourByFactor()
        {
            final GltfFixtures fixture = new GltfFixtures();

            final int material = fixture.material(-1, new float[] {1.0f, 1.0f, 0.0f, 1.0f});

            final int positions = fixture.floats("VEC3", TRIANGLE);

            final int colours = fixture.normalizedBytes("VEC4",
                255, 255, 255, 255, 255, 0, 255, 255, 0, 0, 0, 255);

            fixture.mesh(GltfFixtures.primitive(positions, -1, colours, -1, material, 4));

            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("vcol.gltf", fixture.gltf()));

            assertThat(Rgba.red(model.colour(0))).isEqualTo(255);

            assertThat(Rgba.green(model.colour(0))).isEqualTo(255);

            assertThat(Rgba.blue(model.colour(0))).isZero();

            assertThat(Rgba.green(model.colour(1))).isZero();

            assertThat(Rgba.red(model.colour(2))).isZero();
        }

        @Test
        @DisplayName("a VEC3 COLOR_0 is accepted and its alpha defaults to opaque")
        void shouldAcceptVec3VertexColour()
        {
            final GltfFixtures fixture = new GltfFixtures();

            final int positions = fixture.floats("VEC3", TRIANGLE);

            final int colours = fixture.floats("VEC3",
                1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f);

            fixture.mesh(GltfFixtures.primitive(positions, -1, colours, -1, -1, 4));

            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("vec3col.gltf", fixture.gltf()));

            assertThat(model.colour(0)).isEqualTo(Rgba.pack(255, 255, 255, 255));
        }

        @Test
        @DisplayName("texture coordinates keep glTF's top-left origin, unflipped")
        void shouldKeepTexCoordOrientation()
        {
            final GltfFixtures fixture = new GltfFixtures();

            final int positions = fixture.floats("VEC3", TRIANGLE);

            final int uvs = fixture.floats("VEC2", TRIANGLE_UV);

            fixture.mesh(GltfFixtures.primitive(positions, uvs, -1, -1, -1, 4));

            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("uv.gltf", fixture.gltf()));

            assertThat(model.texCoordU(1)).isEqualTo(1.0f);

            assertThat(model.texCoordV(1)).isEqualTo(0.0f);

            assertThat(model.texCoordV(2)).isEqualTo(1.0f);
        }
    }

    @Nested
    @DisplayName("budget enforcement")
    class BudgetEnforcement
    {
        @Test
        @DisplayName("a model over the triangle budget fails, naming the file and the count")
        void shouldRejectTooManyTriangles()
        {
            final int overBudget = ModelFormat.MAX_TRIANGLES_PER_MODEL + 1;

            final GltfFixtures fixture = new GltfFixtures();

            final int positions = fixture.floats("VEC3", TRIANGLE);

            final int[] indices = new int[overBudget * 3];

            for (int i = 0; i < indices.length; i++)
            {
                indices[i] = i % 3;
            }

            fixture.mesh(GltfFixtures.primitive(positions, -1, -1,
                fixture.indices(GltfFixtures.UNSIGNED_SHORT, indices), -1, 4));

            assertThatThrownBy(() -> GltfConverter.convert("dense.glb", fixture.gltf()))
                .isInstanceOf(AssetBudgetException.class)
                .hasMessageContaining("dense.glb")
                .hasMessageContaining(Integer.toString(overBudget))
                .hasMessageContaining(Integer.toString(ModelFormat.MAX_TRIANGLES_PER_MODEL));
        }

        @Test
        @DisplayName("an over-budget texture fails, naming the image and its measured size")
        void shouldRejectOversizedTexture()
        {
            final int edge = ModelFormat.MAX_TEXTURE_DIMENSION * 2;

            final GltfFixtures fixture = new GltfFixtures();

            final int image = fixture.image("colormap",
                GltfFixtures.solidPng(edge, edge, Rgba.OPAQUE_BLACK));

            simpleMesh(fixture, fixture.material(fixture.texture(image), null));

            assertThatThrownBy(() -> GltfConverter.convert("big.glb", fixture.gltf()))
                .isInstanceOf(AssetBudgetException.class)
                .hasMessageContaining("big.glb")
                .hasMessageContaining("colormap")
                .hasMessageContaining(Integer.toString(edge));
        }

        @Test
        @DisplayName("a non-power-of-two texture fails rather than being silently resized")
        void shouldRejectNonPowerOfTwoTexture()
        {
            final GltfFixtures fixture = new GltfFixtures();

            final int image = fixture.image("colormap",
                GltfFixtures.solidPng(100, 100, Rgba.OPAQUE_BLACK));

            simpleMesh(fixture, fixture.material(fixture.texture(image), null));

            assertThatThrownBy(() -> GltfConverter.convert("odd.glb", fixture.gltf()))
                .isInstanceOf(AssetBudgetException.class)
                .hasMessageContaining("100")
                .hasMessageContaining("power of two");
        }

        @Test
        @DisplayName("a 512-square texture is at the budget and converts")
        void shouldAcceptTextureAtTheBudget()
        {
            final int edge = ModelFormat.MAX_TEXTURE_DIMENSION;

            final GltfFixtures fixture = new GltfFixtures();

            final int image = fixture.image("colormap",
                GltfFixtures.solidPng(edge, edge, Rgba.pack(9, 9, 9, 255)));

            simpleMesh(fixture, fixture.material(fixture.texture(image), null));

            final ModelFormat model = ModelFormat.read(
                GltfConverter.convert("atbudget.glb", fixture.gltf()));

            assertThat(model.textureWidth(0)).isEqualTo(edge);

            assertThat(model.textureLevelCount(0)).isEqualTo(10);
        }

        @Test
        @DisplayName("an image ImageIO cannot decode is refused with the image named")
        void shouldRejectUndecodableImage()
        {
            final GltfFixtures fixture = new GltfFixtures();

            final int image = fixture.image("broken", new byte[] {1, 2, 3, 4, 5, 6, 7, 8});

            simpleMesh(fixture, fixture.material(fixture.texture(image), null));

            assertThatThrownBy(() -> GltfConverter.convert("broken.glb", fixture.gltf()))
                .isInstanceOf(GltfException.class)
                .hasMessageContaining("broken");
        }
    }

    // A single-triangle mesh with UVs, optionally bound to a material.
    private static int simpleMesh(final GltfFixtures fixture, final int material)
    {
        final int positions = fixture.floats("VEC3", TRIANGLE);

        final int uvs = fixture.floats("VEC2", TRIANGLE_UV);

        return fixture.mesh(GltfFixtures.primitive(positions, uvs, -1, -1, material, 4));
    }

    // The canonical fixture: one triangle in one scene, no material.
    private static GltfFixtures triangleFixture()
    {
        final GltfFixtures fixture = new GltfFixtures();

        final int mesh = simpleMesh(fixture, -1);

        fixture.scene(fixture.node(mesh, null, null, null));

        return fixture;
    }

    // Converts a reversed triangle whose indices use the given component type.
    private static int[] indicesOf(final int componentType)
    {
        final GltfFixtures fixture = new GltfFixtures();

        final int positions = fixture.floats("VEC3", TRIANGLE);

        final int indices = fixture.indices(componentType, 2, 1, 0);

        fixture.mesh(GltfFixtures.primitive(positions, -1, -1, indices, -1, 4));

        return ModelFormat.read(GltfConverter.convert("idx.gltf", fixture.gltf())).indices();
    }
}
