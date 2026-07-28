/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

import com.openfps.engine.common.Constants;

/**
 * One computer-controlled opponent: a body that walks a fixed route, turns to
 * face the player, shoots back on a timer, and dies after enough hits.
 *
 * <h2>What this deliberately is not</h2>
 *
 * <p>There is no pathfinding, no state machine, no line-of-sight memory and no
 * aggression model. That is the specification, not a shortcut: these are
 * <b>target practice</b>. A bot that flanks and takes cover makes the shooting
 * hard to evaluate, and right now the thing being evaluated is whether the
 * hitscan, the outline pass and the crosshair agree with each other. A player
 * needs to be able to watch a bot for two seconds and know where it will be.</p>
 *
 * <p>So the movement is a closed-form route ({@link BotPattern}) and the firing
 * is a fixed cadence. Both are pure functions of the tic index, which is what
 * makes a bot reproducible on every peer without exchanging a single byte about
 * it.</p>
 *
 * <h2>Same size as a player, on purpose</h2>
 *
 * <p>{@link #hitbox()} is built with the same {@code PLAYER_RADIUS} and
 * {@code PLAYER_HEIGHT} the local player occupies, so a bot is exactly as hard
 * to hit as another human would be. If that ever diverges, shooting bots stops
 * telling you anything about shooting people.</p>
 *
 * <h2>Mutability and threading</h2>
 *
 * <p>Mutable by design — it <i>is</i> an entity's state — and therefore not
 * thread-safe. It belongs to whichever thread owns the match, which is the game
 * loop thread. {@link Match} holds the lock that makes a tic atomic.</p>
 */
public final class Bot
{
    /** Health a bot spawns with. */
    public static final int MAX_HEALTH = 100;

    /** Bot radius in world units — the player's, so a bot is no easier to hit. */
    public static final float RADIUS_UNITS =
        Constants.PLAYER_RADIUS / (float) Constants.MAP_SCALE;

    /** Bot height in world units — the player's. */
    public static final float HEIGHT_UNITS =
        Constants.PLAYER_HEIGHT / (float) Constants.MAP_SCALE;

    /**
     * How high a bot's shots leave its body, in world units.
     *
     * <p>{@link PlayerController#EYE_HEIGHT_UNITS}, so bots shoot from where a
     * player's eye would be. Firing from the feet would let every piece of low
     * cover in the room block them, and firing from the top of the head would
     * let them shoot over cover the player cannot; neither is the behaviour a
     * player is judging their own line of sight against.</p>
     */
    public static final float EYE_HEIGHT_UNITS = PlayerController.EYE_HEIGHT_UNITS;

    /** One full turn in radians, for the phase computation. */
    private static final float FULL_TURN_RADIANS = (float) (2.0 * StrictMath.PI);

    /** The scene instance and hitbox this bot owns. Strictly positive. */
    private final int entityId;

    /** Route centre, world x. */
    private final float homeX;

    /** Route centre, world y — the floor the bot stands on. */
    private final float homeY;

    /** Route centre, world z. */
    private final float homeZ;

    /** Which route this bot walks. Never null. */
    private final BotPattern pattern;

    /** How far the route reaches from home, in world units. */
    private final float amplitudeUnits;

    /** Tics for one full circuit of the route. Always positive. */
    private final int periodTics;

    /** Where in the circuit this bot starts, so a group does not move in unison. */
    private final int phaseTics;

    /** Tics between this bot's shots. Always positive. */
    private final int fireIntervalTics;

    /** Offset into the firing cadence, so a group does not volley together. */
    private final int fireOffsetTics;

    /** Current feet position, world x. MUTABLE: recomputed every tic. */
    private float positionX;

    /** Current feet position, world z. MUTABLE: recomputed every tic. */
    private float positionZ;

    /** Current heading in radians. MUTABLE: turned toward the player every tic. */
    private float yawRadians;

    /** Remaining health. MUTABLE: reduced by {@link #damage}. Zero means dead. */
    private int health = MAX_HEALTH;

    /**
     * Creates a bot on a route.
     *
     * @param id the entity id this bot owns; must be at least
     *     {@link Target#MIN_ENTITY_ID}
     * @param routeCentreX route centre, world x
     * @param routeCentreY route centre, world y — the floor
     * @param routeCentreZ route centre, world z
     * @param routePattern which route to walk; must not be null
     * @param routeAmplitudeUnits how far the route reaches from its centre; must
     *     be finite and non-negative
     * @param routePeriodTics tics for one circuit; must be positive
     * @param routePhaseTics where in the circuit to start; any value, reduced
     *     modulo the period
     * @param shotIntervalTics tics between shots; must be positive
     * @param shotOffsetTics offset into the firing cadence
     * @throws IllegalArgumentException if any argument is out of range
     */
    public Bot(final int id, final float routeCentreX, final float routeCentreY,
        final float routeCentreZ, final BotPattern routePattern,
        final float routeAmplitudeUnits, final int routePeriodTics, final int routePhaseTics,
        final int shotIntervalTics, final int shotOffsetTics)
    {
        if (id < Target.MIN_ENTITY_ID)
        {
            throw new IllegalArgumentException(
                "entity id must be at least " + Target.MIN_ENTITY_ID + ", got " + id);
        }
        if (routePattern == null)
        {
            throw new IllegalArgumentException("pattern must not be null");
        }
        // Negated >= so NaN, which fails every comparison, is rejected here
        // rather than reaching a position and poisoning the hitbox.
        if (!(routeAmplitudeUnits >= 0.0f))
        {
            throw new IllegalArgumentException(
                "amplitude must be non-negative and a number, got " + routeAmplitudeUnits);
        }
        requireFinite("routeCentreX", routeCentreX);
        requireFinite("routeCentreY", routeCentreY);
        requireFinite("routeCentreZ", routeCentreZ);
        if (routePeriodTics <= 0)
        {
            throw new IllegalArgumentException(
                "period must be positive, got " + routePeriodTics);
        }
        if (shotIntervalTics <= 0)
        {
            throw new IllegalArgumentException(
                "fire interval must be positive, got " + shotIntervalTics);
        }

        this.entityId = id;
        this.homeX = routeCentreX;
        this.homeY = routeCentreY;
        this.homeZ = routeCentreZ;
        this.pattern = routePattern;
        this.amplitudeUnits = routeAmplitudeUnits;
        this.periodTics = routePeriodTics;
        this.phaseTics = routePhaseTics;
        this.fireIntervalTics = shotIntervalTics;
        this.fireOffsetTics = shotOffsetTics;
        // Place it on its route immediately, so a bot is never at its home
        // point for one tic before the first update moves it there.
        moveTo(0);
    }

    /**
     * Puts this bot where its route says it should be at a given tic.
     *
     * <p>Absolute, not incremental: the answer depends on {@code ticIndex} and
     * nothing else, so calling this twice for the same tic is harmless and
     * skipping a tic loses nothing. See {@link BotPattern} for why that matters
     * more than it looks like it should.</p>
     *
     * <p>Dead bots stop moving. They stay where they fell, which is what makes a
     * kill legible — a body that carried on walking its patrol would be
     * indistinguishable from one that was never hit.</p>
     *
     * @param ticIndex the tic to place this bot at
     */
    public void moveTo(final int ticIndex)
    {
        if (!isAlive())
        {
            return;
        }
        final float phase = phaseAt(ticIndex);
        this.positionX = homeX + pattern.offsetX(phase, amplitudeUnits);
        this.positionZ = homeZ + pattern.offsetZ(phase, amplitudeUnits);
    }

    /**
     * Returns where in its route cycle this bot is at a given tic, in radians.
     *
     * <p>Exposed because it is the whole of the movement model and a test that
     * could not see it would be reduced to asserting coordinates it had
     * copied from the implementation.</p>
     *
     * @param ticIndex the tic to evaluate
     * @return the phase in {@code [0, 2pi)}
     */
    public float phaseAt(final int ticIndex)
    {
        final int step = cyclicIndex(ticIndex + phaseTics, periodTics);
        return FULL_TURN_RADIANS * step / periodTics;
    }

    /**
     * Reduces a value into {@code [0, modulus)}, keeping the sign of the
     * divisor.
     *
     * <p>{@code %} alone will not do: it keeps the sign of the <i>dividend</i>,
     * so a negative tic index yields a negative phase. That is harmless to a
     * sine but violates the {@code [0, 2pi)} contract above and makes the
     * cadence in {@link #wantsToFire} misfire on the tics either side of
     * zero.</p>
     *
     * <p><b>Written out rather than calling {@code Math.floorMod}</b>, which
     * does exactly this and is bit-exact for integers. The reason is the guard,
     * not the arithmetic: {@code PlayerControllerTest} enforces its determinism
     * rule by reading the compiled constant pool and failing on any
     * {@code java/lang/Math} reference at all, and that check works precisely
     * because it is flat with no exceptions to remember. One legitimate integer
     * call here would mean the same class could also carry {@code Math.sin} —
     * which is permitted 1-2 ulp of error, is not required to agree between
     * JVMs, and would desync lockstep silently — with nothing able to tell the
     * two apart from the constant pool alone.</p>
     */
    private static int cyclicIndex(final int value, final int modulus)
    {
        final int remainder = value % modulus;
        if (remainder < 0)
        {
            return remainder + modulus;
        }
        return remainder;
    }

    /**
     * Turns this bot to face a point on the ground plane.
     *
     * <p>Cosmetic — nothing about aiming reads {@link #yawRadians()}, because a
     * bot's shot is aimed at the player directly. It is done anyway because a
     * body that shoots you while facing a wall reads as broken, and because the
     * outline pass makes exactly which way a bot is turned very visible.</p>
     *
     * <p>The convention is {@link PlayerController}'s: yaw 0 faces world +z and
     * increases toward +x, hence {@code atan2(dx, dz)} rather than the more
     * familiar {@code atan2(dz, dx)}. Getting that backwards mirrors every bot
     * in the room.</p>
     *
     * @param targetX the point to face, world x
     * @param targetZ the point to face, world z
     */
    public void faceToward(final float targetX, final float targetZ)
    {
        final float deltaX = targetX - positionX;
        final float deltaZ = targetZ - positionZ;
        if (deltaX == 0.0f && deltaZ == 0.0f)
        {
            // Standing exactly on the target. Any heading is as correct as any
            // other, and atan2(0, 0) is 0 rather than an error, so keeping the
            // current one avoids a visible snap for no information.
            return;
        }
        this.yawRadians = (float) StrictMath.atan2(deltaX, deltaZ);
    }

    /**
     * Returns whether this bot's weapon is ready on a given tic.
     *
     * <p>A fixed cadence with a per-bot offset. The offset is what stops seven
     * bots firing on the same tic — a synchronised volley is both harder to
     * survive and much harder to read than the same total rate spread out.</p>
     *
     * @param ticIndex the tic being processed
     * @return true if this bot should take a shot this tic
     */
    public boolean wantsToFire(final int ticIndex)
    {
        if (!isAlive())
        {
            return false;
        }
        return cyclicIndex(ticIndex + fireOffsetTics, fireIntervalTics) == 0;
    }

    /**
     * Applies damage, and reports whether this shot was the one that killed.
     *
     * <p>Returns true <b>exactly once</b> per bot however many further shots
     * land, which is what lets a caller count kills by counting {@code true}
     * without tracking who it has already counted.</p>
     *
     * @param amount hit points to remove; must be positive
     * @return true if this call took the bot from alive to dead
     * @throws IllegalArgumentException if {@code amount} is not positive
     */
    public boolean damage(final int amount)
    {
        if (amount <= 0)
        {
            throw new IllegalArgumentException("damage must be positive, got " + amount);
        }
        if (!isAlive())
        {
            return false;
        }
        final int remaining = health - amount;
        if (remaining <= 0)
        {
            this.health = 0;
            return true;
        }
        this.health = remaining;
        return false;
    }

    /**
     * Returns this bot's current hitbox.
     *
     * <p><b>Allocates</b>, and so must not be called every tic for every bot.
     * {@link Target} is immutable by design and a bot moves, so a fresh box is
     * the only honest answer; {@link Match} builds them only on the tics where
     * somebody actually fires, which is a few times a second rather than sixty.
     * See {@code Target}'s Javadoc on why the box may not be built inside the
     * shot itself.</p>
     *
     * @return an axis-aligned box around this bot's current position
     */
    public Target hitbox()
    {
        return Target.aroundFeet(entityId, positionX, homeY, positionZ,
            RADIUS_UNITS, HEIGHT_UNITS);
    }

    /** Returns the scene instance and hitbox id this bot owns. */
    public int entityId()
    {
        return entityId;
    }

    /** Returns the current feet position, world x. */
    public float positionX()
    {
        return positionX;
    }

    /** Returns the feet position, world y — the floor this bot stands on. */
    public float positionY()
    {
        return homeY;
    }

    /** Returns the current feet position, world z. */
    public float positionZ()
    {
        return positionZ;
    }

    /** Returns the height this bot's shots leave from, world y. */
    public float eyeY()
    {
        return homeY + EYE_HEIGHT_UNITS;
    }

    /** Returns the current heading in radians, on {@link PlayerController}'s convention. */
    public float yawRadians()
    {
        return yawRadians;
    }

    /** Returns which route this bot walks. Never null. */
    public BotPattern pattern()
    {
        return pattern;
    }

    /** Returns remaining health, zero once dead. */
    public int health()
    {
        return health;
    }

    /** Returns whether this bot is still standing. */
    public boolean isAlive()
    {
        return health > 0;
    }

    // Rejects NaN and both infinities before either can reach a position.
    private static void requireFinite(final String name, final float value)
    {
        if (!Float.isFinite(value))
        {
            throw new IllegalArgumentException(name + " must be finite, got " + value);
        }
    }

    /** Returns a debug rendering of this bot's id, route and state. */
    @Override
    public String toString()
    {
        return "Bot{id=" + entityId + ", " + pattern + ", at=(" + positionX + ", " + homeY
            + ", " + positionZ + "), health=" + health + "}";
    }
}
