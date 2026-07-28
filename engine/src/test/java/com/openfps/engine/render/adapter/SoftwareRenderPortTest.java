/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.openfps.engine.core.eventbus.EventBusFactory;
import com.openfps.engine.core.eventbus.I_EventBusPort;
import com.openfps.engine.core.pool.I_ThreadPoolPort;
import com.openfps.engine.core.pool.ThreadPoolFactory;
import com.openfps.engine.core.subsystem.SubsystemRegistry;
import com.openfps.engine.hal.adapter.nulladapter.NullTimePort;
import com.openfps.engine.hal.port.I_TimePort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for the assembled software rasterizer.
 *
 * <p>The centrepiece is the cull-mode oracle. {@code render/README.md} § 7 left
 * the backface winding deliberately unpinned and warned that getting it wrong
 * produces a model rendered inside-out, which "looks like a plausible model,
 * not like an error". These tests settle it the only way that is sound:
 * against {@link Rasterizer.CullMode#NONE}, which needs no winding assumption
 * at all because a z-buffer draws a closed mesh correctly with no culling.</p>
 */
@DisplayName("SoftwareRenderPort")
final class SoftwareRenderPortTest
{
    /** Frame width for the oracle comparisons. */
    private static final int WIDTH = 200;

    /** Frame height for the oracle comparisons. */
    private static final int HEIGHT = 200;

    /** Workers used by the parallel-equals-serial test. */
    private static final int WORKERS = 4;

    /** Event-bus capacity for the standalone pool; nothing is published to it. */
    private static final int BUS_CAPACITY = 16;

    /** Vertical field of view for every test camera. */
    private static final float FOV = 1.0f;

    /** World up. */
    private static final Vec3 UP = new Vec3(0.0f, 1.0f, 0.0f);

    /** Model centre. */
    private static final Vec3 ORIGIN = new Vec3(0.0f, 0.0f, 0.0f);

    /** Near plane. */
    private static final float NEAR = 0.05f;

    /** Screen centre column. */
    private static final int HALF_WIDTH = WIDTH / 2;

    /** Screen centre row. */
    private static final int HALF_HEIGHT = HEIGHT / 2;

    /**
     * The projection scale both axes share, {@code 1 / tan(fovY / 2)}, because
     * the scene tests use a square viewport and therefore an aspect of 1. This
     * is {@code render/README.md} § 4's formula restated independently of
     * {@link Camera}, so the assertions are against the specification rather
     * than against the implementation.
     */
    private static final float PROJECTION_SCALE = (float) (1.0 / Math.tan(FOV * 0.5f));

    /**
     * How far a projected edge may sit from the covered pixels that represent
     * it. A pixel is covered when its <i>centre</i> is inside the triangle, so
     * a covered-rectangle boundary lies within <b>half</b> a pixel of the true
     * edge; one pixel is that bound with a little room. Not a fudge factor: at
     * 1.0 a real transform error — which moves geometry by tens of pixels —
     * cannot hide.
     */
    private static final float PIXEL_TOLERANCE = 1.0f;

    /** Where the scene tests put the camera on the +z axis. */
    private static final float CAMERA_Z = 4.0f;

    /** Half-edge of the standard test quad. */
    private static final float QUAD_HALF = 1.0f;

    /** Long half-edge of the oblong quad the rotation test uses. */
    private static final float WIDE_HALF = 1.0f;

    /** Short half-edge of that oblong. */
    private static final float NARROW_HALF = 0.25f;

    /** A quarter turn, in radians. */
    private static final float QUARTER_TURN = (float) (Math.PI / 2.0);

    /** The scale factor the scale test applies. */
    private static final float HALF_SCALE = 0.5f;

    /** World z of the wall the viewmodel has to beat: one unit from the eye. */
    private static final float WALL_Z = CAMERA_Z - 1.0f;

    /** View-space depth of the viewmodel — three times further out than the wall. */
    private static final float VIEWMODEL_DEPTH = 3.0f;

    /** Half-edge of the viewmodel quad. */
    private static final float VIEWMODEL_HALF = 0.5f;

    /** Colour of the nearer instance in the depth tests. */
    private static final int NEAR_COLOR = 0xFF4020FF;

    /** Colour of the farther instance. */
    private static final int FAR_COLOR = 0x2040FFFF;

    /** Colour of the viewmodel in the mixed scene. */
    private static final int VIEW_COLOR = 0x40FF60FF;

    /** Texture colour of the left instance in the batched-material test. */
    private static final int LEFT_TEXTURE = 0xFF00FFFF;

    /** Texture colour of the right instance in the batched-material test. */
    private static final int RIGHT_TEXTURE = 0x00FF00FF;

    /**
     * Baked vertex colour both textured quads carry. Shared on purpose: it is
     * what a triangle paints when it loses its texture, so a quad falling back
     * to it is unmistakably not the same as a quad sampling its own texture,
     * and a quad wearing its neighbour's texture is not this colour either.
     */
    private static final int UNTEXTURED_FALLBACK = 0x804020FF;

    /** Parallel passes a one-pass frame costs: geometry, setup, scatter, raster. */
    private static final int PASSES_PER_PASS = 4;

    /** Instances the barrier-count test puts in the world pass. */
    private static final int MANY_INSTANCES = 24;

    /** Spacing between those instances, in world units. */
    private static final float INSTANCE_SPACING = 0.05f;

    /** Frames the tearing test renders while another thread presents. */
    private static final int TEARING_FRAMES = 200;

    /** How long the tearing test waits for its renderer, in milliseconds. */
    private static final long TEARING_TIMEOUT_MILLIS = 30_000L;

    @Nested
    @DisplayName("backface winding — settled against the no-cull oracle")
    class Winding
    {
        @Test
        @DisplayName("CLOCKWISE reproduces the no-cull image exactly")
        void shouldMatchTheOracleWhenCullingClockwise()
        {
            final int[] oracle = render(Rasterizer.CullMode.NONE, null);
            final int[] clockwise = render(Rasterizer.CullMode.CLOCKWISE, null);

            // A z-buffer resolves a closed mesh correctly with no culling, so
            // the NONE image is what the cube must look like whatever the
            // winding convention turns out to be. Culling may only remove work.
            assertThat(clockwise).isEqualTo(oracle);
        }

        @Test
        @DisplayName("COUNTER_CLOCKWISE renders the cube inside-out — the trap, made visible")
        void shouldRenderInsideOutWhenCullingCounterClockwise()
        {
            final int[] oracle = render(Rasterizer.CullMode.NONE, null);
            final int[] counterClockwise =
                render(Rasterizer.CullMode.COUNTER_CLOCKWISE, null);

            assertThat(counterClockwise).isNotEqualTo(oracle);
        }

        @Test
        @DisplayName("the chosen convention shows the three near faces and no interior")
        void shouldShowOnlyNearFacesWithTheChosenConvention()
        {
            final Set<Integer> drawn = colorsIn(
                render(SoftwareRenderPort.BACKFACE_CULL_MODE, null));

            // The camera sits at +x, +y, +z, so exactly these three faces are
            // outward-facing towards it.
            assertThat(drawn).contains(CubeFixture.PLUS_X, CubeFixture.PLUS_Y,
                CubeFixture.PLUS_Z);
            assertThat(drawn).doesNotContain(CubeFixture.MINUS_X, CubeFixture.MINUS_Y,
                CubeFixture.MINUS_Z);
        }

        @Test
        @DisplayName("the inverted convention shows only the far faces' interiors")
        void shouldShowOnlyFarFacesWhenTheConventionIsInverted()
        {
            final Set<Integer> drawn =
                colorsIn(render(Rasterizer.CullMode.COUNTER_CLOCKWISE, null));

            assertThat(drawn).contains(CubeFixture.MINUS_X, CubeFixture.MINUS_Y,
                CubeFixture.MINUS_Z);
            assertThat(drawn).doesNotContain(CubeFixture.PLUS_X, CubeFixture.PLUS_Y,
                CubeFixture.PLUS_Z);
        }

        @Test
        @DisplayName("the project convention is CLOCKWISE")
        void shouldPinClockwiseAsTheProjectConvention()
        {
            // Guards the constant itself: the tests above prove which mode is
            // right, this one proves the shipped pipeline uses it.
            //
            // This value is NOT independent of Camera's basis order. It was
            // COUNTER_CLOCKWISE while Camera derived right = up x forward,
            // which mirrored every frame horizontally; correcting that to
            // forward x up negates screen x, negates area2, and flips this.
            // If a change to Camera breaks this test, re-run the oracle above
            // rather than editing this line to match.
            assertThat(SoftwareRenderPort.BACKFACE_CULL_MODE)
                .isEqualTo(Rasterizer.CullMode.CLOCKWISE);
        }
    }

    @Nested
    @DisplayName("threading")
    class Threading
    {
        @Test
        @DisplayName("a pooled frame is bit-identical to a serial one")
        void shouldProduceTheSameFrameInParallelAsSerially()
        {
            final int[] serial = render(SoftwareRenderPort.BACKFACE_CULL_MODE, null);
            final I_EventBusPort bus = EventBusFactory.createShared();
            bus.init(BUS_CAPACITY);
            final I_ThreadPoolPort pool =
                ThreadPoolFactory.createFixed(bus, new SubsystemRegistry());
            pool.init(WORKERS);
            pool.start();
            try
            {
                // Determinism is not needed for lockstep — the renderer cannot
                // desync anything — but a renderer that flickers between runs
                // is miserable to debug, so README § 7 asks for it and this
                // asserts it across the chunk decomposition as well as the
                // tiles.
                assertThat(render(SoftwareRenderPort.BACKFACE_CULL_MODE, pool))
                    .isEqualTo(serial);
            }
            finally
            {
                pool.shutdown();
                bus.shutdown();
            }
        }

        @Test
        @DisplayName("a pooled multi-instance frame is bit-identical to a serial one")
        void shouldProduceTheSameSceneInParallelAsSerially()
        {
            // Same property as above, now across the per-instance loop: every
            // instance re-dispatches four passes and resets the rasterizer, so
            // there is more per-frame state to get wrong.
            final int[] serial = frameOf(mixedScene(), sceneCamera(), null);
            final I_EventBusPort bus = EventBusFactory.createShared();
            bus.init(BUS_CAPACITY);
            final I_ThreadPoolPort pool =
                ThreadPoolFactory.createFixed(bus, new SubsystemRegistry());
            pool.init(WORKERS);
            pool.start();
            try
            {
                assertThat(frameOf(mixedScene(), sceneCamera(), pool))
                    .isEqualTo(serial);
            }
            finally
            {
                pool.shutdown();
                bus.shutdown();
            }
        }

        @Test
        @DisplayName("a many-instance scene is bit-identical serially and in parallel")
        void shouldBatchManyInstancesIdenticallyInParallel()
        {
            // Twenty-four instances is past the point where the batched
            // geometry pass has to split a chunk across an instance boundary at
            // this worker count, which is the part of the flattening that a
            // serial run would never exercise.
            final int[] serial = frameOf(manyInstanceScene(), sceneCamera(), null);
            final I_EventBusPort bus = EventBusFactory.createShared();
            bus.init(BUS_CAPACITY);
            final I_ThreadPoolPort pool =
                ThreadPoolFactory.createFixed(bus, new SubsystemRegistry());
            pool.init(WORKERS);
            pool.start();
            try
            {
                assertThat(frameOf(manyInstanceScene(), sceneCamera(), pool))
                    .isEqualTo(serial);
            }
            finally
            {
                pool.shutdown();
                bus.shutdown();
            }
        }
    }

    @Nested
    @DisplayName("batching — one geometry stream per pass, not per instance")
    class Batching
    {
        @Test
        @DisplayName("a frame costs four parallel passes per pass, whatever the instance count")
        void shouldCostAFixedNumberOfBarriersPerFrame()
        {
            // The regression this exists for: the pipeline used to run once per
            // instance, so a 295-instance room paid 1,180 publish/join
            // boundaries a frame and adding workers made it slower rather than
            // faster. The count is the property; the frame time is downstream
            // of it and far too noisy to assert.
            final SoftwareRenderPort port =
                renderScene(manyInstanceScene(), sceneCamera(), null);

            assertThat(port.scene().worldInstanceCount()).isEqualTo(MANY_INSTANCES);
            assertThat(port.lastFrameParallelPasses())
                .as("world pass only — four passes however many instances are in it")
                .isEqualTo(PASSES_PER_PASS);
            port.shutdown();
        }

        @Test
        @DisplayName("the viewmodel adds a second pass, and only one")
        void shouldCostOneExtraPassForTheViewmodel()
        {
            final SoftwareRenderPort port = renderScene(mixedScene(), sceneCamera(), null);

            assertThat(port.lastFrameParallelPasses()).isEqualTo(2 * PASSES_PER_PASS);
            port.shutdown();
        }

        @Test
        @DisplayName("each instance keeps its own texture through the shared raster pass")
        void shouldKeepPerInstanceTexturesThroughOneRasterPass()
        {
            // One raster pass takes one texture table, so every instance's
            // material indices are rebased into a scene-wide one. Get that
            // wrong and both quads sample the first instance's texture — which
            // looks entirely plausible, because they are the same shape.
            final ModelFormat left = ModelFormat.read(QuadFixture.texturedSquare(
                QUAD_HALF, UNTEXTURED_FALLBACK, LEFT_TEXTURE));
            final ModelFormat right = ModelFormat.read(QuadFixture.texturedSquare(
                QUAD_HALF, UNTEXTURED_FALLBACK, RIGHT_TEXTURE));
            final Scene scene = Scene.builder()
                .addWorldInstance(left, Mat4.translation(-2.0f * QUAD_HALF, 0.0f, 0.0f))
                .addWorldInstance(right, Mat4.translation(2.0f * QUAD_HALF, 0.0f, 0.0f))
                .build();

            final int[] frame = frameOf(scene, sceneCamera(), null);
            final Set<Integer> found = colorsIn(frame);

            assertThat(found)
                .as("each quad must sample its own texture, and neither may fall"
                    + " back to the baked colour")
                .containsExactlyInAnyOrder(LEFT_TEXTURE, RIGHT_TEXTURE);
        }

        @Test
        @DisplayName("adding a second instance of a model already in the scene changes nothing else")
        void shouldShareOneTextureTableSliceBetweenDuplicateModels()
        {
            // The same ModelFormat placed twice shares one prepared instance,
            // so it must be rebased once and only once. Rebasing it twice would
            // shift its material indices off the end of the table, which shows
            // up as the duplicate losing its texture.
            final ModelFormat model = ModelFormat.read(QuadFixture.texturedSquare(
                QUAD_HALF, UNTEXTURED_FALLBACK, LEFT_TEXTURE));
            final ModelFormat other = ModelFormat.read(QuadFixture.texturedSquare(
                QUAD_HALF, UNTEXTURED_FALLBACK, RIGHT_TEXTURE));
            final Scene scene = Scene.builder()
                .addWorldInstance(model, Mat4.translation(-2.0f * QUAD_HALF, 0.0f, 0.0f))
                .addWorldInstance(other, Mat4.identity())
                .addWorldInstance(model, Mat4.translation(2.0f * QUAD_HALF, 0.0f, 0.0f))
                .build();

            assertThat(colorsIn(frameOf(scene, sceneCamera(), null)))
                .containsExactlyInAnyOrder(LEFT_TEXTURE, RIGHT_TEXTURE);
        }

        @Test
        @DisplayName("buffers are sized for the whole pass, not the largest instance")
        void shouldSizeBuffersForTheWholePass()
        {
            final Scene scene = manyInstanceScene();

            assertThat(scene.maxPassTriangles())
                .isEqualTo(MANY_INSTANCES * QuadFixture.TRIANGLES);
            assertThat(scene.maxInstanceTriangles())
                .as("still the largest single model, which is now a different number")
                .isEqualTo(QuadFixture.TRIANGLES);
        }
    }

    @Nested
    @DisplayName("scenes — many instances, each with its own transform")
    class Scenes
    {
        @Test
        @DisplayName("loadModel is a one-instance scene, not a second code path")
        void shouldExpressASingleModelAsAOneInstanceScene()
        {
            final SoftwareRenderPort port =
                newPort(null, SoftwareRenderPort.BACKFACE_CULL_MODE);
            port.resize(WIDTH, HEIGHT);
            port.loadModel(CubeFixture.build());

            assertThat(port.scene()).isNotNull();
            assertThat(port.scene().worldInstanceCount()).isEqualTo(1);
            assertThat(port.scene().viewInstanceCount()).isZero();
            port.shutdown();
        }

        @Test
        @DisplayName("two instances resolve by depth, whichever order they are submitted in")
        void shouldResolveInstancesByDepthIndependentlyOfOrder()
        {
            final ModelFormat near = quad(NEAR_COLOR);
            final ModelFormat far = quad(FAR_COLOR);
            final Mat4 nearAt = Mat4.translation(0.5f, 0.0f, 0.5f);
            final Mat4 farAt = Mat4.translation(-0.5f, 0.0f, -0.5f);

            final int[] nearFirst = frameOf(Scene.builder()
                .addWorldInstance(near, nearAt).addWorldInstance(far, farAt).build(),
                sceneCamera(), null);
            final int[] farFirst = frameOf(Scene.builder()
                .addWorldInstance(far, farAt).addWorldInstance(near, nearAt).build(),
                sceneCamera(), null);

            // The overlap: the nearer instance owns it whatever order the two
            // arrived in, because the depth buffer carries across instances.
            assertThat(pixel(nearFirst, HALF_WIDTH, HALF_HEIGHT)).isEqualTo(NEAR_COLOR);
            assertThat(farFirst)
                .as("submission order must not change one pixel")
                .isEqualTo(nearFirst);
        }

        @Test
        @DisplayName("a translation lands where the projection says it does")
        void shouldTranslateAnInstanceToTheProjectedPosition()
        {
            final float shiftX = 0.5f;
            final float shiftY = -0.25f;
            final int[] frame = frameOf(Scene.builder()
                .addWorldInstance(quad(NEAR_COLOR),
                    Mat4.translation(shiftX, shiftY, 0.0f))
                .build(), sceneCamera(), null);

            final int[] box = boundsOf(frame, NEAR_COLOR);
            assertProjected(box, shiftX, shiftY, QUAD_HALF, QUAD_HALF, CAMERA_Z);
        }

        @Test
        @DisplayName("a quarter turn about z swaps the projected width and height")
        void shouldRotateAnInstanceAboutItsOwnOrigin()
        {
            // A 4:1 quad, so the rotation is unmistakable in the numbers: the
            // projected box must come back transposed, not merely different.
            final ModelFormat oblong =
                ModelFormat.read(QuadFixture.build(WIDE_HALF, NARROW_HALF, NEAR_COLOR));
            final int[] upright = frameOf(
                Scene.of(oblong), sceneCamera(), null);
            final int[] turned = frameOf(Scene.builder()
                .addWorldInstance(oblong, TransformFixture.rotationZ(QUARTER_TURN))
                .build(), sceneCamera(), null);

            assertProjected(boundsOf(upright, NEAR_COLOR), 0.0f, 0.0f, WIDE_HALF,
                NARROW_HALF, CAMERA_Z);
            assertProjected(boundsOf(turned, NEAR_COLOR), 0.0f, 0.0f, NARROW_HALF,
                WIDE_HALF, CAMERA_Z);
        }

        @Test
        @DisplayName("a uniform scale halves the projected extent")
        void shouldScaleAnInstanceAboutItsOwnOrigin()
        {
            final int[] frame = frameOf(Scene.builder()
                .addWorldInstance(quad(NEAR_COLOR), TransformFixture.uniformScale(HALF_SCALE))
                .build(), sceneCamera(), null);

            assertProjected(boundsOf(frame, NEAR_COLOR), 0.0f, 0.0f, QUAD_HALF * HALF_SCALE,
                QUAD_HALF * HALF_SCALE, CAMERA_Z);
        }

        @Test
        @DisplayName("a view instance draws over world geometry that is nearer")
        void shouldDrawTheViewmodelOverNearerWorldGeometry()
        {
            // The wall is at view depth 1 and fills the frame; the viewmodel is
            // at view depth 3, three times FURTHER away, and must still win.
            // Only the depth reset between the passes can produce that.
            final int[] frame = frameOf(Scene.builder()
                .addWorldInstance(quad(FAR_COLOR), Mat4.translation(0.0f, 0.0f, WALL_Z))
                .addViewInstance(ModelFormat.read(QuadFixture.square(VIEWMODEL_HALF,
                    NEAR_COLOR)), Mat4.translation(0.0f, 0.0f, VIEWMODEL_DEPTH))
                .build(), sceneCamera(), null);

            assertThat(pixel(frame, HALF_WIDTH, HALF_HEIGHT))
                .as("the viewmodel is not occluded by the wall in front of it")
                .isEqualTo(NEAR_COLOR);
            assertThat(pixel(frame, 1, 1))
                .as("colour is not cleared between the passes, so the wall survives")
                .isEqualTo(FAR_COLOR);
            assertProjected(boundsOf(frame, NEAR_COLOR), 0.0f, 0.0f, VIEWMODEL_HALF,
                VIEWMODEL_HALF, VIEWMODEL_DEPTH);
        }

        @Test
        @DisplayName("an empty scene renders a cleared frame")
        void shouldRenderAnEmptyScene()
        {
            final SoftwareRenderPort port = renderScene(Scene.EMPTY, sceneCamera(), null);

            assertThat(port.framesRendered()).isEqualTo(1L);
            assertThat(port.lastFrameTriangles()).isZero();
            assertThat(colorsIn(copy(port))).isEmpty();
            port.shutdown();
        }

        @Test
        @DisplayName("a scene of nothing but a viewmodel renders without a camera being set")
        void shouldRenderAViewOnlyScene()
        {
            // No camera and no world instance: the default orbit has nothing to
            // frame, so the port falls back to a frustum at the origin. A view
            // instance never uses the view matrix anyway, which is the point.
            final SoftwareRenderPort port = renderScene(Scene.builder()
                .addViewInstance(quad(NEAR_COLOR),
                    Mat4.translation(0.0f, 0.0f, VIEWMODEL_DEPTH))
                .build(), null, null);

            assertThat(port.lastFrameTriangles()).isEqualTo(QuadFixture.TRIANGLES);
            assertThat(colorsIn(copy(port))).containsExactly(NEAR_COLOR);
            port.shutdown();
        }

        @Test
        @DisplayName("the triangle count is the scene total, across both passes")
        void shouldCountTrianglesAcrossEveryInstance()
        {
            final SoftwareRenderPort port = renderScene(mixedScene(), sceneCamera(), null);

            assertThat(port.lastFrameTriangles()).isEqualTo(3 * QuadFixture.TRIANGLES);
            port.shutdown();
        }

        @Test
        @DisplayName("scenes may be swapped in either size order")
        void shouldSwapScenesWithoutResizingDownAndBackUp()
        {
            // Geometry buffers grow to the largest instance ever seen and never
            // shrink, so a big scene followed by a small one must still be
            // correct — and so must the other order, which does reallocate.
            final SoftwareRenderPort port =
                newPort(null, SoftwareRenderPort.BACKFACE_CULL_MODE);
            port.resize(WIDTH, HEIGHT);
            port.setCamera(sceneCamera());

            port.setScene(Scene.of(ModelFormat.read(CubeFixture.build())));
            port.renderFrame(0);
            final int cubeTriangles = port.lastFrameTriangles();

            port.setScene(Scene.of(quad(NEAR_COLOR)));
            port.renderFrame(1);
            assertThat(port.lastFrameTriangles()).isEqualTo(QuadFixture.TRIANGLES);

            port.setScene(Scene.of(ModelFormat.read(CubeFixture.build())));
            port.renderFrame(2);
            assertThat(port.lastFrameTriangles()).isEqualTo(cubeTriangles);
            port.shutdown();
        }

        @Test
        @DisplayName("a null scene is refused")
        void shouldRefuseANullScene()
        {
            final SoftwareRenderPort port =
                newPort(null, SoftwareRenderPort.BACKFACE_CULL_MODE);

            assertThatThrownBy(() -> port.setScene(null))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("presentation handoff")
    class Presentation
    {
        @Test
        @DisplayName("a padded stride does not shear the presented image")
        void shouldNotShearWhenTheStrideIsWiderThanTheImage()
        {
            // 100 is not a multiple of Framebuffer.STRIDE_ALIGNMENT, so the row
            // stride is 112 and every row of the raw colour buffer starts 12
            // pixels past where the image does. Uploading that raw buffer is
            // the shear README § 7 warns about; copyColorTo is the de-padding
            // step that prevents it.
            final int size = 100;
            final SoftwareRenderPort port = newPort(null, SoftwareRenderPort.BACKFACE_CULL_MODE);
            port.resize(size, size);
            port.loadModel(CubeFixture.build());
            // Head-on at the +z face: its projection is an axis-aligned square,
            // so every covered row must span the identical columns.
            port.setCamera(Camera.lookingAt(new Vec3(0.0f, 0.0f, 4.0f), ORIGIN, UP,
                FOV, 1.0f, NEAR));
            port.renderFrame(0);

            assertThat(port.framebuffer().strideInPixels())
                .as("the test only means anything if the stride really is padded")
                .isGreaterThan(size);

            final int[] frame = new int[size * size];
            assertThat(port.copyColorInto(frame)).isTrue();
            assertRowsAlign(frame, size, size);
            port.shutdown();
        }

        @Test
        @DisplayName("copyColorInto reports no frame before the surface exists")
        void shouldReportNoFrameBeforeTheSurfaceExists()
        {
            final SoftwareRenderPort port = newPort(null, SoftwareRenderPort.BACKFACE_CULL_MODE);

            assertThat(port.copyColorInto(new int[1])).isFalse();
        }

        @Test
        @DisplayName("copyColorInto reports no frame after a resize discards the last one")
        void shouldReportNoFrameAfterAResize()
        {
            final SoftwareRenderPort port = newPort(null, SoftwareRenderPort.BACKFACE_CULL_MODE);
            port.resize(WIDTH, HEIGHT);
            port.loadModel(CubeFixture.build());
            port.renderFrame(0);
            assertThat(port.copyColorInto(new int[WIDTH * HEIGHT])).isTrue();

            // A frame captured at the old size is not a frame at the new one,
            // and a presenter that has already rebuilt its texture would shear
            // it. One platform frame falls back to the menu instead.
            port.resize(WIDTH / 2, HEIGHT / 2);

            assertThat(port.copyColorInto(new int[WIDTH * HEIGHT])).isFalse();
            port.shutdown();
        }

        @Test
        @DisplayName("a concurrent presenter never sees half of one frame and half of the next")
        void shouldNeverPresentATornFrame() throws InterruptedException
        {
            // The double buffer's whole job. Two cameras give two frames whose
            // pixels differ over most of the screen; the renderer alternates
            // between them as fast as it can while this thread presents as fast
            // as it can. Every snapshot must be one whole frame or the other —
            // a torn one is neither, and with a shared colour buffer this test
            // fails within a few dozen iterations.
            final Camera left = Camera.lookingAt(new Vec3(-1.0f, 0.0f, CAMERA_Z), ORIGIN, UP,
                FOV, 1.0f, NEAR);
            final Camera right = Camera.lookingAt(new Vec3(1.0f, 0.0f, CAMERA_Z), ORIGIN, UP,
                FOV, 1.0f, NEAR);
            final int[] fromLeft = frameOf(mixedScene(), left, null);
            final int[] fromRight = frameOf(mixedScene(), right, null);
            assertThat(fromLeft).as("the two poses must differ, or this proves nothing")
                .isNotEqualTo(fromRight);

            final SoftwareRenderPort port = newPort(null, SoftwareRenderPort.BACKFACE_CULL_MODE);
            port.resize(WIDTH, HEIGHT);
            port.setScene(mixedScene());
            final Thread renderer = new Thread(() -> renderAlternating(port, left, right),
                "tearing-probe-renderer");
            renderer.setDaemon(true);
            renderer.start();

            final int[] snapshot = new int[WIDTH * HEIGHT];
            // MUTABLE local — snapshots that actually carried a frame.
            int seen = 0;
            while (renderer.isAlive())
            {
                if (port.copyColorInto(snapshot))
                {
                    seen++;
                    assertThat(Arrays.equals(snapshot, fromLeft)
                        || Arrays.equals(snapshot, fromRight))
                        .as("a presented frame must be one whole frame, not a mixture")
                        .isTrue();
                }
            }
            renderer.join(TEARING_TIMEOUT_MILLIS);

            assertThat(seen).as("the probe has to have presented something").isPositive();
            port.shutdown();
        }
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle
    {
        @Test
        @DisplayName("renderFrame is a no-op before a surface or a model exists")
        void shouldDoNothingBeforeSurfaceAndModel()
        {
            final SoftwareRenderPort port = newPort(null, SoftwareRenderPort.BACKFACE_CULL_MODE);
            port.renderFrame(0);
            assertThat(port.framesRendered()).isZero();

            port.resize(WIDTH, HEIGHT);
            port.renderFrame(1);
            assertThat(port.framesRendered())
                .as("a surface without a model still has nothing to draw")
                .isZero();

            port.loadModel(CubeFixture.build());
            port.renderFrame(2);
            assertThat(port.framesRendered()).isEqualTo(1L);
            port.shutdown();
        }

        @Test
        @DisplayName("the default orbit camera frames the model without one being set")
        void shouldOrbitTheModelWhenNoCameraIsSet()
        {
            final SoftwareRenderPort port = newPort(null, SoftwareRenderPort.BACKFACE_CULL_MODE);
            port.resize(WIDTH, HEIGHT);
            port.loadModel(CubeFixture.build());
            port.renderFrame(0);

            assertThat(port.lastCamera()).isNotNull();
            // Tic 0 puts the orbit on the +z axis, raised enough to see the
            // top. Exactly those two faces, and no interior.
            assertThat(colorsIn(copy(port)))
                .containsExactlyInAnyOrder(CubeFixture.PLUS_Z, CubeFixture.PLUS_Y);
            port.shutdown();
        }

        @Test
        @DisplayName("a null clock is refused")
        void shouldRefuseANullClock()
        {
            assertThatThrownBy(() -> new SoftwareRenderPort(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a model with no triangles is refused")
        void shouldRefuseAnEmptyModel()
        {
            final SoftwareRenderPort port = newPort(null, SoftwareRenderPort.BACKFACE_CULL_MODE);

            assertThatThrownBy(() -> port.loadModel(ModelFormat.read(ModelFileFixture.empty())))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ---- scene helpers ----

    // One flat, untextured quad of a given colour, parsed.
    private static ModelFormat quad(final int colour)
    {
        return ModelFormat.read(QuadFixture.square(QUAD_HALF, colour));
    }

    // The camera the scene tests project against: on the +z axis looking back
    // at the origin, square viewport. World x then maps straight to view x and
    // world y to view y, which is what makes the expected coordinates below
    // readable.
    private static Camera sceneCamera()
    {
        return Camera.lookingAt(new Vec3(0.0f, 0.0f, CAMERA_Z), ORIGIN, UP, FOV, 1.0f, NEAR);
    }

    // Two world instances at different depths plus a viewmodel — the smallest
    // scene that exercises both passes and the depth reset between them.
    private static Scene mixedScene()
    {
        return Scene.builder()
            .addWorldInstance(quad(NEAR_COLOR), Mat4.translation(0.5f, 0.0f, 0.5f))
            .addWorldInstance(quad(FAR_COLOR), Mat4.translation(-0.5f, 0.0f, -0.5f))
            .addViewInstance(quad(VIEW_COLOR),
                Mat4.translation(0.0f, 0.0f, VIEWMODEL_DEPTH))
            .build();
    }

    // Enough instances that a geometry chunk has to start part way through one
    // instance and end part way through another, which is the case the
    // flattened stream introduced and a one-instance scene cannot reach. They
    // are spread along z so none is hidden behind another.
    private static Scene manyInstanceScene()
    {
        final Scene.Builder builder = Scene.builder();
        final ModelFormat model = quad(NEAR_COLOR);
        for (int index = 0; index < MANY_INSTANCES; index++)
        {
            builder.addWorldInstance(model,
                Mat4.translation(0.0f, 0.0f, index * INSTANCE_SPACING));
        }
        return builder.build();
    }

    // Renders alternately from two poses until the frame budget is spent. Runs
    // on its own thread, opposite a presenter, for the tearing probe.
    private static void renderAlternating(final SoftwareRenderPort port, final Camera left,
        final Camera right)
    {
        for (int frame = 0; frame < TEARING_FRAMES; frame++)
        {
            // MUTABLE local — which pose this frame renders from.
            Camera pose = left;
            if (frame % 2 == 1)
            {
                pose = right;
            }
            port.setCamera(pose);
            port.renderFrame(frame);
        }
    }

    // Renders one frame of a scene. A null camera leaves the port on its
    // default, which is what the view-only case wants to exercise.
    private static SoftwareRenderPort renderScene(final Scene scene, final Camera camera,
        final I_ThreadPoolPort pool)
    {
        final SoftwareRenderPort port = newPort(pool, SoftwareRenderPort.BACKFACE_CULL_MODE);
        port.resize(WIDTH, HEIGHT);
        if (camera != null)
        {
            port.setCamera(camera);
        }
        port.setScene(scene);
        port.renderFrame(0);
        return port;
    }

    // Renders one frame of a scene and returns the pixels.
    private static int[] frameOf(final Scene scene, final Camera camera,
        final I_ThreadPoolPort pool)
    {
        final SoftwareRenderPort port = renderScene(scene, camera, pool);
        final int[] frame = copy(port);
        port.shutdown();
        return frame;
    }

    private static int pixel(final int[] frame, final int x, final int y)
    {
        return frame[y * WIDTH + x];
    }

    // The inclusive screen rectangle one colour covers: minX, minY, maxX, maxY.
    private static int[] boundsOf(final int[] frame, final int colour)
    {
        // MUTABLE locals — the running extremes of the covered pixels.
        int minX = WIDTH;
        int minY = HEIGHT;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < HEIGHT; y++)
        {
            for (int x = 0; x < WIDTH; x++)
            {
                if (pixel(frame, x, y) != colour)
                {
                    continue;
                }
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        assertThat(maxX).as("the instance must actually be on screen").isNotNegative();
        return new int[] {minX, minY, maxX, maxY};
    }

    // Asserts that a covered rectangle matches where README § 4's projection
    // puts an axis-aligned quad of the given half-extents, centred at
    // (centreX, centreY) in view space at the given depth.
    private static void assertProjected(final int[] box, final float centreX,
        final float centreY, final float halfWidth, final float halfHeight,
        final float depth)
    {
        // The bounds are inclusive pixel indices and the projected edges are
        // continuous coordinates. A pixel is covered when its centre is inside,
        // so the first covered index sits within half a pixel of the near edge
        // and the first UNcovered index — one past the last covered one —
        // within half a pixel of the far edge. Comparing maxima without the
        // +1 would be comparing a pixel index against an edge one pixel away.
        assertThat((float) box[0]).as("left edge")
            .isCloseTo(screenX(centreX - halfWidth, depth), within(PIXEL_TOLERANCE));
        assertThat((float) (box[2] + 1)).as("right edge")
            .isCloseTo(screenX(centreX + halfWidth, depth), within(PIXEL_TOLERANCE));
        // Screen y grows downward, so the top edge is the LARGER view y.
        assertThat((float) box[1]).as("top edge")
            .isCloseTo(screenY(centreY + halfHeight, depth), within(PIXEL_TOLERANCE));
        assertThat((float) (box[3] + 1)).as("bottom edge")
            .isCloseTo(screenY(centreY - halfHeight, depth), within(PIXEL_TOLERANCE));
    }

    private static float screenX(final float viewX, final float depth)
    {
        return (viewX * PROJECTION_SCALE / depth * 0.5f + 0.5f) * WIDTH;
    }

    private static float screenY(final float viewY, final float depth)
    {
        return (0.5f - viewY * PROJECTION_SCALE / depth * 0.5f) * HEIGHT;
    }

    // ---- helpers ----

    // Renders the cube from a fixed viewpoint at +x, +y, +z, so the +x, +y and
    // +z faces are the outward-facing ones and their opposites are not.
    private static int[] render(final Rasterizer.CullMode cull, final I_ThreadPoolPort pool)
    {
        final SoftwareRenderPort port = newPort(pool, cull);
        port.resize(WIDTH, HEIGHT);
        port.loadModel(CubeFixture.build());
        port.setCamera(Camera.lookingAt(new Vec3(3.0f, 2.4f, 3.6f), ORIGIN, UP,
            FOV, (float) WIDTH / (float) HEIGHT, NEAR));
        port.renderFrame(0);
        final int[] frame = copy(port);
        port.shutdown();
        return frame;
    }

    private static SoftwareRenderPort newPort(final I_ThreadPoolPort pool,
        final Rasterizer.CullMode cull)
    {
        final I_TimePort time = new NullTimePort();
        time.init();
        return new SoftwareRenderPort(pool, time, cull);
    }

    private static int[] copy(final SoftwareRenderPort port)
    {
        final int[] frame = new int[port.surfaceWidth() * port.surfaceHeight()];
        port.copyColorInto(frame);
        return frame;
    }

    // Every distinct colour in the frame except the background clear.
    private static Set<Integer> colorsIn(final int[] frame)
    {
        final Set<Integer> found = new HashSet<>();
        for (final int pixel : frame)
        {
            if (pixel != SoftwareRenderPort.DEFAULT_CLEAR_COLOR)
            {
                found.add(pixel);
            }
        }
        return found;
    }

    // Asserts that every row carrying model pixels covers the identical column
    // span. A stride-vs-width shear displaces each row by a fixed number of
    // pixels relative to the one above, so it cannot survive this.
    private static void assertRowsAlign(final int[] frame, final int width, final int height)
    {
        // MUTABLE locals — the span of the first covered row, and whether one
        // has been seen yet.
        int firstMin = -1;
        int firstMax = -1;
        for (int y = 0; y < height; y++)
        {
            final int min = firstCovered(frame, width, y, 1);
            if (min < 0)
            {
                continue;
            }
            final int max = firstCovered(frame, width, y, -1);
            if (firstMin < 0)
            {
                firstMin = min;
                firstMax = max;
                continue;
            }
            assertThat(min).as("left edge of row %d", y).isEqualTo(firstMin);
            assertThat(max).as("right edge of row %d", y).isEqualTo(firstMax);
        }
        assertThat(firstMin).as("the model must actually be on screen").isNotNegative();
    }

    // Scans one row inward from the left (step 1) or the right (step -1) and
    // returns the first column that is not background, or -1.
    private static int firstCovered(final int[] frame, final int width, final int y,
        final int step)
    {
        // MUTABLE local — the scan cursor.
        int x = 0;
        if (step < 0)
        {
            x = width - 1;
        }
        while (x >= 0 && x < width)
        {
            if (frame[y * width + x] != SoftwareRenderPort.DEFAULT_CLEAR_COLOR)
            {
                return x;
            }
            x += step;
        }
        return -1;
    }
}
