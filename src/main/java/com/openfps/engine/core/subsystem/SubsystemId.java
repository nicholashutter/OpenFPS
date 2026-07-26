/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem;

/**
 * Identifier for every engine subsystem. Mirrors the D_/P_/R_/S_/G_/W_/Z_/I_
 * naming convention from the original DOOM.
 */
public enum SubsystemId
{
    /** Engine core: game loop, bus, pool, registry. */
    CORE,
    /** P_ Gameplay: player, entities, physics, map logic. */
    P_,
    /** R_ Render: BSP traversal, wall clipping, framebuffer. */
    R_,
    /** S_ Audio: SFX, music, 3D positional sound. */
    S_,
    /** G_ Network: P2P tic distribution, peer discovery, lag comp. */
    G_,
    /** W_ Resource: WAD file loading, lump cache, image decode. */
    W_,
    /** Z_ Memory: zone allocator, slab pool, bulk free. */
    Z_,
    /** I_ Hardware abstraction: time, input, file, net, graphics. */
    I_
}
