/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

/**
 * The size of a map's playable area, in world units.
 *
 * <p>Three scalars, in world units, matching the engine's existing
 * {@code +x right, +y up, +z forward} convention. A BO6/BO7 three-lane map is
 * roughly 200-300 units across, and {@code width} and {@code depth} are the
 * two horizontal extents — what a player sees when they look down. The
 * {@code height} is the playable vertical extent and is the same as a
 * {@link com.openfps.engine.common.Constants#MAX_OPEN_HEIGHT} on a single-floor
 * map; multi-floor maps declare a higher value.</p>
 *
 * <p>Immutable, and therefore safe to share between threads.</p>
 *
 * @param width  the playable x extent, in world units; must be positive
 * @param depth  the playable z extent, in world units; must be positive
 * @param height the playable y extent (ceiling - floor), in world units;
 *               must be positive
 */
public record MapDimensions(float width, float depth, float height)
{
    /**
     * Creates a {@code MapDimensions} after validating the inputs.
     *
     * @param width  the playable x extent, in world units
     * @param depth  the playable z extent, in world units
     * @param height the playable y extent, in world units
     * @throws IllegalArgumentException if any extent is not positive or is NaN
     */
    public MapDimensions
    {
        if (!(width > 0.0f))
        {
            throw new IllegalArgumentException("width must be positive, got " + width);
        }
        if (!(depth > 0.0f))
        {
            throw new IllegalArgumentException("depth must be positive, got " + depth);
        }
        if (!(height > 0.0f))
        {
            throw new IllegalArgumentException("height must be positive, got " + height);
        }
    }
}
