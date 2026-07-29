/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

/**
 * A running {@link Match}, frozen for one frame: the score, the health, and
 * whether the player is currently on the floor.
 *
 * <h2>Why this exists as well as {@link MatchSummary}</h2>
 *
 * <p>They are the same idea at two different moments and neither can do the
 * other's job. {@code MatchSummary} is copied <b>once</b>, when a round is
 * decided, and describes something that has stopped changing; this is copied
 * <b>every frame</b>, while everything in it is still moving. The rule
 * {@code MatchSummary} enforces — a match still in progress has no summary — is
 * precisely the case this type exists to serve.</p>
 *
 * <p>It is a snapshot rather than a reference to the live {@code Match} for the
 * reason {@code MatchSummary} gives at greater length: the match is simulation
 * state owned by the game loop thread, the HUD is drawn by the platform's render
 * thread, and reading eight mutable fields across that boundary sixty times a
 * second would give a frame that could show a health bar from one tic beside a
 * kill count from another. One immutable copy per frame costs one allocation on
 * the <i>render</i> thread — not the tic path — and makes every figure on screen
 * agree with every other.</p>
 *
 * <h2>Why the player has to see this at all</h2>
 *
 * <p>Because the complaint that produced the end screen was that a match ended
 * silently, and a death with no acknowledgement is the same complaint one level
 * down. Before this, a player who was killed saw their view snap back to the
 * spawn point with no explanation whatsoever — indistinguishable from a bug, and
 * reported as one. {@link #isPlayerDown()} and
 * {@link #respawnTicsRemaining()} are what let the platform say so on the
 * glass instead of in a log line nobody is reading.</p>
 *
 * <p>Immutable, and therefore safe to publish to any thread.</p>
 */
public final class MatchStatus
{
    /** Bots the player has put down so far. */
    private final int botsKilled;

    /** Bots the round started with. */
    private final int botCount;

    /** Bots still standing. */
    private final int botsAlive;

    /** Times the player has been put down. */
    private final int playerDeaths;

    /** Remaining player health, zero while waiting to respawn. */
    private final int playerHealth;

    /** Whether the player is on the floor waiting to stand up. */
    private final boolean playerDown;

    /** Tics until the player stands up, zero when they are on their feet. */
    private final int respawnTicsRemaining;

    /**
     * Creates a status from explicit figures.
     *
     * <p>Public so a test can build a state a real match would take thousands of
     * tics to reach — a player four deaths in with two bots left, say. Normal
     * callers use {@link #of(Match, int)}.</p>
     *
     * @param kills bots put down; must not be negative
     * @param opponents bots the round started with; must not be negative
     * @param alive bots still standing; must not be negative and must not exceed
     *     {@code opponents}
     * @param deaths times the player was put down; must not be negative
     * @param health remaining player health
     * @param down whether the player is waiting to respawn
     * @param respawnTics tics until they stand up; must not be negative
     * @throws IllegalArgumentException if any rule above is broken
     */
    public MatchStatus(final int kills, final int opponents, final int alive, final int deaths,
        final int health, final boolean down, final int respawnTics)
    {
        if (kills < 0 || opponents < 0 || alive < 0 || deaths < 0 || respawnTics < 0)
        {
            throw new IllegalArgumentException("match counters must not be negative");
        }
        if (alive > opponents)
        {
            throw new IllegalArgumentException(
                "more bots alive than the round started with: " + alive + " of " + opponents);
        }
        this.botsKilled = kills;
        this.botCount = opponents;
        this.botsAlive = alive;
        this.playerDeaths = deaths;
        this.playerHealth = health;
        this.playerDown = down;
        this.respawnTicsRemaining = respawnTics;
    }

    /**
     * Snapshots a running match.
     *
     * @param match the round to copy; must not be null. It need not be over —
     *     that is the difference from {@link MatchSummary#of}
     * @param ticIndex the tic the caller believes the simulation is on, which is
     *     what turns an absolute respawn tic into a countdown
     * @return an immutable snapshot
     * @throws IllegalArgumentException if {@code match} is null
     */
    public static MatchStatus of(final Match match, final int ticIndex)
    {
        if (match == null)
        {
            throw new IllegalArgumentException("match must not be null");
        }
        return new MatchStatus(match.botsKilled(), match.botCount(), match.livingBots(),
            match.playerDeaths(), match.playerHealth(), match.isPlayerDown(),
            match.respawnTicsRemaining(ticIndex));
    }

    /** Returns how many bots the player has put down. */
    public int botsKilled()
    {
        return botsKilled;
    }

    /** Returns how many bots the round started with. */
    public int botCount()
    {
        return botCount;
    }

    /** Returns how many bots are still standing. */
    public int botsAlive()
    {
        return botsAlive;
    }

    /** Returns how many times the player has been put down. */
    public int playerDeaths()
    {
        return playerDeaths;
    }

    /** Returns the remaining player health, zero while waiting to respawn. */
    public int playerHealth()
    {
        return playerHealth;
    }

    /** Returns whether the player is on the floor waiting to stand up. */
    public boolean isPlayerDown()
    {
        return playerDown;
    }

    /** Returns tics until the player stands up, zero when they are on their feet. */
    public int respawnTicsRemaining()
    {
        return respawnTicsRemaining;
    }

    /**
     * Returns the whole seconds remaining before the player stands up, rounded
     * up.
     *
     * <p>Rounded <b>up</b>, and that is the only part of this worth stating: a
     * countdown that truncates shows "0" for a whole second before anything
     * happens, which reads as a game that has stopped. Rounding up means the
     * notice reads "1" until the tic it is over.</p>
     *
     * @param ticsPerSecond the simulation rate; a non-positive value returns 0
     *     rather than dividing by it
     * @return seconds remaining, zero when the player is on their feet
     */
    public int respawnSecondsRemaining(final int ticsPerSecond)
    {
        if (ticsPerSecond <= 0 || respawnTicsRemaining <= 0)
        {
            return 0;
        }
        return (respawnTicsRemaining + ticsPerSecond - 1) / ticsPerSecond;
    }

    /** Returns a debug rendering of the live score. */
    @Override
    public String toString()
    {
        return "MatchStatus{kills=" + botsKilled + "/" + botCount + ", deaths=" + playerDeaths
            + ", hp=" + playerHealth + ", alive=" + botsAlive + ", down=" + playerDown
            + ", respawnIn=" + respawnTicsRemaining + " tics}";
    }
}
