/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the scene description: what it holds, and what it refuses.
 *
 * <p>The refusals are the interesting half. A mirrored transform renders a
 * model inside-out rather than failing, which is exactly the class of defect
 * {@code render/README.md} § 7 warns "looks like a plausible model, not like an
 * error" — so it is rejected at build time, where it can carry a message.</p>
 */
@DisplayName("Scene")
final class SceneTest
{
    /** A quad's colour; irrelevant to these tests, which never rasterize. */
    private static final int COLOUR = 0xFF8040FF;

    /** Half-edge of the test quad. */
    private static final float HALF = 1.0f;

    /** A right angle, for the rotation case. */
    private static final float QUARTER_TURN = (float) (Math.PI / 2.0);

    /** A scale factor that is not one. */
    private static final float SCALE = 2.0f;

    /** An entity id standing in for one player. */
    private static final int PLAYER_ID = 7;

    /** A second, unrelated entity id. */
    private static final int OTHER_ID = 9;

    /** An id far outside any plausible instance-index range. */
    private static final int SPARSE_HIGH_ID = 1_000_000;

    @Nested
    @DisplayName("contents")
    class Contents
    {
        @Test
        @DisplayName("of() is one untransformed world instance")
        void shouldWrapASingleModelAsOneWorldInstance()
        {
            final ModelFormat model = quad();
            final Scene scene = Scene.of(model);

            assertThat(scene.worldInstanceCount()).isEqualTo(1);
            assertThat(scene.viewInstanceCount()).isZero();
            assertThat(scene.worldModel(0)).isSameAs(model);
            assertThat(scene.worldTransform(0).get(0, 0)).isEqualTo(1.0f);
            assertThat(scene.worldTransform(0).get(0, 3)).isZero();
        }

        @Test
        @DisplayName("the two lists are kept apart and in submission order")
        void shouldKeepWorldAndViewInstancesSeparate()
        {
            final ModelFormat world = quad();
            final ModelFormat hand = quad();
            final Scene scene = Scene.builder()
                .addWorldInstance(world, Mat4.translation(1.0f, 0.0f, 0.0f))
                .addViewInstance(hand, Mat4.translation(0.0f, 0.0f, 1.0f))
                .addWorldInstance(world, Mat4.identity())
                .build();

            assertThat(scene.worldInstanceCount()).isEqualTo(2);
            assertThat(scene.viewInstanceCount()).isEqualTo(1);
            assertThat(scene.instanceCount()).isEqualTo(3);
            assertThat(scene.worldTransform(0).get(0, 3)).isEqualTo(1.0f);
            assertThat(scene.worldTransform(1).get(0, 3)).isZero();
            assertThat(scene.viewModel(0)).isSameAs(hand);
            assertThat(scene.viewTransform(0).get(2, 3)).isEqualTo(1.0f);
        }

        @Test
        @DisplayName("the empty scene holds nothing and sizes nothing")
        void shouldDescribeAnEmptyScene()
        {
            assertThat(Scene.EMPTY.worldInstanceCount()).isZero();
            assertThat(Scene.EMPTY.viewInstanceCount()).isZero();
            assertThat(Scene.EMPTY.instanceCount()).isZero();
            assertThat(Scene.EMPTY.maxInstanceTriangles()).isZero();
            assertThat(Scene.EMPTY.toString()).contains("world=0");
        }

        @Test
        @DisplayName("maxInstanceTriangles is the largest single instance, across both passes")
        void shouldReportTheLargestInstance()
        {
            // The render port sizes its clip-space buffers by this figure, and
            // it is a maximum rather than a sum precisely because instances go
            // through the pipeline one at a time.
            final ModelFormat small = quad();
            final ModelFormat large = ModelFormat.read(CubeFixture.build());
            final Scene scene = Scene.builder()
                .addWorldInstance(small, Mat4.identity())
                .addViewInstance(large, Mat4.identity())
                .build();

            assertThat(large.triangleCount()).isGreaterThan(small.triangleCount());
            assertThat(scene.maxInstanceTriangles()).isEqualTo(large.triangleCount());
        }

        @Test
        @DisplayName("a builder may be reused without the built scene changing")
        void shouldNotShareStorageWithItsBuilder()
        {
            final Scene.Builder builder = Scene.builder().addWorldInstance(quad(),
                Mat4.identity());
            final Scene first = builder.build();
            builder.addWorldInstance(quad(), Mat4.identity());

            assertThat(first.worldInstanceCount()).isEqualTo(1);
            assertThat(builder.build().worldInstanceCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("entity ids")
    class EntityIds
    {
        @Test
        @DisplayName("an instance added without an id is UNTAGGED, and the scene knows it")
        void shouldDefaultToUntagged()
        {
            // The gate the whole outline feature hangs off: a scene that tags
            // nothing must be able to say so before any work is done, so the
            // renderer can skip the id clear, the per-pixel id write and the
            // outline dispatch entirely.
            final Scene scene = Scene.builder()
                .addWorldInstance(quad(), Mat4.identity())
                .build();

            assertThat(Scene.UNTAGGED).isZero();
            assertThat(scene.worldEntityId(0)).isEqualTo(Scene.UNTAGGED);
            assertThat(scene.hasTaggedEntities()).isFalse();
        }

        @Test
        @DisplayName("a tagged instance keeps its id, and untagged neighbours keep zero")
        void shouldCarryAnEntityIdPerInstance()
        {
            final Scene scene = Scene.builder()
                .addWorldInstance(quad(), Mat4.identity(), PLAYER_ID)
                .addWorldInstance(quad(), Mat4.translation(1.0f, 0.0f, 0.0f))
                .addWorldInstance(quad(), Mat4.translation(2.0f, 0.0f, 0.0f), OTHER_ID)
                .build();

            assertThat(scene.worldEntityId(0)).isEqualTo(PLAYER_ID);
            assertThat(scene.worldEntityId(1)).isEqualTo(Scene.UNTAGGED);
            assertThat(scene.worldEntityId(2)).isEqualTo(OTHER_ID);
            assertThat(scene.hasTaggedEntities()).isTrue();
        }

        @Test
        @DisplayName("ids need not be dense or ordered — they are opaque")
        void shouldNotRequireIdsToBeIndices()
        {
            // Explicitly protected because the obvious implementation of an
            // outline is an array indexed by id, and that would make this
            // scene allocate a million-entry table.
            final Scene scene = Scene.builder()
                .addWorldInstance(quad(), Mat4.identity(), SPARSE_HIGH_ID)
                .addWorldInstance(quad(), Mat4.translation(1.0f, 0.0f, 0.0f), 1)
                .build();

            assertThat(scene.worldEntityId(0)).isEqualTo(SPARSE_HIGH_ID);
            assertThat(scene.worldEntityId(1)).isEqualTo(1);
        }

        @Test
        @DisplayName("two instances may share one id — an entity of several models")
        void shouldAllowTwoInstancesToShareAnId()
        {
            // This is what stops OutlinePass drawing a seam through a
            // character's own joints, so it must not be rejected as a
            // duplicate.
            final Scene scene = Scene.builder()
                .addWorldInstance(quad(), Mat4.identity(), PLAYER_ID)
                .addWorldInstance(quad(), Mat4.translation(0.5f, 0.0f, 0.0f), PLAYER_ID)
                .build();

            assertThat(scene.worldEntityId(0)).isEqualTo(scene.worldEntityId(1));
        }

        @Test
        @DisplayName("passing UNTAGGED explicitly is exactly the two-argument overload")
        void shouldTreatAnExplicitUntaggedAsUntagged()
        {
            final Scene scene = Scene.builder()
                .addWorldInstance(quad(), Mat4.identity(), Scene.UNTAGGED)
                .build();

            assertThat(scene.hasTaggedEntities()).isFalse();
        }

        @Test
        @DisplayName("a view instance can never be tagged, so a viewmodel is never outlined")
        void shouldNeverTagAViewInstance()
        {
            // There is deliberately no addViewInstance overload taking an id.
            // The held weapon fills a large part of the screen and outlining
            // it would put an unbroken band down the side of every frame.
            final Scene scene = Scene.builder()
                .addViewInstance(quad(), Mat4.translation(0.0f, 0.0f, 1.0f))
                .build();

            assertThat(scene.hasTaggedEntities()).isFalse();
        }

        @Test
        @DisplayName("a negative id is refused — ids are positive or UNTAGGED")
        void shouldRefuseANegativeEntityId()
        {
            final Scene.Builder builder = Scene.builder();

            assertThatThrownBy(() -> builder.addWorldInstance(quad(), Mat4.identity(), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityId");
        }

        @Test
        @DisplayName("the empty scene tags nothing")
        void shouldReportTheEmptySceneAsUntagged()
        {
            assertThat(Scene.EMPTY.hasTaggedEntities()).isFalse();
        }
    }

    @Nested
    @DisplayName("transforms it refuses")
    class Refusals
    {
        @Test
        @DisplayName("a mirror is refused, because it would invert backface culling")
        void shouldRefuseANegativeDeterminant()
        {
            final Scene.Builder builder = Scene.builder();

            assertThatThrownBy(() -> builder.addWorldInstance(quad(),
                TransformFixture.mirrorX()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative determinant")
                .hasMessageContaining("winding");
        }

        @Test
        @DisplayName("a view instance is held to the same winding rule")
        void shouldRefuseANegativeDeterminantOnAViewInstance()
        {
            final Scene.Builder builder = Scene.builder();

            assertThatThrownBy(() -> builder.addViewInstance(quad(),
                TransformFixture.mirrorX()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelToView");
        }

        @Test
        @DisplayName("a singular transform is refused")
        void shouldRefuseAZeroDeterminant()
        {
            final Scene.Builder builder = Scene.builder();

            assertThatThrownBy(() -> builder.addWorldInstance(quad(),
                TransformFixture.flattenZ()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("singular");
        }

        @Test
        @DisplayName("a projective bottom row is refused rather than silently dropped")
        void shouldRefuseANonAffineTransform()
        {
            // The packed clip transform has three rows. A fourth-row term
            // would be ignored, and a transform that is quietly not the one
            // you asked for is worse than one that is refused.
            final Scene.Builder builder = Scene.builder();

            assertThatThrownBy(() -> builder.addWorldInstance(quad(),
                TransformFixture.projective()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("affine");
        }

        @Test
        @DisplayName("rotation, uniform scale and translation are all accepted")
        void shouldAcceptOrientationPreservingTransforms()
        {
            final Scene scene = Scene.builder()
                .addWorldInstance(quad(), TransformFixture.rotationZ(QUARTER_TURN))
                .addWorldInstance(quad(), TransformFixture.uniformScale(SCALE))
                .addWorldInstance(quad(), Mat4.translation(1.0f, 2.0f, 3.0f))
                .build();

            assertThat(scene.worldInstanceCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("a null model, a null transform and an empty model are all refused")
        void shouldRefuseUnusableInstances()
        {
            final Scene.Builder builder = Scene.builder();
            final ModelFormat empty = ModelFormat.read(ModelFileFixture.empty());

            assertThatThrownBy(() -> builder.addWorldInstance(null, Mat4.identity()))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> builder.addWorldInstance(quad(), null))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> builder.addViewInstance(empty, Mat4.identity()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no triangles");
        }
    }

    @Nested
    @DisplayName("per-instance translucency")
    class Translucency
    {
        @Test
        @DisplayName("instances are opaque unless a coverage says otherwise")
        void shouldDefaultToOpaque()
        {
            final Scene scene = Scene.builder()
                .addWorldInstance(quad(), Mat4.identity())
                .addViewInstance(quad(), Mat4.translation(0.0f, 0.0f, 1.0f))
                .build();

            assertThat(scene.worldCoverage(0)).isEqualTo(Scene.OPAQUE);
            assertThat(scene.isWorldTranslucent(0)).isFalse();
            assertThat(scene.translucentInstanceCount())
                .as("a scene built the old way must cost the translucent phase nothing")
                .isZero();
        }

        @Test
        @DisplayName("a coverage below opaque marks the instance translucent and is counted")
        void shouldCountTranslucentInstances()
        {
            final Scene scene = Scene.builder()
                .addWorldInstance(quad(), Mat4.identity())
                .addTranslucentWorldInstance(quad(), Mat4.identity(), Scene.UNTAGGED, 128)
                .addTranslucentWorldInstance(quad(), Mat4.identity(), Scene.UNTAGGED, 64)
                .build();

            assertThat(scene.isWorldTranslucent(0)).isFalse();
            assertThat(scene.isWorldTranslucent(1)).isTrue();
            assertThat(scene.worldCoverage(1)).isEqualTo(128);
            assertThat(scene.worldCoverage(2)).isEqualTo(64);
            assertThat(scene.translucentInstanceCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("passing OPAQUE is exactly the opaque overload, not an error")
        void shouldAcceptOpaqueThroughTheTranslucentOverload()
        {
            final Scene scene = Scene.builder()
                .addTranslucentWorldInstance(quad(), Mat4.identity(), Scene.UNTAGGED,
                    Scene.OPAQUE)
                .build();

            assertThat(scene.translucentInstanceCount()).isZero();
            assertThat(scene.isWorldTranslucent(0)).isFalse();
        }

        @Test
        @DisplayName("a coverage outside 0-255 is refused")
        void shouldRefuseCoverageOutOfRange()
        {
            final Scene.Builder builder = Scene.builder();

            assertThatThrownBy(() -> builder.addTranslucentWorldInstance(quad(),
                Mat4.identity(), Scene.UNTAGGED, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coverage");
            assertThatThrownBy(() -> builder.addTranslucentWorldInstance(quad(),
                Mat4.identity(), Scene.UNTAGGED, Scene.OPAQUE + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coverage");
        }

        @Test
        @DisplayName("a translucent instance still has to pass the transform checks")
        void shouldStillValidateTheTransform()
        {
            final Scene.Builder builder = Scene.builder();

            assertThatThrownBy(() -> builder.addTranslucentWorldInstance(quad(),
                TransformFixture.uniformScale(-1.0f), Scene.UNTAGGED, 128))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static ModelFormat quad()
    {
        return ModelFormat.read(QuadFixture.square(HALF, COLOUR));
    }
}
