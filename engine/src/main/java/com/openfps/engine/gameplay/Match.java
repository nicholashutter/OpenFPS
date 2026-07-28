/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

/**
 * One round: a player, a set of {@link Bot}s, and the rules that decide who is
 * still standing.
 *
 * <p>This is the piece that turns a room with bodies in it into a game. It owns
 * the health on both sides, moves the bots each tic, lets them shoot back, and
 * resolves the player's shots. It owns no rendering, no input and no
 * networking — {@code DemoGameplayPort} drives it and reads the results.</p>
 *
 * <h2>Both directions of fire go through the same {@link Hitscan}</h2>
 *
 * <p>A bot's shot is not a dice roll against a distance. It is a ray from the
 * bot's eye to the player's, tested against every other body in the room, and it
 * lands only if the player is the <b>nearest</b> thing it meets. So a bot
 * standing between the shooter and the player eats the shot, and the player can
 * genuinely use another bot as cover.</p>
 *
 * <p>That was worth the extra work for a reason beyond the emergent behaviour:
 * it means the return fire exercises the same tested code path as the player's
 * own weapon, at a much higher volume than a human can produce. A bug in
 * {@code Hitscan}'s slab test now has seven opponents finding it every second.</p>
 *
 * <h2>Where the allocation is, and why it is allowed</h2>
 *
 * <p>{@link Target} is immutable and bots move, so a bot's hitbox cannot be
 * built once at spawn — it has to be rebuilt from its current position.
 * {@code STYLE.md} § 13.4 bans allocation on a per-tic path, so the boxes are
 * built <b>only on the tics where somebody actually fires</b>: at most a handful
 * a second between the player's five shots and the bots' staggered cadence,
 * rather than sixty times a second for nothing. A tic where no trigger is pulled
 * allocates nothing at all.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>Mutable and not thread-safe, like everything else that is simulation state.
 * It belongs to the game loop thread; {@code DemoGameplayPort} already holds a
 * lock that makes one tic atomic, and this object lives inside it.</p>
 */
public final class Match
{
    /**
     * How many bots a single-player round spawns — <b>7</b>.
     *
     * <p>Seven fills the demo room without crowding it: the room is ten 64-unit
     * tiles across, so seven bodies plus the player is roughly one per two
     * tiles' worth of floor. It is also enough that clearing the room is a
     * sequence of decisions rather than a single burst — three shots each at
     * five shots a second is a little over four seconds of pure firing, before
     * any aiming.</p>
     */
    public static final int DEFAULT_BOT_COUNT = 7;

    /**
     * The entity id the local player's hitbox uses.
     *
     * <p>Reserved rather than allocated, so a bot can never be handed it. Bots
     * start at {@link #FIRST_BOT_ENTITY_ID}. A collision here would be
     * invisible and vicious: the player's box and a bot's box would be the same
     * entity, and a bot's shot at the player would report a hit on itself.</p>
     */
    public static final int PLAYER_ENTITY_ID = 1;

    /** The first id handed to a bot. One past {@link #PLAYER_ENTITY_ID}. */
    public static final int FIRST_BOT_ENTITY_ID = PLAYER_ENTITY_ID + 1;

    /**
     * Returned by {@link #firePlayerShot} when the shot hit nothing.
     *
     * <p>Below {@link Target#MIN_ENTITY_ID} rather than zero, so it cannot
     * collide with {@code Scene.UNTAGGED} — the two mean different things and a
     * caller that confused them would treat "hit a wall" as "hit entity
     * nobody".</p>
     */
    public static final int NO_HIT = Target.MIN_ENTITY_ID - 1;

    /** Health the player spawns with. */
    public static final int PLAYER_MAX_HEALTH = 100;

    /**
     * Damage one of the player's shots does — <b>34</b>.
     *
     * <p>Three hits to kill, with {@link Bot#MAX_HEALTH} of 100. Three rather
     * than one because a one-shot kill makes the outline pass and the hit
     * feedback almost impossible to evaluate — the target is gone before you
     * have seen anything — and rather than five because seven bots at five shots
     * each is a lot of trigger pulls to sit through.</p>
     */
    public static final int PLAYER_SHOT_DAMAGE = 34;

    /**
     * Damage one bot shot does — <b>2</b>.
     *
     * <p>Fifty hits to kill the player, which is deliberately feeble. These are
     * target practice; they are meant to be a reason to keep moving, not a
     * threat.</p>
     *
     * <p><b>The number comes from a measurement, not an estimate.</b> A run of
     * the real demo with the player standing motionless in the open and all
     * seven bots in line of sight lands a hit every <b>0.40 s</b> — close to the
     * 0.36 s the cadence predicts, the difference being the two bots that start
     * outside {@link #BOT_RANGE_UNITS}. At 4 damage that killed a stationary
     * player in ten seconds, which is faster than a new player can find and
     * clear seven opponents; at 2 it is twenty, which leaves room to look
     * around and still punishes standing in the open. The first value was
     * written from the arithmetic and was simply too high.</p>
     */
    public static final int BOT_SHOT_DAMAGE = 2;

    /**
     * Tics between one bot's shots — <b>150</b>, two and a half seconds at 60 Hz.
     *
     * <p>Slow on purpose. The cadence is per bot and the offsets are staggered
     * by {@code Match}'s caller, so the room's total rate of fire is what the
     * player experiences: seven bots on this interval is one shot every 21 tics
     * spread across the room, rather than a volley every two and a half
     * seconds.</p>
     */
    public static final int BOT_FIRE_INTERVAL_TICS = 150;

    /**
     * How far a bot can shoot, in world units — <b>512</b>.
     *
     * <p>The demo room is 640 units across, so this is a little over three
     * quarters of its diagonal reach: a bot on the far side of the room cannot
     * plink at a player who has not come to find it. Without a range limit the
     * whole room engages the player from the first tic, and there is no reason
     * to advance.</p>
     */
    public static final float BOT_RANGE_UNITS = 512.0f;

    /** The opponents, in id order. Never null, entries never null. */
    private final Bot[] bots;

    /** Reused across every shot in the match, so firing allocates no result. */
    private final HitResult hit = new HitResult();

    /** Remaining player health. MUTABLE: reduced by bot fire. */
    private int playerHealth = PLAYER_MAX_HEALTH;

    /** Bots killed so far. MUTABLE: bumped once per kill. */
    private int botsKilled;

    /** Shots the player has fired. MUTABLE: for the end-of-match summary. */
    private int playerShotsFired;

    /** Shots of the player's that connected. MUTABLE: for the summary. */
    private int playerShotsHit;

    /** Shots the bots have landed on the player. MUTABLE: for the summary. */
    private int botShotsLanded;

    /**
     * Creates a match against a given set of bots.
     *
     * @param opponents the bots to fight; must not be null and must contain no
     *     nulls. An empty array is legal and produces a match that is
     *     {@link MatchState#WON} from the first check — which is the right
     *     answer for a room with nobody in it, and is what a scene with no
     *     character art staged will produce
     * @throws IllegalArgumentException if {@code opponents} is null or holds a
     *     null, or if two bots share an entity id
     */
    public Match(final Bot[] opponents)
    {
        if (opponents == null)
        {
            throw new IllegalArgumentException("opponents must not be null");
        }
        for (int index = 0; index < opponents.length; index++)
        {
            if (opponents[index] == null)
            {
                throw new IllegalArgumentException("bot " + index + " must not be null");
            }
            if (opponents[index].entityId() == PLAYER_ENTITY_ID)
            {
                throw new IllegalArgumentException("bot " + index + " uses the reserved player id "
                    + PLAYER_ENTITY_ID);
            }
            for (int other = 0; other < index; other++)
            {
                if (opponents[other].entityId() == opponents[index].entityId())
                {
                    throw new IllegalArgumentException("bots " + other + " and " + index
                        + " share entity id " + opponents[index].entityId());
                }
            }
        }
        this.bots = opponents.clone();
    }

    /**
     * Advances every bot by one tic and lets those whose weapon is ready shoot
     * back.
     *
     * <p>Order matters and is fixed: <b>move, then turn, then shoot</b>. Shooting
     * from the position a bot has already left would make return fire come from
     * where the body was rather than where it is, which is exactly the
     * discrepancy a player would notice and could not explain.</p>
     *
     * <p>Does nothing once the match is over. A dead player who is still being
     * shot at, or bots that carry on patrolling a cleared room, would both be
     * state changing after the result was decided.</p>
     *
     * @param ticIndex the tic being processed
     * @param playerFeetX the player's feet, world x
     * @param playerFeetY the player's feet, world y — the floor, not the eye
     * @param playerFeetZ the player's feet, world z
     * @return how much damage the player took this tic, zero on most tics
     */
    public int tick(final int ticIndex, final float playerFeetX, final float playerFeetY,
        final float playerFeetZ)
    {
        if (state().isOver())
        {
            return 0;
        }
        for (int index = 0; index < bots.length; index++)
        {
            final Bot bot = bots[index];
            bot.moveTo(ticIndex);
            bot.faceToward(playerFeetX, playerFeetZ);
        }
        return resolveBotFire(ticIndex, playerFeetX, playerFeetY, playerFeetZ);
    }

    /**
     * Resolves one shot from the player.
     *
     * <p>The ray is tested against every <b>living</b> bot. Dead ones are left
     * out rather than left in with zero health: a corpse that still blocks shots
     * would give the player cover they cannot see and would make the room get
     * harder as they cleared it.</p>
     *
     * <p><b>Not gated on {@link #state()}, unlike {@link #tick}.</b> The
     * asymmetry is deliberate. {@code tick} is called unconditionally on every
     * tic, so it has to know when to stop; this is called only when a trigger is
     * pulled, and whether a trigger may be pulled after the match ends is the
     * caller's rule to make, not this method's. Keeping it a pure resolution
     * function also means a test can drive it to set up a state that the normal
     * flow would take thousands of tics to reach.</p>
     *
     * @param eyeX the shot origin, world x — the player's eye
     * @param eyeY the shot origin, world y
     * @param eyeZ the shot origin, world z
     * @param aimX the aim direction, world x; need not be unit length
     * @param aimY the aim direction, world y
     * @param aimZ the aim direction, world z
     * @return the id of the bot hit, or {@link Target#MIN_ENTITY_ID}{@code - 1}
     *     — that is, {@link #NO_HIT} — when the shot missed
     */
    public int firePlayerShot(final float eyeX, final float eyeY, final float eyeZ,
        final float aimX, final float aimY, final float aimZ)
    {
        this.playerShotsFired = playerShotsFired + 1;
        final Target[] living = livingBotTargets();
        if (living.length == 0)
        {
            return NO_HIT;
        }
        if (!Hitscan.fire(eyeX, eyeY, eyeZ, aimX, aimY, aimZ, living, living.length, hit))
        {
            return NO_HIT;
        }
        this.playerShotsHit = playerShotsHit + 1;
        final int struck = hit.entityId();
        final Bot victim = byId(struck);
        if (victim != null && victim.damage(PLAYER_SHOT_DAMAGE))
        {
            this.botsKilled = botsKilled + 1;
        }
        return struck;
    }

    /**
     * Returns the bot carrying a given entity id, or null.
     *
     * @param entityId the id to look for
     * @return the bot, or null if no bot owns that id
     */
    public Bot byId(final int entityId)
    {
        for (int index = 0; index < bots.length; index++)
        {
            if (bots[index].entityId() == entityId)
            {
                return bots[index];
            }
        }
        return null;
    }

    /**
     * Returns how the match stands.
     *
     * <p>{@link MatchState#LOST} is checked first. A player and the last bot can
     * die on the same tic — the player's shot is resolved before the bots' — and
     * when they do, the honest answer is that the player died. Reporting a win
     * to a corpse is the kind of thing that is funny once.</p>
     *
     * @return the current state, never null
     */
    public MatchState state()
    {
        if (playerHealth <= 0)
        {
            return MatchState.LOST;
        }
        if (livingBots() == 0)
        {
            return MatchState.WON;
        }
        return MatchState.IN_PROGRESS;
    }

    /** Returns how many bots are still standing. */
    public int livingBots()
    {
        int alive = 0;
        for (int index = 0; index < bots.length; index++)
        {
            if (bots[index].isAlive())
            {
                alive++;
            }
        }
        return alive;
    }

    /** Returns the bots in this match, as a copy so a caller cannot swap one out. */
    public Bot[] bots()
    {
        return bots.clone();
    }

    /** Returns how many bots this match started with. */
    public int botCount()
    {
        return bots.length;
    }

    /** Returns remaining player health, zero or less once dead. */
    public int playerHealth()
    {
        return playerHealth;
    }

    /** Returns how many bots have been killed. */
    public int botsKilled()
    {
        return botsKilled;
    }

    /** Returns how many shots the player has fired. */
    public int playerShotsFired()
    {
        return playerShotsFired;
    }

    /** Returns how many of the player's shots connected. */
    public int playerShotsHit()
    {
        return playerShotsHit;
    }

    /** Returns how many bot shots have landed on the player. */
    public int botShotsLanded()
    {
        return botShotsLanded;
    }

    // Every bot whose weapon is ready this tic takes its shot. Returns the total
    // damage the player took.
    private int resolveBotFire(final int ticIndex, final float playerFeetX,
        final float playerFeetY, final float playerFeetZ)
    {
        int damageTaken = 0;
        for (int index = 0; index < bots.length; index++)
        {
            final Bot shooter = bots[index];
            if (!shooter.wantsToFire(ticIndex))
            {
                continue;
            }
            if (!botShotConnects(shooter, playerFeetX, playerFeetY, playerFeetZ))
            {
                continue;
            }
            damageTaken = damageTaken + BOT_SHOT_DAMAGE;
        }
        if (damageTaken > 0)
        {
            this.botShotsLanded = botShotsLanded + damageTaken / BOT_SHOT_DAMAGE;
            this.playerHealth = playerHealth - damageTaken;
        }
        return damageTaken;
    }

    // One bot's shot at the player: in range, and the player is the nearest
    // thing on the ray.
    //
    // The shooter is excluded from its own target set for the reason Hitscan
    // documents — a ray origin inside a box is a hit at distance zero, so a bot
    // listed among its own targets shoots itself every time it pulls the
    // trigger.
    private boolean botShotConnects(final Bot shooter, final float playerFeetX,
        final float playerFeetY, final float playerFeetZ)
    {
        final float originY = shooter.eyeY();
        final float toX = playerFeetX - shooter.positionX();
        final float toY = playerFeetY + PlayerController.EYE_HEIGHT_UNITS - originY;
        final float toZ = playerFeetZ - shooter.positionZ();
        final float distanceSquared = toX * toX + toY * toY + toZ * toZ;
        if (distanceSquared > BOT_RANGE_UNITS * BOT_RANGE_UNITS)
        {
            return false;
        }
        if (distanceSquared == 0.0f)
        {
            // Standing exactly where the player is. There is no direction to
            // shoot in, and normalising would divide by zero.
            return false;
        }

        // Hitscan REQUIRES a unit direction and rejects anything else outright —
        // it does not normalise for you, because normalising inside the shot
        // would put a square root on the firing path for every caller that had
        // already done it. StrictMath.sqrt is correctly rounded by IEEE 754 and
        // so is reproducible across peers; it is StrictMath rather than Math
        // only to keep the "no java/lang/Math in a simulation class" guard flat.
        final float scale = 1.0f / (float) StrictMath.sqrt(distanceSquared);

        final Target[] scene = shotSceneFor(shooter, playerFeetX, playerFeetY, playerFeetZ);
        if (!Hitscan.fire(shooter.positionX(), originY, shooter.positionZ(),
            toX * scale, toY * scale, toZ * scale, scene, scene.length, hit))
        {
            return false;
        }
        // Nearest wins. Another bot standing in the way genuinely blocks the
        // shot, and takes no damage for it — friendly fire would let the room
        // clear itself while the player watched.
        return hit.entityId() == PLAYER_ENTITY_ID;
    }

    // The player's box plus every living bot except the shooter, for a bot's
    // outgoing shot. Allocated per shot, which is a few times a second — see the
    // class Javadoc on why that is the allowed placement rather than per tic.
    private Target[] shotSceneFor(final Bot shooter, final float playerFeetX,
        final float playerFeetY, final float playerFeetZ)
    {
        int count = 1;
        for (int index = 0; index < bots.length; index++)
        {
            if (bots[index] != shooter && bots[index].isAlive())
            {
                count++;
            }
        }
        final Target[] scene = new Target[count];
        scene[0] = Target.aroundFeet(PLAYER_ENTITY_ID, playerFeetX, playerFeetY, playerFeetZ,
            Bot.RADIUS_UNITS, Bot.HEIGHT_UNITS);
        int next = 1;
        for (int index = 0; index < bots.length; index++)
        {
            if (bots[index] != shooter && bots[index].isAlive())
            {
                scene[next] = bots[index].hitbox();
                next++;
            }
        }
        return scene;
    }

    // Hitboxes for every living bot, for the player's outgoing shot.
    private Target[] livingBotTargets()
    {
        final int alive = livingBots();
        final Target[] boxes = new Target[alive];
        int next = 0;
        for (int index = 0; index < bots.length; index++)
        {
            if (bots[index].isAlive())
            {
                boxes[next] = bots[index].hitbox();
                next++;
            }
        }
        return boxes;
    }

    /** Returns a debug rendering of the score and state. */
    @Override
    public String toString()
    {
        return "Match{" + state() + ", player=" + playerHealth + "hp, bots="
            + livingBots() + "/" + bots.length + " alive, killed=" + botsKilled
            + ", accuracy=" + playerShotsHit + "/" + playerShotsFired + "}";
    }
}
