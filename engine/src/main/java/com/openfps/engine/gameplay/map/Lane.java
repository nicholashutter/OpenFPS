/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

import java.util.List;

/**
 * One of the three parallel routes a COD-style map is built around: A, B, or C.
 *
 * <p>The three-lane pattern is a deliberate design choice: two flanking lanes
 * (A, C) are the "safe" routes with cover and predictable fights, and the
 * middle lane (B) is the risk/reward route that gets you across the map
 * quickly but offers little cover. A {@code Lane} declares its {@code id}, the
 * {@link LaneAxis} it runs along, and an ordered list of {@link Chokepoint}s
 * the lane passes through. The chokepoints are listed in travel order from
 * the lane's start end to its finish end.</p>
 *
 * <p>Lane A is conventionally the north lane, lane B the middle, and lane C
 * the south — the convention is recorded here because a minimap and a HUD
 * that want to label the lanes will use the id to look it up, and the
 * convention is what makes "A" mean the same thing on every map.</p>
 *
 * @param id           a stable identifier unique within the map; must not be
 *                     null or blank. Conventionally {@code "lane_a"},
 *                     {@code "lane_b"}, or {@code "lane_c"}
 * @param axis         the axis the lane runs along; must not be null
 * @param chokepoints  the lane's named chokepoints in travel order; must not
 *                     be null and must contain at least two entries (a lane
 *                     with a single chokepoint is a corridor, not a lane)
 */
public record Lane(String id, LaneAxis axis, List<Chokepoint> chokepoints)
{
    /**
     * Creates a {@code Lane} after validating the inputs.
     *
     * @throws IllegalArgumentException if any rule above is broken
     */
    public Lane
    {
        if (id == null || id.isBlank())
        {
            throw new IllegalArgumentException("id must not be null or blank");
        }

        if (axis == null)
        {
            throw new IllegalArgumentException("axis must not be null");
        }

        if (chokepoints == null)
        {
            throw new IllegalArgumentException("chokepoints must not be null");
        }

        if (chokepoints.size() < 2)
        {
            throw new IllegalArgumentException(
                "a lane must have at least two chokepoints, got " + chokepoints.size());
        }

        id = id.intern();

        // Defensive copy so a caller cannot mutate the list after the fact.
        chokepoints = List.copyOf(chokepoints);
    }
}
