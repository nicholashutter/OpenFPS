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
 * <h2>Three kills in one life earn a bigger gun</h2>
 *
 * <p>{@link #SUPER_BLASTER_KILL_STREAK} kills <b>without dying</b> arm the super
 * blaster for {@link #SUPER_BLASTER_TICS} tics, during which the player's shot
 * does {@link #SUPER_BLASTER_SHOT_DAMAGE} — exactly twice
 * {@link #PLAYER_SHOT_DAMAGE}, so a bot falls in two hits instead of three. A
 * death takes both the streak and any live buff with it, because "kill streak" is
 * a claim about one life and a reward that outlived the life that earned it would
 * be a reward for nothing.</p>
 *
 * <p>The duration is in <b>tics</b>, for the reason
 * {@link #RESPAWN_DELAY_TICS} spells out at length. It is a countdown rather than
 * an absolute expiry tic, which is the one place this differs from the respawn —
 * see {@link #SUPER_BLASTER_TICS}.</p>
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
     * The first id handed to a remote peer's body, one past the whole bot block.
     *
     * <p><b>This class allocates it without using it</b>, and that is the point:
     * the entity id space has to have exactly one owner, or two features that
     * each pick "the next free id" independently will eventually pick the same
     * one. {@code Match} is that owner because it already reserves
     * {@link #PLAYER_ENTITY_ID} and hands out the bot block; a remote body is
     * tagged by {@code DemoScene} rather than here, since it is a scene instance
     * the match does not simulate.</p>
     *
     * <p>Reserved for {@code Constants.MAX_PLAYERS - 1} peers, the same bound
     * {@code NetSession.MAX_PEERS} enforces. Sized off
     * {@link #DEFAULT_BOT_COUNT} rather than the live roster because an id block
     * must be a compile-time constant — a scene built with fewer bots would
     * otherwise shift every remote id and make a saved reference to one mean a
     * different body.</p>
     */
    public static final int FIRST_REMOTE_ENTITY_ID = FIRST_BOT_ENTITY_ID + DEFAULT_BOT_COUNT;

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
     * Damage one bot shot does — <b>20</b>. Five hits to kill.
     *
     * <p><b>The number was re-derived from scratch when the bots stopped being
     * able to aim, and the old measurement is deleted rather than left to rot.</b>
     * It used to be 2, against opponents who hit every single shot inside
     * {@link #BOT_RANGE_UNITS} on a fixed 150-tic cadence — "a hit every 0.40 s
     * with all seven in sight", which at 2 damage killed a motionless player in
     * twenty seconds. Every clause of that described a bot that could shoot
     * straight, and none of it is true any more.</p>
     *
     * <h2>The replacement measurement</h2>
     *
     * <p>Taken from {@code MatchTest.Balance}, which pins it so this cannot go
     * stale again: seven {@link BotSkill#DUMB} bots spread between 100 and 400
     * units, all in line of sight, player standing motionless in the open — the
     * worst position a player can put themselves in.</p>
     *
     * <pre>
     *   the room fires once every       18 tics   (old fixed cadence: 21)
     *   of those shots, landing          6 %
     *   so a hit lands every           4.5 s
     *   and a motionless player dies in 22 s      (old figure: 20 s)
     * </pre>
     *
     * <p><b>Which is the whole point of the exercise: the room is as dangerous as
     * it was, while missing 94% of what it fires.</b> The threat is unchanged and
     * the <i>character</i> of it is completely different — it used to be an
     * unavoidable trickle, and it is now a stream of near-misses with an
     * occasional heavy hit in it. That is a fight a player can read and can do
     * something about.</p>
     *
     * <p>The hit rate falls sharply with distance, which is a consequence of
     * scattering the ray rather than rolling against the outcome, and is what
     * makes closing on a bot a decision rather than a formality:</p>
     *
     * <pre>
     *    80 units   100 %      300 units    5 %
     *   150 units    29 %      460 units    2 %
     *   200 units    20 %
     * </pre>
     *
     * <p>Twenty rather than ten — which is where this landed first — because at
     * ten the same motionless player survived <b>48 seconds</b>, and something
     * that takes three quarters of a minute to kill you while you ignore it is
     * scenery. Twenty also divides {@link #PLAYER_MAX_HEALTH} exactly, so "five
     * hits" is a fact rather than a rounding, and it makes an individual hit
     * <b>legible</b> in a way 2 never was: a fifth of the health bar is something
     * a player notices and can attribute to the bot that just fired. The whole
     * point of making the bots miss is that the shots which do land have to mean
     * something.</p>
     */
    public static final int BOT_SHOT_DAMAGE = 20;

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
     * Kills in one life that earn the super blaster — <b>3</b>.
     *
     * <p><b>Without dying, and a death resets the count to zero.</b> That is the
     * decision, and it is the one a player already believes: a "streak" that
     * survived being killed would be a running total with a misleading name, and
     * the counter would tick over three deaths later with nothing on screen to
     * explain why the gun had changed. The rule also has teeth in the other
     * direction — it makes staying alive worth something in a demo where dying
     * costs two seconds and nothing else.</p>
     *
     * <p>Three rather than two, because two is most of a magazine and would arm
     * the reward almost every time the player engaged anybody; and rather than
     * five, because the room only holds {@link #DEFAULT_BOT_COUNT} bots — a
     * five-kill streak in a seven-bot room is a reward for having very nearly
     * finished, which is the wrong moment to hand somebody a better gun.</p>
     */
    public static final int SUPER_BLASTER_KILL_STREAK = 3;

    /**
     * How long the super blaster lasts, in <b>tics</b> — <b>240</b>, four seconds
     * at 60 Hz.
     *
     * <p>Tics and <b>never milliseconds</b>, for exactly the reason
     * {@link #RESPAWN_DELAY_TICS} gives: every peer runs the same
     * {@code GameConfig}, so a duration counted in tics expires on the same tic on
     * every machine, while one counted against a wall clock expires on
     * <i>different</i> tics on two peers. That is not a cosmetic difference here —
     * it is a shot doing 68 damage on one peer and 34 on the other, which is the
     * same class of desync {@link BotRng} exists to prevent arriving by a
     * different door.</p>
     *
     * <p>Four seconds is twenty shots at the demo's twelve-tic rate of fire, which
     * is the figure the number was chosen against: long enough to convert the
     * reward into two or three more bodies, short enough that the round does not
     * simply end because the player got ahead once. It is deliberately not derived
     * from that rate of fire — the cooldown belongs to {@code DemoGameplayPort},
     * and a rule in this class that depended on a number the port owns would make
     * the balance of a match a property of whatever is driving it.</p>
     *
     * <h2>A countdown, not an expiry tic</h2>
     *
     * <p>{@link #RESPAWN_DELAY_TICS} is served by an absolute
     * {@code respawnAtTic}; this is served by a per-tic countdown, and the
     * asymmetry is deliberate. A respawn is scheduled inside {@link #tick}, which
     * is handed the tic index; the buff is awarded inside
     * {@link #firePlayerShot}, which is <b>not</b> — and deliberately is not, since
     * whether a trigger may be pulled is the caller's rule and a pure resolution
     * function has no clock. Giving it one would mean either widening that
     * signature or keeping a second copy of the tic index in this class, and a
     * second copy of a fact is the failure mode this codebase has already been
     * bitten by. A countdown also cannot be stranded by a caller that skips a tic
     * index, which is the hazard {@code advanceRespawn} has to compare with
     * {@code >=} to avoid.</p>
     */
    public static final int SUPER_BLASTER_TICS = 240;

    /**
     * What the super blaster multiplies the player's damage by — <b>2</b>.
     *
     * <p>Named so that {@link #SUPER_BLASTER_SHOT_DAMAGE} can be
     * <i>derived</i> rather than written down a second time. A literal 68 beside a
     * literal 34 is one re-balance away from a reward that quietly stops being
     * double anything, and nothing about a wrong number here looks wrong.</p>
     */
    public static final int SUPER_BLASTER_DAMAGE_MULTIPLIER = 2;

    /**
     * Damage one super-blaster shot does — twice {@link #PLAYER_SHOT_DAMAGE}.
     *
     * <p><b>Two hits to kill instead of three</b>, with {@link Bot#MAX_HEALTH} of
     * 100. That is the whole of what the reward is worth, and it is worth stating
     * as a hit count rather than as a damage figure: 68 does not read as "a third
     * off every kill" until it is put beside a bot's health.</p>
     */
    public static final int SUPER_BLASTER_SHOT_DAMAGE =
        PLAYER_SHOT_DAMAGE * SUPER_BLASTER_DAMAGE_MULTIPLIER;

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

    /**
     * This tic's return fire, for whatever draws it. Never null, allocated once.
     *
     * <p>The seam that makes incoming fire <b>visible</b>, and it is the same
     * shape as {@link #respawnedThisTic}: this class owns <i>what happened</i>,
     * something else owns what it looks like. {@code Match} has never heard of a
     * tracer and must not; it writes down the rays it fired and
     * {@code DemoGameplayPort} — which owns the effect pool — turns them into
     * bolts and smoke. See {@link BotShotLog} for why this is a reused buffer
     * rather than an immutable snapshot like {@link MatchStatus}.</p>
     */
    private final BotShotLog shots;

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

    /**
     * Kills since the player last died, or since the last award. MUTABLE.
     *
     * <p>Zeroed by a death <b>and</b> by the award itself, which is what makes the
     * reward cost three fresh kills every time rather than one kill once the
     * player has ever been three ahead.</p>
     */
    private int killStreak;

    /**
     * Tics of super blaster left, zero when the blaster is ordinary. MUTABLE:
     * set by the award, counted down one per {@link #tick}, cleared by a death.
     */
    private int superBlasterTicsLeft;

    /**
     * Set on the tic the streak is completed, cleared by
     * {@link #consumeSuperBlasterAwarded()}. MUTABLE.
     *
     * <p>The same seam as {@link #respawnedThisTic}, for the same reason: this
     * class owns <i>that</i> the player earned something and has never heard of a
     * sound or a banner. A buff the player is not told about is indistinguishable
     * from no buff, and the telling belongs to whatever owns a speaker.</p>
     */
    private boolean superBlasterAwardedThisTic;

    /**
     * Set on the tic the super blaster runs out or is cancelled, cleared by
     * {@link #consumeSuperBlasterExpired()}. MUTABLE.
     *
     * <p>An <b>edge</b> and not a state, because "it has stopped" is the thing the
     * player has to be told and it is true for exactly one tic. A reader comparing
     * {@link #isSuperBlaster()} against its own remembered copy would work and
     * would put that remembering — and the bug where two readers disagree about
     * which tic it happened on — in every caller.</p>
     */
    private boolean superBlasterExpiredThisTic;

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
        this.shots = new BotShotLog(bots.length);
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
        // BOTH halves of the reward, and the second one is the one that matters.
        // A rematch that inherited a live super blaster would open with four
        // seconds of double damage nobody earned — and it is exactly the class of
        // bug this reset already shipped once, when reviving the bots in the
        // simulation left them hidden in the renderer.
        this.killStreak = 0;
        this.superBlasterTicsLeft = 0;
        this.superBlasterAwardedThisTic = false;
        this.superBlasterExpiredThisTic = false;
        this.playerShotsFired = 0;
        this.playerShotsHit = 0;
        this.botShotsFired = 0;
        this.botShotsLanded = 0;
        this.respawnAtTic = 0;
        this.playerDown = false;
        this.respawnedThisTic = false;
        // The next tick would clear this anyway, so nothing observable depends on
        // it. It is here because the invariant MatchTest asserts is "a reset match
        // is indistinguishable from a freshly started one", and a field left
        // holding last round's final volley is a difference — which is exactly the
        // kind this reset is meant to catch.
        shots.clear();
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
        // The two reward edges, cleared here for the reason the respawn flag is:
        // they describe something that happened on ONE tic, and a flag left raised
        // would have the effect layer announce the same award again next tic. The
        // clear is before ageSuperBlaster below, which is what may re-raise the
        // expiry one on this very tic.
        this.superBlasterAwardedThisTic = false;
        this.superBlasterExpiredThisTic = false;
        // FIRST, and before the early-outs below. Every one of them is a tic on
        // which nobody fires, and a log still holding the previous tic's rays
        // would have the effect layer spawn the same bolts again the moment the
        // player went down or the room emptied.
        shots.clear();
        if (state().isOver())
        {
            return 0;
        }
        // AFTER the early-out, so a decided round stops the reward's clock along
        // with everything else — the same rule this method already applies to the
        // bots, and the alternative is a power-down noise over the win screen.
        // What that leaves behind is a live buff on a finished round, which
        // reset() is what clears.
        ageSuperBlaster();
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

    // One tic off the super blaster, raising the expiry edge on the tic it runs
    // out. A no-op on the great majority of tics, which is the normal state.
    //
    // Counted down BEFORE this tic's shot is resolved, so the buff covers exactly
    // SUPER_BLASTER_TICS tics of firing: the tic it was awarded on plus the 239
    // after it. The other order would give it one free tic, which is invisible and
    // is the sort of off-by-one that only shows up as a test asserting 241.
    private void ageSuperBlaster()
    {
        if (superBlasterTicsLeft == 0)
        {
            return;
        }
        this.superBlasterTicsLeft = superBlasterTicsLeft - 1;
        if (superBlasterTicsLeft == 0)
        {
            this.superBlasterExpiredThisTic = true;
        }
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
        // playerShotDamage(), not PLAYER_SHOT_DAMAGE. The one line in this method
        // the reward touches at all, and the reason the buff needed no second
        // firing path: a super shot is this shot with a bigger number in it.
        if (victim != null && victim.damage(playerShotDamage()))
        {
            this.botsKilled = botsKilled + 1;
            countTowardTheStreak();
        }
        return struck;
    }

    // One kill on the streak, and the award if that was the third.
    //
    // THE RULE FOR A KILL THAT LANDS WHILE THE BUFF IS LIVE: the timer neither
    // extends nor refreshes. An ordinary kill moves the counter and nothing else,
    // and it takes another full SUPER_BLASTER_KILL_STREAK to re-arm.
    //
    // Extending was the first instinct and it is wrong twice over. It is a
    // positive feedback loop — double damage makes the next kill easier, an easier
    // kill buys more double damage — and in a room of DEFAULT_BOT_COUNT bots the
    // loop has nothing to stop it: the reward for getting three ahead would be the
    // rest of the round, so the buff would END the match rather than punctuate it.
    // And a window that moves is a window the player cannot read: "how long have I
    // got" has to be answerable from the HUD, which means the answer has to be a
    // countdown from a fixed number rather than a figure that jumps whenever
    // somebody falls over.
    //
    // The counter is zeroed BY the award, so kills four and five do not re-arm on
    // the next single kill. Kill six does, and that is deliberate — it is a fresh
    // streak, earned the same way as the first, and re-arming it sets a fresh full
    // window.
    private void countTowardTheStreak()
    {
        this.killStreak = killStreak + 1;
        if (killStreak < SUPER_BLASTER_KILL_STREAK)
        {
            return;
        }
        this.killStreak = 0;
        this.superBlasterTicsLeft = SUPER_BLASTER_TICS;
        this.superBlasterAwardedThisTic = true;
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

    /**
     * Returns kills toward the next super blaster, {@code 0} to
     * {@link #SUPER_BLASTER_KILL_STREAK}{@code  - 1}.
     *
     * <p>Shown on the HUD, and that is not decoration: a gun that changes on the
     * third kill with nothing counting up to it is a mystery rather than a reward.
     * Never reaches the threshold as a readable value, because the kill that
     * reaches it spends it — see {@code countTowardTheStreak}.</p>
     *
     * @return the current streak
     */
    public int killStreak()
    {
        return killStreak;
    }

    /**
     * Returns whether the player's next shot is a super-blaster shot.
     *
     * @return true while the reward is live
     */
    public boolean isSuperBlaster()
    {
        return superBlasterTicsLeft > 0;
    }

    /**
     * Returns tics of super blaster left, zero when the blaster is ordinary.
     *
     * <p>For the on-screen countdown, which counts <b>down</b> for the reason
     * {@link #respawnTicsRemaining} does: a static badge is indistinguishable from
     * a badge somebody forgot to take away.</p>
     *
     * @return tics remaining
     */
    public int superBlasterTicsRemaining()
    {
        return superBlasterTicsLeft;
    }

    /**
     * Returns what one of the player's shots does <b>right now</b>.
     *
     * <p>{@link #SUPER_BLASTER_SHOT_DAMAGE} while the reward is live and
     * {@link #PLAYER_SHOT_DAMAGE} otherwise. Public because it is the honest
     * answer to "how much does my gun do", which a HUD or a test would otherwise
     * have to reassemble from the multiplier and the buff state — two copies of
     * one rule.</p>
     *
     * @return damage per shot, in bot health
     */
    public int playerShotDamage()
    {
        if (isSuperBlaster())
        {
            return SUPER_BLASTER_SHOT_DAMAGE;
        }
        return PLAYER_SHOT_DAMAGE;
    }

    /**
     * Reports whether the streak was completed on the most recent shot, and clears
     * the flag.
     *
     * <p>A consuming read for the reason {@link #consumePlayerRespawned} is one:
     * exactly one caller acts on each award, and what that caller does is make a
     * noise. Two callers would make it twice, in phase, which is one noise at
     * double the volume.</p>
     *
     * @return true once per award
     */
    public boolean consumeSuperBlasterAwarded()
    {
        final boolean awarded = superBlasterAwardedThisTic;
        this.superBlasterAwardedThisTic = false;
        return awarded;
    }

    /**
     * Reports whether the super blaster ran out — or was cancelled by a death — on
     * the most recent tic, and clears the flag.
     *
     * @return true once per expiry
     */
    public boolean consumeSuperBlasterExpired()
    {
        final boolean expired = superBlasterExpiredThisTic;
        this.superBlasterExpiredThisTic = false;
        return expired;
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

    /**
     * Returns the rays the bots fired on the most recent {@link #tick}.
     *
     * <p>The live buffer, not a copy — see {@link BotShotLog} on why that is the
     * right call here and the wrong one for {@link MatchStatus}. Read it in the
     * same tic, on the same thread, before the next {@code tick} clears it.</p>
     *
     * <p>Empty on most tics, and empty is the normal answer: seven
     * {@link BotSkill#DUMB} opponents produce a shot every eighteen tics between
     * them.</p>
     *
     * @return this tic's return fire, never null
     */
    public BotShotLog shotsThisTic()
    {
        return shots;
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
        // The streak dies with the life that was building it. That is what the
        // word means, and it is the half of the rule a player can feel: without it
        // "three kills" is a running total and the gun changes at a moment nothing
        // on screen accounts for.
        this.killStreak = 0;
        if (superBlasterTicsLeft > 0)
        {
            // A live buff goes too, and it raises the same edge an expiry does, so
            // the player hears it stop. Letting it survive would keep the reward
            // whose one precondition — being alive and three ahead — has just
            // stopped being true; and the two seconds on the floor would eat most
            // of the window anyway, which would make death cancel it in practice
            // while the rules said otherwise.
            this.superBlasterTicsLeft = 0;
            this.superBlasterExpiredThisTic = true;
        }
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

        // Written down HERE — after the scatter, before the trace, and whatever
        // the trace decides. After the scatter because a bolt drawn down the
        // unscattered heading would arrive dead-on while the damage said the shot
        // went wide, and the near-miss is the entire point of showing it. Before
        // and regardless of the outcome for the same reason the player's own
        // tracer is spawned before their hitscan resolves: a bolt that only
        // appeared when the shot connected would tell the player they had been hit
        // before the health did.
        //
        // Nothing above this line is recorded, and that is the right cut: the
        // early-outs are trigger pulls with no ray behind them — a bot that has
        // never seen the player, or is shooting at a memory beyond its range — and
        // there is no direction to draw a bolt along. One entry per hitscan.
        shots.record(id, shooter.positionX(), shooter.eyeY(), shooter.positionZ(),
            dirX, dirY, dirZ, (float) StrictMath.sqrt(groundDistanceSquared));

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
            + ", deaths=" + playerDeaths + ", streak=" + killStreak + "/"
            + SUPER_BLASTER_KILL_STREAK + ", super=" + superBlasterTicsLeft
            + " tics, accuracy=" + playerShotsHit + "/"
            + playerShotsFired + ", return fire=" + botShotsLanded + "/" + botShotsFired + "}";
    }
}
