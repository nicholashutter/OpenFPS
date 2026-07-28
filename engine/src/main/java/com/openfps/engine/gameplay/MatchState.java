/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

/**
 * How a {@link Match} is going: still being played, or over one way or the
 * other.
 *
 * <p>Three states rather than a pair of booleans, because "won" and "lost" are
 * mutually exclusive and a boolean pair can represent the combination that
 * is not — a player who is dead <i>and</i> has cleared the room. Making that
 * unrepresentable is cheaper than checking for it.</p>
 */
public enum MatchState
{
    /** Bots are still standing and the player is still alive. */
    IN_PROGRESS,

    /** Every bot is down. */
    WON,

    /** The player is down. */
    LOST;

    /**
     * Returns whether the match has finished.
     *
     * @return true for {@link #WON} and {@link #LOST}
     */
    public boolean isOver()
    {
        return this != IN_PROGRESS;
    }
}
