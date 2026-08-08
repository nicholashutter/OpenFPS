/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

/**
 * Which way a {@link Lane} runs across the map.
 *
 * <p>The two-axis choice is enough for a three-lane COD map: lane A and lane C
 * run along the same axis (parallel), and lane B runs between them. The axis
 * is recorded so a HUD or a minimap can render the lanes without re-deriving
 * the geometry, and so a bot path can pick "the lane that runs north-south" in
 * a way the map can answer.</p>
 */
public enum LaneAxis
{
    /** Runs from north to south; the lane's chokepoints are arrayed along the z axis. */
    NORTH_SOUTH,

    /** Runs from east to west; the lane's chokepoints are arrayed along the x axis. */
    EAST_WEST
}
