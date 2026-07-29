/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.openfps.engine.core.eventbus.EventBusFactory;
import com.openfps.engine.core.eventbus.I_EventBusPort;
import com.openfps.engine.core.pool.I_ThreadPoolPort;
import com.openfps.engine.core.pool.ThreadPoolFactory;
import com.openfps.engine.core.subsystem.SubsystemRegistry;
import com.openfps.engine.hal.adapter.nulladapter.NullSystemInfoPort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for {@link Rasterizer} — projection, culling, bounding boxes, tile
 * binning, and the property that tiling is invisible in the output.
 *
 * The threading tests carry a hard timeout: the failure mode a tiled renderer
 * risks is a hang, and a hang that is not a test failure is a hung CI job.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RasterizerTest
{
    private static final int WIDTH = 64;
    private static final int HEIGHT = 64;
    private static final int TILE = 16;
    private static final int CLEAR = Framebuffer.CLEAR_COLOR_OPAQUE_BLACK;
    private static final int RED = Rgba.pack(255, 0, 0, 255);

    private I_EventBusPort bus;
    private I_ThreadPoolPort pool;

    @AfterEach
    void tearDown() throws Exception
    {
        if (pool != null && pool.state() != I_ThreadPoolPort.State.SHUTDOWN)
        {
            pool.shutdown();
            pool.awaitTermination(5000);
            pool = null;
        }
        if (bus != null && bus.state() != I_EventBusPort.State.SHUTDOWN)
        {
            bus.shutdown();
            bus = null;
        }
    }

    // Brings a real WorkerPool up with the given worker count.
    private I_ThreadPoolPort startPool(final int workers)
    {
        bus = EventBusFactory.createShared();
        bus.init(512);
        pool = ThreadPoolFactory.createFixed(bus, new SubsystemRegistry());
        pool.init(workers);
        pool.start();
        return pool;
    }

    // A screen-space triangle covering roughly the top-left quadrant, wound
    // clockwise on screen (positive signed area with y growing downward).
    private static float[] clockwiseTriangle(final int width, final int height)
    {
        final float[] out = new float[TriangleClipper.TRIANGLE_VERTICES
            * TriangleClipper.POSITION_FLOATS];
        final float[] screen = {4.0f, 4.0f, 40.0f, 4.0f, 4.0f, 40.0f};
        final float[][] attributes = {RasterFixtures.NO_ATTRIBUTES,
            RasterFixtures.NO_ATTRIBUTES, RasterFixtures.NO_ATTRIBUTES};
        RasterFixtures.triangle(out, 0, width, height, screen, 1.0f, attributes);
        return out;
    }

    // The same triangle with two vertices swapped, so it winds the other way.
    private static float[] counterClockwiseTriangle(final int width, final int height)
    {
        final float[] out = new float[TriangleClipper.TRIANGLE_VERTICES
            * TriangleClipper.POSITION_FLOATS];
        final float[] screen = {4.0f, 4.0f, 4.0f, 40.0f, 40.0f, 4.0f};
        final float[][] attributes = {RasterFixtures.NO_ATTRIBUTES,
            RasterFixtures.NO_ATTRIBUTES, RasterFixtures.NO_ATTRIBUTES};
        RasterFixtures.triangle(out, 0, width, height, screen, 1.0f, attributes);
        return out;
    }

    // Counts colour-buffer pixels that are not the clear value.
    private static int paintedCount(final Framebuffer target)
    {
        // MUTABLE local — running total.
        int painted = 0;
        for (int y = 0; y < target.height(); y++)
        {
            for (int x = 0; x < target.width(); x++)
            {
                if (target.pixel(x, y) != CLEAR)
                {
                    painted++;
                }
            }
        }
        return painted;
    }

    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        void shouldRejectNegativeAttributeCount()
        {
            assertThatThrownBy(() -> new Rasterizer(-1, 8, 1, Rasterizer.CullMode.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attributeCount");
        }

        @Test
        void shouldRejectNonPositiveTriangleCapacity()
        {
            assertThatThrownBy(() -> new Rasterizer(0, 0, 1, Rasterizer.CullMode.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxTriangles");
        }

        @Test
        void shouldRejectNonPositiveChunkCount()
        {
            assertThatThrownBy(() -> new Rasterizer(0, 8, 0, Rasterizer.CullMode.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunkCount");
        }

        @Test
        void shouldRejectNullCullMode()
        {
            assertThatThrownBy(() -> new Rasterizer(0, 8, 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cullMode");
        }

        @Test
        void shouldDeriveStridesFromAttributeCount()
        {
            final Rasterizer rasterizer = new Rasterizer(2, 8, 1, Rasterizer.CullMode.NONE);
            assertThat(rasterizer.vertexStride())
                .isEqualTo(TriangleClipper.POSITION_FLOATS + 2);
            assertThat(rasterizer.recordStride())
                .isEqualTo(Rasterizer.RECORD_HEADER_FLOATS + Rasterizer.PLANE_FLOATS * 2);
            assertThat(Rasterizer.attributePlaneOffset(1))
                .isEqualTo(Rasterizer.RECORD_HEADER_FLOATS + Rasterizer.PLANE_FLOATS);
        }

        @Test
        void shouldRejectUseBeforeBeginFrame()
        {
            final Rasterizer rasterizer = new Rasterizer(0, 8, 1, Rasterizer.CullMode.NONE);
            assertThatThrownBy(() -> rasterizer.setupAndBin(new float[9], 1, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("beginFrame");
        }

        @Test
        void shouldRejectAnUninitialisedFramebuffer()
        {
            final Rasterizer rasterizer = new Rasterizer(0, 8, 1, Rasterizer.CullMode.NONE);
            assertThatThrownBy(() -> rasterizer.beginFrame(new Framebuffer(TILE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("READY");
        }

        @Test
        void shouldRejectMoreTrianglesThanCapacity()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            final Rasterizer rasterizer = new Rasterizer(0, 2, 1, Rasterizer.CullMode.NONE);
            rasterizer.beginFrame(target);
            assertThatThrownBy(() -> rasterizer.setupAndBin(new float[100], 3, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("triangles");
        }

        @Test
        void shouldRejectAShortVertexStream()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            final Rasterizer rasterizer = new Rasterizer(0, 4, 1, Rasterizer.CullMode.NONE);
            rasterizer.beginFrame(target);
            assertThatThrownBy(() -> rasterizer.setupAndBin(new float[8], 1, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("floats");
        }

        @Test
        void shouldRejectASpanRendererWithADifferentAttributeCount()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            final Rasterizer rasterizer = new Rasterizer(0, 4, 1, Rasterizer.CullMode.NONE);
            rasterizer.beginFrame(target);
            final SpanRenderer renderer = new SpanRenderer(SpanRenderer.ShadingMode.FLAT, 2);
            assertThatThrownBy(() -> rasterizer.rasterize(renderer, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attributes");
        }
    }

    @Nested
    @DisplayName("backface culling")
    class Culling
    {
        private int paint(final Rasterizer.CullMode mode, final float[] vertices)
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            final Rasterizer rasterizer = new Rasterizer(0, 4, 1, mode);
            rasterizer.beginFrame(target);
            rasterizer.setupAndBin(vertices, 1, null, new int[] {RED});
            rasterizer.rasterize(new SpanRenderer(SpanRenderer.ShadingMode.FLAT, 0), null);
            return paintedCount(target);
        }

        @Test
        void shouldDrawBothWindingsWhenCullingIsOff()
        {
            assertThat(paint(Rasterizer.CullMode.NONE, clockwiseTriangle(WIDTH, HEIGHT)))
                .isGreaterThan(0);
            assertThat(paint(Rasterizer.CullMode.NONE, counterClockwiseTriangle(WIDTH, HEIGHT)))
                .isGreaterThan(0);
        }

        @Test
        void shouldDiscardClockwiseAndKeepCounterClockwise()
        {
            assertThat(paint(Rasterizer.CullMode.CLOCKWISE, clockwiseTriangle(WIDTH, HEIGHT)))
                .isZero();
            assertThat(paint(Rasterizer.CullMode.CLOCKWISE,
                counterClockwiseTriangle(WIDTH, HEIGHT))).isGreaterThan(0);
        }

        @Test
        void shouldDiscardCounterClockwiseAndKeepClockwise()
        {
            assertThat(paint(Rasterizer.CullMode.COUNTER_CLOCKWISE,
                counterClockwiseTriangle(WIDTH, HEIGHT))).isZero();
            assertThat(paint(Rasterizer.CullMode.COUNTER_CLOCKWISE,
                clockwiseTriangle(WIDTH, HEIGHT))).isGreaterThan(0);
        }

        @Test
        void shouldPaintTheSamePixelsForBothWindingsWhenCullingIsOff()
        {
            final Framebuffer a = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            final Framebuffer b = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            final Rasterizer rasterizer = new Rasterizer(0, 4, 1, Rasterizer.CullMode.NONE);
            final SpanRenderer renderer = new SpanRenderer(SpanRenderer.ShadingMode.FLAT, 0);
            rasterizer.beginFrame(a);
            rasterizer.setupAndBin(clockwiseTriangle(WIDTH, HEIGHT), 1, null, new int[] {RED});
            rasterizer.rasterize(renderer, null);
            rasterizer.beginFrame(b);
            rasterizer.setupAndBin(counterClockwiseTriangle(WIDTH, HEIGHT), 1, null,
                new int[] {RED});
            rasterizer.rasterize(renderer, null);
            assertThat(b.colorBuffer()).isEqualTo(a.colorBuffer());
        }
    }

    @Nested
    @DisplayName("rejection")
    class Rejection
    {
        private Rasterizer setup(final float[] screen, final Framebuffer target)
        {
            final float[] vertices = new float[TriangleClipper.TRIANGLE_VERTICES
                * TriangleClipper.POSITION_FLOATS];
            final float[][] attributes = {RasterFixtures.NO_ATTRIBUTES,
                RasterFixtures.NO_ATTRIBUTES, RasterFixtures.NO_ATTRIBUTES};
            RasterFixtures.triangle(vertices, 0, WIDTH, HEIGHT, screen, 1.0f, attributes);
            final Rasterizer rasterizer = new Rasterizer(0, 4, 1, Rasterizer.CullMode.NONE);
            rasterizer.beginFrame(target);
            rasterizer.setupAndBin(vertices, 1, null, new int[] {RED});
            return rasterizer;
        }

        @Test
        void shouldRejectAZeroAreaTriangle()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            final Rasterizer rasterizer =
                setup(new float[] {4.0f, 4.0f, 20.0f, 20.0f, 40.0f, 40.0f}, target);
            assertThat(rasterizer.isLive(0)).isFalse();
            rasterizer.rasterize(new SpanRenderer(SpanRenderer.ShadingMode.FLAT, 0), null);
            assertThat(paintedCount(target)).isZero();
        }

        @Test
        void shouldRejectADuplicatedVertexTriangle()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            final Rasterizer rasterizer =
                setup(new float[] {4.0f, 4.0f, 4.0f, 4.0f, 40.0f, 40.0f}, target);
            assertThat(rasterizer.isLive(0)).isFalse();
        }

        @Test
        void shouldRejectATriangleEntirelyLeftOfTheViewport()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            final Rasterizer rasterizer =
                setup(new float[] {-40.0f, 4.0f, -8.0f, 4.0f, -40.0f, 40.0f}, target);
            assertThat(rasterizer.isLive(0)).isFalse();
        }

        @Test
        void shouldRejectATriangleEntirelyRightOfTheViewport()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            final Rasterizer rasterizer = setup(new float[] {
                WIDTH + 4.0f, 4.0f, WIDTH + 40.0f, 4.0f, WIDTH + 4.0f, 40.0f}, target);
            assertThat(rasterizer.isLive(0)).isFalse();
        }

        @Test
        void shouldRejectATriangleEntirelyAboveTheViewport()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            final Rasterizer rasterizer =
                setup(new float[] {4.0f, -40.0f, 40.0f, -40.0f, 4.0f, -8.0f}, target);
            assertThat(rasterizer.isLive(0)).isFalse();
        }

        @Test
        void shouldRejectATriangleEntirelyBelowTheViewport()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            final Rasterizer rasterizer = setup(new float[] {
                4.0f, HEIGHT + 4.0f, 40.0f, HEIGHT + 4.0f, 4.0f, HEIGHT + 40.0f}, target);
            assertThat(rasterizer.isLive(0)).isFalse();
        }

        @Test
        void shouldClampTheBoundingBoxOfAPartlyOffScreenTriangle()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            final Rasterizer rasterizer = setup(new float[] {
                -100.0f, -100.0f, 200.0f, -100.0f, -100.0f, 200.0f}, target);
            final float[] records = rasterizer.records();
            assertThat(records[Rasterizer.BOUND_MIN_X]).isZero();
            assertThat(records[Rasterizer.BOUND_MIN_Y]).isZero();
            assertThat(records[Rasterizer.BOUND_MAX_X]).isEqualTo(WIDTH - 1);
            assertThat(records[Rasterizer.BOUND_MAX_Y]).isEqualTo(HEIGHT - 1);
        }

        @Test
        void shouldNotBinARejectedTriangleIntoAnyTile()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            final Rasterizer rasterizer =
                setup(new float[] {4.0f, 4.0f, 20.0f, 20.0f, 40.0f, 40.0f}, target);
            for (int tile = 0; tile < target.tileCount(); tile++)
            {
                assertThat(rasterizer.binnedTriangleCount(tile)).isZero();
            }
        }
    }

    @Nested
    @DisplayName("tile binning")
    class Binning
    {
        @Test
        void shouldBinATriangleIntoOnlyTheTilesItsBoxCovers()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            final float[] vertices = new float[TriangleClipper.TRIANGLE_VERTICES
                * TriangleClipper.POSITION_FLOATS];
            final float[][] attributes = {RasterFixtures.NO_ATTRIBUTES,
                RasterFixtures.NO_ATTRIBUTES, RasterFixtures.NO_ATTRIBUTES};
            // Entirely inside tile (0, 0), which is the 16x16 top-left square.
            RasterFixtures.triangle(vertices, 0, WIDTH, HEIGHT,
                new float[] {2.0f, 2.0f, 12.0f, 2.0f, 2.0f, 12.0f}, 1.0f, attributes);
            final Rasterizer rasterizer = new Rasterizer(0, 4, 1, Rasterizer.CullMode.NONE);
            rasterizer.beginFrame(target);
            rasterizer.setupAndBin(vertices, 1, null, null);

            assertThat(rasterizer.binnedTriangleCount(0)).isEqualTo(1);
            for (int tile = 1; tile < target.tileCount(); tile++)
            {
                assertThat(rasterizer.binnedTriangleCount(tile)).isZero();
            }
        }

        @Test
        void shouldBinAFullScreenTriangleIntoEveryTile()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            final float[] vertices = new float[TriangleClipper.TRIANGLE_VERTICES
                * TriangleClipper.POSITION_FLOATS];
            final float[][] attributes = {RasterFixtures.NO_ATTRIBUTES,
                RasterFixtures.NO_ATTRIBUTES, RasterFixtures.NO_ATTRIBUTES};
            RasterFixtures.triangle(vertices, 0, WIDTH, HEIGHT,
                new float[] {-200.0f, -200.0f, 400.0f, -200.0f, -200.0f, 400.0f}, 1.0f,
                attributes);
            final Rasterizer rasterizer = new Rasterizer(0, 4, 1, Rasterizer.CullMode.NONE);
            rasterizer.beginFrame(target);
            rasterizer.setupAndBin(vertices, 1, null, null);

            assertThat(target.tileCount()).isEqualTo(16);
            for (int tile = 0; tile < target.tileCount(); tile++)
            {
                assertThat(rasterizer.binnedTriangleCount(tile)).isEqualTo(1);
            }
        }

        @Test
        void shouldBinATriangleSmallerThanOnePixel()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            final float[] vertices = new float[TriangleClipper.TRIANGLE_VERTICES
                * TriangleClipper.POSITION_FLOATS];
            final float[][] attributes = {RasterFixtures.NO_ATTRIBUTES,
                RasterFixtures.NO_ATTRIBUTES, RasterFixtures.NO_ATTRIBUTES};
            RasterFixtures.triangle(vertices, 0, WIDTH, HEIGHT,
                new float[] {20.1f, 20.1f, 20.3f, 20.1f, 20.1f, 20.3f}, 1.0f, attributes);
            final Rasterizer rasterizer = new Rasterizer(0, 4, 1, Rasterizer.CullMode.NONE);
            rasterizer.beginFrame(target);
            rasterizer.setupAndBin(vertices, 1, null, new int[] {RED});

            assertThat(rasterizer.isLive(0)).isTrue();
            rasterizer.rasterize(new SpanRenderer(SpanRenderer.ShadingMode.FLAT, 0), null);
            // No pixel centre falls inside it, so it covers nothing — and must
            // not have thrown or painted a neighbouring pixel doing so.
            assertThat(paintedCount(target)).isZero();
        }

        @Test
        void shouldSurviveAResizeOfTheTileGrid()
        {
            final Rasterizer rasterizer = new Rasterizer(0, 4, 2, Rasterizer.CullMode.NONE);
            final Framebuffer small = RasterFixtures.framebuffer(32, 32, TILE);
            rasterizer.beginFrame(small);
            rasterizer.setupAndBin(clockwiseTriangle(32, 32), 1, null, new int[] {RED});
            rasterizer.rasterize(new SpanRenderer(SpanRenderer.ShadingMode.FLAT, 0), null);

            final Framebuffer large = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            rasterizer.beginFrame(large);
            rasterizer.setupAndBin(clockwiseTriangle(WIDTH, HEIGHT), 1, null, new int[] {RED});
            rasterizer.rasterize(new SpanRenderer(SpanRenderer.ShadingMode.FLAT, 0), null);
            assertThat(paintedCount(large)).isGreaterThan(0);
        }

        @Test
        void shouldClearBinsBetweenFrames()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            final Rasterizer rasterizer = new Rasterizer(0, 4, 1, Rasterizer.CullMode.NONE);
            final float[] vertices = clockwiseTriangle(WIDTH, HEIGHT);
            rasterizer.beginFrame(target);
            rasterizer.setupAndBin(vertices, 1, null, null);
            final int first = rasterizer.binnedTriangleCount(0);
            rasterizer.beginFrame(target);
            rasterizer.setupAndBin(vertices, 1, null, null);
            assertThat(rasterizer.binnedTriangleCount(0)).isEqualTo(first).isPositive();
        }

        @Test
        void shouldAcceptAnEmptyFrame()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            final Rasterizer rasterizer = new Rasterizer(0, 4, 3, Rasterizer.CullMode.NONE);
            rasterizer.beginFrame(target);
            rasterizer.setupAndBin(new float[0], 0, null, null);
            rasterizer.rasterize(new SpanRenderer(SpanRenderer.ShadingMode.FLAT, 0), null);
            assertThat(paintedCount(target)).isZero();
            assertThat(rasterizer.triangleCount()).isZero();
        }
    }

    @Nested
    @DisplayName("depth")
    class Depth
    {
        // Two overlapping full-quadrant triangles at different depths, drawn in
        // the given order. Returns the colour at a pixel both cover.
        private int drawOverlapping(final boolean nearFirst)
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            final int stride = TriangleClipper.POSITION_FLOATS;
            final float[] vertices = new float[2 * TriangleClipper.TRIANGLE_VERTICES * stride];
            final float[][] attributes = {RasterFixtures.NO_ATTRIBUTES,
                RasterFixtures.NO_ATTRIBUTES, RasterFixtures.NO_ATTRIBUTES};
            final float[] screen = {4.0f, 4.0f, 60.0f, 4.0f, 4.0f, 60.0f};
            // w = 1 is nearer than w = 5, because the test is 1/w greater passes.
            final int[] colors = new int[2];
            if (nearFirst)
            {
                RasterFixtures.triangle(vertices, 0, WIDTH, HEIGHT, screen, 1.0f, attributes);
                RasterFixtures.triangle(vertices, 1, WIDTH, HEIGHT, screen, 5.0f, attributes);
                colors[0] = RED;
                colors[1] = Rgba.pack(0, 0, 255, 255);
            }
            else
            {
                RasterFixtures.triangle(vertices, 0, WIDTH, HEIGHT, screen, 5.0f, attributes);
                RasterFixtures.triangle(vertices, 1, WIDTH, HEIGHT, screen, 1.0f, attributes);
                colors[0] = Rgba.pack(0, 0, 255, 255);
                colors[1] = RED;
            }
            final Rasterizer rasterizer = new Rasterizer(0, 4, 1, Rasterizer.CullMode.NONE);
            rasterizer.beginFrame(target);
            rasterizer.setupAndBin(vertices, 2, null, colors);
            rasterizer.rasterize(new SpanRenderer(SpanRenderer.ShadingMode.FLAT, 0), null);
            return target.pixel(10, 10);
        }

        @Test
        void shouldKeepTheNearerSurfaceWhateverTheSubmissionOrder()
        {
            assertThat(drawOverlapping(true)).isEqualTo(RED);
            assertThat(drawOverlapping(false)).isEqualTo(RED);
        }

        @Test
        void shouldWriteOneOverWIntoTheDepthBuffer()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            final float[] vertices = new float[TriangleClipper.TRIANGLE_VERTICES
                * TriangleClipper.POSITION_FLOATS];
            final float[][] attributes = {RasterFixtures.NO_ATTRIBUTES,
                RasterFixtures.NO_ATTRIBUTES, RasterFixtures.NO_ATTRIBUTES};
            RasterFixtures.triangle(vertices, 0, WIDTH, HEIGHT,
                new float[] {4.0f, 4.0f, 60.0f, 4.0f, 4.0f, 60.0f}, 4.0f, attributes);
            final Rasterizer rasterizer = new Rasterizer(0, 4, 1, Rasterizer.CullMode.NONE);
            rasterizer.beginFrame(target);
            rasterizer.setupAndBin(vertices, 1, null, new int[] {RED});
            rasterizer.rasterize(new SpanRenderer(SpanRenderer.ShadingMode.FLAT, 0), null);

            assertThat(target.depthAt(10, 10))
                .isCloseTo(0.25f, within(RenderTestEpsilon.EPSILON));
            assertThat(target.depthAt(WIDTH - 1, HEIGHT - 1))
                .isEqualTo(Framebuffer.DEPTH_CLEAR);
        }
    }

    @Nested
    @DisplayName("tiling is invisible")
    class TilingIsInvisible
    {
        private static final int SCENE_TRIANGLES = 400;

        // The fixed ladder, plus the count this machine's pool would actually
        // build. The invariant is "output does not depend on worker count", so
        // the one count nobody chose deliberately — the auto-sized one — is
        // exactly the one worth covering, and it differs on every CI runner.
        // Deduplicated, because a two-processor box would otherwise run 1 twice.
        static IntStream workerCounts()
        {
            return IntStream.of(1, 2, 3, 4, 8, ThreadPoolFactory.resolveWorkerCount(
                new NullSystemInfoPort().logicalProcessorCount())).distinct();
        }

        // Renders the same scene into a fresh framebuffer, optionally on a pool.
        private Framebuffer render(final float[] vertices, final int[] colors,
            final int chunks, final I_ThreadPoolPort workerPool, final int width,
            final int height)
        {
            final Framebuffer target = RasterFixtures.framebuffer(width, height, TILE);
            final Rasterizer rasterizer =
                new Rasterizer(0, SCENE_TRIANGLES, chunks, Rasterizer.CullMode.NONE);
            final SpanRenderer renderer = new SpanRenderer(SpanRenderer.ShadingMode.FLAT, 0);
            rasterizer.beginFrame(target);
            rasterizer.setupAndBin(vertices, SCENE_TRIANGLES, null, colors, workerPool);
            rasterizer.rasterize(renderer, null, workerPool);
            return target;
        }

        @ParameterizedTest
        @MethodSource("workerCounts")
        @DisplayName("multi-threaded output is byte-identical to single-threaded")
        void shouldMatchSingleThreadedOutputAtEveryWorkerCount(final int workers)
        {
            final int[] colors = new int[SCENE_TRIANGLES];
            final float[] vertices =
                RasterFixtures.randomScene(20260727L, SCENE_TRIANGLES, WIDTH, HEIGHT, colors);
            final Framebuffer serial = render(vertices, colors, 4, null, WIDTH, HEIGHT);
            final Framebuffer parallel =
                render(vertices, colors, 4, startPool(workers), WIDTH, HEIGHT);

            assertThat(parallel.colorBuffer()).isEqualTo(serial.colorBuffer());
            assertThat(parallel.depthBuffer()).isEqualTo(serial.depthBuffer());
        }

        @ParameterizedTest
        @MethodSource("workerCounts")
        @DisplayName("identical at a resolution that is not a tile multiple")
        void shouldMatchSingleThreadedOutputAtARaggedResolution(final int workers)
        {
            final int width = 70;
            final int height = 50;
            final int[] colors = new int[SCENE_TRIANGLES];
            final float[] vertices =
                RasterFixtures.randomScene(31337L, SCENE_TRIANGLES, width, height, colors);
            final Framebuffer serial = render(vertices, colors, 4, null, width, height);
            final Framebuffer parallel =
                render(vertices, colors, 4, startPool(workers), width, height);

            assertThat(serial.strideInPixels()).isNotEqualTo(serial.width());
            assertThat(parallel.colorBuffer()).isEqualTo(serial.colorBuffer());
            assertThat(parallel.depthBuffer()).isEqualTo(serial.depthBuffer());
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 2, 5, 16, 64})
        @DisplayName("output does not depend on how the stream is chunked")
        void shouldNotDependOnChunkCount(final int chunks)
        {
            final int[] colors = new int[SCENE_TRIANGLES];
            final float[] vertices =
                RasterFixtures.randomScene(4242L, SCENE_TRIANGLES, WIDTH, HEIGHT, colors);
            final Framebuffer reference = render(vertices, colors, 1, null, WIDTH, HEIGHT);
            final Framebuffer chunked = render(vertices, colors, chunks, null, WIDTH, HEIGHT);
            assertThat(chunked.colorBuffer()).isEqualTo(reference.colorBuffer());
        }

        @Test
        @DisplayName("the same scene rasterized twice produces identical output")
        void shouldBeReproducibleRunToRun()
        {
            final int[] colors = new int[SCENE_TRIANGLES];
            final float[] vertices =
                RasterFixtures.randomScene(99L, SCENE_TRIANGLES, WIDTH, HEIGHT, colors);
            final I_ThreadPoolPort workerPool = startPool(4);
            final Framebuffer first = render(vertices, colors, 4, workerPool, WIDTH, HEIGHT);
            final Framebuffer second = render(vertices, colors, 4, workerPool, WIDTH, HEIGHT);
            assertThat(second.colorBuffer()).isEqualTo(first.colorBuffer());
            assertThat(second.depthBuffer()).isEqualTo(first.depthBuffer());
        }

        @Test
        @DisplayName("a scene wider than one tile paints across the seams")
        void shouldPaintAcrossTileSeams()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            final float[] vertices = new float[TriangleClipper.TRIANGLE_VERTICES
                * TriangleClipper.POSITION_FLOATS];
            final float[][] attributes = {RasterFixtures.NO_ATTRIBUTES,
                RasterFixtures.NO_ATTRIBUTES, RasterFixtures.NO_ATTRIBUTES};
            RasterFixtures.triangle(vertices, 0, WIDTH, HEIGHT,
                new float[] {-100.0f, -100.0f, 400.0f, -100.0f, -100.0f, 400.0f}, 1.0f,
                attributes);
            final Rasterizer rasterizer = new Rasterizer(0, 4, 1, Rasterizer.CullMode.NONE);
            rasterizer.beginFrame(target);
            rasterizer.setupAndBin(vertices, 1, null, new int[] {RED});
            rasterizer.rasterize(new SpanRenderer(SpanRenderer.ShadingMode.FLAT, 0), null);

            // Every visible pixel is covered, including the columns and rows
            // where one tile hands over to the next.
            assertThat(paintedCount(target)).isEqualTo(WIDTH * HEIGHT);
        }

        @Test
        @DisplayName("the stride padding is never written by the raster pass")
        void shouldNotWriteThePaddingColumns()
        {
            final int width = 70;
            final int height = 50;
            final Framebuffer target = RasterFixtures.framebuffer(width, height, TILE);
            final int[] colors = new int[SCENE_TRIANGLES];
            final float[] vertices =
                RasterFixtures.randomScene(7L, SCENE_TRIANGLES, width, height, colors);
            final Rasterizer rasterizer =
                new Rasterizer(0, SCENE_TRIANGLES, 4, Rasterizer.CullMode.NONE);
            rasterizer.beginFrame(target);
            rasterizer.setupAndBin(vertices, SCENE_TRIANGLES, null, colors);
            rasterizer.rasterize(new SpanRenderer(SpanRenderer.ShadingMode.FLAT, 0), null);

            for (int y = 0; y < height; y++)
            {
                for (int x = width; x < target.strideInPixels(); x++)
                {
                    assertThat(target.colorBuffer()[y * target.strideInPixels() + x])
                        .isEqualTo(CLEAR);
                    assertThat(target.depthBuffer()[y * target.strideInPixels() + x])
                        .isEqualTo(Framebuffer.DEPTH_CLEAR);
                }
            }
        }
    }
}
