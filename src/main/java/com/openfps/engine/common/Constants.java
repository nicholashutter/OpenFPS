/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.common;

/**
 * Global constants for the OpenFPS engine.
 * All values are static final primitives to avoid any boxing.
 *
 * Note: frame rate constants (TIC_RATE, MS_PER_TIC, NANOS_PER_TIC) were
 * removed in Phase 1.3. The engine now runs at a configurable rate
 * via {@code com.openfps.engine.core.FrameRate} (30, 60, or 120 Hz).
 * See core/FrameRate.java for the math.
 */
public final class Constants
{
    private Constants()
    {
        // utility class
    }

    // ---- Networking ----

    /** Maximum number of players in a match. */
    public static final int MAX_PLAYERS = 8;

    /** Default port for P2P game traffic. */
    public static final int DEFAULT_NET_PORT = 5021;

    /** Tic command buffer depth (ring buffer size). */
    public static final int TIC_BUFFER_SIZE = 64;

    /** Maximum peer latency tracked in tics. */
    public static final int MAX_LATENCY_TICS = 5;

    // ---- Memory ----

    /** Default zone allocator heap size in bytes (16 MB). */
    public static final int ZONE_HEAP_SIZE = 16 * 1024 * 1024;

    /** Smallest allocation alignment in bytes. */
    public static final int ZONE_ALIGN = 8;

    // ---- Map ----

    /** Fixed-point scale for map coordinates. */
    public static final int MAP_SCALE = 65536;

    /** Player radius in map units (fixed-point). */
    public static final int PLAYER_RADIUS = 16 * MAP_SCALE;

    /** Maximum open space height in map units (fixed-point). */
    public static final int MAX_OPEN_HEIGHT = 128 * MAP_SCALE;

    // ---- Physics ----

    /** Gravity acceleration per tic (fixed-point). */
    public static final int GRAVITY = 8 * MAP_SCALE / 120 / 120;

    /** Player movement speed (fixed-point units per tic, at 120 Hz). */
    public static final int PLAYER_SPEED = 256 * MAP_SCALE / 120;

    /** Maximum velocity magnitude before clamping. */
    public static final int MAX_VELOCITY = 50 * MAP_SCALE;

    // ---- Entity ----

    /** Maximum number of active entities per map. */
    public static final int MAX_ENTITIES = 4096;

    /** Null entity sentinel. */
    public static final int NULL_ENTITY = -1;

    /** Entity type sentinel for empty slots. */
    public static final int ENTITY_EMPTY = 0;
}
