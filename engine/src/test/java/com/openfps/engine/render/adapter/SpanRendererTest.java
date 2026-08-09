/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

/**
 * Tests for {@link SpanRenderer} — the fill rule, the depth test, and
 * perspective-correct interpolation.
 *
 * The fill-rule tests are the reason the rule exists, so they assert it
 * directly rather than inferring it from an image: each triangle of a pair
 * sharing an edge is rendered into its own framebuffer, and the two painted
 * sets must be disjoint and must together cover the shape with no gap.
 */
class SpanRendererTest
{
    private static final int WIDTH = 32;
    private static final int HEIGHT = 32;
    private static final int TILE = 16;
    private static final int CLEAR = Framebuffer.CLEAR_COLOR_OPAQUE_BLACK;
    private static final int RED = Rgba.pack(255, 0, 0, 255);
    private static final int MAX_CHANNEL = 255;

    // Renders one flat triangle given in screen coordinates at a uniform w.
    private static Framebuffer renderFlat(final float[] screen)
    {
        final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);

        final float[] vertices = new float[TriangleClipper.TRIANGLE_VERTICES
            * TriangleClipper.POSITION_FLOATS];

        final float[][] attributes = {RasterFixtures.NO_ATTRIBUTES,
            RasterFixtures.NO_ATTRIBUTES, RasterFixtures.NO_ATTRIBUTES};

        RasterFixtures.triangle(vertices, 0, WIDTH, HEIGHT, screen, 1.0f, attributes);

        final Rasterizer rasterizer = new Rasterizer(0, 2, 1, Rasterizer.CullMode.NONE);

        rasterizer.beginFrame(target);

        rasterizer.setupAndBin(vertices, 1, null, new int[] {RED});

        rasterizer.rasterize(new SpanRenderer(SpanRenderer.ShadingMode.FLAT, 0), null);

        return target;
    }

    // The orientation determinant this rasterizer's edge functions are built on.
    private static float orient(final float ax, final float ay, final float bx,
        final float by, final float cx, final float cy)
    {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    /**
     * Perspective-correct interpolation computed the textbook way — barycentric
     * weights from three edge determinants, then Heckbert's u/w over 1/w — with
     * none of {@link Rasterizer}'s precomputed plane equations involved. An
     * error in the plane setup shows up as a disagreement with this.
     */
    private static float oracle(final float[] screen, final float[] w, final float[] q,
        final int px, final int py)
    {
        final float cx = px + 0.5f;

        final float cy = py + 0.5f;

        final float area2 = orient(screen[0], screen[1], screen[2], screen[3],
            screen[4], screen[5]);

        final float l0 = orient(screen[2], screen[3], screen[4], screen[5], cx, cy) / area2;

        final float l1 = orient(screen[4], screen[5], screen[0], screen[1], cx, cy) / area2;

        final float l2 = orient(screen[0], screen[1], screen[2], screen[3], cx, cy) / area2;

        final float invW = l0 / w[0] + l1 / w[1] + l2 / w[2];

        final float overW = l0 * q[0] / w[0] + l1 * q[1] / w[1] + l2 * q[2] / w[2];

        return overW / invW;
    }

    // The screen-linear 1/w the depth buffer is supposed to hold, computed
    // independently of the rasterizer's plane equations.
    private static float oracleInvW(final float[] screen, final float[] w, final int px,
        final int py)
    {
        final float cx = px + 0.5f;

        final float cy = py + 0.5f;

        final float area2 = orient(screen[0], screen[1], screen[2], screen[3],
            screen[4], screen[5]);

        final float l0 = orient(screen[2], screen[3], screen[4], screen[5], cx, cy) / area2;

        final float l1 = orient(screen[4], screen[5], screen[0], screen[1], cx, cy) / area2;

        final float l2 = orient(screen[0], screen[1], screen[2], screen[3], cx, cy) / area2;

        return l0 / w[0] + l1 / w[1] + l2 / w[2];
    }

    // Screen-space linear interpolation of the same quantity — what an affine
    // rasterizer would produce. Used to prove the perspective test has teeth.
    private static float affine(final float[] screen, final float[] q, final int px,
        final int py)
    {
        final float cx = px + 0.5f;

        final float cy = py + 0.5f;

        final float area2 = orient(screen[0], screen[1], screen[2], screen[3],
            screen[4], screen[5]);

        final float l0 = orient(screen[2], screen[3], screen[4], screen[5], cx, cy) / area2;

        final float l1 = orient(screen[4], screen[5], screen[0], screen[1], cx, cy) / area2;

        final float l2 = orient(screen[0], screen[1], screen[2], screen[3], cx, cy) / area2;

        return l0 * q[0] + l1 * q[1] + l2 * q[2];
    }

    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        void shouldRejectANullMode()
        {
            assertThatThrownBy(() -> new SpanRenderer(null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode");
        }

        @Test
        void shouldRejectTooFewAttributesForTexturing()
        {
            assertThatThrownBy(() -> new SpanRenderer(SpanRenderer.ShadingMode.TEXTURED, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attributes");
        }

        @Test
        void shouldRejectTooFewAttributesForVertexColour()
        {
            assertThatThrownBy(() -> new SpanRenderer(SpanRenderer.ShadingMode.VERTEX_COLOR, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attributes");
        }

        @Test
        void shouldAcceptFlatShadingWithNoAttributes()
        {
            final SpanRenderer renderer = new SpanRenderer(SpanRenderer.ShadingMode.FLAT, 0);

            assertThat(renderer.mode()).isEqualTo(SpanRenderer.ShadingMode.FLAT);

            assertThat(renderer.attributeCount()).isZero();

            assertThat(SpanRenderer.ShadingMode.FLAT.requiredAttributes()).isZero();
        }
    }

    @Nested
    @DisplayName("the top-left fill rule")
    class FillRule
    {
        // The two halves of an 8x8 square, split by the diagonal from (0, 0) to
        // (8, 8). Every pixel centre on that diagonal — (0.5, 0.5), (1.5, 1.5)
        // and so on — makes the shared edge function exactly zero, which is
        // precisely the case the rule decides.
        private static final float[] UPPER_RIGHT = {0.0f, 0.0f, 8.0f, 0.0f, 8.0f, 8.0f};
        private static final float[] LOWER_LEFT = {0.0f, 0.0f, 8.0f, 8.0f, 0.0f, 8.0f};

        // Two triangles meeting along the horizontal line y = 4.5, which passes
        // exactly through the centres of row 4.
        private static final float[] POINTING_UP = {0.0f, 4.5f, 8.0f, 4.5f, 4.0f, 0.0f};
        private static final float[] POINTING_DOWN = {8.0f, 4.5f, 0.0f, 4.5f, 4.0f, 8.0f};

        private void assertPaintedExactlyOnce(final float[] first, final float[] second,
            final int expectedUnion)
        {
            final boolean[] a = RasterFixtures.paintedMask(renderFlat(first), CLEAR);

            final boolean[] b = RasterFixtures.paintedMask(renderFlat(second), CLEAR);

            // MUTABLE locals — running tallies over the two coverage masks.
            int both = 0;

            int either = 0;

            for (int i = 0; i < a.length; i++)
            {
                if (a[i] && b[i])
                {
                    both++;
                }

                if (a[i] || b[i])
                {
                    either++;
                }
            }

            assertThat(both).as("pixels painted by both triangles").isZero();

            assertThat(either).as("pixels painted by either triangle").isEqualTo(expectedUnion);
        }

        @Test
        @DisplayName("a diagonal shared edge is painted once, with no gap")
        void shouldPaintADiagonalSharedEdgeExactlyOnce()
        {
            assertPaintedExactlyOnce(UPPER_RIGHT, LOWER_LEFT, 8 * 8);
        }

        @Test
        @DisplayName("a horizontal shared edge is painted once, with no gap")
        void shouldPaintAHorizontalSharedEdgeExactlyOnce()
        {
            final boolean[] up = RasterFixtures.paintedMask(renderFlat(POINTING_UP), CLEAR);

            final boolean[] down = RasterFixtures.paintedMask(renderFlat(POINTING_DOWN), CLEAR);

            // MUTABLE locals — tallies restricted to the shared row.
            int both = 0;

            int either = 0;

            for (int x = 1; x < 7; x++)
            {
                final int index = 4 * WIDTH + x;

                if (up[index] && down[index])
                {
                    both++;
                }

                if (up[index] || down[index])
                {
                    either++;
                }
            }

            assertThat(both).as("row 4 pixels painted twice").isZero();

            assertThat(either).as("row 4 pixels painted at all").isEqualTo(6);
        }

        @Test
        @DisplayName("the diagonal really does land on pixel centres")
        void shouldActuallyExerciseTheZeroEdgeCase()
        {
            // If this ever stops holding, the fill-rule test above degenerates
            // into a plain coverage test and stops proving anything.
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);

            final float[] vertices = new float[TriangleClipper.TRIANGLE_VERTICES
                * TriangleClipper.POSITION_FLOATS];

            final float[][] attributes = {RasterFixtures.NO_ATTRIBUTES,
                RasterFixtures.NO_ATTRIBUTES, RasterFixtures.NO_ATTRIBUTES};

            RasterFixtures.triangle(vertices, 0, WIDTH, HEIGHT, UPPER_RIGHT, 1.0f, attributes);

            final Rasterizer rasterizer = new Rasterizer(0, 2, 1, Rasterizer.CullMode.NONE);

            rasterizer.beginFrame(target);

            rasterizer.setupAndBin(vertices, 1, null, new int[] {RED});

            // Edge 1 of this triangle runs from (8, 8) back to (0, 0), the
            // shared diagonal. Evaluated at pixel (3, 3) it must be exactly zero.
            final float[] records = rasterizer.records();

            final float value = records[Rasterizer.EDGE1_DX] * 3
                + records[Rasterizer.EDGE1_DY] * 3 + records[Rasterizer.EDGE1_CONST];

            assertThat(value).isZero();
        }

        @Test
        @DisplayName("a quad split into two triangles has no seam")
        void shouldLeaveNoSeamAcrossAQuad()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);

            final int stride = TriangleClipper.POSITION_FLOATS;

            final float[] vertices = new float[2 * TriangleClipper.TRIANGLE_VERTICES * stride];

            final float[][] attributes = {RasterFixtures.NO_ATTRIBUTES,
                RasterFixtures.NO_ATTRIBUTES, RasterFixtures.NO_ATTRIBUTES};

            RasterFixtures.triangle(vertices, 0, WIDTH, HEIGHT, UPPER_RIGHT, 1.0f, attributes);

            RasterFixtures.triangle(vertices, 1, WIDTH, HEIGHT, LOWER_LEFT, 1.0f, attributes);

            final Rasterizer rasterizer = new Rasterizer(0, 2, 1, Rasterizer.CullMode.NONE);

            rasterizer.beginFrame(target);

            rasterizer.setupAndBin(vertices, 2, null, new int[] {RED, RED});

            rasterizer.rasterize(new SpanRenderer(SpanRenderer.ShadingMode.FLAT, 0), null);

            for (int y = 0; y < 8; y++)
            {
                for (int x = 0; x < 8; x++)
                {
                    assertThat(target.pixel(x, y)).as("pixel %d,%d", x, y).isEqualTo(RED);
                }
            }

            assertThat(target.pixel(8, 8)).isEqualTo(CLEAR);
        }
    }

    @Nested
    @DisplayName("coverage")
    class Coverage
    {
        @Test
        void shouldPaintNothingWhenTheTileDoesNotMeetTheTriangle()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);

            final float[] vertices = new float[TriangleClipper.TRIANGLE_VERTICES
                * TriangleClipper.POSITION_FLOATS];

            final float[][] attributes = {RasterFixtures.NO_ATTRIBUTES,
                RasterFixtures.NO_ATTRIBUTES, RasterFixtures.NO_ATTRIBUTES};

            RasterFixtures.triangle(vertices, 0, WIDTH, HEIGHT,
                new float[] {0.0f, 0.0f, 8.0f, 0.0f, 0.0f, 8.0f}, 1.0f, attributes);

            final Rasterizer rasterizer = new Rasterizer(0, 2, 1, Rasterizer.CullMode.NONE);

            rasterizer.beginFrame(target);

            rasterizer.setupAndBin(vertices, 1, null, new int[] {RED});

            // Hand the triangle a tile it does not overlap at all.
            new SpanRenderer(SpanRenderer.ShadingMode.FLAT, 0).renderTriangle(target,
                rasterizer.records(), 0, Rasterizer.NO_MATERIAL, RED, null,
                16, 16, 31, 31);

            assertThat(RasterFixtures.paintedMask(target, CLEAR)).containsOnly(false);
        }

        @Test
        void shouldPaintOnlyPixelsWhoseCentreIsInside()
        {
            // The right triangle (4,4)-(7,4)-(4,7) has the hypotenuse x + y = 11.
            // Three pixel centres fall strictly inside it — (4.5, 4.5),
            // (5.5, 4.5) and (4.5, 5.5) — and the centres at exactly 11 are on
            // an edge the fill rule does not award to this triangle.
            final Framebuffer target = renderFlat(new float[] {4.0f, 4.0f, 7.0f, 4.0f,
                4.0f, 7.0f});

            assertThat(target.pixel(4, 4)).isEqualTo(RED);

            assertThat(target.pixel(5, 4)).isEqualTo(RED);

            assertThat(target.pixel(4, 5)).isEqualTo(RED);

            assertThat(target.pixel(6, 4)).as("centre exactly on the hypotenuse")
                .isEqualTo(CLEAR);

            assertThat(target.pixel(5, 5)).as("centre exactly on the hypotenuse")
                .isEqualTo(CLEAR);

            assertThat(target.pixel(3, 4)).isEqualTo(CLEAR);

            assertThat(target.pixel(4, 3)).isEqualTo(CLEAR);
        }
    }

    @Nested
    @DisplayName("perspective-correct interpolation")
    class Perspective
    {
        private static final float[] SCREEN = {2.0f, 2.0f, 30.0f, 4.0f, 6.0f, 30.0f};
        private static final float[] W = {1.0f, 8.0f, 3.0f};
        private static final float[] REDS = {0.0f, 255.0f, 128.0f};

        // A triangle whose three vertices sit at very different depths, so that
        // the perspective divide has a large effect on every interpolant.
        private Framebuffer renderVertexColored()
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);

            final int stride = TriangleClipper.POSITION_FLOATS
                + SpanRenderer.VERTEX_COLOR_ATTRIBUTES;

            final float[] vertices =
                new float[TriangleClipper.TRIANGLE_VERTICES * stride];

            for (int v = 0; v < TriangleClipper.TRIANGLE_VERTICES; v++)
            {
                RasterFixtures.vertex(vertices, v * stride, WIDTH, HEIGHT,
                    SCREEN[v * 2], SCREEN[v * 2 + 1], W[v],
                    new float[] {REDS[v], 0.0f, 0.0f});
            }

            final Rasterizer rasterizer = new Rasterizer(SpanRenderer.VERTEX_COLOR_ATTRIBUTES,
                2, 1, Rasterizer.CullMode.NONE);

            rasterizer.beginFrame(target);

            rasterizer.setupAndBin(vertices, 1, null, null);

            rasterizer.rasterize(new SpanRenderer(SpanRenderer.ShadingMode.VERTEX_COLOR,
                SpanRenderer.VERTEX_COLOR_ATTRIBUTES), null);

            return target;
        }

        @Test
        @DisplayName("interpolated attributes match an independent oracle")
        void shouldMatchTheTextbookInterpolation()
        {
            final Framebuffer target = renderVertexColored();

            // MUTABLE local — how many covered pixels were actually compared.
            int compared = 0;

            for (int y = 3; y < 28; y++)
            {
                for (int x = 3; x < 28; x++)
                {
                    if (target.pixel(x, y) == CLEAR)
                    {
                        continue;
                    }

                    final float expected = oracle(SCREEN, W, REDS, x, y);

                    // The renderer truncates toward zero on the way into the
                    // 8-bit channel, so allow one whole step either side.
                    assertThat((float) Rgba.red(target.pixel(x, y)))
                        .as("red at %d,%d", x, y)
                        .isCloseTo(expected, offset(1.5f));

                    compared++;
                }
            }

            assertThat(compared).isGreaterThan(100);
        }

        @Test
        @DisplayName("the result is not the affine one")
        void shouldDifferFromAffineInterpolation()
        {
            final Framebuffer target = renderVertexColored();

            // MUTABLE local — largest gap between the correct and affine values.
            float worst = 0.0f;

            for (int y = 3; y < 28; y++)
            {
                for (int x = 3; x < 28; x++)
                {
                    if (target.pixel(x, y) == CLEAR)
                    {
                        continue;
                    }

                    final float gap = Math.abs(oracle(SCREEN, W, REDS, x, y)
                        - affine(SCREEN, REDS, x, y));

                    worst = Math.max(worst, gap);
                }
            }

            // If the renderer ever dropped the divide, the assertion above would
            // still have to hold against the oracle — so the two together pin it.
            assertThat(worst).as("perspective vs affine difference").isGreaterThan(20.0f);
        }

        @Test
        @DisplayName("depth is the interpolated 1/w")
        void shouldStoreTheInterpolatedReciprocalAsDepth()
        {
            final Framebuffer target = renderVertexColored();

            for (int y = 5; y < 20; y += 3)
            {
                for (int x = 5; x < 20; x += 3)
                {
                    if (target.pixel(x, y) == CLEAR)
                    {
                        continue;
                    }

                    assertThat(target.depthAt(x, y)).as("depth at %d,%d", x, y)
                        .isCloseTo(oracleInvW(SCREEN, W, x, y), offset(1.0e-5f));
                }
            }
        }
    }

    @Nested
    @DisplayName("texturing")
    class Texturing
    {
        private Framebuffer renderTextured(final MipChain[] textures, final int material)
        {
            final Framebuffer target = RasterFixtures.framebuffer(WIDTH, HEIGHT, TILE);

            final int stride = TriangleClipper.POSITION_FLOATS
                + SpanRenderer.TEXTURED_ATTRIBUTES;

            final float[] vertices = new float[TriangleClipper.TRIANGLE_VERTICES * stride];

            final float[] screen = {2.0f, 2.0f, 28.0f, 2.0f, 2.0f, 28.0f};

            final float[][] uvs = {{0.0f, 0.0f}, {1.0f, 0.0f}, {0.0f, 1.0f}};

            for (int v = 0; v < TriangleClipper.TRIANGLE_VERTICES; v++)
            {
                RasterFixtures.vertex(vertices, v * stride, WIDTH, HEIGHT,
                    screen[v * 2], screen[v * 2 + 1], 2.0f, uvs[v]);
            }

            final Rasterizer rasterizer = new Rasterizer(SpanRenderer.TEXTURED_ATTRIBUTES,
                2, 1, Rasterizer.CullMode.NONE);

            rasterizer.beginFrame(target);

            rasterizer.setupAndBin(vertices, 1, new int[] {material}, new int[] {RED});

            rasterizer.rasterize(new SpanRenderer(SpanRenderer.ShadingMode.TEXTURED,
                SpanRenderer.TEXTURED_ATTRIBUTES), textures);

            return target;
        }

        @Test
        void shouldPaintTexelsFromTheBoundTexture()
        {
            final int green = TextureFixtures.rgba(0, MAX_CHANNEL, 0, MAX_CHANNEL);

            final MipChain[] textures = {TextureFixtures.solid(16, 16, 5, green)};

            final Framebuffer target = renderTextured(textures, 0);

            assertThat(target.pixel(6, 6)).isEqualTo(green);

            assertThat(target.pixel(30, 30)).isEqualTo(CLEAR);
        }

        @Test
        void shouldFallBackToTheFlatColourWhenNoMaterialIsSet()
        {
            final Framebuffer target = renderTextured(null, Rasterizer.NO_MATERIAL);

            assertThat(target.pixel(6, 6)).isEqualTo(RED);
        }

        @Test
        void shouldFallBackToTheFlatColourWhenTheMaterialIsOutOfRange()
        {
            final int green = TextureFixtures.rgba(0, MAX_CHANNEL, 0, MAX_CHANNEL);

            final MipChain[] textures = {TextureFixtures.solid(16, 16, 5, green)};

            final Framebuffer target = renderTextured(textures, 7);

            assertThat(target.pixel(6, 6)).isEqualTo(RED);
        }

        @Test
        @DisplayName("a magnified texture selects level 0")
        void shouldSelectTheTopLevelWhenMagnifying()
        {
            // A 4x4 texture stretched over ~26 screen pixels is heavy
            // magnification, so the finest level must win. Only level 0 carries
            // the marker colour; a coarser level would report black.
            final int[] base = new int[4 * 4];

            Arrays.fill(base, TextureFixtures.rgba(MAX_CHANNEL, 0, 0, MAX_CHANNEL));

            final int[][] levels = {
                base,
                {TextureFixtures.rgba(0, 0, MAX_CHANNEL, MAX_CHANNEL),
                    TextureFixtures.rgba(0, 0, MAX_CHANNEL, MAX_CHANNEL),
                    TextureFixtures.rgba(0, 0, MAX_CHANNEL, MAX_CHANNEL),
                    TextureFixtures.rgba(0, 0, MAX_CHANNEL, MAX_CHANNEL)},
                {TextureFixtures.rgba(0, 0, MAX_CHANNEL, MAX_CHANNEL)}};

            final MipChain[] textures = {TextureFixtures.chain(4, 4, levels)};

            final Framebuffer target = renderTextured(textures, 0);

            assertThat(Rgba.red(target.pixel(6, 6))).isEqualTo(MAX_CHANNEL);

            assertThat(Rgba.blue(target.pixel(6, 6))).isZero();
        }
    }
}
