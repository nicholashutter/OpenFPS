/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

/**
 * A finished {@link Match}, frozen: the outcome and the numbers behind it.
 *
 * <p>{@code Match} is live simulation state owned by the game loop thread. The
 * end-of-match screen is drawn by the platform's render thread, and it wants to
 * read the same six numbers on every frame for as long as the player leaves the
 * screen up. Reading them off the live object would be a cross-thread read of
 * mutable fields repeated sixty times a second, for values that by definition
 * stopped changing the moment the match ended.</p>
 *
 * <p>So the result is <b>copied once</b>, at the transition, into this. After
 * that the UI holds an immutable object and the simulation is not consulted
 * again. That is the whole reason this type exists — it is not a convenience
 * wrapper, it is the thread boundary.</p>
 *
 * <h2>Why it lives in gameplay rather than in the UI module</h2>
 *
 * <p>Because {@link #accuracyPercent()} is a rule about the game, not about a
 * screen: "shots that connected, out of shots taken, rounded". Two platforms
 * draw this, and a second copy of that division in the second platform's UI is
 * exactly the kind of divergence {@code STYLE.md} § 13 exists to stop. The
 * arithmetic is also the only part of the end screen a headless test can
 * reach.</p>
 *
 * <p>Immutable, and therefore safe to publish to any thread.</p>
 */
public final class MatchSummary
{
    /** Percent, for turning a hit ratio into a whole number. */
    private static final int PERCENT = 100;

    /** How the match finished. Never null, never {@link MatchState#IN_PROGRESS}. */
    private final MatchState outcome;

    /** Bots the player put down. */
    private final int botsKilled;

    /**
     * Times the player was put down.
     *
     * <p>A score rather than a failure count, which is the whole of the change
     * that put it here: a death respawns the player after
     * {@link Match#RESPAWN_DELAY_TICS} and ends nothing. Reported beside the
     * kills because the pair is the result — "seven for two" says something
     * about a round that "seven" on its own does not.</p>
     */
    private final int playerDeaths;

    /** Bots the match started with. */
    private final int botCount;

    /** Shots the player took. */
    private final int shotsFired;

    /** Shots of the player's that connected. */
    private final int shotsHit;

    /** Health the player lost over the round. */
    private final int damageTaken;

    /** Health the player finished with; zero or less when they died. */
    private final int playerHealth;

    /**
     * Creates a summary from explicit figures.
     *
     * <p>Public so a test can build a result that a real match would take
     * thousands of tics to reach. Normal callers use {@link #of(Match)}.</p>
     *
     * @param finalState how the match finished; must not be null and must not
     *     be {@link MatchState#IN_PROGRESS} — an unfinished match has no summary
     * @param kills bots put down; must not be negative
     * @param deaths times the player was put down; must not be negative
     * @param opponents bots the match started with; must not be negative
     * @param fired shots the player took; must not be negative
     * @param hits shots that connected; must not be negative and must not
     *     exceed {@code fired}
     * @param damage health the player lost; must not be negative
     * @param healthLeft health the player finished with, which may be negative
     *     because the killing shot is not clamped
     * @throws IllegalArgumentException if any rule above is broken
     */
    public MatchSummary(final MatchState finalState, final int kills, final int deaths,
        final int opponents, final int fired, final int hits, final int damage,
        final int healthLeft)
    {
        if (finalState == null)
        {
            throw new IllegalArgumentException("finalState must not be null");
        }

        if (!finalState.isOver())
        {
            throw new IllegalArgumentException(
                "a match still in progress has no summary, got " + finalState);
        }

        if (kills < 0 || deaths < 0 || opponents < 0 || fired < 0 || hits < 0 || damage < 0)
        {
            throw new IllegalArgumentException("match counters must not be negative");
        }

        if (hits > fired)
        {
            throw new IllegalArgumentException(
                "hits must not exceed shots fired, got " + hits + " of " + fired);
        }

        this.outcome = finalState;

        this.botsKilled = kills;

        this.playerDeaths = deaths;

        this.botCount = opponents;

        this.shotsFired = fired;

        this.shotsHit = hits;

        this.damageTaken = damage;

        this.playerHealth = healthLeft;
    }

    /**
     * Freezes a finished match.
     *
     * @param match the round to copy; must not be null and must be over
     * @return an immutable snapshot of the result
     * @throws IllegalArgumentException if {@code match} is null or still running
     */
    public static MatchSummary of(final Match match)
    {
        if (match == null)
        {
            throw new IllegalArgumentException("match must not be null");
        }

        // Landed shots times the damage per shot, and NOT "max health minus what
        // is left" — which is what this used to be, and which stopped being the
        // same number the moment a death respawned the player at full health.
        // Over a round with two deaths in it the health bar goes back up twice,
        // so the difference at the end understates the damage taken by two
        // hundred. What the player actually absorbed is the sum of the hits.
        final int damage = match.botShotsLanded() * Match.BOT_SHOT_DAMAGE;

        return new MatchSummary(match.state(), match.botsKilled(), match.playerDeaths(),
            match.botCount(), match.playerShotsFired(), match.playerShotsHit(), damage,
            match.playerHealth());
    }

    /** Returns how the match finished. Never null, never in progress. */
    public MatchState outcome()
    {
        return outcome;
    }

    /**
     * Returns whether the player cleared the room.
     *
     * @return true for {@link MatchState#WON}
     */
    public boolean isWin()
    {
        return outcome == MatchState.WON;
    }

    /** Returns how many bots the player put down. */
    public int botsKilled()
    {
        return botsKilled;
    }

    /** Returns how many times the player was put down. */
    public int playerDeaths()
    {
        return playerDeaths;
    }

    /** Returns how many bots the match started with. */
    public int botCount()
    {
        return botCount;
    }

    /** Returns how many shots the player took. */
    public int shotsFired()
    {
        return shotsFired;
    }

    /** Returns how many of the player's shots connected. */
    public int shotsHit()
    {
        return shotsHit;
    }

    /** Returns how much health the player lost over the round. */
    public int damageTaken()
    {
        return damageTaken;
    }

    /** Returns the health the player finished with; zero or less once dead. */
    public int playerHealth()
    {
        return playerHealth;
    }

    /**
     * Returns hit rate as a whole percentage, rounded to nearest.
     *
     * <p><b>Zero shots is zero percent, not an error and not "100".</b> A player
     * who dies without firing has hit nothing, and integer division would
     * otherwise divide by zero here — which is the one arithmetic fault in this
     * class that a real run can produce, since standing still is exactly how a
     * new player loses their first match.</p>
     *
     * @return 0 to 100
     */
    public int accuracyPercent()
    {
        if (shotsFired <= 0)
        {
            return 0;
        }

        // Rounded rather than truncated: 2 of 3 reads as 67%, not 66%.
        return (shotsHit * PERCENT + shotsFired / 2) / shotsFired;
    }

    /** Returns a debug rendering of the result. */
    @Override
    public String toString()
    {
        return "MatchSummary{" + outcome + ", killed=" + botsKilled + "/" + botCount
            + ", deaths=" + playerDeaths + ", accuracy=" + shotsHit + "/" + shotsFired
            + " (" + accuracyPercent() + "%), damage=" + damageTaken + ", hp="
            + playerHealth + "}";
    }
}
