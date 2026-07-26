/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.port;

/**
 * R_ Port interface — rendering.
 * Stubbed for Phase 0; implementations wired in Phase 5.
 *
 * ====================================================================
 *  RENDER MATH (Phase 5+ — references below)
 * ====================================================================
 *
 *  All the math needed for the renderer is documented in the subsystem
 *  README: src/main/java/com/openfps/engine/render/README.md. The port
 *  interface itself stays minimal — implementation does the math.
 *
 *  Key formulas you'll need to implement (full derivations + sources in
 *  the README):
 *
 *  1. BSP traversal (back-to-front for painter's algorithm):
 *       renderBsp(node, box):
 *           isFront = pointOnFront(player, node)
 *           near, far = pickChildren(node, isFront)
 *           renderBsp(far,  box)
 *           renderBsp(near, intersectBox(box, node.line))
 *     Source: DOOM source r_bsp.c
 *     https://github.com/id-Software/DOOM/blob/master/linuxdoom-1.10/r_bsp.c
 *
 *  2. Sutherland-Hodgman polygon clipping (4 half-planes for frustum):
 *     For each edge (a, b) in polygon, against each plane:
 *       a_in  = a inside plane
 *       b_in  = b inside plane
 *       if  a_in &&  b_in: out += b
 *       if  a_in && !b_in: out += intersect(a, b, plane)
 *       if !a_in &&  b_in: out += intersect(a, b, plane); out += b
 *     Source: Sutherland & Hodgman, "Reentrant Polygon Clipping", CACM 1974
 *     https://dl.acm.org/doi/10.1145/360767.360802
 *
 *  3. Per-column wall height (perspective projection, no matrices):
 *     screenHeight = (WALL_HEIGHT * FOCAL_LENGTH) / distanceToWall
 *     (all values 16.16 fixed-point — see common/FixedMath.java)
 *     Source: Michael Abrash, GPBB Ch. 63
 *     http://www.drdobbs.com/parallel/graphics-programming-black-book/184404919
 *
 *  4. Affine texture mapping (no per-pixel perspective divide):
 *     u = (x - lineStart.x) * textureWidth / lineLength
 *     For each pixel in column, v interpolated linearly per row.
 *     Source: Chris Hecker, "Perspective Texture Mapping" series
 *     http://www.chrishecker.com/Miscellaneous_Technical_Articles
 *
 *  5. Visplane management:
 *     Track up to 128 screen-space horizontal bands. New band = new
 *     visplane. 128 reached = force flush. Implementation detail in
 *     R_VisplaneBuilder (Phase 5).
 *     Source: DOOM source r_plane.c
 *     https://github.com/id-Software/DOOM/blob/master/linuxdoom-1.10/r_plane.c
 *
 *  See render/README.md for worked examples and full source citations.
 */
public interface I_RenderPort
{
    /**
     * Renders one frame for the given tic.
     *
     * @param ticIndex the current tic
     */
    void renderFrame(final int ticIndex);

    /**
     * Initializes the renderer.
     */
    void init();

    /**
     * Shuts down the renderer.
     */
    void shutdown();
}
