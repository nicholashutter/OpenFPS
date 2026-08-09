/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

/**
 * One spawn placement a player or a bot can start a life at.
 *
 * <p>A spawn point is a position and a facing on a given {@link Team}. The
 * facing is a yaw in radians — the direction the body will be turned when
 * the spawn is used. Pitch is left at zero (looking straight ahead); the
 * first frame after the spawn is when the player picks their own look.</p>
 *
 * <p>The {@code id} is unique within the map and is what a network packet
 * references when it says "the player respawned at the second RED spawn".
 * {@code NEUTRAL} spawns are for the local player in modes without teams
 * (TDM against bots, or any single-player match).</p>
 *
 * @param id        a stable identifier unique within the map; must not be
 *                  null or blank
 * @param team      which team the spawn belongs to; must not be null
 * @param x         world x
 * @param y         world y — the floor, not the eye
 * @param z         world z
 * @param yawRadians facing on spawn, in radians
 */
public record SpawnPoint(String id, Team team, float x, float y, float z, float yawRadians)
{
    /**
     * Creates a {@code SpawnPoint} after validating the inputs.
     *
     * @throws IllegalArgumentException if {@code id} is null or blank,
     *     {@code team} is null, or {@code yawRadians} is NaN
     */
    public SpawnPoint
    {
        if (id == null || id.isBlank())
        {
            throw new IllegalArgumentException("id must not be null or blank");
        }

        if (team == null)
        {
            throw new IllegalArgumentException("team must not be null");
        }

        if (Float.isNaN(yawRadians))
        {
            throw new IllegalArgumentException("yawRadians must not be NaN");
        }

        id = id.intern();
    }
}
