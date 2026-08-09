/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

/**
 * How a {@link Bot} moves: a closed-form displacement from its home point,
 * evaluated from a phase angle.
 *
 * <h2>Closed form, not integration — and that is the whole design</h2>
 *
 * <p>Every pattern here is a pure function of {@code (phase, amplitude)}. A bot
 * does not accumulate velocity from tic to tic; it computes where it should be
 * at tic <i>n</i> directly. Three things follow, and all three were the
 * reason:</p>
 *
 * <ol>
 *   <li><b>It cannot drift.</b> An integrated patrol accumulates float error and
 *       slowly wanders off its route over a long match. This one is the same
 *       expression every time and returns to exactly the same coordinates each
 *       cycle.</li>
 *   <li><b>It is trivially reproducible across peers.</b> Position at tic
 *       <i>n</i> depends on <i>n</i> and on nothing that happened before it, so
 *       a peer that joins late, or one that drops a frame, computes the
 *       identical answer without replaying history. Under lockstep that removes
 *       a whole class of desync before it can exist.</li>
 *   <li><b>It is genuinely predictable</b>, which is what these bots are for.
 *       They are target practice. A player should be able to lead a shot after
 *       watching one for a couple of seconds.</li>
 * </ol>
 *
 * <p>{@link StrictMath} throughout, for the reason {@code PlayerController}
 * spells out at length: {@code Math.sin} and {@code Math.cos} are permitted 1-2
 * ulp of error and are explicitly not required to agree between JVMs, so using
 * them here would put two peers' bots in fractionally different places and
 * eventually disagree about whether a shot connected.</p>
 */
public enum BotPattern
{
    /**
     * Does not move. The stationary target, and the one to shoot first.
     *
     * <p>Worth having as a pattern rather than as "amplitude zero": a match with
     * nothing standing still in it is much harder to sight in on, and this
     * states the intent where a zero would look like an oversight.</p>
     */
    SENTRY,

    /** Slides back and forth along world x, through the home point. */
    PACE_X,

    /** Slides back and forth along world z, through the home point. */
    PACE_Z,

    /**
     * Circles the home point at a constant radius.
     *
     * <p>The only pattern whose speed is constant rather than sinusoidal, which
     * makes it the one that reads as "walking" instead of "sliding". It is also
     * the one that will move behind cover, so it is the interesting target.</p>
     */
    ORBIT;

    /**
     * Returns the displacement from the home point along world x.
     *
     * @param phaseRadians where in the cycle the bot is, in radians
     * @param amplitudeUnits how far from home the pattern reaches, in world
     *     units
     * @return the x offset to add to the home position
     */
    public float offsetX(final float phaseRadians, final float amplitudeUnits)
    {
        return switch (this)
        {
            case PACE_X, ORBIT -> amplitudeUnits * (float) StrictMath.sin(phaseRadians);
            case SENTRY, PACE_Z -> 0.0f;
        };
    }

    /**
     * Returns the displacement from the home point along world z.
     *
     * @param phaseRadians where in the cycle the bot is, in radians
     * @param amplitudeUnits how far from home the pattern reaches, in world
     *     units
     * @return the z offset to add to the home position
     */
    public float offsetZ(final float phaseRadians, final float amplitudeUnits)
    {
        return switch (this)
        {
            case PACE_Z -> amplitudeUnits * (float) StrictMath.sin(phaseRadians);
            // Cosine against the sine above, which is what turns two
            // one-dimensional paces into a circle rather than a diagonal
            // line — sin on both axes would trace the latter.
            case ORBIT -> amplitudeUnits * (float) StrictMath.cos(phaseRadians);
            case SENTRY, PACE_X -> 0.0f;
        };
    }

    /**
     * Returns whether this pattern ever leaves the home point.
     *
     * @return false only for {@link #SENTRY}
     */
    public boolean moves()
    {
        return this != SENTRY;
    }
}
