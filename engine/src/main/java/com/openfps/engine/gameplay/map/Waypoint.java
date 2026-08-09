/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

/**
 * One node on a closed-form bot patrol route.
 *
 * <p>Like {@link com.openfps.engine.gameplay.BotPattern}, a {@code Waypoint}
 * is a position a bot visits, but here the position is the actual world
 * coordinates (a {@code Bot} holds a home position in the same units) rather
 * than a relative offset. A bot route is then the cycle of waypoints in
 * {@code id} order — closed form, so a position at tic <i>n</i> is a pure
 * function of <i>n</i> and a late-joining peer computes the same answer
 * without replaying history.</p>
 *
 * @param id a stable identifier unique within the map; must not be null
 *           or blank
 * @param x  world x
 * @param y  world y — the floor
 * @param z  world z
 */
public record Waypoint(String id, float x, float y, float z)
{
    /**
     * Creates a {@code Waypoint} after validating the inputs.
     *
     * @throws IllegalArgumentException if {@code id} is null or blank
     */
    public Waypoint
    {
        if (id == null || id.isBlank())
        {
            throw new IllegalArgumentException("id must not be null or blank");
        }

        id = id.intern();
    }
}
