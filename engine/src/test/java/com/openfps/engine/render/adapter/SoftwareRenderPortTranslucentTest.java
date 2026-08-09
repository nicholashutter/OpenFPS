/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.openfps.engine.hal.adapter.nulladapter.NullTimePort;
import com.openfps.engine.hal.port.I_TimePort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The translucent phase, driven end-to-end through the real render port.
 *
 * <h2>Why the assertions are arithmetic rather than "it looks blended"</h2>
 *
 * <p>Source-over is not commutative, so "both quads contributed" is satisfied
 * by the wrong order as well as the right one. Every expectation here is
 * therefore the exact composite computed from {@link Rgba#srcOver} in the order
 * the phase is supposed to draw in, and {@link Ordering#reversedOrderIsADifferentPicture}
 * asserts that the opposite order produces a different number — without which
 * the rest of the file would pass whatever the sort did.</p>
 *
 * <p>The camera sits on the +z axis looking back at the origin, so a larger
 * world z is <b>nearer</b>. That is the opposite of the depth buffer's own
 * convention ({@code invW > depth}, so a smaller w is nearer) and is worth
 * holding on to while reading: they are two different quantities.</p>
 */
@DisplayName("SoftwareRenderPort translucency")
final class SoftwareRenderPortTranslucentTest
{
    /** Square viewport, so aspect is 1 and world x maps straight to view x. */
    private static final int SIZE = 96;

    /** The pixel every assertion samples: dead centre, covered by every quad. */
    private static final int CENTRE = SIZE / 2;

    /** Vertical field of view. */
    private static final float FOV = 1.0f;

    /** Near plane. */
    private static final float NEAR = 0.05f;

    /** World up. */
    private static final Vec3 UP = new Vec3(0.0f, 1.0f, 0.0f);

    /** What the camera looks at. */
    private static final Vec3 ORIGIN = new Vec3(0.0f, 0.0f, 0.0f);

    /** Where the camera sits on the +z axis. */
    private static final float CAMERA_Z = 4.0f;

    /** Half-edge of every quad; large enough that all of them cover the centre. */
    private static final float HALF = 1.0f;

    /** World z of the nearer quad — nearer because the camera is further out on +z. */
    private static final float NEAR_Z = 1.0f;

    /** World z of the farther quad. */
    private static final float FAR_Z = -1.0f;

    /** Colour of the nearer translucent quad. */
    private static final int NEAR_COLOUR = Rgba.pack(255, 0, 0, 255);

    /** Colour of the farther translucent quad. */
    private static final int FAR_COLOUR = Rgba.pack(0, 0, 255, 255);

    /** Colour of the opaque backdrop some tests put behind both. */
    private static final int BACKDROP_COLOUR = Rgba.pack(0, 255, 0, 255);

    /** World z of that backdrop, behind everything translucent. */
    private static final float BACKDROP_Z = -2.0f;

    /** The coverage both quads share when the test wants a single blended run. */
    private static final int COVERAGE = 128;

    /** A second, different coverage, so the phase has to split into two runs. */
    private static final int OTHER_COVERAGE = 64;

    /** Indices of a quad wound counter-clockwise seen from +z. */
    private static final int[] QUAD_INDICES = {0, 1, 2, 0, 2, 3};

    /** Corners in a quad. */
    private static final int QUAD_CORNERS = 4;

    // A flat, single-coloured quad in the z = 0 plane, built in memory. Using
    // the in-memory factory here rather than a file fixture is deliberate: it
    // puts ModelFormat.ofGeometry on the same end-to-end path the demo's
    // effects use, so a model this test accepts is one the renderer accepts.
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
        return Camera.lookingAt(new Vec3(0.0f, 0.0f, CAMERA_Z), ORIGIN, UP, FOV, 1.0f, NEAR);
    }

    // Renders one scene on the serial path and returns the de-padded frame.
    private static int[] frameOf(final Scene scene)
    {
        final I_TimePort time = new NullTimePort();

        time.init();

        final SoftwareRenderPort port = new SoftwareRenderPort(null, time);

        port.init();

        port.resize(SIZE, SIZE);

        port.setCamera(camera());

        port.setScene(scene);

        port.renderFrame(0);

        final int[] frame = new int[SIZE * SIZE];

        port.copyColorInto(frame);

        port.shutdown();

        return frame;
    }

    private static int centreOf(final Scene scene)
    {
        return frameOf(scene)[CENTRE * SIZE + CENTRE];
    }

    // The far quad over the clear colour, then the near quad over that — which
    // is what "back to front" means, spelled out so the expectation is derived
    // from the rule rather than from a previous run.
    private static int composited(final int farCoverage, final int nearCoverage)
    {
        final int overClear = Rgba.srcOver(FAR_COLOUR,
            SoftwareRenderPort.DEFAULT_CLEAR_COLOR, farCoverage);

        return Rgba.srcOver(NEAR_COLOUR, overClear, nearCoverage);
    }

    @Nested
    @DisplayName("back-to-front ordering")
    final class Ordering
    {
        @Test
        @DisplayName("two translucent instances composite far first, near second")
        void compositesBackToFront()
        {
            final Scene scene = Scene.builder()
                .addTranslucentWorldInstance(quad(NEAR_COLOUR),
                    Mat4.translation(0.0f, 0.0f, NEAR_Z), Scene.UNTAGGED, COVERAGE)
                .addTranslucentWorldInstance(quad(FAR_COLOUR),
                    Mat4.translation(0.0f, 0.0f, FAR_Z), Scene.UNTAGGED, COVERAGE)
                .build();

            assertThat(centreOf(scene)).isEqualTo(composited(COVERAGE, COVERAGE));
        }

        @Test
        @DisplayName("the order they were added in makes no difference to the result")
        void sceneOrderDoesNotMatter()
        {
            // The near one first above, the far one first here. If the phase
            // drew in submission order rather than sorting, exactly one of these
            // two tests would pass — which is the point of having both.
            final Scene scene = Scene.builder()
                .addTranslucentWorldInstance(quad(FAR_COLOUR),
                    Mat4.translation(0.0f, 0.0f, FAR_Z), Scene.UNTAGGED, COVERAGE)
                .addTranslucentWorldInstance(quad(NEAR_COLOUR),
                    Mat4.translation(0.0f, 0.0f, NEAR_Z), Scene.UNTAGGED, COVERAGE)
                .build();

            assertThat(centreOf(scene)).isEqualTo(composited(COVERAGE, COVERAGE));
        }

        @Test
        @DisplayName("reversed order really is a different picture")
        void reversedOrderIsADifferentPicture()
        {
            // Without this the two tests above would both pass if blending were
            // commutative, which would make them assert nothing at all.
            final int frontToBack = Rgba.srcOver(FAR_COLOUR,
                Rgba.srcOver(NEAR_COLOUR, SoftwareRenderPort.DEFAULT_CLEAR_COLOR, COVERAGE),
                COVERAGE);

            assertThat(composited(COVERAGE, COVERAGE)).isNotEqualTo(frontToBack);
        }

        @Test
        @DisplayName("differing coverages split the phase into runs and still sort globally")
        void runsPreserveTheGlobalOrder()
        {
            // Two coverages means two batched passes. Cutting the sorted list at
            // coverage CHANGES keeps each run contiguous in it, so concatenating
            // the runs reproduces the order exactly; grouping by coverage would
            // not, and would fail here whenever the grouping disagreed with the
            // depth order.
            final Scene scene = Scene.builder()
                .addTranslucentWorldInstance(quad(NEAR_COLOUR),
                    Mat4.translation(0.0f, 0.0f, NEAR_Z), Scene.UNTAGGED, OTHER_COVERAGE)
                .addTranslucentWorldInstance(quad(FAR_COLOUR),
                    Mat4.translation(0.0f, 0.0f, FAR_Z), Scene.UNTAGGED, COVERAGE)
                .build();

            assertThat(centreOf(scene)).isEqualTo(composited(COVERAGE, OTHER_COVERAGE));
        }

        @Test
        @DisplayName("moving an instance re-sorts it, without rebuilding the scene")
        void sortFollowsTheOverride()
        {
            // The scene puts the red quad in front. setWorldTransform swaps them
            // over, and the composite has to swap with them: the sort is a
            // per-frame thing, not a bind-time one.
            final ModelFormat red = quad(NEAR_COLOUR);

            final ModelFormat blue = quad(FAR_COLOUR);

            final Scene scene = Scene.builder()
                .addTranslucentWorldInstance(red, Mat4.translation(0.0f, 0.0f, NEAR_Z),
                    Scene.UNTAGGED, COVERAGE)
                .addTranslucentWorldInstance(blue, Mat4.translation(0.0f, 0.0f, FAR_Z),
                    Scene.UNTAGGED, COVERAGE)
                .build();

            final I_TimePort time = new NullTimePort();

            time.init();

            final SoftwareRenderPort port = new SoftwareRenderPort(null, time);

            port.init();

            port.resize(SIZE, SIZE);

            port.setCamera(camera());

            port.setScene(scene);

            port.setWorldTransform(0, Mat4.translation(0.0f, 0.0f, FAR_Z));

            port.setWorldTransform(1, Mat4.translation(0.0f, 0.0f, NEAR_Z));

            port.renderFrame(0);

            final int[] frame = new int[SIZE * SIZE];

            port.copyColorInto(frame);

            port.shutdown();

            final int blueOverClear = Rgba.srcOver(NEAR_COLOUR,
                SoftwareRenderPort.DEFAULT_CLEAR_COLOR, COVERAGE);

            assertThat(frame[CENTRE * SIZE + CENTRE])
                .as("red is now the far one, so it must be composited first")
                .isEqualTo(Rgba.srcOver(FAR_COLOUR, blueOverClear, COVERAGE));
        }
    }

    @Nested
    @DisplayName("the two partitions")
    final class Partitions
    {
        @Test
        @DisplayName("an opaque backdrop is drawn first and shows through the translucent quads")
        void opaqueFirstThenTranslucent()
        {
            final Scene scene = Scene.builder()
                .addTranslucentWorldInstance(quad(NEAR_COLOUR),
                    Mat4.translation(0.0f, 0.0f, NEAR_Z), Scene.UNTAGGED, COVERAGE)
                .addWorldInstance(quad(BACKDROP_COLOUR),
                    Mat4.translation(0.0f, 0.0f, BACKDROP_Z))
                .addTranslucentWorldInstance(quad(FAR_COLOUR),
                    Mat4.translation(0.0f, 0.0f, FAR_Z), Scene.UNTAGGED, COVERAGE)
                .build();

            // The opaque instance is listed BETWEEN the two translucent ones, so
            // this also asserts that lifting them out of the world pass leaves
            // the opaque pass's own order alone.
            final int overBackdrop =
                Rgba.srcOver(FAR_COLOUR, BACKDROP_COLOUR, COVERAGE);

            assertThat(centreOf(scene))
                .isEqualTo(Rgba.srcOver(NEAR_COLOUR, overBackdrop, COVERAGE));
        }

        @Test
        @DisplayName("an opaque instance in front hides translucent geometry behind it")
        void translucentStillTestsDepth()
        {
            // Blended spans test depth even though they do not write it. Without
            // the test a puff of smoke would show through the wall it is behind.
            final Scene scene = Scene.builder()
                .addWorldInstance(quad(BACKDROP_COLOUR),
                    Mat4.translation(0.0f, 0.0f, NEAR_Z))
                .addTranslucentWorldInstance(quad(FAR_COLOUR),
                    Mat4.translation(0.0f, 0.0f, FAR_Z), Scene.UNTAGGED, COVERAGE)
                .build();

            assertThat(centreOf(scene)).isEqualTo(BACKDROP_COLOUR);
        }

        @Test
        @DisplayName("a translucent instance writes no depth, so one behind it still draws")
        void translucentWritesNoDepth()
        {
            // Drawn far-first, so the near quad is composited over the far one.
            // If the far quad had written depth this would still pass; what
            // would NOT pass is the same pair with an opaque backdrop behind
            // both, which the previous test covers. What this one catches is a
            // translucent instance occluding a LATER translucent instance at the
            // same depth.
            final Scene scene = Scene.builder()
                .addTranslucentWorldInstance(quad(FAR_COLOUR),
                    Mat4.translation(0.0f, 0.0f, FAR_Z), Scene.UNTAGGED, COVERAGE)
                .addTranslucentWorldInstance(quad(NEAR_COLOUR),
                    Mat4.translation(0.0f, 0.0f, FAR_Z), Scene.UNTAGGED, COVERAGE)
                .build();

            final int first = Rgba.srcOver(FAR_COLOUR,
                SoftwareRenderPort.DEFAULT_CLEAR_COLOR, COVERAGE);

            assertThat(centreOf(scene))
                .as("equal depths keep scene order, and neither may occlude the other")
                .isEqualTo(Rgba.srcOver(NEAR_COLOUR, first, COVERAGE));
        }

        @Test
        @DisplayName("an entirely opaque scene renders exactly as it did before translucency")
        void opaqueScenesAreUntouched()
        {
            final Scene scene = Scene.builder()
                .addWorldInstance(quad(BACKDROP_COLOUR), Mat4.translation(0.0f, 0.0f, FAR_Z))
                .build();

            assertThat(scene.translucentInstanceCount()).isZero();

            assertThat(centreOf(scene)).isEqualTo(BACKDROP_COLOUR);
        }

        @Test
        @DisplayName("a fully transparent instance leaves the frame alone")
        void zeroCoverageDrawsNothing()
        {
            final Scene scene = Scene.builder()
                .addWorldInstance(quad(BACKDROP_COLOUR), Mat4.translation(0.0f, 0.0f, FAR_Z))
                .addTranslucentWorldInstance(quad(NEAR_COLOUR),
                    Mat4.translation(0.0f, 0.0f, NEAR_Z), Scene.UNTAGGED, 0)
                .build();

            assertThat(centreOf(scene)).isEqualTo(BACKDROP_COLOUR);
        }
    }
}
