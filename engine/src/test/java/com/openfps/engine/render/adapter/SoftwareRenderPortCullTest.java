/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import static org.assertj.core.api.Assertions.assertThat;

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
 * The per-instance frustum cull, driven end-to-end through the real render port.
 *
 * <h2>What is actually being asserted</h2>
 *
 * <p>{@link InstanceCullTest} proves the geometric predicate. This file proves
 * the thing the predicate is <i>for</i>: that adding instances the camera cannot
 * see changes the triangle count and <b>nothing else</b>. Every case here builds
 * two scenes that differ only by invisible instances and compares the finished
 * frames <b>byte for byte</b> — not "close", not "within an epsilon", equal.</p>
 *
 * <p>That is the same standard the pooled-versus-serial tests hold the renderer
 * to, and for the same reason: a culling bug is a change to the image, so an
 * assertion that tolerates changes to the image cannot detect one.</p>
 *
 * <p>The pooled case is included because culling happens on the frame thread,
 * before any worker starts, and therefore must not disturb the chunk boundaries
 * the parallel path relies on — the stream it splits is now the visible
 * instances rather than all of them.</p>
 */
@DisplayName("SoftwareRenderPort culling")
final class SoftwareRenderPortCullTest
{
    /** Square viewport, so aspect is 1 and the frustum is symmetric. */
    private static final int SIZE = 64;

    /** Vertical field of view, 90 degrees. */
    private static final float FOV = (float) (Math.PI / 2.0);

    /** Near plane. */
    private static final float NEAR = 0.05f;

    /** World up. */
    private static final Vec3 UP = new Vec3(0.0f, 1.0f, 0.0f);

    /** Eye position: the origin, looking along +z. */
    private static final Vec3 EYE = new Vec3(0.0f, 0.0f, 0.0f);

    /** Viewing direction. */
    private static final Vec3 FORWARD = new Vec3(0.0f, 0.0f, 1.0f);

    /** Half-edge of every quad. */
    private static final float HALF = 1.0f;

    /** Where the visible quad sits: straight ahead, comfortably inside. */
    private static final float VISIBLE_Z = 3.0f;

    /** How far behind the eye the invisible quads sit. */
    private static final float BEHIND_Z = -20.0f;

    /** How far off to the side the invisible quads sit. */
    private static final float BESIDE_X = 400.0f;

    /** Invisible quads added, enough that missing the cull would be obvious. */
    private static final int DECOY_COUNT = 24;

    /**
     * Indices of a quad whose front face points back down -z, at the camera.
     *
     * <p>Reversed relative to the winding {@code SoftwareRenderPortTranslucentTest}
     * uses, and deliberately: that camera sits out on +z looking back at the
     * origin, while this one sits at the origin looking along +z, so a quad
     * facing the camera there faces away from it here. Getting this wrong
     * renders nothing at all, which every "the two frames are equal" assertion
     * in this file would happily accept — which is exactly what
     * {@link ImageUnchanged#theVisibleQuadIsDrawn} is here to refuse.</p>
     */
    private static final int[] QUAD_INDICES = {0, 2, 1, 0, 3, 2};

    /** Corners in a quad. */
    private static final int QUAD_CORNERS = 4;

    /** Triangles in a quad. */
    private static final int QUAD_TRIANGLES = 2;

    /** Coverage the translucent cases composite at. */
    private static final int COVERAGE = 128;

    /** A second coverage, so the translucent phase has to cut runs. */
    private static final int OTHER_COVERAGE = 64;

    /** Colour of the quad that is meant to be on screen. */
    private static final int VISIBLE_COLOUR = Rgba.pack(220, 40, 40, 255);

    /** Colour of the quads that are not. */
    private static final int DECOY_COLOUR = Rgba.pack(40, 220, 40, 255);

    /** Square aspect, so the frustum's horizontal half-angle matches the vertical. */
    private static final float ASPECT = 1.0f;

    /** Event-bus capacity for the standalone pool; nothing is published to it. */
    private static final int BUS_CAPACITY = 16;

    // A flat, single-coloured quad in the model's z = 0 plane.
    private static ModelFormat quad(final int colour)
    {
        final int[] vertices = new int[QUAD_CORNERS * ModelFormat.VERTEX_STRIDE_INTS];
        ModelFormat.writeVertex(vertices, 0, -HALF, -HALF, 0.0f, 0.0f, 0.0f, colour);
        ModelFormat.writeVertex(vertices, 1, HALF, -HALF, 0.0f, 1.0f, 0.0f, colour);
        ModelFormat.writeVertex(vertices, 2, HALF, HALF, 0.0f, 1.0f, 1.0f, colour);
        ModelFormat.writeVertex(vertices, 3, -HALF, HALF, 0.0f, 0.0f, 1.0f, colour);
        return ModelFormat.ofGeometry(vertices, QUAD_INDICES);
    }

    private static Camera camera()
    {
        return Camera.create(EYE, FORWARD, UP, FOV, ASPECT, NEAR);
    }

    // One rendered frame, de-padded, plus the two counters that explain it.
    //
    // A worker count of zero is the serial reference path — a null pool, every
    // dispatch a plain loop on the calling thread — which is the same reference
    // the rest of the render suite compares against.
    private static Rendered render(final Scene scene, final int workers)
    {
        if (workers <= 0)
        {
            return renderWith(scene, null);
        }
        final I_EventBusPort bus = EventBusFactory.createShared();
        bus.init(BUS_CAPACITY);
        final I_ThreadPoolPort pool =
            ThreadPoolFactory.createFixed(bus, new SubsystemRegistry());
        pool.init(workers);
        pool.start();
        try
        {
            return renderWith(scene, pool);
        }
        finally
        {
            pool.shutdown();
            bus.shutdown();
        }
    }

    // Brings the port up on one pool — or none — draws one frame, and reads it
    // back out through the presentation handoff the platform uses.
    private static Rendered renderWith(final Scene scene, final I_ThreadPoolPort pool)
    {
        final I_TimePort time = new NullTimePort();
        time.init();
        final SoftwareRenderPort port = new SoftwareRenderPort(pool, time);
        port.init();
        port.resize(SIZE, SIZE);
        port.setCamera(camera());
        port.setScene(scene);
        port.renderFrame(0);
        final int[] frame = new int[SIZE * SIZE];
        port.copyColorInto(frame);
        final Rendered result = new Rendered(frame, port.lastFrameTriangles(),
            port.lastFrameParallelPasses());
        port.shutdown();
        time.shutdown();
        return result;
    }

    // The scene every case starts from: one quad, straight ahead, on screen.
    private static Scene.Builder withVisibleQuad()
    {
        return Scene.builder().addWorldInstance(quad(VISIBLE_COLOUR),
            Mat4.translation(0.0f, 0.0f, VISIBLE_Z));
    }

    // Adds instances that cannot contribute a pixel: half behind the eye, half
    // far enough off to one side that their screen bounding box misses the
    // viewport. Both are cases the rasterizer already rejected per triangle;
    // the cull's job is to reject them per instance instead.
    private static Scene.Builder withDecoys(final Scene.Builder builder)
    {
        final ModelFormat decoy = quad(DECOY_COLOUR);
        for (int index = 0; index < DECOY_COUNT; index++)
        {
            if (index % 2 == 0)
            {
                builder.addWorldInstance(decoy,
                    Mat4.translation(0.0f, 0.0f, BEHIND_Z - index));
                continue;
            }
            builder.addWorldInstance(decoy,
                Mat4.translation(BESIDE_X + index, 0.0f, VISIBLE_Z));
        }
        return builder;
    }

    @Nested
    @DisplayName("the image is unchanged")
    final class ImageUnchanged
    {
        @Test
        @DisplayName("invisible world instances do not change one byte of the frame")
        void invisibleInstancesChangeNothing()
        {
            final Rendered bare = render(withVisibleQuad().build(), 0);
            final Rendered padded = render(withDecoys(withVisibleQuad()).build(), 0);
            assertThat(padded.color).isEqualTo(bare.color);
        }

        @Test
        @DisplayName("and not with a worker pool either, at several worker counts")
        void invisibleInstancesChangeNothingInParallel()
        {
            final Rendered reference = render(withVisibleQuad().build(), 0);
            for (final int workers : new int[] {1, 2, 3, 8})
            {
                final Rendered padded = render(withDecoys(withVisibleQuad()).build(), workers);
                assertThat(padded.color)
                    .withFailMessage("frame differs at %d workers", workers)
                    .isEqualTo(reference.color);
            }
        }

        @Test
        @DisplayName("the visible quad really is on screen, so the comparison means something")
        void theVisibleQuadIsDrawn()
        {
            // Without this the file would pass just as well if every case
            // rendered an empty frame.
            final Rendered bare = render(withVisibleQuad().build(), 0);
            assertThat(bare.color[SIZE / 2 * SIZE + SIZE / 2]).isEqualTo(VISIBLE_COLOUR);
        }

        @Test
        @DisplayName("invisible translucent instances do not change one byte either")
        void invisibleTranslucentInstancesChangeNothing()
        {
            final ModelFormat visible = quad(VISIBLE_COLOUR);
            final ModelFormat decoy = quad(DECOY_COLOUR);
            final Scene bare = Scene.builder()
                .addTranslucentWorldInstance(visible,
                    Mat4.translation(0.0f, 0.0f, VISIBLE_Z), Scene.UNTAGGED, COVERAGE)
                .build();
            // The decoys carry the OTHER coverage and are interleaved in depth
            // with the visible one, so before culling they cut the back-to-front
            // order into three runs. Culling must leave the survivor composited
            // exactly as it was on its own.
            final Scene padded = Scene.builder()
                .addTranslucentWorldInstance(decoy,
                    Mat4.translation(0.0f, 0.0f, BEHIND_Z), Scene.UNTAGGED, OTHER_COVERAGE)
                .addTranslucentWorldInstance(visible,
                    Mat4.translation(0.0f, 0.0f, VISIBLE_Z), Scene.UNTAGGED, COVERAGE)
                .addTranslucentWorldInstance(decoy,
                    Mat4.translation(BESIDE_X, 0.0f, VISIBLE_Z), Scene.UNTAGGED,
                    OTHER_COVERAGE)
                .build();
            assertThat(render(padded, 0).color).isEqualTo(render(bare, 0).color);
            assertThat(render(padded, 8).color).isEqualTo(render(bare, 0).color);
        }
    }

    @Nested
    @DisplayName("what the cull actually saves")
    final class Savings
    {
        @Test
        @DisplayName("the invisible instances never reach the rasterizer")
        void triangleCountIsUnchangedByInvisibleInstances()
        {
            final Rendered bare = render(withVisibleQuad().build(), 0);
            final Rendered padded = render(withDecoys(withVisibleQuad()).build(), 0);
            assertThat(bare.triangles).isEqualTo(QUAD_TRIANGLES);
            assertThat(padded.triangles).isEqualTo(bare.triangles);
        }

        @Test
        @DisplayName("a pass with nothing visible in it dispatches nothing")
        void anEntirelyCulledPassCostsNoBarriers()
        {
            // Every instance behind the eye: the opaque pass has no triangles,
            // so it must not reach a single publish/join boundary.
            final Scene nothing = withDecoys(Scene.builder()).build();
            final Rendered frame = render(nothing, 8);
            assertThat(frame.triangles).isZero();
            assertThat(frame.parallelPasses).isZero();
        }

        @Test
        @DisplayName("culled translucent instances collapse the run count, not just the work")
        void cullingCollapsesTranslucentRuns()
        {
            final ModelFormat visible = quad(VISIBLE_COLOUR);
            final ModelFormat decoy = quad(DECOY_COLOUR);
            // Two visible puffs of one coverage with an invisible puff of another
            // coverage between them in depth. Cutting runs at coverage changes
            // would give three runs — twelve dispatches — for two visible
            // instances; culling the middle one merges them into one run.
            final Scene scene = Scene.builder()
                .addTranslucentWorldInstance(visible,
                    Mat4.translation(0.0f, 0.0f, VISIBLE_Z + 2.0f), Scene.UNTAGGED, COVERAGE)
                .addTranslucentWorldInstance(decoy,
                    Mat4.translation(BESIDE_X, 0.0f, VISIBLE_Z + 1.0f), Scene.UNTAGGED,
                    OTHER_COVERAGE)
                .addTranslucentWorldInstance(visible,
                    Mat4.translation(0.0f, 0.0f, VISIBLE_Z), Scene.UNTAGGED, COVERAGE)
                .build();
            // Four for the one surviving translucent run and nothing else: there
            // is no opaque instance and no viewmodel in this scene.
            assertThat(render(scene, 8).parallelPasses).isEqualTo(4);
        }
    }

    // One finished frame and the two counters that explain it.
    private static final class Rendered
    {
        /** The de-padded colour buffer. */
        private final int[] color;

        /** Triangles the frame handed the rasterizer, after clipping. */
        private final int triangles;

        /** Publish/join boundaries the frame paid. */
        private final int parallelPasses;

        Rendered(final int[] frame, final int frameTriangles, final int passes)
        {
            this.color = frame;
            this.triangles = frameTriangles;
            this.parallelPasses = passes;
        }
    }
}
