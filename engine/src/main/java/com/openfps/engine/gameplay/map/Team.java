/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.map;

/**
 * Which side a {@link SpawnPoint} belongs to.
 *
 * <p>Three values rather than the two most FPS games carry, because
 * {@link #NEUTRAL} is a real state: a Domination flag with no controlling
 * team, a Hardpoint zone not currently held, a CTF base while the flag is
 * away. A boolean "is red" pair would have to invent the third state in every
 * caller, and "neutral" with the meaning "no team currently owns this" is what
 * all four modes want.</p>
 */
public enum Team
{
    /** Red team. Conventional FPS team one. */
    RED,

    /** Blue team. Conventional FPS team two. */
    BLUE,

    /**
     * Not owned by any team, or owned by neither. Used for Domination flags at
     * match start, for Hardpoint zones between captures, and for spawn points
     * that belong to the local player rather than to a team.
     */
    NEUTRAL
}
