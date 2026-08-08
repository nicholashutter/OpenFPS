/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

/**
 * The visual setting a map belongs to — what the world around the geometry
 * looks like.
 *
 * <p>Four settings, in launch order. Each setting reuses the same
 * {@link com.openfps.engine.render.adapter.Scene} (a flat list of world
 * instances, one model-to-world transform each) but pulls its level kit,
 * textures and accent colours from a different Kenney pack. A map's setting is
 * therefore a hint to the renderer about which assets to load, and a hint to
 * the player about what they are looking at: Urban Warzone is concrete and
 * glass, Industrial Complex is steel and pipe, Desert Ravine is sandstone and
 * scrub, Arctic Station is sheet metal and snow.</p>
 *
 * <p>Each setting is a sibling of the others; none is more or less real, and
 * the choice of which one a map belongs to is committed by its
 * {@link MapSpec}. Two maps in the same setting share an art palette and a
 * texture atlas.</p>
 */
public enum MapSetting
{
    /**
     * City blocks — concrete, glass, painted steel, parked vehicles.
     * Uses the {@code Urban Warzone} Kenney kit. The grid is BO6/BO7 three-lane
     * COD style; see {@code docs/maps/urban-warzone/}.
     */
    URBAN_WARZONE,

    /**
     * Heavy industrial — I-beams, pipes, machinery, catwalks. Uses the
     * {@code Industrial Complex} kit. Tall, multi-level, lots of vertical play.
     */
    INDUSTRIAL_COMPLEX,

    /**
     * Canyons and washes — sandstone, scrub, weathered wood. Uses the
     * {@code Desert Ravine} kit. Open sightlines, few corners, sniping
     * favoured.
     */
    DESERT_RAVINE,

    /**
     * A research station in the snow — sheet metal, frosted glass, fuel
     * drums. Uses the {@code Arctic Station} kit. Indoor-outdoor mix, cold
     * colour palette.
     */
    ARCTIC_STATION
}
