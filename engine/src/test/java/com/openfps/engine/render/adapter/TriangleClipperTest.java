/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import static com.openfps.engine.render.adapter.RenderTestEpsilon.EPSILON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TriangleClipper}.
 *
 * <p>Every case is stated directly in clip space, because that is the clipper's
 * input and stating it any other way would mean testing {@link Camera} again by
 * accident. The near plane is 2.0 throughout and the geometry is chosen so that
 * every crossing parameter {@code t} is a dyadic fraction — 0, 0.25, 0.5 or 1 —
 * which are exact in binary floating point, so an expected value that is wrong
 * is wrong by an obvious amount rather than by a rounding.</p>
 *
 * <p>Attributes are asserted everywhere positions are. A clipper that
 * interpolates position at the correct {@code t} and attributes at some other
 * one produces geometry that looks right with textures that swim near the near
 * plane, and it passes a position-only suite completely.</p>
 */
@DisplayName("TriangleClipper")
class TriangleClipperTest
{
    private static final float NEAR = 2.0f;
    private static final int UV_ATTRIBUTES = 2;
    private static final int UV_STRIDE = TriangleClipper.POSITION_FLOATS + UV_ATTRIBUTES;

    // One vertex: x_clip, y_clip, w_clip, u, v.
    private static float[] vertex(final float x, final float y, final float w,
        final float u, final float v)
    {
        return new float[] {x, y, w, u, v};
    }

    private static float[] triangle(final float[] a, final float[] b, final float[] c)
    {
        final float[] out = new float[3 * UV_STRIDE];
        System.arraycopy(a, 0, out, 0, UV_STRIDE);
        System.arraycopy(b, 0, out, UV_STRIDE, UV_STRIDE);
        System.arraycopy(c, 0, out, 2 * UV_STRIDE, UV_STRIDE);
        return out;
    }

    // Reads vertex `index` of triangle `tri` out of a clip output buffer.
    private static float[] vertexAt(final float[] buffer, final int triangleIndex,
        final int vertexIndex)
    {
        final int offset = (triangleIndex * 3 + vertexIndex) * UV_STRIDE;
        return Arrays.copyOfRange(buffer, offset, offset + UV_STRIDE);
    }

    private static void assertVertex(final float[] actual, final float[] expected)
    {
        assertThat(actual).containsExactly(expected, within(EPSILON));
    }

    // Twice the signed area of a triangle after the perspective divide. Its
    // sign is the winding, which is what the rasterizer's backface test reads.
    private static float signedAreaNdc(final float[] buffer, final int triangleIndex)
    {
        final float[] a = vertexAt(buffer, triangleIndex, 0);
        final float[] b = vertexAt(buffer, triangleIndex, 1);
        final float[] c = vertexAt(buffer, triangleIndex, 2);
        final float ax = a[0] / a[2];
        final float ay = a[1] / a[2];
        final float bx = b[0] / b[2];
        final float by = b[1] / b[2];
        final float cx = c[0] / c[2];
        final float cy = c[1] / c[2];
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    private static float[] outputBuffer(final TriangleClipper clipper)
    {
        return new float[clipper.maxOutputFloats()];
    }

    @Nested
    @DisplayName("layout")
    class Layout
    {
        @Test
        @DisplayName("derives stride and output size from the attribute count")
        void shouldDeriveStrideAndOutputSizeWhenConstructed()
        {
            final TriangleClipper uv = new TriangleClipper(UV_ATTRIBUTES);

            assertThat(TriangleClipper.POSITION_FLOATS).isEqualTo(3);
            assertThat(uv.attributeCount()).isEqualTo(UV_ATTRIBUTES);
            assertThat(uv.stride()).isEqualTo(UV_STRIDE);
            assertThat(uv.maxOutputFloats()).isEqualTo(2 * 3 * UV_STRIDE);
            assertThat(uv.toString()).contains("stride=5");

            final TriangleClipper positionOnly = new TriangleClipper(0);
            assertThat(positionOnly.stride()).isEqualTo(TriangleClipper.POSITION_FLOATS);
        }

        @Test
        @DisplayName("bounds one plane's output at a quad, so two triangles")
        void shouldBoundOutputAtAQuadWhenClippingOnePlane()
        {
            assertThat(TriangleClipper.MAX_CLIPPED_VERTICES).isEqualTo(4);
            assertThat(TriangleClipper.MAX_OUTPUT_TRIANGLES).isEqualTo(2);
        }

        @Test
        @DisplayName("rejects a negative attribute count")
        void shouldRejectWhenAttributeCountIsNegative()
        {
            assertThatThrownBy(() -> new TriangleClipper(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attributeCount");
        }
    }

    @Nested
    @DisplayName("trivial cases")
    class Trivial
    {
        @Test
        @DisplayName("passes a triangle wholly in front through untouched")
        void shouldPassThroughUnchangedWhenAllVerticesAreInFront()
        {
            final TriangleClipper clipper = new TriangleClipper(UV_ATTRIBUTES);
            final float[] in = triangle(
                vertex(-1.0f, -1.0f, 3.0f, 0.0f, 0.0f),
                vertex(1.0f, -1.0f, 50.0f, 1.0f, 0.0f),
                vertex(0.0f, 1.0f, 4.0f, 0.5f, 1.0f));
            final float[] out = outputBuffer(clipper);

            assertThat(clipper.clipTriangle(NEAR, in, 0, out, 0)).isEqualTo(1);
            assertThat(Arrays.copyOfRange(out, 0, in.length)).containsExactly(in);
        }

        @Test
        @DisplayName("takes the fast path for w only just past the near plane")
        void shouldTakeFastPathWhenWIsJustPastNear()
        {
            final TriangleClipper clipper = new TriangleClipper(UV_ATTRIBUTES);
            final float justInside = Math.nextUp(NEAR);
            final float[] in = triangle(
                vertex(0.0f, 0.0f, justInside, 0.0f, 0.0f),
                vertex(1.0f, 0.0f, justInside, 1.0f, 0.0f),
                vertex(0.0f, 1.0f, justInside, 0.0f, 1.0f));
            final float[] out = outputBuffer(clipper);

            assertThat(clipper.clipTriangle(NEAR, in, 0, out, 0)).isEqualTo(1);
            assertThat(Arrays.copyOfRange(out, 0, in.length)).containsExactly(in);
        }

        @Test
        @DisplayName("rejects a triangle wholly behind the near plane")
        void shouldRejectWhenAllVerticesAreBehindNear()
        {
            final TriangleClipper clipper = new TriangleClipper(UV_ATTRIBUTES);
            final float[] in = triangle(
                vertex(0.0f, 0.0f, 1.0f, 0.0f, 0.0f),
                vertex(1.0f, 0.0f, -5.0f, 1.0f, 0.0f),
                vertex(0.0f, 1.0f, 0.0f, 0.0f, 1.0f));
            final float[] out = outputBuffer(clipper);
            Arrays.fill(out, Float.NaN);

            assertThat(clipper.clipTriangle(NEAR, in, 0, out, 0)).isZero();
            assertThat(out).containsOnly(Float.NaN);
        }

        @Test
        @DisplayName("rejects a triangle lying exactly on the near plane")
        void shouldRejectWhenEveryVertexSitsExactlyOnNear()
        {
            // render/README.md § 6 defines inside as w > near, strictly. A
            // triangle coincident with the plane is edge-on and covers no
            // pixels, so rejecting it is correct as well as conservative.
            final TriangleClipper clipper = new TriangleClipper(UV_ATTRIBUTES);
            final float[] in = triangle(
                vertex(0.0f, 0.0f, NEAR, 0.0f, 0.0f),
                vertex(1.0f, 0.0f, NEAR, 1.0f, 0.0f),
                vertex(0.0f, 1.0f, NEAR, 0.0f, 1.0f));

            assertThat(clipper.clipTriangle(NEAR, in, 0, outputBuffer(clipper), 0)).isZero();
        }

        @Test
        @DisplayName("rejects a degenerate triangle behind the plane and keeps one in front")
        void shouldHandleDegenerateTrianglesWhereverTheySit()
        {
            final TriangleClipper clipper = new TriangleClipper(UV_ATTRIBUTES);
            final float[] point = vertex(1.0f, 2.0f, 10.0f, 0.25f, 0.75f);
            final float[] inFront = triangle(point, point, point);
            final float[] out = outputBuffer(clipper);

            // Zero-area triangles are the rasterizer's problem (area2 == 0), not
            // the clipper's; it must simply not choke on them.
            assertThat(clipper.clipTriangle(NEAR, inFront, 0, out, 0)).isEqualTo(1);
            assertVertex(vertexAt(out, 0, 0), point);

            final float[] behind = vertex(1.0f, 2.0f, -10.0f, 0.25f, 0.75f);
            assertThat(clipper.clipTriangle(NEAR, triangle(behind, behind, behind), 0, out, 0))
                .isZero();
        }
    }

    @Nested
    @DisplayName("one vertex inside")
    class OneInside
    {
        // V0 is in front at w = 4; V1 and V2 sit exactly on the plane through
        // the eye (w = 0). Both crossings are at t = 0.5.
        private final float[] input = triangle(
            vertex(0.0f, 0.0f, 4.0f, 0.0f, 0.0f),
            vertex(8.0f, 0.0f, 0.0f, 1.0f, 0.0f),
            vertex(0.0f, 8.0f, 0.0f, 0.0f, 1.0f));

        @Test
        @DisplayName("clips to a single triangle with interpolated attributes")
        void shouldClipToOneTriangleWhenOnlyOneVertexIsInside()
        {
            final TriangleClipper clipper = new TriangleClipper(UV_ATTRIBUTES);
            final float[] out = outputBuffer(clipper);

            assertThat(clipper.clipTriangle(NEAR, input, 0, out, 0)).isEqualTo(1);

            // Sutherland-Hodgman emits, per edge: the crossing on 0->1, then the
            // crossing on 2->0, then the surviving vertex 0.
            assertVertex(vertexAt(out, 0, 0), vertex(4.0f, 0.0f, NEAR, 0.5f, 0.0f));
            assertVertex(vertexAt(out, 0, 1), vertex(0.0f, 4.0f, NEAR, 0.0f, 0.5f));
            assertVertex(vertexAt(out, 0, 2), vertex(0.0f, 0.0f, 4.0f, 0.0f, 0.0f));
        }

        @Test
        @DisplayName("puts every new vertex exactly on the near plane")
        void shouldPlaceNewVerticesExactlyOnNearWhenClipping()
        {
            final TriangleClipper clipper = new TriangleClipper(UV_ATTRIBUTES);
            final float[] out = outputBuffer(clipper);
            clipper.clipTriangle(NEAR, input, 0, out, 0);

            // Exact equality, not an epsilon: w is assigned, not interpolated,
            // so that the rasterizer's 1/w is bounded by 1/near with no chance
            // of a rounding pushing a vertex a hair behind the plane.
            assertThat(vertexAt(out, 0, 0)[TriangleClipper.OFFSET_W]).isEqualTo(NEAR);
            assertThat(vertexAt(out, 0, 1)[TriangleClipper.OFFSET_W]).isEqualTo(NEAR);
        }

        @Test
        @DisplayName("preserves winding")
        void shouldPreserveWindingWhenClipping()
        {
            final TriangleClipper clipper = new TriangleClipper(UV_ATTRIBUTES);
            final float[] out = outputBuffer(clipper);

            // The same triangle wholly in front, for the reference sign.
            final float[] unclipped = triangle(
                vertex(0.0f, 0.0f, 4.0f, 0.0f, 0.0f),
                vertex(8.0f, 0.0f, 4.0f, 1.0f, 0.0f),
                vertex(0.0f, 8.0f, 4.0f, 0.0f, 1.0f));
            clipper.clipTriangle(NEAR, unclipped, 0, out, 0);
            final float reference = signedAreaNdc(out, 0);
            assertThat(reference).isGreaterThan(0.0f);

            clipper.clipTriangle(NEAR, input, 0, out, 0);
            assertThat(signedAreaNdc(out, 0)).isGreaterThan(0.0f);

            // Reverse the input winding; both must flip together.
            final float[] reversed = triangle(
                Arrays.copyOfRange(input, 0, UV_STRIDE),
                Arrays.copyOfRange(input, 2 * UV_STRIDE, 3 * UV_STRIDE),
                Arrays.copyOfRange(input, UV_STRIDE, 2 * UV_STRIDE));
            clipper.clipTriangle(NEAR, reversed, 0, out, 0);
            assertThat(signedAreaNdc(out, 0)).isLessThan(0.0f);
        }

        @Test
        @DisplayName("interpolates a vertex behind the eye at the right parameter")
        void shouldInterpolateCorrectlyWhenAVertexIsBehindTheEye()
        {
            // w = -4 behind the eye: t = (2 - 4) / (-4 - 4) = 0.25, a quarter of
            // the way along the edge, not the halfway a sign error would give.
            final TriangleClipper clipper = new TriangleClipper(UV_ATTRIBUTES);
            final float[] in = triangle(
                vertex(0.0f, 0.0f, 4.0f, 0.0f, 0.0f),
                vertex(8.0f, 0.0f, -4.0f, 1.0f, 0.0f),
                vertex(0.0f, 8.0f, -4.0f, 0.0f, 1.0f));
            final float[] out = outputBuffer(clipper);

            assertThat(clipper.clipTriangle(NEAR, in, 0, out, 0)).isEqualTo(1);
            assertVertex(vertexAt(out, 0, 0), vertex(2.0f, 0.0f, NEAR, 0.25f, 0.0f));
            assertVertex(vertexAt(out, 0, 1), vertex(0.0f, 2.0f, NEAR, 0.0f, 0.25f));
            assertVertex(vertexAt(out, 0, 2), vertex(0.0f, 0.0f, 4.0f, 0.0f, 0.0f));
        }
    }

    @Nested
    @DisplayName("two vertices inside — the quad case")
    class TwoInside
    {
        // V0 and V1 in front at w = 4, V2 on the eye plane at w = 0. Both
        // crossings are at t = 0.5, so the clipped polygon is a quad.
        private final float[] input = triangle(
            vertex(0.0f, 0.0f, 4.0f, 0.0f, 0.0f),
            vertex(8.0f, 0.0f, 4.0f, 1.0f, 0.0f),
            vertex(0.0f, 8.0f, 0.0f, 0.0f, 1.0f));

        @Test
        @DisplayName("fan-triangulates the quad into two triangles sharing an edge")
        void shouldFanTriangulateWhenTwoVerticesAreInside()
        {
            final TriangleClipper clipper = new TriangleClipper(UV_ATTRIBUTES);
            final float[] out = outputBuffer(clipper);

            assertThat(clipper.clipTriangle(NEAR, input, 0, out, 0)).isEqualTo(2);

            final float[] p0 = vertex(8.0f, 0.0f, 4.0f, 1.0f, 0.0f);
            final float[] p1 = vertex(4.0f, 4.0f, NEAR, 0.5f, 0.5f);
            final float[] p2 = vertex(0.0f, 4.0f, NEAR, 0.0f, 0.5f);
            final float[] p3 = vertex(0.0f, 0.0f, 4.0f, 0.0f, 0.0f);

            // Fan from polygon vertex 0: (0, 1, 2) then (0, 2, 3).
            assertVertex(vertexAt(out, 0, 0), p0);
            assertVertex(vertexAt(out, 0, 1), p1);
            assertVertex(vertexAt(out, 0, 2), p2);
            assertVertex(vertexAt(out, 1, 0), p0);
            assertVertex(vertexAt(out, 1, 1), p2);
            assertVertex(vertexAt(out, 1, 2), p3);
        }

        @Test
        @DisplayName("gives both fan triangles the same winding")
        void shouldGiveBothFanTrianglesTheSameWindingWhenQuadIsSplit()
        {
            final TriangleClipper clipper = new TriangleClipper(UV_ATTRIBUTES);
            final float[] out = outputBuffer(clipper);
            clipper.clipTriangle(NEAR, input, 0, out, 0);

            final float first = signedAreaNdc(out, 0);
            final float second = signedAreaNdc(out, 1);

            assertThat(first).isNotZero();
            assertThat(second).isNotZero();
            assertThat(Math.signum(first)).isEqualTo(Math.signum(second));
        }

        @Test
        @DisplayName("fills exactly the buffer maxOutputFloats promises")
        void shouldFillExactlyTheAdvertisedBufferWhenQuadIsSplit()
        {
            final TriangleClipper clipper = new TriangleClipper(UV_ATTRIBUTES);
            final float[] out = outputBuffer(clipper);
            Arrays.fill(out, Float.NaN);

            assertThat(clipper.clipTriangle(NEAR, input, 0, out, 0)).isEqualTo(2);
            assertThat(out).doesNotContain(Float.NaN);
            assertThat(out).hasSize(2 * 3 * UV_STRIDE);
        }
    }

    @Nested
    @DisplayName("boundary cases")
    class Boundary
    {
        @Test
        @DisplayName("keeps the geometry when one vertex lies exactly on the plane")
        void shouldKeepGeometryWhenOneVertexLiesExactlyOnNear()
        {
            // V1 sits exactly on the plane, so it is outside by the strict rule.
            // Both of its edges then cross at t = 1 and t = 0, placing the
            // intersections exactly on V1 — the polygon is the original triangle
            // with V1 duplicated. The fan emits the original triangle plus one
            // zero-area triangle, which the rasterizer discards.
            final TriangleClipper clipper = new TriangleClipper(UV_ATTRIBUTES);
            final float[] v0 = vertex(0.0f, 0.0f, 4.0f, 0.0f, 0.0f);
            final float[] v1 = vertex(8.0f, 0.0f, NEAR, 1.0f, 0.0f);
            final float[] v2 = vertex(0.0f, 8.0f, 4.0f, 0.0f, 1.0f);
            final float[] out = outputBuffer(clipper);

            assertThat(clipper.clipTriangle(NEAR, triangle(v0, v1, v2), 0, out, 0)).isEqualTo(2);

            // Triangle 0 is the degenerate one: two coincident vertices.
            assertVertex(vertexAt(out, 0, 0), v1);
            assertVertex(vertexAt(out, 0, 1), v1);
            assertVertex(vertexAt(out, 0, 2), v2);
            assertThat(signedAreaNdc(out, 0)).isZero();

            // Triangle 1 is the input, cyclically rotated — same winding, same
            // attributes, nothing lost.
            assertVertex(vertexAt(out, 1, 0), v1);
            assertVertex(vertexAt(out, 1, 1), v2);
            assertVertex(vertexAt(out, 1, 2), v0);
            assertThat(signedAreaNdc(out, 1)).isNotZero();
        }

        @Test
        @DisplayName("lands the crossing on the endpoint when w is exactly near")
        void shouldLandCrossingOnEndpointWhenWEqualsNearExactly()
        {
            // Only V2 is outside, and it is exactly on the plane, so the
            // crossing parameters are t = 1 on 1->2 and t = 0 on 2->0. Both
            // intersections must reproduce V2 exactly, attributes included.
            final TriangleClipper clipper = new TriangleClipper(UV_ATTRIBUTES);
            final float[] v0 = vertex(-1.0f, -1.0f, 6.0f, 0.0f, 0.0f);
            final float[] v1 = vertex(5.0f, -1.0f, 6.0f, 1.0f, 0.0f);
            final float[] v2 = vertex(0.0f, 3.0f, NEAR, 0.3f, 0.9f);
            final float[] out = outputBuffer(clipper);

            assertThat(clipper.clipTriangle(NEAR, triangle(v0, v1, v2), 0, out, 0)).isEqualTo(2);
            assertVertex(vertexAt(out, 0, 1), v2);
            assertVertex(vertexAt(out, 0, 2), v2);
        }

        @Test
        @DisplayName("survives a vertex whose w is exactly zero")
        void shouldSurviveWhenAVertexHasZeroW()
        {
            // w == 0 is the case that makes the divide produce an infinity. It
            // must never reach the rasterizer, so every emitted w is >= near.
            final TriangleClipper clipper = new TriangleClipper(UV_ATTRIBUTES);
            final float[] in = triangle(
                vertex(1.0f, 1.0f, 9.0f, 0.0f, 0.0f),
                vertex(2.0f, 0.0f, 0.0f, 1.0f, 0.0f),
                vertex(0.0f, 2.0f, 7.0f, 0.0f, 1.0f));
            final float[] out = outputBuffer(clipper);

            final int count = clipper.clipTriangle(NEAR, in, 0, out, 0);
            assertThat(count).isEqualTo(2);
            for (int i = 0; i < count * 3; i++)
            {
                final float w = out[i * UV_STRIDE + TriangleClipper.OFFSET_W];
                assertThat(w).isGreaterThanOrEqualTo(NEAR);
                assertThat(w).isFinite();
            }
        }
    }

    @Nested
    @DisplayName("attributes")
    class Attributes
    {
        @Test
        @DisplayName("interpolates every attribute at the same parameter as position")
        void shouldInterpolateAllAttributesWhenClippingWithColour()
        {
            // Five attributes: u, v and a baked r, g, b. All are interpolated by
            // the same loop, so a wide vertex is the case that would catch a
            // hard-coded UV-only lerp.
            final int wideAttributes = 5;
            final int wideStride = TriangleClipper.POSITION_FLOATS + wideAttributes;
            final TriangleClipper clipper = new TriangleClipper(wideAttributes);
            final float[] in = new float[3 * wideStride];
            // V0 in front at w = 4, attributes all 0.
            in[0] = 0.0f;
            in[1] = 0.0f;
            in[2] = 4.0f;
            // V1 behind the eye at w = -4, attributes all 1: t = 0.25.
            in[wideStride] = 8.0f;
            in[wideStride + 1] = 0.0f;
            in[wideStride + 2] = -4.0f;
            Arrays.fill(in, wideStride + 3, wideStride + wideStride, 1.0f);
            // V2 in front at w = 4, attributes all 2.
            in[2 * wideStride] = 0.0f;
            in[2 * wideStride + 1] = 8.0f;
            in[2 * wideStride + 2] = 4.0f;
            Arrays.fill(in, 2 * wideStride + 3, 3 * wideStride, 2.0f);

            final float[] out = new float[clipper.maxOutputFloats()];
            assertThat(clipper.clipTriangle(NEAR, in, 0, out, 0)).isEqualTo(2);

            // Crossing on edge 0->1 at t = 0.25: every attribute is 0.25.
            for (int a = 0; a < wideAttributes; a++)
            {
                assertThat(out[3 + a]).isCloseTo(0.25f, within(EPSILON));
            }
            assertThat(out[0]).isCloseTo(2.0f, within(EPSILON));
            assertThat(out[2]).isEqualTo(NEAR);

            // Crossing on edge 1->2 at t = (2 - -4) / (4 - -4) = 0.75: every
            // attribute is 1 + 0.75 * (2 - 1) = 1.75.
            final int second = wideStride;
            for (int a = 0; a < wideAttributes; a++)
            {
                assertThat(out[second + 3 + a]).isCloseTo(1.75f, within(EPSILON));
            }
        }

        @Test
        @DisplayName("clips position-only geometry with no attributes at all")
        void shouldClipWhenThereAreNoAttributes()
        {
            final TriangleClipper clipper = new TriangleClipper(0);
            final int stride = TriangleClipper.POSITION_FLOATS;
            final float[] in = {
                0.0f, 0.0f, 4.0f,
                8.0f, 0.0f, 0.0f,
                0.0f, 8.0f, 0.0f,
            };
            final float[] out = new float[clipper.maxOutputFloats()];

            assertThat(clipper.clipTriangle(NEAR, in, 0, out, 0)).isEqualTo(1);
            assertThat(Arrays.copyOfRange(out, 0, 3 * stride)).containsExactly(new float[] {
                4.0f, 0.0f, NEAR,
                0.0f, 4.0f, NEAR,
                0.0f, 0.0f, 4.0f,
            }, within(EPSILON));
        }
    }

    @Nested
    @DisplayName("buffer discipline")
    class BufferDiscipline
    {
        @Test
        @DisplayName("reads and writes only at the offsets it is given")
        void shouldRespectOffsetsWhenBuffersAreShared()
        {
            final TriangleClipper clipper = new TriangleClipper(UV_ATTRIBUTES);
            final float[] source = triangle(
                vertex(0.0f, 0.0f, 4.0f, 0.0f, 0.0f),
                vertex(8.0f, 0.0f, 0.0f, 1.0f, 0.0f),
                vertex(0.0f, 8.0f, 0.0f, 0.0f, 1.0f));

            // Same triangle, parked in the middle of a larger buffer full of
            // poison, as a real vertex stream would be.
            final int inOffset = 7;
            final float[] in = new float[inOffset + source.length + 4];
            Arrays.fill(in, Float.NaN);
            System.arraycopy(source, 0, in, inOffset, source.length);

            final int outOffset = 3;
            final float[] out = new float[outOffset + clipper.maxOutputFloats() + 5];
            Arrays.fill(out, Float.NaN);

            assertThat(clipper.clipTriangle(NEAR, in, inOffset, out, outOffset)).isEqualTo(1);

            for (int i = 0; i < outOffset; i++)
            {
                assertThat(out[i]).isNaN();
            }
            for (int i = outOffset + 3 * UV_STRIDE; i < out.length; i++)
            {
                assertThat(out[i]).isNaN();
            }
            assertThat(Arrays.copyOfRange(out, outOffset, outOffset + 3 * UV_STRIDE))
                .containsExactly(new float[] {
                    4.0f, 0.0f, NEAR, 0.5f, 0.0f,
                    0.0f, 4.0f, NEAR, 0.0f, 0.5f,
                    0.0f, 0.0f, 4.0f, 0.0f, 0.0f,
                }, within(EPSILON));
        }

        @Test
        @DisplayName("gives identical results across repeated calls on one instance")
        void shouldGiveIdenticalResultsWhenScratchIsReused()
        {
            // The scratch polygon is instance state reused on every call — the
            // mechanism that keeps the per-triangle path allocation-free. If it
            // were ever read before being written, alternating shapes would leak
            // one into the next.
            final TriangleClipper clipper = new TriangleClipper(UV_ATTRIBUTES);
            final float[] quadCase = triangle(
                vertex(0.0f, 0.0f, 4.0f, 0.0f, 0.0f),
                vertex(8.0f, 0.0f, 4.0f, 1.0f, 0.0f),
                vertex(0.0f, 8.0f, 0.0f, 0.0f, 1.0f));
            final float[] triangleCase = triangle(
                vertex(0.0f, 0.0f, 4.0f, 0.0f, 0.0f),
                vertex(8.0f, 0.0f, 0.0f, 1.0f, 0.0f),
                vertex(0.0f, 8.0f, 0.0f, 0.0f, 1.0f));

            final float[] firstQuad = outputBuffer(clipper);
            clipper.clipTriangle(NEAR, quadCase, 0, firstQuad, 0);

            final float[] scratchpad = outputBuffer(clipper);
            for (int i = 0; i < 4; i++)
            {
                assertThat(clipper.clipTriangle(NEAR, triangleCase, 0, scratchpad, 0))
                    .isEqualTo(1);
                assertThat(clipper.clipTriangle(NEAR, quadCase, 0, scratchpad, 0)).isEqualTo(2);
                assertThat(scratchpad).containsExactly(firstQuad);
            }
        }
    }

    @Nested
    @DisplayName("with a Camera in front of it")
    class WithCamera
    {
        @Test
        @DisplayName("clips a world-space triangle that straddles the camera")
        void shouldClipWorldTriangleWhenItStraddlesTheCamera()
        {
            // The end-to-end seam: Camera produces clip space, the clipper makes
            // it safe to divide. The triangle runs from well in front of the
            // camera to well behind it.
            final float near = 0.5f;
            final Camera camera = Camera.create(
                new Vec3(0.0f, 0.0f, -10.0f), new Vec3(0.0f, 0.0f, 1.0f),
                new Vec3(0.0f, 1.0f, 0.0f), (float) (Math.PI / 2.0), 16.0f / 9.0f, near);
            final TriangleClipper clipper = new TriangleClipper(UV_ATTRIBUTES);

            final float[] in = new float[3 * UV_STRIDE];
            camera.transformToClip(-4.0f, -2.0f, 5.0f, in, 0);
            in[3] = 0.0f;
            in[4] = 0.0f;
            camera.transformToClip(4.0f, -2.0f, -20.0f, in, UV_STRIDE);
            in[UV_STRIDE + 3] = 1.0f;
            in[UV_STRIDE + 4] = 0.0f;
            camera.transformToClip(0.0f, 6.0f, -30.0f, in, 2 * UV_STRIDE);
            in[2 * UV_STRIDE + 3] = 0.5f;
            in[2 * UV_STRIDE + 4] = 1.0f;

            // One vertex 15 units in front, two behind the camera entirely.
            assertThat(in[TriangleClipper.OFFSET_W]).isCloseTo(15.0f, within(EPSILON));
            assertThat(in[UV_STRIDE + TriangleClipper.OFFSET_W]).isNegative();
            assertThat(in[2 * UV_STRIDE + TriangleClipper.OFFSET_W]).isNegative();

            final float[] out = outputBuffer(clipper);
            final int count = clipper.clipTriangle(near, in, 0, out, 0);
            assertThat(count).isEqualTo(1);

            for (int i = 0; i < count * 3; i++)
            {
                final float w = out[i * UV_STRIDE + TriangleClipper.OFFSET_W];
                assertThat(w).isGreaterThanOrEqualTo(near);
                // Every surviving vertex is safe to divide by.
                assertThat(1.0f / w).isFinite();
            }

            // UVs stay inside the source triangle's range, so nothing has been
            // extrapolated past an edge.
            for (int i = 0; i < count * 3; i++)
            {
                assertThat(out[i * UV_STRIDE + 3]).isBetween(0.0f, 1.0f);
                assertThat(out[i * UV_STRIDE + 4]).isBetween(0.0f, 1.0f);
            }
        }
    }
}
