/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.port;

/**
 * P_ Port interface — gameplay logic.
 * Called once per tic from the game loop after input sampling.
 *
 * ====================================================================
 *  GAMEPLAY / PHYSICS MATH (Phase 4+ — references below)
 * ====================================================================
 *
 *  The full math is documented in src/main/java/com/openfps/engine/gameplay/README.md.
 *  Summary of formulas you'll need to implement:
 *
 *  1. GRAVITY (per tic):
 *       v.y -= GRAVITY * deltaT      (deltaT is TickEvent.deltaNanos in fixed-point)
 *       y   += v.y * deltaT
 *     All values are 16.16 fixed-point. Use common.FixedMath for math.
 *     Source: gameplay/README.md "Gravity" section.
 *     Reference: LaMothe, "Tricks of the Windows Game Programming Gurus" Ch. 8
 *
 *  2. PLAYER MOVEMENT (per tic):
 *       forwardComponent = (cos(angle) * forwardSpeed, sin(angle) * forwardSpeed)
 *       strafeComponent  = (-sin(angle) * strafeSpeed, cos(angle) * strafeSpeed)
 *       desiredMove      = forwardComponent + strafeComponent
 *       attempted        = pos + desiredMove
 *       if collision on attempted:
 *           tryX = pos + (forwardComponent.x + strafeComponent.x, 0)
 *           if collision on tryX:
 *               tryY = pos + (0, forwardComponent.y + strafeComponent.y)
 *               if collision on tryY:
 *                   pos unchanged
 *               else: pos = tryY
 *           else: pos = tryX
 *       else: pos = attempted
 *     This 2-axis slide is the DOOM feel — explained in
 *     "Player Movement in DOOM-like Games" by Jake McArthur:
 *     http://jake.mcarthur.io/blog/posts/4/player-movement-in-doom-like-games
 *
 *  3. COLLISION DETECTION:
 *     a) BSP leaf lookup — find which subsector the entity is in.
 *        Walk the tree, test each partition line.
 *     b) Within the subsector, clip movement against each linedef
 *        that could be crossed (AABB-segment intersection).
 *     See gameplay/README.md "Collision detection" for line equations.
 *     Source: DOOM source p_map.c, p_maputl.c
 *     https://github.com/id-Software/DOOM/blob/master/linuxdoom-1.10/p_map.c
 *
 *  4. SQUARED DISTANCE (used everywhere, no Math.sqrt):
 *       distSq = dx*dx + dy*dy + dz*dz
 *       if (distSq < radius * radius) → collision
 *     Why: sqrt is expensive, and we only ever compare against a
 *     threshold. (A^2 < B^2) iff (A < B) when A, B ≥ 0.
 *     Reference: any game physics textbook
 *
 *  5. MAP LUMP PARSING (THINGS, LINEDEFS, SECTORS):
 *     See resource/README.md "WAD file format" for byte layout.
 *     All fields are int16 little-endian.
 *
 *  All gameplay math uses common.FixedMath.mul / div for
 *  fixed-point arithmetic. NEVER call Math.sin / Math.cos / Math.sqrt
 *  in a hot path.
 */
public interface I_GameplayPort
{
    /**
     * Processes one game tic — advances player physics,
     * entity updates, and map logic.
     *
     * @param ticIndex the current tic number
     */
    void tick(int ticIndex);

    /**
     * Loads a map by name. Frees all tagged game memory.
     *
     * @param mapName name of the map to load (e.g., "E1M1")
     * @return true if the map was found and loaded
     */
    boolean loadMap(String mapName);

    /**
     * Initializes the gameplay subsystem.
     */
    void init();

    /**
     * Shuts down the gameplay subsystem.
     */
    void shutdown();
}
