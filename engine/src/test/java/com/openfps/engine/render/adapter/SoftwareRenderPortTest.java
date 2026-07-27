/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
