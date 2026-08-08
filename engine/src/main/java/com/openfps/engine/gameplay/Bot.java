/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

import com.openfps.engine.common.Constants;
import com.openfps.engine.gameplay.map.Team;

/**
 * One computer-controlled opponent: a body that walks a fixed route, turns
 * toward where it last saw the player, shoots back when a die says so, and dies
 * after enough hits.
 *
 * <h2>What this deliberately is not</h2>
 *
 * <p>There is no pathfinding, no cover, no flanking and no squad behaviour. That
 * is the specification, not a shortcut: these are <b>target practice</b>. A bot
 * that manoeuvres makes the shooting hard to evaluate, and the thing being
 * evaluated is whether the hitscan, the outline pass and the crosshair agree
 * with each other. A player needs to be able to watch a bot for two seconds and
 * know where it will be.</p>
 *
 * <p>So the movement is still a closed-form route ({@link BotPattern}) and a
 * pure function of the tic index. What is no longer fixed is the <b>shooting</b>
 * — see below.</p>
 *
 * <h2>The firing is random, and the randomness is deterministic</h2>
 *
 * <p>A bot used to fire on a fixed cadence and hit every time it was in range.
 * It now rolls for the trigger on every tic its weapon is ready, and the shot it
 * takes is scattered into a cone by {@link Match}. Both draws come from
 * {@link BotRng}, which is <b>seeded, stateless and addressed by (tic, entity,
 * channel)</b> — so the same tic sequence produces the same shots on every
 * machine, and the lockstep model in {@code engine/net/README.md} survives.</p>
 *
 * <p><b>{@code Math.random()} here would desync two peers on the first shot.</b>
 * {@link BotRng}'s Javadoc lists everything else that would do the same, and
 * explains why a seeded {@code java.util.Random} held in a field is not good
 * enough either. There is a constant-pool test on this class that fails on any
 * {@code java/lang/Math} reference at all, for the reason {@link #cyclicIndex}
 * records.</p>
 *
 * <h2>It shoots at where you were, not where you are</h2>
 *
 * <p>{@link #observePlayer} refreshes the remembered player position only every
 * {@link BotSkill#reactionTics}, and both the body's facing and the shot's
 * direction come off that memory. That is the whole of "reacts slowly" and "does
 * not lead a moving target", and it is the one piece of the dumbness a player
 * can <b>see</b> rather than infer from a health bar: strafe past a bot and it
 * swings round behind you.</p>
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
 *
 * <p>{@link #reset()} returns every one of those mutable fields to its
 * construction value, which is what makes a rematch possible without rebuilding
 * the immutable {@code Scene} the body's model lives in.</p>
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

    /** Sentinel meaning "this bot's weapon has never been fired". */
    public static final int NEVER_FIRED = Integer.MIN_VALUE;

    /** One full turn in radians, for the phase computation. */
    private static final float FULL_TURN_RADIANS = (float) (2.0 * StrictMath.PI);

    /** The scene instance and hitbox this bot owns. Strictly positive. */
    private final int entityId;

    /**
     * Which team this bot fights for. {@link Team#NEUTRAL} for the legacy
     * single-player demo, which has no team assignment; team modes
     * (TDM, Hardpoint, Domination, CTF) assign RED or BLUE at spawn.
     *
     * <p>Imported from the map subpackage because the question "which team
     * does this body belong to" is a map-design question, not a bot-AI
     * question, and a {@code MapSpec}'s {@code SpawnPoint}s are the
     * authoritative source of that answer.</p>
     */
    private final Team team;

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

    /** Current feet position, world x. MUTABLE: recomputed every tic. */
    private float positionX;

    /** Current feet position, world z. MUTABLE: recomputed every tic. */
    private float positionZ;

    /** Current heading in radians. MUTABLE: turned toward the memory every tic. */
    private float yawRadians;

    /** Remaining health. MUTABLE: reduced by {@link #damage}. Zero means dead. */
    private int health = MAX_HEALTH;

    /**
     * The tic this bot's weapon becomes ready again. MUTABLE: pushed forward by
     * every shot.
     *
     * <p>Starts at {@link Integer#MIN_VALUE} — "ready, and has been for as long
     * as there has been a match" — rather than at zero, because a match may be
     * ticked from a negative index and {@code ticIndex >= readyAtTic} has to be
     * true on the very first tic whatever that index is.</p>
     */
    private int readyAtTic = Integer.MIN_VALUE;

    /** The tic this bot last fired, or {@link #NEVER_FIRED}. MUTABLE. */
    private int lastFiredTic = NEVER_FIRED;

    /** Where the player was last seen, world x. MUTABLE: refreshed on reaction tics. */
    private float rememberedPlayerX;

    /** Where the player was last seen, world z. MUTABLE. */
    private float rememberedPlayerZ;

    /**
     * Whether this bot has ever seen the player. MUTABLE.
     *
     * <p>False until the first {@link #observePlayer}, and it matters: without
     * it a fresh bot remembers the world origin, turns to face it, and shoots at
     * it — which in this room is seven bodies staring at the middle of the floor
     * for the first half-second of every match.</p>
     */
    private boolean hasSeenPlayer;

    /**
     * Creates a bot on a route, with no team assignment.
     *
     * <p>Equivalent to {@link #Bot(int, float, float, float, BotPattern, float,
     * int, int, Team)} with {@link Team#NEUTRAL}. The legacy eight-argument
     * constructor is the one every existing test and the legacy demo call;
     * the team-aware overload exists for the 16-map library's team modes.</p>
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
     * @throws IllegalArgumentException if any argument is out of range
     */
    public Bot(final int id, final float routeCentreX, final float routeCentreY,
        final float routeCentreZ, final BotPattern routePattern,
        final float routeAmplitudeUnits, final int routePeriodTics, final int routePhaseTics)
    {
        this(id, routeCentreX, routeCentreY, routeCentreZ, routePattern, routeAmplitudeUnits,
            routePeriodTics, routePhaseTics, Team.NEUTRAL);
    }

    /**
     * Creates a bot on a route, with a team assignment.
     *
     * <p>The team is read by the mode-specific Match layer to decide which
     * side a body counts for in Hardpoint zone captures, Domination flag
     * ownership, and CTF flag pickup. A {@link Team#NEUTRAL} bot does not
     * count for any side — the legacy single-player demo uses NEUTRAL
     * throughout because it has no team semantics.</p>
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
     * @param team which team this bot fights for; must not be null
     * @throws IllegalArgumentException if any argument is out of range
     */
    public Bot(final int id, final float routeCentreX, final float routeCentreY,
        final float routeCentreZ, final BotPattern routePattern,
        final float routeAmplitudeUnits, final int routePeriodTics, final int routePhaseTics,
        final Team team)
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
        if (team == null)
        {
            throw new IllegalArgumentException("team must not be null");
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

        this.entityId = id;
        this.homeX = routeCentreX;
        this.homeY = routeCentreY;
        this.homeZ = routeCentreZ;
        this.pattern = routePattern;
        this.amplitudeUnits = routeAmplitudeUnits;
        this.periodTics = routePeriodTics;
        this.phaseTics = routePhaseTics;
        this.team = team;
        // Place it on its route immediately, so a bot is never at its home
        // point for one tic before the first update moves it there.
        moveTo(0);
    }

    /**
     * Puts this bot back exactly as it was constructed.
     *
     * <p><b>This is what makes a rematch possible at all.</b> A {@code Scene} is
     * immutable and a bot's model occupies a fixed instance index in it, so a
     * fresh round cannot build fresh bodies — it has to reuse these ones. Every
     * mutable field is therefore restored: alive at {@link #MAX_HEALTH}, back at
     * the position tic 0 puts it, facing the way a bot that has seen nobody
     * faces, weapon ready, and with no memory of where the player was.</p>
     *
     * <p>The last of those is easy to forget and immediately visible: a bot that
     * kept its memory across a reset would spawn already turned toward wherever
     * the previous round happened to end.</p>
     *
     * <p>{@code MatchTest} asserts the invariant that matters — a reset bot is
     * indistinguishable from a newly constructed one — rather than checking each
     * field one at a time, so a field added later and not reset here fails
     * it.</p>
     */
    public void reset()
    {
        this.health = MAX_HEALTH;
        this.yawRadians = 0.0f;
        this.readyAtTic = Integer.MIN_VALUE;
        this.lastFiredTic = NEVER_FIRED;
        this.rememberedPlayerX = 0.0f;
        this.rememberedPlayerZ = 0.0f;
        this.hasSeenPlayer = false;
        // Last, and after the health: moveTo does nothing to a dead bot, so a
        // reset that placed the body before reviving it would leave every corpse
        // where it fell and restart the room with the survivors misplaced.
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
     * sine but violates the {@code [0, 2pi)} contract above, and it would make
     * the reaction stagger in {@link #observePlayer} misfire on the tics either
     * side of zero.</p>
     *
     * <p><b>Written out rather than calling {@code Math.floorMod}</b>, which
     * does exactly this and is bit-exact for integers. The reason is the guard,
     * not the arithmetic: {@code BotTest} enforces its determinism rule by
     * reading the compiled constant pool and failing on any
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
     * Lets this bot notice where the player is — occasionally.
     *
     * <p><b>The point of this method is the tics on which it does nothing.</b> A
     * bot refreshes its memory once every {@link BotSkill#reactionTics}, and
     * everything it then does — which way it faces, where it shoots — comes off
     * that memory rather than off the arguments. At the player's 256 units a
     * second and a 24-tic reaction, a bot works from a position about a hundred
     * units stale, which is three player diameters.</p>
     *
     * <p>The refresh tic is <b>staggered by entity id</b>, so seven bots do not
     * all snap round on the same tic. Two bodies turning in unison read as one
     * mechanism, which is the same reason their routes have different periods
     * ({@code DemoScene}'s BOT_PERIODS).</p>
     *
     * <p>Dead bots notice nothing, so a corpse does not swivel.</p>
     *
     * @param ticIndex the tic being processed
     * @param playerX the player's true feet position, world x
     * @param playerZ the player's true feet position, world z
     * @param skill how slowly this bot reacts; must not be null
     */
    public void observePlayer(final int ticIndex, final float playerX, final float playerZ,
        final BotSkill skill)
    {
        if (!isAlive())
        {
            return;
        }
        if (hasSeenPlayer && cyclicIndex(ticIndex + entityId, skill.reactionTics()) != 0)
        {
            return;
        }
        this.rememberedPlayerX = playerX;
        this.rememberedPlayerZ = playerZ;
        this.hasSeenPlayer = true;
    }

    /**
     * Turns this bot to face the position it last remembered the player at.
     *
     * <p>Cosmetic in the sense that no hit is decided by it — a shot's direction
     * is built from the same memory in {@link Match} rather than read back off
     * this angle — but very far from decorative. It is the one part of the
     * dumbness the player can watch happen: a body facing where you were half a
     * second ago is legible in a way a miss statistic never is.</p>
     *
     * <p>Does nothing until the bot has seen the player once, so a fresh bot
     * keeps its spawn heading instead of turning to stare at the world
     * origin.</p>
     */
    public void faceRemembered()
    {
        if (!hasSeenPlayer)
        {
            return;
        }
        faceToward(rememberedPlayerX, rememberedPlayerZ);
    }

    /**
     * Turns this bot to face a point on the ground plane.
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
     * Rolls for the trigger, and books the cooldown if it comes up.
     *
     * <p><b>Not a pure predicate: it has a side effect.</b> A bot that decides to
     * fire schedules its own next opportunity here, because the cooldown is
     * drawn from the same tic's randomness and doing it anywhere else would mean
     * two places had to agree about when a shot happened. Call it exactly once
     * per bot per tic, from {@link Match#tick}.</p>
     *
     * <p>Every draw is addressed by {@code (tic, entityId, channel)}, so two
     * bots asked on the same tic get independent answers and the room never
     * volleys. Nothing here depends on how many times the generator has been
     * called before, which is what lets the early-outs above the roll exist at
     * all — see {@link BotRng}.</p>
     *
     * @param ticIndex the tic being processed
     * @param rng the match's generator; must not be null
     * @param skill how eager and how slow this bot is; must not be null
     * @return true if this bot takes a shot this tic
     */
    public boolean wantsToFire(final int ticIndex, final BotRng rng, final BotSkill skill)
    {
        if (!isAlive() || ticIndex < readyAtTic)
        {
            return false;
        }
        if (!rng.chance(ticIndex, entityId, BotRng.CHANNEL_FIRE, skill.fireChancePermille()))
        {
            return false;
        }
        this.lastFiredTic = ticIndex;
        this.readyAtTic = ticIndex + skill.cooldownTics() + extraCooldown(ticIndex, rng, skill);
        return true;
    }

    // The random tail on a cooldown. A spread of zero is legal and means a fixed
    // floor, which is what BotSkill.MARKSMAN uses to be able to fire every tic;
    // boundedInt would reject a bound of zero, so the case is answered here
    // rather than by widening the generator's contract for one caller.
    private int extraCooldown(final int ticIndex, final BotRng rng, final BotSkill skill)
    {
        if (skill.cooldownSpreadTics() <= 0)
        {
            return 0;
        }
        return rng.boundedInt(ticIndex, entityId, BotRng.CHANNEL_COOLDOWN,
            skill.cooldownSpreadTics());
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

    /**
     * Returns which team this bot fights for. Never null.
     *
     * @return the team, or {@link Team#NEUTRAL} for a bot with no team
     *     assignment
     */
    public Team team()
    {
        return team;
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

    /** Returns whether this bot has ever been told where the player is. */
    public boolean hasSeenPlayer()
    {
        return hasSeenPlayer;
    }

    /**
     * Returns the player position this bot is working from, world x.
     *
     * <p>Exposed because it is the whole of the reaction model, and because a
     * test that could not see it would have to infer the lag from a heading —
     * which is an angle compared against another angle, and this package has
     * already learned what that kind of assertion is worth.</p>
     *
     * @return the remembered x, or zero before the bot has seen anything
     */
    public float rememberedPlayerX()
    {
        return rememberedPlayerX;
    }

    /**
     * Returns the player position this bot is working from, world z.
     *
     * @return the remembered z, or zero before the bot has seen anything
     */
    public float rememberedPlayerZ()
    {
        return rememberedPlayerZ;
    }

    /** Returns the tic this bot's weapon next becomes ready. */
    public int readyAtTic()
    {
        return readyAtTic;
    }

    /** Returns the tic this bot last fired on, or {@link #NEVER_FIRED}. */
    public int lastFiredTic()
    {
        return lastFiredTic;
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
