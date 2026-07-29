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
 * the health on both sides, moves the bots each tic, lets them shoot back,
 * resolves the player's shots, respawns the player, and keeps the score. It owns
 * no rendering, no input and no networking — {@code DemoGameplayPort} drives it
 * and reads the results.</p>
 *
 * <h2>Dying is a score, not an ending</h2>
 *
 * <p>{@link MatchState#LOST} used to mean "the player's health reached zero
 * once", which made every death terminal. It no longer does. A dead player is
 * <b>respawned</b> after {@link #RESPAWN_DELAY_TICS}, the death is counted, and
 * the round carries on. What ends a round is clearing the room: bots do not
 * respawn, so the place empties as the player works through it and the run has a
 * finish the player earned rather than survived.</p>
 *
 * <p>There is still a losing condition available — {@link #deathLimit()} — but
 * it defaults to {@link #UNLIMITED_DEATHS} and the shipped demo does not use it.
 * The reason it exists rather than being deleted: "deaths are unlimited" is a
 * <i>decision</i>, and a decision with no constant behind it is
 * indistinguishable from an oversight the next time someone reads the file.</p>
 *
 * <h2>Both directions of fire go through the same {@link Hitscan}</h2>
 *
 * <p>A bot's shot is not a dice roll against a distance. It is a ray from the
 * bot's eye toward where it last thought the player was, scattered by a random
 * angle, tested against every other body in the room, and it lands only if the
 * player is the <b>nearest</b> thing it meets. So a bot standing between the
 * shooter and the player eats the shot, the player can genuinely use another bot
 * as cover, and a bot at the far side of the room misses far more often than one
 * at the player's elbow — because a fixed angular error is a bigger miss the
 * further it has to travel, which is how missing actually works.</p>
 *
 * <p>That was worth the extra work for a reason beyond the emergent behaviour:
 * the return fire exercises the same tested code path as the player's own
 * weapon, at a much higher volume than a human can produce.</p>
 *
 * <h2>The randomness is seeded, and that is not optional</h2>
 *
 * <p>Every random decision in this class comes from {@link BotRng}, which is
 * seeded, stateless, and addressed by {@code (tic, entity, channel)}. The seed
 * is injectable — see the constructors — so a test can pin it and so a networked
 * match can agree one across peers.</p>
 *
 * <p><b>{@code Math.random()}, {@code new Random()}, {@code System.nanoTime()}
 * and anything else derived from a clock or from thread scheduling are forbidden
 * anywhere on this path.</b> {@code engine/net/README.md} commits the engine to
 * deterministic lockstep: peers exchange inputs and nothing else, so one
 * unshared random number makes a bot fire on one machine and not on another, and
 * the two simulations diverge silently from there. {@link BotRng}'s Javadoc has
 * the full list and the reasoning; this note is here because the place a future
 * contributor will reach for {@code Math.random()} is exactly this file.</p>
 *
 * <h2>Where the allocation is, and why it is allowed</h2>
 *
 * <p>{@link Target} is immutable and bots move, so a bot's hitbox cannot be
 * built once at spawn — it has to be rebuilt from its current position.
 * {@code STYLE.md} § 13.4 bans allocation on a per-tic path, so the boxes are
 * built <b>only on the tics where somebody actually fires</b>: a few a second
 * between the player's five shots and the bots' staggered rolls, rather than
 * sixty times a second for nothing. A tic where no trigger is pulled allocates
 * nothing at all.</p>
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

    /** Health the player spawns with, and respawns with. */
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
     * Damage one bot shot does — <b>10</b>.
     *
     * <p>Ten hits to kill the player, and <b>the number was re-derived when the
     * bots stopped being able to aim.</b> It used to be 2, against opponents who
     * hit every single shot inside {@link #BOT_RANGE_UNITS} on a fixed 150-tic
     * cadence — a hit every 0.40 s with all seven in sight, measured, which at 2
     * damage killed a motionless player in twenty seconds.</p>
     *
     * <p><b>That measurement is void and this one replaces it.</b> With
     * {@link BotSkill#DUMB} the room still <i>fires</i> at very nearly the old
     * rate — seven bots roll a shot roughly every 19 tics between them, against
     * the old 21 — but only about one shot in eight lands at mid range, because
     * a shot is now a scattered ray rather than a certainty.
     * {@code MatchTest.Balance} measures the result in the real demo room with
     * the player standing motionless at the spawn point, and pins it so this
     * paragraph cannot go stale again.</p>
     *
     * <p>Ten also makes a single hit <b>legible</b>, which 2 never was: a tenth
     * of the health bar is something a player notices and can attribute to the
     * bot that just fired. The whole point of making the bots miss is that the
     * shots which do land have to mean something.</p>
     */
    public static final int BOT_SHOT_DAMAGE = 10;

    /**
     * How far a bot can shoot, in world units — <b>512</b>.
     *
     * <p>The demo room is 640 units across, so this is a little over three
     * quarters of its diagonal reach: a bot on the far side of the room cannot
     * plink at a player who has not come to find it. Without a range limit the
     * whole room engages the player from the first tic, and there is no reason
     * to advance.</p>
     *
     * <p>Measured against the position the bot <b>remembers</b> the player at,
     * not the real one, because everything else about a bot's shot is. A bot
     * whose range check used current information and whose aim used stale
     * information would be a hybrid nobody could reason about.</p>
     */
    public static final float BOT_RANGE_UNITS = 512.0f;

    /**
     * Tics between the player dying and standing up again — <b>120</b>, two
     * seconds at 60 Hz.
     *
     * <p>Measured in <b>tics and never in milliseconds</b>, which is why this
     * constant is worth a paragraph. The simulation is tic-driven and every peer
     * runs the same {@code GameConfig}, so a delay counted in tics elapses on
     * the same tic on every machine. A delay counted against a wall clock would
     * elapse on <i>different tics</i> on two peers — the player standing up in
     * one simulation and still on the floor in the other — which is the same
     * class of desync {@link BotRng} exists to prevent, arriving by a different
     * door.</p>
     *
     * <p>Two seconds rather than none: an instant respawn makes death
     * meaningless, and the player needs long enough to read that they died. It
     * is also long enough for the on-screen notice to be seen rather than
     * flashed.</p>
     */
    public static final int RESPAWN_DELAY_TICS = 120;

    /**
     * The value of {@link #deathLimit()} that means the player cannot lose.
     *
     * <p>Zero, and it is the default. Deaths are a <b>score</b> in this demo:
     * counted, shown while playing and on the end screen, and ending nothing.
     * The room emptying is what ends a round.</p>
     *
     * <p>A named constant rather than a bare zero because "unlimited" is a
     * decision somebody has to be able to find and argue with. A build wanting a
     * life count passes one here and gets {@link MatchState#LOST} back on the
     * death that reaches it.</p>
     */
    public static final int UNLIMITED_DEATHS = 0;

    /** One full turn in radians, for the wild shot's arbitrary heading. */
    private static final float FULL_TURN_RADIANS = (float) (2.0 * StrictMath.PI);

    /** The opponents, in id order. Never null, entries never null. */
    private final Bot[] bots;

    /** Reused across every shot in the match, so firing allocates no result. */
    private final HitResult hit = new HitResult();

    /** Where every random decision comes from. Never null, immutable. */
    private final BotRng rng;

    /** How badly the opponents shoot. Never null, immutable. */
    private final BotSkill skill;

    /** Deaths before the round is lost, or {@link #UNLIMITED_DEATHS}. */
    private final int deathLimit;

    /** Remaining player health. MUTABLE: reduced by bot fire, restored on respawn. */
    private int playerHealth = PLAYER_MAX_HEALTH;

    /** Bots killed so far. MUTABLE: bumped once per kill. */
    private int botsKilled;

    /** Times the player has been killed. MUTABLE: bumped once per death. */
    private int playerDeaths;

    /** Shots the player has fired. MUTABLE: for the end-of-match summary. */
    private int playerShotsFired;

    /** Shots of the player's that connected. MUTABLE: for the summary. */
    private int playerShotsHit;

    /** Shots the bots have taken. MUTABLE: the denominator of their hit rate. */
    private int botShotsFired;

    /** Shots the bots have landed on the player. MUTABLE: for the summary. */
    private int botShotsLanded;

    /**
     * The tic the player stands up again, meaningful only while
     * {@link #isPlayerDown()}. MUTABLE.
     */
    private int respawnAtTic;

    /**
     * Whether the player is waiting to respawn. MUTABLE.
     *
     * <p>A flag rather than "health is zero", because the two differ for one
     * tic: the respawn restores the health and clears this in the same call, and
     * a reader inferring the state from health alone would see a live player at
     * full health who had not yet been moved back to the spawn point.</p>
     */
    private boolean playerDown;

    /**
     * Set on the tic the player stands up, cleared by
     * {@link #consumePlayerRespawned()}. MUTABLE.
     *
     * <p>This is the seam between the rules and the world. {@code Match} owns
     * <i>when</i> a respawn happens, because that is deterministic tic
     * arithmetic; it does not own the {@link PlayerController} and has no
     * business knowing where a spawn point is. So it raises a flag and
     * {@code DemoGameplayPort} — which owns both — puts the body back.</p>
     */
    private boolean respawnedThisTic;

    /**
     * Creates a match against a given set of bots, with the default seed and the
     * dumb opponents the demo ships.
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
        this(opponents, new BotRng(), BotSkill.DUMB, UNLIMITED_DEATHS);
    }

    /**
     * Creates a match with an explicit generator, skill profile and death limit.
     *
     * <p>The seam tests use, and the seam a networked match would use to agree
     * one seed across peers. See {@link BotRng} on why the seed has to be shared
     * rather than derived per process.</p>
     *
     * @param opponents the bots to fight; same rules as {@link #Match(Bot[])}
     * @param generator where every random decision comes from; must not be null
     * @param botSkill how badly the opponents shoot; must not be null.
     *     {@link BotSkill#MARKSMAN} makes return fire deterministic again, which
     *     is what the geometry tests want
     * @param deathsAllowed deaths before the round is {@link MatchState#LOST},
     *     or {@link #UNLIMITED_DEATHS}; must not be negative
     * @throws IllegalArgumentException if any argument is out of range
     */
    public Match(final Bot[] opponents, final BotRng generator, final BotSkill botSkill,
        final int deathsAllowed)
    {
        if (opponents == null)
        {
            throw new IllegalArgumentException("opponents must not be null");
        }
        if (generator == null)
        {
            throw new IllegalArgumentException("generator must not be null");
        }
        if (botSkill == null)
        {
            throw new IllegalArgumentException("botSkill must not be null");
        }
        if (deathsAllowed < 0)
        {
            throw new IllegalArgumentException(
                "deathsAllowed must not be negative, got " + deathsAllowed);
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
        this.rng = generator;
        this.skill = botSkill;
        this.deathLimit = deathsAllowed;
    }

    /**
     * Puts this match back exactly as a freshly constructed one: every bot up,
     * every counter zero, the player at full health.
     *
     * <h2>Why a reset rather than a rebuilt {@code Match}</h2>
     *
     * <p>A rebuild looks like the cleaner answer and is not, because of one
     * constraint that is not negotiable: <b>{@code Scene} is immutable and a
     * bot's model occupies a fixed instance index assigned when the scene was
     * built</b>. Nothing can be added to a scene afterwards. So a rebuilt match
     * cannot have new {@link Bot} objects — it would have to be handed the same
     * seven, which are still dead, still lying where they fell, and still
     * carrying the previous round's cooldowns and memories. A rebuild therefore
     * needs a per-bot reset anyway, and having written that, the only thing the
     * rebuild adds is a new object identity.</p>
     *
     * <p>That identity is a liability rather than a benefit. The gameplay port's
     * match field is {@code final}, and the launcher's end-of-round supplier
     * closes over the port and reads it from the <b>render thread</b> while the
     * game loop thread ticks it. Swapping the object would mean a volatile
     * field, a re-publication, and a window in which the two threads are looking
     * at different rounds. Resetting in place has no such window: every counter
     * goes to zero under the same tic lock that guards every other write to
     * it.</p>
     *
     * <p>The invariant worth testing is therefore not "a new object was made"
     * but <b>"a reset match is indistinguishable from a freshly started
     * one"</b>, which is what {@code MatchTest} asserts — against a newly
     * constructed match rather than against a list somebody remembered to
     * update, so a field added later and not reset here fails it.</p>
     *
     * <p>What this does <b>not</b> reset, because it does not own them: the
     * player's position, yaw and pitch, and the in-flight tracers and smoke.
     * Those belong to {@link PlayerController} and {@code DemoEffects}, and
     * {@code DemoGameplayPort.restartMatch} restores all three together.</p>
     */
    public void reset()
    {
        for (int index = 0; index < bots.length; index++)
        {
            bots[index].reset();
        }
        this.playerHealth = PLAYER_MAX_HEALTH;
        this.botsKilled = 0;
        this.playerDeaths = 0;
        this.playerShotsFired = 0;
        this.playerShotsHit = 0;
        this.botShotsFired = 0;
        this.botShotsLanded = 0;
        this.respawnAtTic = 0;
        this.playerDown = false;
        this.respawnedThisTic = false;
    }

    /**
     * Advances every bot by one tic and lets those whose weapon is ready shoot
     * back.
     *
     * <p>Order matters and is fixed: <b>move, then notice, then turn, then
     * shoot</b>. Shooting from the position a bot has already left would make
     * return fire come from where the body was rather than where it is, which is
     * exactly the discrepancy a player notices and cannot explain. The
     * <i>target</i>, by contrast, is deliberately stale — see
     * {@link Bot#observePlayer}.</p>
     *
     * <p>A dead player is not shot at. The bots keep patrolling, so the room is
     * alive while the respawn counts down, but they do not fire: emptying
     * magazines into a corpse would run the death counter away and make the two
     * seconds after a death the most dangerous part of the round.</p>
     *
     * <p>Does nothing once the match is over. Bots patrolling a cleared room
     * would be state changing after the result was decided.</p>
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
        this.respawnedThisTic = false;
        if (state().isOver())
        {
            return 0;
        }
        for (int index = 0; index < bots.length; index++)
        {
            bots[index].moveTo(ticIndex);
        }
        if (playerDown)
        {
            // The bodies keep walking and keep facing whatever they last knew,
            // so the room does not freeze while the player is on the floor.
            faceAll();
            advanceRespawn(ticIndex);
            return 0;
        }
        for (int index = 0; index < bots.length; index++)
        {
            bots[index].observePlayer(ticIndex, playerFeetX, playerFeetZ, skill);
        }
        faceAll();
        return resolveBotFire(ticIndex, playerFeetX, playerFeetY, playerFeetZ);
    }

    // Every living bot turns toward what it last knew. Separate from the
    // observation loop because a player who is down is not observed but is still
    // faced — a room of bodies frozen mid-turn reads as a crash.
    private void faceAll()
    {
        for (int index = 0; index < bots.length; index++)
        {
            bots[index].faceRemembered();
        }
    }

    // Counts down the respawn and stands the player up on the tic it expires.
    //
    // Compared with >= rather than ==, so a caller that skips a tic — which the
    // loop is entitled to do, since Match is driven by an index rather than by
    // an increment — does not leave the player on the floor forever.
    private void advanceRespawn(final int ticIndex)
    {
        if (ticIndex < respawnAtTic)
        {
            return;
        }
        this.playerHealth = PLAYER_MAX_HEALTH;
        this.playerDown = false;
        this.respawnedThisTic = true;
    }

    /**
     * Resolves one shot from the player.
     *
     * <p>The ray is tested against every <b>living</b> bot. Dead ones are left
     * out rather than left in with zero health: a corpse that still blocked
     * shots would give the player cover they cannot see and would make the room
     * get harder as they cleared it.</p>
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
     * @return the id of the bot hit, or {@link #NO_HIT} when the shot missed
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
     * <p><b>{@link MatchState#LOST} is checked first, and now almost never
     * happens.</b> It requires a {@link #deathLimit()} to have been set and
     * reached; with {@link #UNLIMITED_DEATHS} — the default, and what the demo
     * ships — a player who has died nine times is still simply in progress. The
     * check stays first for the case that used to matter and still would with a
     * limit set: the player and the last bot can be finished on the same tic,
     * and reporting a win to somebody who has just spent their last life is the
     * kind of thing that is funny once.</p>
     *
     * @return the current state, never null
     */
    public MatchState state()
    {
        if (deathLimit != UNLIMITED_DEATHS && playerDeaths >= deathLimit)
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

    /** Returns remaining player health, zero while waiting to respawn. */
    public int playerHealth()
    {
        return playerHealth;
    }

    /** Returns how many bots have been killed. */
    public int botsKilled()
    {
        return botsKilled;
    }

    /** Returns how many times the player has been killed. */
    public int playerDeaths()
    {
        return playerDeaths;
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

    /**
     * Returns how many shots the bots have taken.
     *
     * <p>The denominator of their hit rate, and the reason it is recorded at
     * all: "the bots miss most of the time" is a claim, and without a count of
     * the shots that were <i>taken</i> there is nothing to check it against.
     * {@code MatchTest} asserts the ratio rather than trusting the spread
     * angle.</p>
     *
     * @return every trigger pull by every bot since the round began
     */
    public int botShotsFired()
    {
        return botShotsFired;
    }

    /** Returns how many bot shots have landed on the player. */
    public int botShotsLanded()
    {
        return botShotsLanded;
    }

    /** Returns whether the player is on the floor waiting to respawn. */
    public boolean isPlayerDown()
    {
        return playerDown;
    }

    /**
     * Returns how many tics remain before the player stands up.
     *
     * <p>For the on-screen notice, which counts down rather than sitting there:
     * a static "you died" is indistinguishable from a hung game.</p>
     *
     * @param ticIndex the tic being processed
     * @return tics remaining, or zero when the player is not down
     */
    public int respawnTicsRemaining(final int ticIndex)
    {
        if (!playerDown || ticIndex >= respawnAtTic)
        {
            return 0;
        }
        return respawnAtTic - ticIndex;
    }

    /**
     * Reports whether the player stood up on the most recent tic, and clears the
     * flag.
     *
     * <p>A consuming read, so exactly one caller acts on each respawn — the
     * caller has to move a body, and moving it twice would be harmless only by
     * luck. See {@link #respawnedThisTic} for why the rule and the world are
     * split across two objects at all.</p>
     *
     * @return true once per respawn
     */
    public boolean consumePlayerRespawned()
    {
        final boolean respawned = respawnedThisTic;
        this.respawnedThisTic = false;
        return respawned;
    }

    /** Returns how badly this match's opponents shoot. Never null. */
    public BotSkill skill()
    {
        return skill;
    }

    /** Returns the generator every random decision comes from. Never null. */
    public BotRng rng()
    {
        return rng;
    }

    /** Returns the deaths allowed before the round is lost, or {@link #UNLIMITED_DEATHS}. */
    public int deathLimit()
    {
        return deathLimit;
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
            if (!shooter.wantsToFire(ticIndex, rng, skill))
            {
                continue;
            }
            this.botShotsFired = botShotsFired + 1;
            if (!botShotConnects(shooter, ticIndex, playerFeetX, playerFeetY, playerFeetZ))
            {
                continue;
            }
            damageTaken = damageTaken + BOT_SHOT_DAMAGE;
        }
        if (damageTaken > 0)
        {
            this.botShotsLanded = botShotsLanded + damageTaken / BOT_SHOT_DAMAGE;
            this.playerHealth = playerHealth - damageTaken;
            if (playerHealth <= 0)
            {
                killPlayer(ticIndex);
            }
        }
        return damageTaken;
    }

    // The player goes down. Counted once, and the clock for standing up again
    // starts here rather than when the UI notices — the UI runs on another
    // thread at the display's rate, and a respawn timed off it would elapse on a
    // different tic on every machine.
    private void killPlayer(final int ticIndex)
    {
        this.playerHealth = 0;
        this.playerDeaths = playerDeaths + 1;
        this.playerDown = true;
        this.respawnAtTic = ticIndex + RESPAWN_DELAY_TICS;
    }

    // One bot's shot at the player: in range of what it remembers, aimed badly,
    // and landing only if the player is the nearest thing on the resulting ray.
    //
    // The shooter is excluded from its own target set for the reason Hitscan
    // documents — a ray origin inside a box is a hit at distance zero, so a bot
    // listed among its own targets shoots itself every time it pulls the
    // trigger.
    private boolean botShotConnects(final Bot shooter, final int ticIndex,
        final float playerFeetX, final float playerFeetY, final float playerFeetZ)
    {
        if (!shooter.hasSeenPlayer())
        {
            return false;
        }
        final float toX = shooter.rememberedPlayerX() - shooter.positionX();
        final float toZ = shooter.rememberedPlayerZ() - shooter.positionZ();
        final float groundDistanceSquared = toX * toX + toZ * toZ;
        if (groundDistanceSquared > BOT_RANGE_UNITS * BOT_RANGE_UNITS)
        {
            return false;
        }
        if (groundDistanceSquared == 0.0f)
        {
            // Standing exactly where it thinks the player is. There is no
            // direction to shoot in, and atan2(0, 0) would answer zero — which
            // is a real heading and would therefore be a lie.
            return false;
        }

        // The shot is aimed LEVEL, at where the bot last thought the player
        // stood. Nothing about the player's height is remembered and nothing
        // needs to be: the room's floor is flat, every eye is at the same 41
        // units, and a level shot from one eye toward another passes through the
        // chest of a 56-unit body. Modelling the vertical would be modelling a
        // constant.
        final int id = shooter.entityId();
        final float aimYaw = shotYaw(shooter, ticIndex, toX, toZ)
            + rng.symmetric(ticIndex, id, BotRng.CHANNEL_AIM_YAW, skill.aimSpreadRadians());
        final float aimPitch =
            rng.symmetric(ticIndex, id, BotRng.CHANNEL_AIM_PITCH, skill.aimSpreadRadians());

        // PlayerController's own basis, so a bot's shot and a player's shot mean
        // the same thing by "forward". Unit length by construction, which
        // matters: Hitscan REQUIRES a unit direction and rejects anything else
        // outright rather than normalising for its callers.
        final float cosPitch = (float) StrictMath.cos(aimPitch);
        final float dirX = cosPitch * (float) StrictMath.sin(aimYaw);
        final float dirY = (float) StrictMath.sin(aimPitch);
        final float dirZ = cosPitch * (float) StrictMath.cos(aimYaw);

        final Target[] scene = shotSceneFor(shooter, playerFeetX, playerFeetY, playerFeetZ);
        if (!Hitscan.fire(shooter.positionX(), shooter.eyeY(), shooter.positionZ(),
            dirX, dirY, dirZ, scene, scene.length, hit))
        {
            return false;
        }
        // Nearest wins. Another bot standing in the way genuinely blocks the
        // shot, and takes no damage for it — friendly fire would let the room
        // clear itself while the player watched.
        return hit.entityId() == PLAYER_ENTITY_ID;
    }

    // Which way a shot goes before the scatter is added: at the remembered
    // player, or — on a wild shot — at nothing whatsoever.
    //
    // The wild shot is the difference between an opponent that is inaccurate and
    // one that is STUPID. It is fired with no line of sight, possibly while
    // facing the other way, because the bot decided to shoot rather than because
    // it saw anything. It very nearly always misses, and that is the point: a
    // room where every shot is at least pointed at you is a room where you are
    // permanently under threat, and these are meant to be target practice.
    private float shotYaw(final Bot shooter, final int ticIndex, final float toX,
        final float toZ)
    {
        final int id = shooter.entityId();
        if (rng.chance(ticIndex, id, BotRng.CHANNEL_WILD, skill.wildShotChancePermille()))
        {
            return rng.unitFloat(ticIndex, id, BotRng.CHANNEL_WILD) * FULL_TURN_RADIANS;
        }
        // PlayerController's convention: yaw 0 faces world +z and increases
        // toward +x, hence atan2(dx, dz). Getting it backwards mirrors the room.
        return (float) StrictMath.atan2(toX, toZ);
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
            + livingBots() + "/" + bots.length + " alive, kills=" + botsKilled
            + ", deaths=" + playerDeaths + ", accuracy=" + playerShotsHit + "/"
            + playerShotsFired + ", return fire=" + botShotsLanded + "/" + botShotsFired + "}";
    }
}
