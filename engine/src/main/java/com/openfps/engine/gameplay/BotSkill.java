/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

/**
 * How badly a {@link Bot} shoots: every number that makes the opponents random
 * and stupid, in one immutable value.
 *
 * <h2>Dumb is the specification, not a shortcut</h2>
 *
 * <p>Bots used to hit <b>every</b> shot inside {@link Match#BOT_RANGE_UNITS}, on
 * a fixed 150-tic cadence shared by the whole room. That is not difficulty, it
 * is a metronome with a laser on it: the player cannot dodge it, cannot read it,
 * and cannot tell a good position from a bad one because position does not
 * matter. Every field below exists to take one of those properties away.</p>
 *
 * <ul>
 *   <li><b>{@link #fireChancePermille}</b> replaces the cadence. A bot rolls for
 *       the trigger on every tic it is ready, so seven bots never volley
 *       together and no two runs sound the same.</li>
 *   <li><b>{@link #cooldownTics} and {@link #cooldownSpreadTics}</b> stop that
 *       becoming a machine gun, and the <i>spread</i> is what stops the bots
 *       drifting back into step with each other over a long round.</li>
 *   <li><b>{@link #aimSpreadRadians}</b> is the miss. It is an angle applied to
 *       the actual ray, not a dice roll against the outcome, so a bot at the far
 *       side of the room misses more than one at your elbow — for the reason a
 *       real shooter would — and cover still works exactly as it did.</li>
 *   <li><b>{@link #reactionTics}</b> is the slow reaction. A bot only learns
 *       where you are every so often, and it <i>faces</i> the place it last
 *       learned about, so a strafing player watches seven bodies swing round
 *       behind them. It is also why nothing here leads a moving target: a bot
 *       shoots at where you were, which is the definition of not leading.</li>
 *   <li><b>{@link #wildShotChancePermille}</b> is the shot taken at nothing at
 *       all — no line of sight, no target, sometimes facing away.</li>
 * </ul>
 *
 * <p><b>What is deliberately absent: pathfinding, cover, flanking, squad
 * behaviour, target prioritisation.</b> None of it is planned. A bot that
 * manoeuvres makes the shooting impossible to evaluate, and the thing being
 * evaluated is still whether the hitscan, the outline pass and the crosshair
 * agree with each other.</p>
 *
 * <h2>Why this is a value rather than six constants on {@link Match}</h2>
 *
 * <p>Because {@link #MARKSMAN} has to exist. Half of {@code MatchTest} is about
 * <b>geometry</b> — a bot behind another bot cannot shoot through it, a shot is
 * resolved from where the body is rather than where it was — and none of those
 * questions can be asked of an opponent that misses nine shots in ten. With the
 * dumbness in a value, those tests keep asserting geometry instead of dice, and
 * the dice get their own tests. Constants on {@code Match} would have forced
 * every geometry assertion to become a statistical one, which is how a suite
 * stops catching anything.</p>
 *
 * <p>Immutable, and therefore safe to share between peers and threads.</p>
 */
public final class BotSkill
{
    /**
     * Chance per ready tic that a bot pulls the trigger — <b>25</b> in a
     * thousand for {@link #DUMB}.
     *
     * <p>With {@link #cooldownTics} and {@link #cooldownSpreadTics} this sets
     * the room's rate of fire, which is the figure the player actually
     * experiences. The arithmetic is in {@link #meanShotIntervalTics()}.</p>
     */
    private final int fireChancePermille;

    /** Tics a bot must wait after firing before it may roll again. */
    private final int cooldownTics;

    /**
     * Random extra tics added to each cooldown, drawn per shot.
     *
     * <p>Zero would be a fixed floor, and a fixed floor is a cadence again: two
     * bots that happened to fire on the same tic would stay in step for the rest
     * of the round. The spread is what makes that impossible.</p>
     */
    private final int cooldownSpreadTics;

    /**
     * Half-angle of the cone a bot's shot is scattered into, in radians.
     *
     * <p>Applied to the ray, in the shooter's own frame — see
     * {@code Match.botShotConnects}. The consequence is that hit probability
     * falls with distance without anything having to model that: the player
     * subtends {@code atan(16 / distance)} horizontally, so at 250 units a
     * 14-degree cone lands roughly one shot in eight and at 500 units roughly
     * one in thirty.</p>
     */
    private final float aimSpreadRadians;

    /**
     * Chance in a thousand that a shot is taken with nothing acquired at all.
     *
     * <p>A wild shot is fired in an arbitrary horizontal direction — the bot has
     * no line of sight, may be facing away, and is shooting because it decided
     * to shoot rather than because it saw anything. It is very nearly always a
     * miss, and it is the difference between an opponent that is inaccurate and
     * one that is <i>stupid</i>.</p>
     */
    private final int wildShotChancePermille;

    /**
     * Tics between refreshes of a bot's idea of where the player is.
     *
     * <p>The bot turns toward, and shoots at, the position it last learned —
     * never the current one. At the player's 256 units a second, 24 tics is
     * about 102 units of stale information, which is three player diameters and
     * is plainly visible as bodies swinging round behind a strafing player.</p>
     */
    private final int reactionTics;

    /**
     * The opponents the demo actually ships: inaccurate, slow, and occasionally
     * shooting at nothing.
     *
     * <p>The numbers are chosen against each other rather than individually, and
     * the constraint they are solved under is that <b>the room must still sound
     * as busy as it did</b>. The noise of return fire is what tells a player they
     * are in a fight, and an opponent that misses is only an improvement if it is
     * still shooting. Measured: seven bots produce a shot every <b>18</b> tics
     * between them, against 21 under the old fixed cadence — so the demo sounds
     * the same, while the fraction that lands falls from 100% to <b>20%</b> at
     * 200 units and 6% averaged over the room.</p>
     *
     * <p>{@code Match.BOT_SHOT_DAMAGE} was raised from 2 to 20 against those
     * figures, which leaves the room as dangerous as it was to a player who
     * stands still — 22 seconds rather than 20. See its Javadoc for the whole
     * measurement, and {@code MatchTest.Balance} for the test that pins it.</p>
     */
    public static final BotSkill DUMB = new BotSkill(25, 45, 90, 0.244f, 150, 24);

    /**
     * An opponent that never misses, never hesitates and always knows exactly
     * where the player is.
     *
     * <p><b>This is a test instrument, and it is here rather than in the test
     * source on purpose.</b> The questions {@code MatchTest} asks about return
     * fire are geometric — does a body in the way block a shot, is a shot
     * resolved from the post-move position, does a shooter appear in its own
     * target set — and every one of them becomes unaskable against an opponent
     * that misses most of the time. Answering them statistically instead would
     * mean thousands of tics per assertion and a suite that fails once a month
     * for no reason.</p>
     *
     * <p>Nothing in the shipped demo uses it. {@code Match}'s no-skill
     * constructor is {@link #DUMB}.</p>
     */
    public static final BotSkill MARKSMAN =
        new BotSkill(BotRng.PER_MILLE, 1, 0, 0.0f, 0, 1);

    /**
     * Creates a skill profile.
     *
     * @param triggerChancePermille chance per ready tic of firing, in parts per
     *     thousand; must be in {@code [0, 1000]}
     * @param minimumCooldownTics tics between shots at the very least; must be
     *     positive, or a bot could fire twice on one tic
     * @param extraCooldownTics random tics added to each cooldown; must not be
     *     negative. Zero is legal and means a fixed cooldown
     * @param spreadRadians half-angle of the scatter cone; must be finite and
     *     not negative. Zero is legal and means a perfect shot
     * @param wildChancePermille chance in a thousand of a shot at nothing; must
     *     be in {@code [0, 1000]}
     * @param awarenessTics tics between refreshes of the remembered player
     *     position; must be positive. 1 means the bot always knows
     * @throws IllegalArgumentException if any argument is out of range
     */
    public BotSkill(final int triggerChancePermille, final int minimumCooldownTics,
        final int extraCooldownTics, final float spreadRadians,
        final int wildChancePermille, final int awarenessTics)
    {
        requirePermille("triggerChancePermille", triggerChancePermille);
        requirePermille("wildChancePermille", wildChancePermille);
        if (minimumCooldownTics <= 0)
        {
            throw new IllegalArgumentException(
                "minimumCooldownTics must be positive, got " + minimumCooldownTics);
        }
        if (extraCooldownTics < 0)
        {
            throw new IllegalArgumentException(
                "extraCooldownTics must not be negative, got " + extraCooldownTics);
        }
        // Negated >= so NaN, which fails every comparison, is rejected here
        // rather than reaching a shot direction and poisoning it.
        if (!(spreadRadians >= 0.0f) || Float.isInfinite(spreadRadians))
        {
            throw new IllegalArgumentException(
                "spreadRadians must be finite and not negative, got " + spreadRadians);
        }
        if (awarenessTics <= 0)
        {
            throw new IllegalArgumentException(
                "awarenessTics must be positive, got " + awarenessTics);
        }
        this.fireChancePermille = triggerChancePermille;
        this.cooldownTics = minimumCooldownTics;
        this.cooldownSpreadTics = extraCooldownTics;
        this.aimSpreadRadians = spreadRadians;
        this.wildShotChancePermille = wildChancePermille;
        this.reactionTics = awarenessTics;
    }

    // Every chance in this class is in parts per thousand, checked once.
    private static void requirePermille(final String name, final int value)
    {
        if (value < 0 || value > BotRng.PER_MILLE)
        {
            throw new IllegalArgumentException(
                name + " must be 0-" + BotRng.PER_MILLE + ", got " + value);
        }
    }

    /** Returns the chance per ready tic of firing, in parts per thousand. */
    public int fireChancePermille()
    {
        return fireChancePermille;
    }

    /** Returns the least number of tics between one bot's shots. */
    public int cooldownTics()
    {
        return cooldownTics;
    }

    /** Returns how many random tics may be added to a cooldown. */
    public int cooldownSpreadTics()
    {
        return cooldownSpreadTics;
    }

    /** Returns the half-angle of the scatter cone, in radians. */
    public float aimSpreadRadians()
    {
        return aimSpreadRadians;
    }

    /** Returns the chance in a thousand of a shot taken at nothing. */
    public int wildShotChancePermille()
    {
        return wildShotChancePermille;
    }

    /** Returns how many tics a bot's idea of the player's position survives. */
    public int reactionTics()
    {
        return reactionTics;
    }

    /**
     * Returns the average tics between one bot's shots.
     *
     * <p>The cooldown, plus half its spread, plus the expected wait for the
     * trigger roll to come up — a geometric distribution whose mean is
     * {@code 1000 / chance} tics. Exposed rather than commented because it is
     * the number the whole profile is balanced against, and because a test that
     * pins the room's rate of fire has to compute it from the same definition
     * the balance argument uses.</p>
     *
     * @return the mean interval in tics, or {@link Integer#MAX_VALUE} for a
     *     profile that never fires at all
     */
    public int meanShotIntervalTics()
    {
        if (fireChancePermille <= 0)
        {
            return Integer.MAX_VALUE;
        }
        return cooldownTics + cooldownSpreadTics / 2 + BotRng.PER_MILLE / fireChancePermille;
    }

    /** Returns a debug rendering of the whole profile, for logs and failures. */
    @Override
    public String toString()
    {
        return "BotSkill{fire=" + fireChancePermille + "/1000, cooldown=" + cooldownTics
            + "+" + cooldownSpreadTics + " tics, spread=" + aimSpreadRadians
            + " rad, wild=" + wildShotChancePermille + "/1000, reaction=" + reactionTics
            + " tics, mean shot every " + meanShotIntervalTics() + " tics}";
    }
}
