/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.port;

/**
 * R_ Port interface — rendering.
 * Stubbed for Phase 0; implementations wired in Phase 5.
 *
 * <b>The renderer is a multi-threaded software triangle rasterizer</b>:
 * z-buffer, perspective-correct interpolation, mipmapped bilinear
 * sampling, 32-bit colour, glTF models with baked lighting. See
 * {@code docs/ASSETS.md} section 2 for the canonical render target and
 * {@code render/README.md} for the full Phase 5 specification, including
 * every derivation and citation. The port interface itself stays
 * minimal — the implementation does the math.
 *
 * <b>What this replaces.</b> An earlier version of this Javadoc specified
 * a 1993-era 2.5D renderer: BSP traversal for visibility, visplanes, a
 * column renderer, affine texture mapping, an 8-bit palette, a fixed
 * 320x200 framebuffer. That design is retired. Those were VGA-era
 * compromises, not properties of software rendering, and they cannot draw
 * a glTF model. BSP survives as a gameplay and collision structure
 * ({@code PLAN.md} Phase 4); it is no longer the renderer's visibility
 * algorithm, because the z-buffer does that job now.
 *
 * <b>The shape of the pipeline</b> (full detail in the README):
 *
 * <ol>
 *   <li>{@code Camera} transforms world space to clip space.</li>
 *   <li>{@code TriangleClipper} clips against the near plane in
 *       homogeneous clip space using Sutherland-Hodgman. Only the near
 *       plane needs a geometric clip: the four side planes fall out of
 *       the screen-space bounding box, and 1/w depth needs no far plane.
 *       Clipping happens before the perspective divide because that is
 *       what keeps position and UVs both linear in the edge parameter,
 *       so one lerp handles all attributes.
 *       Sources: Sutherland &amp; Hodgman, "Reentrant Polygon Clipping",
 *       CACM 17(1), 1974, https://dl.acm.org/doi/10.1145/360767.360802 ;
 *       Blinn &amp; Newell, "Clipping using homogeneous coordinates",
 *       SIGGRAPH '78, https://dl.acm.org/doi/10.1145/800248.807398</li>
 *   <li>{@code Rasterizer} divides by w, transforms to the viewport,
 *       culls backfaces, sets up incremental edge functions, and bins
 *       each triangle into the screen tiles its bounding box touches.
 *       Source: Pineda, "A Parallel Algorithm for Polygon
 *       Rasterization", SIGGRAPH '88,
 *       https://dl.acm.org/doi/10.1145/378456.378457</li>
 *   <li>{@code SpanRenderer} runs the inner loop. It interpolates u/w,
 *       v/w and 1/w linearly in screen space, then divides per pixel or
 *       per 8-to-16-pixel segment. Note that u and v themselves are NOT
 *       screen-linear; interpolating them directly is affine mapping,
 *       which is the artefact the retired design lived with. Depth is
 *       the interpolated 1/w, so it costs no extra interpolant, and the
 *       test is "greater is nearer" against a buffer cleared to zero.</li>
 *   <li>{@code TextureSampler} does mipmapped bilinear sampling, picking
 *       the level from the UV derivatives per the OpenGL minification
 *       rule.</li>
 * </ol>
 *
 * <b>Threading.</b> The screen is divided into tiles. Each worker owns
 * its tiles outright and is the only writer to those regions of the
 * colour and depth buffers, so the depth test is a plain array
 * read-modify-write with no lock and no atomic. Parallel work goes
 * through the existing {@code WorkerPool} — never {@code new Thread}.
 *
 * <b>Numerics.</b> The renderer uses {@code float}; gameplay and
 * networking stay 16.16 fixed-point ({@code PLAN.md} section 4). This is
 * deliberate, not an oversight. Fixed-point exists for lockstep
 * simulation determinism, and rendering never feeds simulation state, so
 * it cannot desync anything. Since JEP 306 (Java 17) all floating-point
 * arithmetic is always-strict IEEE 754 and the basic operations are
 * bit-reproducible regardless. README section 2 has the full argument,
 * including the {@code Math} versus {@code StrictMath} caveat for
 * transcendentals.
 *
 * <b>Presentation is NOT here.</b> This port produces a finished
 * framebuffer and stops; the platform adapter uploads it, via
 * {@code I_WindowPort} and {@code I_FrameCallback}. Those exist already.
 * Do not add a presentation method to this interface.
 *
 * <b>Citation policy.</b> Per {@code docs/ASSETS.md} section 8 rule 1,
 * cite specifications and papers, never GPL source repositories. The
 * DOOM source links that used to appear here have been removed.
 */
public interface I_RenderPort
{
    /**
     * Renders one frame for the given tic.
     *
     * @param ticIndex the current tic
     */
    void renderFrame(int ticIndex);

    /**
     * Initializes the renderer.
     */
    void init();

    /**
     * Shuts down the renderer.
     */
    void shutdown();
}
