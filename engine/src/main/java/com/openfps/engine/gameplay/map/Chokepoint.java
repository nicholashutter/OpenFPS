/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

/**
 * A named bottleneck on a {@link Lane}.
 *
 * <p>Chokepoints are what make a lane a lane rather than an open corridor:
 * a doorway, a bridge, a narrow alley, a staircase landing. A bot path
 * between two chokepoints is the lane's "go from here to there" line, and
 * a player's callout — "they're at the Bridge" — refers to a chokepoint
 * by its display name.</p>
 *
 * <p>The position is in world coordinates; the {@code callout} is the
 * human-readable name a player uses ("Bridge", "Market", "Plaza").</p>
 *
 * @param id       a stable identifier unique within the map; must not be null
 *                 or blank
 * @param callout  the human-readable name; must not be null or blank
 * @param x        world x
 * @param z        world z
 */
public record Chokepoint(String id, String callout, float x, float z)
{
    /**
     * Creates a {@code Chokepoint} after validating the inputs.
     *
     * @throws IllegalArgumentException if {@code id} or {@code callout} is null
     *     or blank
     */
    public Chokepoint
    {
        if (id == null || id.isBlank())
        {
            throw new IllegalArgumentException("id must not be null or blank");
        }
        if (callout == null || callout.isBlank())
        {
            throw new IllegalArgumentException("callout must not be null or blank");
        }
        id = id.intern();
        callout = callout.intern();
    }
}
