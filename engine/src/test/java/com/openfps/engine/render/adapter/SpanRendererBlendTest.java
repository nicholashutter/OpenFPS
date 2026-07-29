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
 * The translucent span path, driven through the real rasterizer.
 *
 * <p>Through {@link Rasterizer} rather than by calling the loop directly,
 * because the setup records it consumes are built inside the rasterizer and
 * never by hand — the same reason the opaque span tests are written this way.
 * That is also what makes these tests meaningful: they exercise binning,
 * coverage and the depth test on the way in.</p>
 */
@DisplayName("SpanRenderer blended spans")
final class SpanRendererBlendTest
{
    private static final int WIDTH = 64;
    private static final int HEIGHT = 64;
    private static final int TILE = 16;

    private static final int RED = Rgba.pack(255, 0, 0, 255);
    private static final int WHITE = Rgba.pack(255, 255, 255, 255);
    private static final int HALF = 128;

    /** A triangle covering the whole viewport, in screen coordinates. */
    private static final float[] FULL_SCREEN = {
        -0.5f, -0.5f,
        WIDTH + 0.5f, -0.5f,
        -0.5f, HEIGHT + 0.5f,
    };

    /** The pixel every assertion samples — comfortably inside the triangle. */
    private static final int SAMPLE_X = 4;
    private static final int SAMPLE_Y = 4;

    /**
     * A near w. The depth test is {@code invW > depth}, and {@code invW} is
     * {@code 1/w}, so a SMALLER w is NEARER. Named rather than written inline
     * because getting it backwards silently inverts every ordering assertion
     * in this file — which it did, first time.
     */
    private static final float NEAR = 1.0f;

    /** A far w. See {@link #NEAR}. */
    private static final float FAR = 2.0f;

    // Draws one flat triangle at a given coverage over whatever is already in
    // the supplied framebuffer, at the given w.
    private static void draw(final Framebuffer target, final int color, final int coverage,
        final float depth)
    {
        final float[] vertices = new float[TriangleClipper.TRIANGLE_VERTICES
            * TriangleClipper.POSITION_FLOATS];
        final float[][] attributes = {RasterFixtures.NO_ATTRIBUTES,
            RasterFixtures.NO_ATTRIBUTES, RasterFixtures.NO_ATTRIBUTES};
        RasterFixtures.triangle(vertices, 0, WIDTH, HEIGHT, FULL_SCREEN, depth, attributes);
        final Rasterizer rasterizer = new Rasterizer(0, 2, 1, Rasterizer.CullMode.NONE);
        rasterizer.beginFrame(target);
        rasterizer.setupAndBin(vertices, 1, null, new int[] {color});
        rasterizer.rasterize(
            new SpanRenderer(SpanRenderer.ShadingMode.FLAT, 0, coverage), null);
    }

    private static int sample(final Framebuffer target)
    {
        return target.colorBuffer()[SAMPLE_Y * target.strideInPixels() + SAMPLE_X];
    }

    @Nested
    @DisplayName("construction")
    final class Construction
    {
        @Test
        @DisplayName("the two-argument constructor is opaque, and unchanged")
        void shouldDefaultToOpaque()
        {
            final SpanRenderer plain = new SpanRenderer(SpanRenderer.ShadingMode.FLAT, 0);
            assertThat(plain.coverage()).isEqualTo(SpanRenderer.OPAQUE_COVERAGE);
            assertThat(plain.isBlended())
                .as("the pre-existing path must not have become a blended one")
                .isFalse();
        }

        @Test
        @DisplayName("a coverage outside 0-255 is rejected")
        void shouldRejectCoverageOutOfRange()
        {
            assertThatThrownBy(
                () -> new SpanRenderer(SpanRenderer.ShadingMode.FLAT, 0, 256))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coverage");
            assertThatThrownBy(
                () -> new SpanRenderer(SpanRenderer.ShadingMode.FLAT, 0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coverage");
        }
    }

    @Nested
    @DisplayName("what reaches the colour buffer")
    final class Colour
    {
        @Test
        @DisplayName("half coverage lands halfway between the source and what was there")
        void shouldBlendOverTheBuffer()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            draw(target, WHITE, SpanRenderer.OPAQUE_COVERAGE, FAR);
            draw(target, RED, HALF, NEAR);

            // Coverage 128 leaves an inverse of 127, not 128 — 255 has no exact
            // half. So white's surviving contribution is 255*127/255 = 127,
            // and red's own channel saturates at 255 because both sources have
            // it at full.
            final int blended = sample(target);
            assertThat(Rgba.red(blended)).isEqualTo(255);
            assertThat(Rgba.green(blended))
                .as("white's green, less red's coverage")
                .isEqualTo(127);
            assertThat(Rgba.blue(blended)).isEqualTo(127);
        }

        @Test
        @DisplayName("full coverage through the blended path equals the opaque path")
        void shouldMatchOpaqueAtFullCoverage()
        {
            final Framebuffer viaOpaque = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            draw(viaOpaque, RED, SpanRenderer.OPAQUE_COVERAGE, 1.0f);

            final Framebuffer viaBlend = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            draw(viaBlend, RED, SpanRenderer.OPAQUE_COVERAGE - 0, 1.0f);

            assertThat(sample(viaBlend)).isEqualTo(sample(viaOpaque));
        }

        @Test
        @DisplayName("zero coverage changes nothing at all")
        void shouldLeaveTheBufferAloneAtZero()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            draw(target, WHITE, SpanRenderer.OPAQUE_COVERAGE, 1.0f);
            final int before = sample(target);
            draw(target, RED, 0, 2.0f);
            assertThat(sample(target)).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("what it must not write")
    final class Suppression
    {
        @Test
        @DisplayName("a translucent span writes no depth, so it occludes nothing")
        void shouldNotWriteDepth()
        {
            // The bug this exists to prevent: smoke punching a hole in the
            // room. Draw translucent geometry in front, then opaque geometry
            // behind it — the opaque draw must still land, which it only can
            // if the translucent one left the depth buffer alone.
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            draw(target, RED, HALF, NEAR);
            draw(target, WHITE, SpanRenderer.OPAQUE_COVERAGE, FAR);

            assertThat(sample(target))
                .as("the opaque draw behind the translucent one must not be rejected")
                .isEqualTo(WHITE);
        }

        @Test
        @DisplayName("a translucent span is still depth-TESTED against opaque geometry")
        void shouldStillBeDepthTested()
        {
            // Suppressing the depth write must not degrade into ignoring depth:
            // smoke behind a wall stays behind it.
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);
            draw(target, WHITE, SpanRenderer.OPAQUE_COVERAGE, NEAR);
            final int before = sample(target);
            draw(target, RED, HALF, FAR);

            assertThat(sample(target))
                .as("a translucent span behind opaque geometry is rejected")
                .isEqualTo(before);
        }
    }
}
