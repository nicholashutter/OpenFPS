/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.log;

/**
 * The severity of a {@link LogEvent}.
 *
 * <p>Mirrors SLF4J's level set, minus the {@code OFF} sentinel (the
 * engine does not need it) and the {@code ALL} sentinel (the same).
 * The numeric ranks are stable for serialisation; do not renumber.</p>
 */
public enum LogLevel
{
    /** Verbose per-step diagnostics, off by default in shipped builds. */
    TRACE(0),

    /** Per-tic diagnostics the player can read to see what the engine is doing. */
    DEBUG(1),

    /** State changes a player or operator cares about. */
    INFO(2),

    /** Recoverable problems the engine handled on its own. */
    WARN(3),

    /** Unrecoverable problems a player or operator must act on. */
    ERROR(4);

    /** Numeric rank, used to filter and to compare. Never changes. */
    private final int rank;

    LogLevel(final int rank)
    {
        this.rank = rank;
    }

    /** Returns the numeric rank. Lower is finer-grained. */
    public int rank()
    {
        return rank;
    }

    /**
     * Returns true if this level is at least as severe as the floor.
     * Used by subscribers to apply a minimum level.
     *
     * @param floor the minimum level to accept; must not be null
     * @return true if this level's rank is greater than or equal to
     *     {@code floor}'s rank
     */
    public boolean isAtLeast(final LogLevel floor)
    {
        if (floor == null)
        {
            throw new IllegalArgumentException("floor must not be null");
        }

        return rank >= floor.rank;
    }
}
