/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

import com.openfps.engine.gameplay.map.MapMarkers;
import com.openfps.engine.gameplay.map.MapSpec;
import com.openfps.engine.gameplay.map.Team;

import java.util.List;

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
     * How many bots a single-player round spawns - <b>32</b>.
     *
     * <p><b>2026-08:</b> raised from 7 to 32. The 16 shipped maps have
     * 21-30 waypoints each, and the populated scene in
     * {@code MapScene.addBotInstances} renders all of them. The
     * previous cap of 7 left 14-23 visual bots per map frozen at their
     * initial waypoint - the simulation did not drive them, so
     * {@code publishBotPlacements} never updated their world
     * transforms and they read as "the bots don't move". 32 covers
     * the largest waypoint count (30, in mesa) plus headroom for
     * future maps.</p>
     *
     * <p>The demo continues to use 7 bots (its own
     * {@code DemoScene.BOT_ROUTE_CENTRES} constant is the source of
     * truth for the demo's room), so the bump does not over-populate
     * the 20m demo room. The map rooms are 200-350m, and 32 bots is
     * one per 6-11m of side, the same density the demo achieves at 7
     * bots in 20m.</p>
     */
    public static final int DEFAULT_BOT_COUNT = 32;

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
     * How far a bot can shoot, in world units — <b>2048</b>.
     *
     * <p>The demo room is 640 units across, so the original 512 was a little over
     * three quarters of its diagonal reach: a bot on the far side of the room
     * could not plink at a player who had not come to find it. Without a range
     * limit the whole room engages the player from the first tic, and there is
     * no reason to advance.</p>
     *
     * <p>The 16 shipped maps were re-sized to MW2-Rust / BO6 large-map
     * proportions in 2026-08 (commits 15f00cb / c419c58 / 7675496 /
     * ec7be2e). The 3200-5600 world-unit playable areas put the
     * 512-unit limit on roughly a tenth of a side — most bots were out of
     * range of the player from any sensible spawn. 2048 covers the full
     * diagonal of the smallest map (3200 x 3200) and ~70% of the
     * largest (5600 x 5600), which is what "this bot will shoot you if it
     * has line of sight" means on a real-size map.</p>
     *
     * <p>Measured against the position the bot <b>remembers</b> the player at,
     * not the real one, because everything else about a bot's shot is. A bot
     * whose range check used current information and whose aim used stale
     * information would be a hybrid nobody could reason about.</p>
     */
    public static final float BOT_RANGE_UNITS = 2048.0f;

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

    /**
     * The capture limit for a CTF match. A team that reaches this many captures
     * wins the round; the standard COD number, hardcoded because every shipped
     * map agrees on it and a future pass can lift it into the spec if the
     * maps start to disagree.
     */
    public static final int CTF_CAPTURE_LIMIT = 5;

    /**
     * The time limit for a CTF match, in tics. At 60 Hz, this is ten minutes
     * (600 seconds). The match is a draw if the time limit is reached before
     * either team hits {@link #CTF_CAPTURE_LIMIT}.
     */
    public static final int CTF_TIME_LIMIT_TICS = 60 * 60 * 10;

    /** One full turn in radians, for the wild shot's arbitrary heading. */
    private static final float FULL_TURN_RADIANS = (float) (2.0 * StrictMath.PI);

    /** The opponents, in id order. Never null, entries never null. */
    private final Bot[] bots;

    /** Reused across every shot in the match, so firing allocates no result. */
    private final HitResult hit = new HitResult();

    /**
     * Pre-allocated buffer for the per-shot target scene.
     *
     * <p>{@link #populateShotSceneFor} and {@link #botShotConnects} rewrite
     * the populated prefix in place each call. The size is {@code bots.length
     * + 1} — one slot for the player plus one per potential bot — so no
     * call ever grows it. The actual population is tracked by
     * {@link #shotSceneCount}.</p>
     */
    private final Target[] shotSceneScratch;

    /**
     * Pre-allocated buffer for the player's outgoing shot.
     *
     * <p>Reused by {@link #populateLivingBotTargets} each call. The size
     * is {@code bots.length} — the worst case is every bot alive — and
     * the actual population is tracked by {@link #livingSceneCount}.</p>
     */
    private final Target[] livingSceneScratch;

    /**
     * How many of {@link #shotSceneScratch} are populated for the current
     * shot. Written by {@link #populateShotSceneFor}, read by the caller's
     * {@link Hitscan#fire}.
     */
    private int shotSceneCount;

    /**
     * How many of {@link #livingSceneScratch} are populated for the current
     * player shot. Written by {@link #populateLivingBotTargets}, read by
     * the caller's {@link Hitscan#fire}.
     */
    private int livingSceneCount;

    /**
     * Pre-allocated buffer for {@link #teamScores()} so the scoreboard
     * read costs the same per tic whether the renderer asks once or a
     * frame.
     */
    private final int[] teamScoreScratch;

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
     * The map spec this match is running. Null for the legacy single-room
     * demo. Set by the spec-aware constructor; never reassigned.
     */
    private final MapSpec mapSpec;

    /**
     * Per-tic counter for Hardpoint zone rotation. MUTABLE. Zero when the
     * match is not Hardpoint, and the value is meaningless between rounds.
     */
    private int hardpointRotationCounter;

    /**
     * Which Hardpoint zone is currently active (0-2). MUTABLE. Zero when
     * the match is not Hardpoint, and the value is meaningless between
     * rounds.
     */
    private int hardpointActiveZone;

    /**
     * Red team's Hardpoint score. MUTABLE. Zero when the match is not
     * Hardpoint, and the value is meaningless between rounds.
     */
    private int hardpointRedScore;

    /**
     * Blue team's Hardpoint score. MUTABLE. Zero when the match is not
     * Hardpoint, and the value is meaningless between rounds.
     */
    private int hardpointBlueScore;

    /**
     * Which team currently holds the active Hardpoint zone. MUTABLE.
     * {@link Team#NEUTRAL} when no team holds the zone (it is empty or
     * contested). Reset by {@link #reset()}.
     */
    private Team hardpointActiveHolder = Team.NEUTRAL;

    /**
     * The team the local player is on. MUTABLE; set by the gameplay
     * port when the player picks a side at spawn. Defaults to
     * {@link Team#NEUTRAL} for the legacy single-player demo and the
     * headless smoke-test path, both of which have no team assignment.
     */
    private Team playerTeam = Team.NEUTRAL;

    /**
     * Per-flag owner for the three Domination flags, indexed by flag
     * order in the spec. MUTABLE. Initialized to
     * {@link Team#NEUTRAL} for all three — the spec's flags start
     * unclaimed, which is the design doc's neutral start. Reset by
     * {@link #reset()}.
     */
    private Team[] dominationFlagOwners = new Team[]
    {
        Team.NEUTRAL, Team.NEUTRAL, Team.NEUTRAL
    };

    /**
     * Red team's Domination score. MUTABLE. Zero when the match is not
     * Domination, and the value is meaningless between rounds.
     */
    private int dominationRedScore;

    /**
     * Blue team's Domination score. MUTABLE. Zero when the match is not
     * Domination, and the value is meaningless between rounds.
     */
    private int dominationBlueScore;

    /**
     * Red team's CTF capture count. MUTABLE. Zero when the match is not
     * CTF, and the value is meaningless between rounds.
     */
    private int ctfRedCaptures;

    /**
     * Blue team's CTF capture count. MUTABLE. Zero when the match is not
     * CTF, and the value is meaningless between rounds.
     */
    private int ctfBlueCaptures;

    /**
     * Who is carrying RED's flag. {@code null} means the flag is on its base;
     * otherwise, the carrier is on the named team (the only legal non-null
     * value is {@link Team#BLUE}, because the player can only carry the
     * enemy flag). MUTABLE.
     */
    private Team ctfRedFlagCarrier;

    /**
     * Who is carrying BLUE's flag. {@code null} means the flag is on its
     * base; otherwise, the carrier is on the named team (the only legal
     * non-null value is {@link Team#RED}). MUTABLE.
     */
    private Team ctfBlueFlagCarrier;

    /**
     * Tics elapsed since the CTF match started. Used by the time-limit check
     * in {@link #state()}. Incremented on every {@link #updateCtf} call. The
     * counter resets to zero in {@link #reset()}.
     */
    private int ctfElapsedTics;

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
        this(opponents, generator, botSkill, deathsAllowed, null);
    }

    /**
     * Creates a match against a given set of bots on a specific map. The
     * match's mode is taken from the spec; the bots, the seed, the skill
     * profile and the death limit match the no-spec constructor's defaults.
     *
     * <p>The spec is held by reference (immutable), so a future modification
     * to the registry does not retroactively change this match's mode. The
     * mode itself is read off the spec at construction time and again on
     * every {@link #tick}; both reads see the same value because the spec
     * is immutable.</p>
     *
     * @param opponents the bots to fight; same rules as
     *     {@link #Match(Bot[], BotRng, BotSkill, int)}
     * @param spec the map spec; must not be null
     * @throws IllegalArgumentException if {@code opponents} is null or holds
     *     a null, or if two bots share an entity id, or if {@code spec} is
     *     null
     */
    public Match(final Bot[] opponents, final MapSpec spec)
    {
        this(opponents, new BotRng(), BotSkill.DUMB, UNLIMITED_DEATHS, spec);

        // Validate after chaining. The 5-arg constructor accepts a null
        // spec for the legacy demo path, but the 2-arg form is only ever
        // useful with a real spec. The check is below the chained call
        // because Java 17 forbids statements before this(...), and a
        // null spec is the one input that breaks this contract.
        if (spec == null)
        {
            throw new IllegalArgumentException("spec must not be null");
        }
    }

    /**
     * Creates a match against a given set of bots on a specific map, with
     * the full set of construction arguments. The spec may be null for the
     * legacy single-room demo.
     *
     * @param opponents the bots to fight; same rules as
     *     {@link #Match(Bot[], BotRng, BotSkill, int)}
     * @param generator where every random decision comes from; must not be null
     * @param botSkill how badly the opponents shoot; must not be null
     * @param deathsAllowed deaths before the round is lost, or
     *     {@link #UNLIMITED_DEATHS}; must not be negative
     * @param spec the map spec, or null for the legacy demo
     * @throws IllegalArgumentException if any argument is out of range
     */
    public Match(final Bot[] opponents, final BotRng generator, final BotSkill botSkill,
        final int deathsAllowed, final MapSpec spec)
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

        // Pre-allocated hot-path scratch. Sized for the worst case (every
        // bot alive) so the firing loop never has to grow a buffer. The
        // per-call count is in {@link #shotSceneCount} / {@link
        // #livingSceneCount}, which the caller passes to {@link Hitscan#fire}
        // — the array length is the capacity, not the population.
        this.shotSceneScratch = new Target[bots.length + 1];

        for (int index = 0; index < shotSceneScratch.length; index++)
        {
            shotSceneScratch[index] = new Target(1, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        }

        this.livingSceneScratch = new Target[bots.length];

        for (int index = 0; index < livingSceneScratch.length; index++)
        {
            livingSceneScratch[index] = new Target(1, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        }

        this.teamScoreScratch = new int[2];

        this.rng = generator;

        this.skill = botSkill;

        this.deathLimit = deathsAllowed;

        this.shots = new BotShotLog(bots.length);

        this.mapSpec = spec;
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

        // Mode-specific state, reset so a rematch opens in the same
        // shape a fresh match would. Same justification as the
        // super-blaster lines above: a Hardpoint rematch that
        // remembered last round's active zone would open mid-rotation,
        // and a player who earned four seconds of capture in a zone
        // that is no longer active would see a HUD that does not agree
        // with the room.
        this.hardpointRotationCounter = 0;

        this.hardpointActiveZone = 0;

        this.hardpointRedScore = 0;

        this.hardpointBlueScore = 0;

        this.hardpointActiveHolder = Team.NEUTRAL;

        // Domination per-flag owners reset to NEUTRAL so the round
        // opens with the spec's neutral start, not with the previous
        // round's captures. Same justification as the Hardpoint
        // resets: a rematch that inherited a captured flag would
        // open with a score nobody earned on this round.
        for (int index = 0; index < dominationFlagOwners.length; index++)
        {
            dominationFlagOwners[index] = Team.NEUTRAL;
        }

        this.dominationRedScore = 0;

        this.dominationBlueScore = 0;

        // CTF per-flag carriers return to null and per-team capture
        // counts return to zero. The match opens with both flags at
        // home and no captures scored — a rematch that inherited a
        // carried flag would open mid-run with a flag the carrier
        // already had, and a rematch with an inherited capture count
        // would be one closer to the limit than the round earned.
        this.ctfRedFlagCarrier = null;

        this.ctfBlueFlagCarrier = null;

        this.ctfRedCaptures = 0;

        this.ctfBlueCaptures = 0;

        this.ctfElapsedTics = 0;

        // The player's team is a rematch input, not a rematch
        // output: a rematch opens with the same team the previous
        // round did, set by the gameplay port. The smoke-test path
        // does not change it, which is the right default.
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

        final int damage = resolveBotFire(ticIndex, playerFeetX, playerFeetY, playerFeetZ);

        // Mode-specific update, AFTER the bot fire has been resolved so a
        // Hardpoint score from this tic reflects every bot that landed a
        // shot on a player who is also holding a zone. No-op for modes that
        // have no per-tic state (TDM); stubs for the other three that keep
        // the simulation state coherent without implementing the rules.
        updateMode(ticIndex, playerFeetX, playerFeetZ);

        return damage;
    }

    /**
     * Dispatches the per-tic mode update.
     *
     * <p>For {@link MatchMode#TDM} (and for the no-spec legacy demo, which
     * runs as a default TDM) this is a no-op: TDM has no per-tic state
     * beyond the respawn / score logic {@link #tick} already does. The
     * other three modes have stubs that keep the rotation counters and
     * flag-state tables coherent on every tic, with the actual scoring
     * logic and capture detection left for the mode-specific
     * implementation in Pass 2-4.</p>
     *
     * @param ticIndex the tic being processed
     * @param playerX the player's feet, world x — the x used to test
     *                capture radii
     * @param playerZ the player's feet, world z — the z used to test
     *                capture radii
     */
    private void updateMode(final int ticIndex, final float playerX, final float playerZ)
    {
        final MatchMode currentMode = mode();

        switch (currentMode)
        {
            case HARDPOINT -> updateHardpoint(ticIndex, playerX, playerZ);
            case DOMINATION -> updateDomination(ticIndex, playerX, playerZ);
            case CTF -> updateCtf(ticIndex, playerX, playerZ);
            case TDM ->
            {
                // TDM's per-tic work is the respawn, score, streak and
                // bot-fire logic in tick().
            }
            // SINGLE_PLAYER and MULTIPLAYER are not mode dispatchers;
            // they fall back to TDM behaviour through mode().
            case SINGLE_PLAYER, MULTIPLAYER -> { /* no-op */ }
            default -> { /* defensive: every enum constant is covered above */ }
        }
    }

    /**
     * Hardpoint per-tic update.
     *
     * <p>Three steps, in order: <b>resolve</b> which team currently holds
     * the active zone, <b>score</b> the holding team, then <b>advance</b>
     * the rotation counter and rotate on schedule.</p>
     *
     * <h2>Resolve — who holds the zone</h2>
     *
     * <p>A body is in the active zone when its horizontal distance to the
     * zone's centre is within {@code zone.radius()}. Both the player and
     * every living bot are tested. The player counts for whichever team
     * the match's caller hands in via {@code playerTeam} — the
     * {@link #tick} signature does not take a team today, so the smoke-test
     * path uses {@link Team#NEUTRAL}, and a real {@code MapScene} would
     * forward the player's team from the spawn selection. A
     * {@link Team#NEUTRAL} body does not claim the zone for either team.</p>
     *
     * <p>Two teams in the zone simultaneously leaves the zone
     * <b>uncontested-held</b>: the contested rule is the same rule the
     * UI shows as "CONTESTED", and the score is paused for the duration.
     * The active holder field therefore reports
     * {@link Team#NEUTRAL} when both teams are present or when no team
     * is present, and the score does not advance on those tics.</p>
     *
     * <h2>Score</h2>
     *
     * <p>On every tic where the active holder is RED, red's score advances
     * by {@code spec.scorePerTick()}. Same for blue. The score-per-tick
     * rule is part of the spec, not a constant, because a future pass
     * may want a faster rotation that earns less per held tic, or a
     * slower one that earns more, and the spec is the right place to
     * capture that trade.</p>
     *
     * <h2>Advance</h2>
     *
     * <p>The rotation counter advances by one every tic and wraps every
     * {@code spec.rotationTics()} tics, moving the active zone index
     * forward by one (mod the zone count). The counter and the index
     * advance AFTER the score is awarded, so a tic on which the
     * rotation triggers still scores the previous zone — the player
     * who held the zone for the last tic of the rotation is paid for
     * that last tic, and the next tic's score belongs to whoever
     * holds the new active zone.</p>
     */
    private void updateHardpoint(final int ticIndex, final float playerX, final float playerZ)
    {
        if (!(mapSpec.markers() instanceof MapMarkers.Hardpoint hp))
        {
            return;
        }

        // 1. Resolve the active holder.
        final MapMarkers.HardpointZone active = hp.zones().get(hardpointActiveZone);

        final boolean playerInZone = isInHardpointZone(playerX, playerZ, active);

        final boolean playerRed = playerInZone && playerTeam == Team.RED;

        final boolean playerBlue = playerInZone && playerTeam == Team.BLUE;

        final boolean redBody = playerRed || botTeamInZone(Team.RED, active);

        final boolean blueBody = playerBlue || botTeamInZone(Team.BLUE, active);

        final Team newHolder;

        if (redBody && !blueBody)
        {
            newHolder = Team.RED;
        }
        else if (blueBody && !redBody)
        {
            newHolder = Team.BLUE;
        }
        else
        {
            newHolder = Team.NEUTRAL;
        }

        this.hardpointActiveHolder = newHolder;

        // 2. Award score.
        if (newHolder == Team.RED)
        {
            this.hardpointRedScore = hardpointRedScore + hp.scorePerTick();
        }

        if (newHolder == Team.BLUE)
        {
            this.hardpointBlueScore = hardpointBlueScore + hp.scorePerTick();
        }

        // 3. Advance the rotation.
        this.hardpointRotationCounter = hardpointRotationCounter + 1;

        if (hardpointRotationCounter >= hp.rotationTics())
        {
            this.hardpointRotationCounter = 0;

            this.hardpointActiveZone = (hardpointActiveZone + 1) % hp.zones().size();
        }
    }

    /**
     * Returns whether a body at ({@code x}, {@code z}) is inside the
     * capture radius of a Hardpoint zone. The check is horizontal —
     * the y axis is the floor, not the body height, so a player on a
     * catwalk above a zone is NOT in the zone.
     *
     * @param x body feet, world x
     * @param z body feet, world z
     * @param zone the zone to test against
     * @return true if the body's horizontal distance to the zone's
     *     centre is within the zone's radius
     */
    private static boolean isInHardpointZone(final float x, final float z,
        final MapMarkers.HardpointZone zone)
    {
        final float dx = x - zone.x();

        final float dz = z - zone.z();

        // Squared distance — sqrt is unnecessary; the comparison is
        // (d <= r), which is the same as (d^2 <= r^2). Saves a StrictMath.sqrt
        // on the tic path.
        return dx * dx + dz * dz <= zone.radius() * zone.radius();
    }

    /**
     * Returns whether at least one living bot on the given team is in
     * the given Hardpoint zone.
     *
     * <p>Iterates the bot array; a {@code Match} with seven bots runs
     * this up to seven times per tic per team, which is the right
     * answer for the current scale and is O(N) per tic — a
     * future optimisation could bin bots to zones if the count
     * climbs.</p>
     *
     * @param team the team to test
     * @param zone the zone to test against
     * @return true if any living bot on the team is in the zone
     */
    private boolean botTeamInZone(final Team team, final MapMarkers.HardpointZone zone)
    {
        for (int index = 0; index < bots.length; index++)
        {
            final Bot bot = bots[index];

            if (!bot.isAlive() || bot.team() != team)
            {
                continue;
            }

            if (isInHardpointZone(bot.positionX(), bot.positionZ(), zone))
            {
                return true;
            }
        }

        return false;
    }

    // (hasRedPresence / hasBluePresence were removed in Pass 2 when
    // the resolve switched from "the player can be on a team that has
    // at least one bot" to reading playerTeam directly — those helpers
    // described the wrong condition.)

    /**
     * Domination per-tic update.
     *
     * <p>Two steps, in order: <b>resolve</b> each flag's owner from the
     * bodies in its capture radius, then <b>score</b> each flag that
     * has an owner.</p>
     *
     * <h2>Resolve — who owns each flag</h2>
     *
     * <p>For each flag, test every body (the player and every living
     * bot) for whether its feet are inside the flag's capture radius.
     * A body is RED if the player is on RED or the bot is on RED;
     * same for BLUE. The resolve rule is:</p>
     *
     * <ul>
     *   <li>Only RED bodies in the radius → owner = RED.</li>
     *   <li>Only BLUE bodies in the radius → owner = BLUE.</li>
     *   <li>Both teams in the radius (contested) → owner <b>unchanged</b>.
     *       A contested flag does not switch sides; the team that held
     *       it last keeps the score and the next tic re-resolves.</li>
     *   <li>Neither team in the radius (empty) → owner <b>unchanged</b>.
     *       This is the standard COD rule: a flag, once captured, stays
     *       captured until the enemy team stands on it uncontested.</li>
     * </ul>
     *
     * <p>The "unchanged on contested/empty" rule is the difference
     * between a working Domination and a Domination that flips a flag
     * the moment a defender steps on it. It is the rule the scoreboard
     * reads as "DEFENDING", and the contested case is the one the
     * HUD shows as "CONTESTED" with a per-tic flicker as bodies
     * move in and out.</p>
     *
     * <h2>Score</h2>
     *
     * <p>On every tic, each flag whose owner is RED adds one point to
     * the red score; each flag whose owner is BLUE adds one to the
     * blue score. Three flags all held by RED score 3 per tic; one
     * contested, one RED, one NEUTRAL scores 1 per tic. The
     * Domination per-tick rate is hardcoded to 1 — different per map
     * would be a future balance pass, not a Pass 3 change.</p>
     */
    private void updateDomination(final int ticIndex, final float playerX, final float playerZ)
    {
        if (!(mapSpec.markers() instanceof MapMarkers.Domination dom))
        {
            return;
        }

        // 1. Resolve each flag.
        final List<MapMarkers.Flag> flags = dom.flags();

        for (int index = 0; index < flags.size(); index++)
        {
            final MapMarkers.Flag flag = flags.get(index);

            final boolean redIn = isInDominationRadius(playerX, playerZ, flag, Team.RED);

            final boolean blueIn = isInDominationRadius(playerX, playerZ, flag, Team.BLUE);

            final Team newOwner;

            if (redIn && !blueIn)
            {
                newOwner = Team.RED;
            }
            else if (blueIn && !redIn)
            {
                newOwner = Team.BLUE;
            }
            else
            {
                // Contested (both in) or empty (neither in): the
                // owner does not change. A NEUTRAL flag stays NEUTRAL
                // (still empty); a captured flag stays captured
                // (still contested or empty). The score logic
                // below awards points only to the captured side.
                continue;
            }

            dominationFlagOwners[index] = newOwner;
        }

        // 2. Score: one point per flag per tic for the holding team.
        for (int index = 0; index < dominationFlagOwners.length; index++)
        {
            final Team owner = dominationFlagOwners[index];

            if (owner == Team.RED)
            {
                this.dominationRedScore = dominationRedScore + 1;
            }
            else if (owner == Team.BLUE)
            {
                this.dominationBlueScore = dominationBlueScore + 1;
            }
        }
    }

    /**
     * Returns whether a body on the given team is inside a Domination
     * flag's capture radius. The check covers the player (whose team
     * is {@link #playerTeam}) and every living bot on the given team.
     *
     * <p>The horizontal-distance comparison uses squared distance so
     * the per-tic check does not call {@code StrictMath.sqrt}.</p>
     *
     * @param x body feet, world x
     * @param z body feet, world z
     * @param flag the flag to test against
     * @param team the team whose presence is being checked
     * @return true if a body on {@code team} is in {@code flag}'s
     *     capture radius
     */
    private boolean isInDominationRadius(final float x, final float z,
        final MapMarkers.Flag flag, final Team team)
    {
        if (playerTeam == team)
        {
            final float dx = x - flag.x();

            final float dz = z - flag.z();

            if (dx * dx + dz * dz <= flag.radius() * flag.radius())
            {
                return true;
            }
        }

        for (int index = 0; index < bots.length; index++)
        {
            final Bot bot = bots[index];

            if (!bot.isAlive() || bot.team() != team)
            {
                continue;
            }

            final float bx = bot.positionX();

            final float bz = bot.positionZ();

            final float dx = bx - flag.x();

            final float dz = bz - flag.z();

            if (dx * dx + dz * dz <= flag.radius() * flag.radius())
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Capture The Flag per-tic update.
     *
     * <p>Three checks, in order: <b>drop on death</b>, <b>pickup</b>, and
     * <b>return or capture</b>. Each check is gated on the previous one
     * not having moved a flag this tic — a flag that was just returned to
     * its base by a save cannot be picked up again on the same tic, and a
     * flag that was just captured cannot be picked up by the other team on
     * the same tic. The order is the same as the player's intuition reads
     * the screen.</p>
     *
     * <h2>Drop on death</h2>
     *
     * <p>If the player is on the floor waiting to respawn and was carrying
     * a flag, that flag returns to its base. Drop on death is instant —
     * there is no 30-second "lying on the ground" state. The standard COD
     * rule: a dead carrier is a save, not a recovery.</p>
     *
     * <h2>Pickup</h2>
     *
     * <p>If the player is on team T and standing inside the enemy team's
     * flag radius, and the enemy flag is on its base (not already being
     * carried), the player picks it up. The flag's carrier becomes team T.
     * A player cannot pick up the same flag twice; a player who is
     * already carrying a flag touches the enemy base and the second flag
     * is ignored.</p>
     *
     * <h2>Return or capture</h2>
     *
     * <p>If the player is on team T, carrying the enemy flag, and standing
     * inside team T's flag radius, the carrier has "saved" the flag: both
     * flags return to their bases (this is a no-op for the flag already
     * on its base). If instead the player is inside team T's capture
     * point radius, the carrier has scored: the carrying team earns one
     * capture and both flags return to their bases. The capture point is
     * spec-defined per base; in the standard pack it sits at the same
     * coordinates as the home flag, so a save and a capture are different
     * positions on the same base.</p>
     */
    private void updateCtf(final int ticIndex, final float playerX, final float playerZ)
    {
        if (!(mapSpec.markers() instanceof MapMarkers.CaptureTheFlag ctf))
        {
            return;
        }

        this.ctfElapsedTics = ctfElapsedTics + 1;

        // 1. Drop on death: a dead carrier returns the flag to its base
        // instantly. A player who is down is not in any flag's radius (a
        // future "lying on the ground for 30s" pass would change this, but
        // the standard COD rule is instant).
        if (playerDown)
        {
            ctfRedFlagCarrier = null;

            ctfBlueFlagCarrier = null;

            return;
        }

        // 2. The player is the only carrier; bots do not pick up. A
        // NEUTRAL player (legacy demo, smoke test) does not interact with
        // flags at all.
        if (playerTeam == Team.NEUTRAL)
        {
            return;
        }

        final boolean playerIsRed = playerTeam == Team.RED;

        final MapMarkers.Base enemyBase;

        final MapMarkers.Base homeBase;

        if (playerIsRed)
        {
            enemyBase = ctf.blueBase();

            homeBase = ctf.redBase();
        }
        else
        {
            enemyBase = ctf.redBase();

            homeBase = ctf.blueBase();
        }

        // 3. Pickup: enemy flag at home (its slot is null), player inside
        // the enemy flag's radius. Reads the carrier field directly so
        // the check sees the start-of-tic state, not the post-pickup
        // state from earlier in this tic.
        final boolean enemyFlagAtHome;

        if (playerIsRed)
        {
            enemyFlagAtHome = ctfBlueFlagCarrier == null;
        }
        else
        {
            enemyFlagAtHome = ctfRedFlagCarrier == null;
        }

        if (enemyFlagAtHome && isInCtfFlagRadius(playerX, playerZ, enemyBase))
        {
            if (playerIsRed)
            {
                ctfBlueFlagCarrier = Team.RED;
            }
            else
            {
                ctfRedFlagCarrier = Team.BLUE;
            }
        }

        // 4. Return or capture: player carrying the enemy flag, touching
        // their own base. The carrier check reads the field directly for
        // the same reason as the pickup above.
        final boolean isCarrying;

        if (playerIsRed)
        {
            isCarrying = ctfBlueFlagCarrier == Team.RED;
        }
        else
        {
            isCarrying = ctfRedFlagCarrier == Team.BLUE;
        }

        if (isCarrying && isInCtfFlagRadius(playerX, playerZ, homeBase))
        {
            if (isInCtfCaptureRadius(playerX, playerZ, homeBase))
            {
                if (playerIsRed)
                {
                    this.ctfRedCaptures = ctfRedCaptures + 1;
                }
                else
                {
                    this.ctfBlueCaptures = ctfBlueCaptures + 1;
                }
            }

            // Either save or capture, both flags return home.
            ctfRedFlagCarrier = null;

            ctfBlueFlagCarrier = null;
        }
    }

    /**
     * Returns whether the player's feet are inside the {@code base}'s
     * flag pickup / return radius.
     *
     * <p>Uses squared distance, so the per-tic check does not call
     * {@code StrictMath.sqrt}.</p>
     *
     * @param x player feet, world x
     * @param z player feet, world z
     * @param base the base to test
     * @return true if the player is within {@code base.radius()} of the
     *     flag's home position
     */
    private static boolean isInCtfFlagRadius(final float x, final float z,
        final MapMarkers.Base base)
    {
        final float dx = x - base.flagX();

        final float dz = z - base.flagZ();

        return dx * dx + dz * dz <= base.radius() * base.radius();
    }

    /**
     * Returns whether the player's feet are inside the {@code base}'s
     * capture-point radius.
     *
     * <p>The capture point is spec-defined; in the standard pack it sits
     * at the same coordinates as the home flag, but the spec lets a map
     * place them independently. Uses squared distance.</p>
     */
    private static boolean isInCtfCaptureRadius(final float x, final float z,
        final MapMarkers.Base base)
    {
        final float dx = x - base.captureX();

        final float dz = z - base.captureZ();

        return dx * dx + dz * dz <= base.radius() * base.radius();
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
        return firePlayerShot(eyeX, eyeY, eyeZ, aimX, aimY, aimZ, Weapon.BLASTER);
    }

    /**
     * Fires one trigger pull. 2026-08: weapon-aware dispatch. The
     * {@link Weapon#BLASTER} is a single hitscan with the
     * standard blaster damage; the {@link Weapon#SHOTGUN} is a
     * cone of pellets each with their own hitscan, doing
     * "infinite" damage (a one-shot kill) at close range and
     * the blaster's far-range damage at long range. The
     * {@link Weapon#ROCKET_LAUNCHER} is a single projectile,
     * which lands in a follow-up commit; until then it fires
     * like the blaster.
     *
     * <p>Returns the entity id of the first hit (which is the
     * closest pellet, for the shotgun, or the single hitscan's
     * hit, for the blaster). The match state ({@code botsKilled}
     * etc.) is updated for every pellet that lands.</p>
     *
     * @param eyeX the shot origin, world x - the player's eye
     * @param eyeY the shot origin, world y
     * @param eyeZ the shot origin, world z
     * @param aimX the central aim direction, world x; need not be unit length
     * @param aimY the central aim direction, world y
     * @param aimZ the central aim direction, world z
     * @param weapon the weapon the player is firing
     * @return the id of the bot hit, or {@link #NO_HIT} when the shot missed
     */
    public int firePlayerShot(final float eyeX, final float eyeY, final float eyeZ,
        final float aimX, final float aimY, final float aimZ, final Weapon weapon)
    {
        if (weapon.fireMode() == Weapon.FireMode.CONE)
        {
            return fireShotgun(eyeX, eyeY, eyeZ, aimX, aimY, aimZ);
        }

        if (weapon.fireMode() == Weapon.FireMode.PROJECTILE)
        {
            return fireRocket(eyeX, eyeY, eyeZ, aimX, aimY, aimZ);
        }

        // Blaster.
        this.playerShotsFired = playerShotsFired + 1;

        populateLivingBotTargets();

        if (livingSceneCount == 0)
        {
            return NO_HIT;
        }

        if (!Hitscan.fire(eyeX, eyeY, eyeZ, aimX, aimY, aimZ, livingSceneScratch, livingSceneCount, hit))
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

    /**
     * Fires one shotgun blast: a horizontal fan of pellets, each
     * a separate hitscan. The fan is centred on the player's
     * central aim, so pointing the crosshair at a target puts
     * that target in the densest part of the spread.
     *
     * <p>The "infinite damage at close range" rule: a pellet
     * that lands within {@link Weapon#SHOTGUN_CLOSE_RANGE_UNITS}
     * does {@link Weapon#SHOTGUN_CLOSE_DAMAGE}, which is enough
     * to kill a bot with full health in one hit. A pellet that
     * lands beyond that range does
     * {@link Weapon#SHOTGUN_FAR_DAMAGE}, half the blaster's
     * shot damage, because each pellet is only one of seven and
     * the bot's health is the same as the blaster would have
     * hit. The damage split is the one that makes "aim with the
     * crosshair at point-blank" a kill and "aim from across the
     * room" a tickle.</p>
     *
     * <p>The pellet count is fixed at
     * {@link Weapon#SHOTGUN_PELLETS}; the spread is fixed at
     * {@link Weapon#SHOTGUN_SPREAD_RADIANS}. A deterministic
     * fan (not a random scatter) is the right call for the
     * lockstep claim: the same aim produces the same pellet
     * hits on every peer, with no random source in the path.</p>
     */
    private int fireShotgun(final float eyeX, final float eyeY, final float eyeZ,
        final float aimX, final float aimY, final float aimZ)
    {
        this.playerShotsFired = playerShotsFired + 1;

        populateLivingBotTargets();

        if (livingSceneCount == 0)
        {
            return NO_HIT;
        }

        // Normalize the central aim once. Hitscan.fire rejects
        // anything that is not unit length, and the pellets are
        // unit-length rotations of this vector. StrictMath, not
        // Math - the deterministic lockstep test in BotRngTest
        // asserts that Match is free of java.lang.Math, and the
        // squared-length + StrictMath.sqrt keeps the same shape.
        final double aimLenSq = (double) aimX * aimX + (double) aimY * aimY + (double) aimZ * aimZ;

        if (aimLenSq < 1.0e-12)
        {
            return NO_HIT;
        }

        final float aimLen = (float) StrictMath.sqrt(aimLenSq);

        if (aimLen < 1.0e-6f)
        {
            return NO_HIT;
        }

        final float ax = aimX / aimLen;
        final float ay = aimY / aimLen;
        final float az = aimZ / aimLen;

        // Pre-allocate one scratch for every pellet's rotated
        // aim. Reusing the muzzle scratch is the cheap move
        // because muzzleScratch is a 3-float buffer that no
        // other path in the shotgun frame reads.
        final int pellets = Weapon.SHOTGUN_PELLETS;

        final float spread = Weapon.SHOTGUN_SPREAD_RADIANS;

        // The pellets' yaw offsets in radians. 5 pellets across
        // [-2, -1, 0, 1, 2] multiples of the spread step; the
        // centre pellet is the zero offset and reads as a
        // normal hitscan.
        //   offsetIndex = -2: -2 * spreadStep
        //   offsetIndex = -1: -1 * spreadStep
        //   offsetIndex =  0:  0 (the central aim)
        //   offsetIndex =  1: +1 * spreadStep
        //   offsetIndex =  2: +2 * spreadStep
        // where spreadStep = spread * 2 / (pellets - 1) so the
        // outermost pellets land at +/- spread.
        final float spreadStep;

        if (pellets > 1)
        {
            spreadStep = (spread * 2.0f) / (pellets - 1);
        }
        else
        {
            spreadStep = 0.0f;
        }

        int firstHit = NO_HIT;

        float firstHitDistance = Float.POSITIVE_INFINITY;

        for (int p = 0; p < pellets; p++)
        {
            final int offsetIndex = p - (pellets / 2);

            final float da = offsetIndex * spreadStep;

            final float cosDa = (float) StrictMath.cos(da);

            final float sinDa = (float) StrictMath.sin(da);

            // Yaw rotation around world +y. The aim's y is
            // preserved; the x and z rotate. This is a 2D
            // spread (no vertical fan), which reads as a
            // sawed-off rather than a vertical-pattern shotgun,
            // and avoids the basis-vector math a 3D spread
            // would need.
            final float pelletAimX = ax * cosDa + az * sinDa;

            final float pelletAimY = ay;

            final float pelletAimZ = -ax * sinDa + az * cosDa;

            if (!Hitscan.fire(eyeX, eyeY, eyeZ, pelletAimX, pelletAimY, pelletAimZ,
                livingSceneScratch, livingSceneCount, hit))
            {
                continue;
            }

            this.playerShotsHit = playerShotsHit + 1;

            final int struck = hit.entityId();

            final Bot victim = byId(struck);

            // The damage rule: a pellet that lands within
            // SHOTGUN_CLOSE_RANGE_UNITS does the close-range
            // damage (one-shot kill); a pellet beyond that
            // does the far-range damage. The hit's distance()
            // is the same units the threshold is in.
            final boolean close = hit.distance() <= Weapon.SHOTGUN_CLOSE_RANGE_UNITS;

            final int pelletDamage;

            if (close)
            {
                pelletDamage = Weapon.SHOTGUN_CLOSE_DAMAGE;
            }
            else
            {
                pelletDamage = Weapon.SHOTGUN_FAR_DAMAGE;
            }

            if (victim != null && victim.damage(pelletDamage))
            {
                this.botsKilled = botsKilled + 1;

                countTowardTheStreak();
            }

            // Track the closest hit across the fan so the
            // return value (the entity id) is the
            // nearest-struck bot, which is the same shape the
            // blaster's single hitscan returns.
            if (hit.distance() < firstHitDistance)
            {
                firstHit = struck;

                firstHitDistance = hit.distance();
            }
        }

        return firstHit;
    }

    /**
     * Fires one rocket: a single hitscan with splash damage at
     * the impact point.
     *
     * <p>2026-08: the rocket launcher's fire path. The rocket
     * is conceptually a projectile, but for the lockstep
     * claim (every peer computes the same answer) the
     * implementation is a fast hitscan with splash. The bolt
     * flies the length of the playable area in one tic; the
     * damage lands on the bot the ray hit (one-shot kill,
     * since {@link Weapon#ROCKET_LAUNCHER}'s damage is
     * above any bot's health) and on every other bot within
     * {@link Weapon#ROCKET_SPLASH_RADIUS_UNITS} of the
     * impact point, with linear falloff from
     * {@link Weapon#ROCKET_SPLASH_DAMAGE_CENTER} at the
     * centre to 0 at the radius.
     *
     * <p>The splash is a 2D circle on the XZ plane at the
     * impact's Y, not a 3D sphere. A rocket exploding at
     * the foot of a multi-storey level is a problem for
     * the bot in the same storey, not the bot two floors
     * up - the Y of the impact is the Y of the bot that
     * was hit, and a bot whose Y is well above or below
     * the impact's Y is far enough away to be outside the
     * splash in any meaningful sense. The 2D circle
     * captures the "same room" feel the splash is meant
     * to have.</p>
     *
     * <p>Returns the entity id of the directly-hit bot, or
     * {@link #NO_HIT} when the rocket flew into empty
     * space. The match state ({@code botsKilled},
     * {@code killStreak}, etc.) is updated for the
     * direct hit and for every splash kill.</p>
     */
    private int fireRocket(final float eyeX, final float eyeY, final float eyeZ,
        final float aimX, final float aimY, final float aimZ)
    {
        this.playerShotsFired = playerShotsFired + 1;

        populateLivingBotTargets();

        if (livingSceneCount == 0)
        {
            return NO_HIT;
        }

        if (!Hitscan.fire(eyeX, eyeY, eyeZ, aimX, aimY, aimZ, livingSceneScratch, livingSceneCount, hit))
        {
            return NO_HIT;
        }

        this.playerShotsHit = playerShotsHit + 1;

        final int struck = hit.entityId();

        final Bot directVictim = byId(struck);

        // Direct hit: the rocket's base damage, which is well
        // past any bot's health and so one-shot-kills. The
        // call to victim.damage() also marks the bot as
        // dead, so the splash loop below skips it (the
        // direct-hit kill is what the player's UI shows).
        if (directVictim != null)
        {
            if (directVictim.damage(Weapon.ROCKET_LAUNCHER.damage()))
            {
                this.botsKilled = botsKilled + 1;

                countTowardTheStreak();
            }
        }

        // Splash: every living bot within the splash radius
        // of the impact point, with linear damage falloff
        // from the centre to the radius. A bot at the
        // impact point takes the centre damage; a bot at
        // the radius takes 0; a bot in between takes the
        // linear interpolation. Bots in the damage roll
        // (that take damage() returns true for) count
        // toward the kill streak and the botsKilled
        // counter.
        final float impactX = eyeX + aimX * hit.distance();

        final float impactY = eyeY + aimY * hit.distance();

        final float impactZ = eyeZ + aimZ * hit.distance();

        final float splashRadius = Weapon.ROCKET_SPLASH_RADIUS_UNITS;

        final float splashRadiusSq = splashRadius * splashRadius;

        for (int i = 0; i < bots.length; i++)
        {
            final Bot b = bots[i];

            if (!b.isAlive())
            {
                continue;
            }

            if (b.entityId() == struck)
            {
                // The direct-hit victim already had its
                // damage applied above; the splash loop
                // would re-hit it, and a bot should not
                // take damage twice from one rocket.
                continue;
            }

            final float dx = b.positionX() - impactX;
            final float dz = b.positionZ() - impactZ;

            final float distSq = dx * dx + dz * dz;

            if (distSq > splashRadiusSq)
            {
                continue;
            }

            // The Y check uses the impact's Y, not the
            // bot's. A bot that is much higher or much
            // lower than the impact is far enough in 3D
            // that the splash should not catch them.
            final float dy = b.positionY() - impactY;

            if (dy * dy > splashRadiusSq)
            {
                continue;
            }

            // Linear falloff: damage = centre * (1 - dist / radius).
            // The exact form keeps the centre damage at the
            // impact and 0 at the radius, and the same
            // shape on every peer (no random source in the
            // path).
            final float dist = (float) StrictMath.sqrt(distSq);

            final float falloff = 1.0f - (dist / splashRadius);

            final int splashDamage =
                (int) (Weapon.ROCKET_SPLASH_DAMAGE_CENTER * falloff);

            if (splashDamage <= 0)
            {
                continue;
            }

            if (b.damage(splashDamage))
            {
                this.botsKilled = botsKilled + 1;

                countTowardTheStreak();
            }
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

        // CTF win conditions: a team reaches the capture limit, or the
        // time limit is reached. Checked AFTER the living-bots rule
        // because the legacy demo runs without a spec (no CTF check)
        // and the bots-alive rule is the only one that fires there.
        if (mapSpec != null && mode() == MatchMode.CTF)
        {
            if (ctfRedCaptures >= CTF_CAPTURE_LIMIT
                || ctfBlueCaptures >= CTF_CAPTURE_LIMIT
                || ctfElapsedTics >= CTF_TIME_LIMIT_TICS)
            {
                return MatchState.WON;
            }
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

    /** How badly this match's opponents shoot. Never null. */
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

    /**
     * Returns the map spec this match is running, or null for the legacy
     * single-room demo.
     *
     * <p>Null is a supported state. The pre-Pass-1 demo has no spec — the
     * demo room is hardcoded into {@code DemoScene} — and the match layer
     * runs unchanged on it. A spec is only required when the match is
     * running on one of the 16 library maps, because that is where the
     * mode-specific rules live.</p>
     *
     * @return the spec, or null
     */
    public MapSpec mapSpec()
    {
        return mapSpec;
    }

    /**
     * Returns the mode the match is running, or {@link MatchMode#TDM} for
     * the legacy demo (which has no spec and runs a vanilla TDM).
     *
     * <p>The default TDM keeps the pre-Pass-1 demo running unchanged; a
     * spec'd match returns the spec's mode.</p>
     *
     * @return the mode, never null
     */
    public MatchMode mode()
    {
        if (mapSpec == null)
        {
            return MatchMode.TDM;
        }

        return mapSpec.mode();
    }

    /**
     * Returns the per-team score for the current round, indexed by team id
     * (0 = red, 1 = blue). A team with no score yet returns 0.
     *
     * <p>Mode-specific: TDM (and the no-spec legacy demo) returns the
     * shared kill count in the red slot and 0 in the blue slot — the
     * legacy demo has no team semantics, so a single score on the red
     * slot is the closest one can do. Hardpoint returns the two
     * per-team scores accumulated by zone captures. Domination returns
     * the per-team scores accumulated by flag holds. CTF returns the
     * per-team capture counts.</p>
     *
     * @return a two-element array of red score, blue score
     */
    public int[] teamScores()
    {
        final int[] out = teamScoreScratch;

        if (mapSpec == null)
        {
            out[0] = botsKilled;
            out[1] = 0;

            return out;
        }

        switch (mode())
        {
            case TDM:
                out[0] = botsKilled;
                out[1] = 0;
                break;
            case HARDPOINT:
                out[0] = hardpointRedScore;
                out[1] = hardpointBlueScore;
                break;
            case DOMINATION:
                out[0] = dominationRedScore;
                out[1] = dominationBlueScore;
                break;
            case CTF:
                out[0] = ctfRedCaptures;
                out[1] = ctfBlueCaptures;
                break;
            default:
                out[0] = 0;
                out[1] = 0;
                break;
        }

        return out;
    }

    /**
     * Sets the local player's team.
     *
     * <p>The player has to be on a team for Hardpoint zone captures
     * (and the future Domination / CTF mode rules) to credit the
     * player. The legacy single-player demo leaves this at the default
     * {@link Team#NEUTRAL}, which means the player is a body in the
     * room but not a body on a side.</p>
     *
     * <p>Setting the team does not reset the match; if the player
     * switches sides mid-round the new team takes over from the next
     * tic. This is the correct behaviour for a menu-driven team
     * change and the deliberate one for the smoke-test path, which
     * never calls this method.</p>
     *
     * @param team the player's new team; must not be null
     * @throws IllegalArgumentException if {@code team} is null
     */
    public void setPlayerTeam(final Team team)
    {
        if (team == null)
        {
            throw new IllegalArgumentException("team must not be null");
        }

        this.playerTeam = team;
    }

    /**
     * Returns the local player's team. Defaults to {@link Team#NEUTRAL}
     * for matches that have no team assignment (the legacy demo and the
     * headless smoke test).
     *
     * @return the player's team, never null
     */
    public Team playerTeam()
    {
        return playerTeam;
    }

    /**
     * Returns which team currently holds the active Hardpoint zone.
     *
     * <p>Useful for the HUD: a body on a side reads the held team from
     * this accessor, sees its own team, and knows it is on the point.
     * {@link Team#NEUTRAL} means the zone is empty or contested.</p>
     *
     * @return the active holder, never null
     */
    public Team hardpointActiveHolder()
    {
        return hardpointActiveHolder;
    }

    /**
     * Returns the index of the currently active Hardpoint zone, in the
     * order the spec declared them. Zero before the first rotation.
     *
     * @return the active zone index
     */
    public int hardpointActiveZoneIndex()
    {
        return hardpointActiveZone;
    }

    /**
     * Returns the team that currently owns a Domination flag, by index.
     *
     * <p>Useful for the HUD: a body on a side reads the flag owner
     * from this accessor and sees its own team, knowing it is on
     * the point. {@link Team#NEUTRAL} means the flag is unclaimed.
     * The same accessor covers the contested case — a contested
     * flag does not switch sides, so the index returns the team
     * that held it before the contest.</p>
     *
     * @param flagIndex the flag index, 0..2
     * @return the owning team, never null
     * @throws IllegalArgumentException if the index is out of range
     */
    public Team dominationFlagOwner(final int flagIndex)
    {
        if (flagIndex < 0 || flagIndex >= dominationFlagOwners.length)
        {
            throw new IllegalArgumentException("flagIndex out of range: " + flagIndex);
        }

        return dominationFlagOwners[flagIndex];
    }

    /**
     * Returns red team's CTF capture count.
     *
     * <p>Zero when the match is not CTF, and the value is meaningless
     * between rounds. The match ends when either team's count reaches
     * {@link #CTF_CAPTURE_LIMIT}, at which point {@link #state()} returns
     * {@link MatchState#WON}.</p>
     *
     * @return red team's capture count
     */
    public int ctfRedCaptures()
    {
        return ctfRedCaptures;
    }

    /**
     * Returns blue team's CTF capture count. See {@link #ctfRedCaptures()}.
     *
     * @return blue team's capture count
     */
    public int ctfBlueCaptures()
    {
        return ctfBlueCaptures;
    }

    /**
     * Returns who is carrying RED's flag, or {@code null} if the flag is
     * on its base.
     *
     * <p>The standard COD rule: only the local player carries; bots never
     * pick up. The only legal non-null value is {@link Team#BLUE}, because
     * a RED player on their own flag is "touching base", not "carrying
     * it".</p>
     *
     * @return the carrier team, or null if the flag is at home
     */
    public Team ctfRedFlagCarrier()
    {
        return ctfRedFlagCarrier;
    }

    /**
     * Returns who is carrying BLUE's flag, or {@code null} if the flag is
     * on its base. The mirror of {@link #ctfRedFlagCarrier()}.
     *
     * @return the carrier team, or null if the flag is at home
     */
    public Team ctfBlueFlagCarrier()
    {
        return ctfBlueFlagCarrier;
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

        // Aim at where the player is RIGHT NOW, not the bot's stale memory.
        // The memory lags the player by a reaction tic or more, which would
        // make every moving bot a guaranteed miss; the parameter the
        // gameplay port hands in is the player's actual feet position, and
        // using it is what "lines up the projectile with the player's
        // movement" means in practice. The bot still aims from its own
        // current (moving) position, so the projectile's origin tracks
        // the bot's route and the aim tracks the player's.
        final float toX = playerFeetX - shooter.positionX();

        final float toZ = playerFeetZ - shooter.positionZ();

        final float groundDistanceSquared = toX * toX + toZ * toZ;

        if (groundDistanceSquared > BOT_RANGE_UNITS * BOT_RANGE_UNITS)
        {
            return false;
        }

        if (groundDistanceSquared == 0.0f)
        {
            // Standing exactly where the player is. There is no direction to
            // shoot in, and atan2(0, 0) would answer zero — which is a real
            // heading and would therefore be a lie.
            return false;
        }

        // The shot is aimed LEVEL, at where the player is right now.
        // Nothing about the player's height is remembered and nothing needs
        // to be: the room's floor is flat, every eye is at the same 41
        // units, and a level shot from one eye toward another passes
        // through the chest of a 56-unit body. Modelling the vertical
        // would be modelling a constant.
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

        // Wall test. A bot behind a wall that can see the player (a thin
        // rail, a half-height partition, anything the sightline reaches but
        // a body cannot cross) is the case the "pay attention to
        // collisions" requirement is for: a hitscan alone would arrive at
        // the player on the other side, and that is the failure mode. The
        // 2D world raycast is the same shape PhysicsWorld.slideX/Z use for
        // movement, and the comparison is the 2D ground distance to the
        // player: a wall in the way at less than the player's distance
        // blocks the shot. Skipped on a null world (the demo, the headless
        // smoke path) so a test that has not wired a scene still fires.
        final PhysicsWorld world = shooter.world();

        if (world != null)
        {
            final float groundDistance = (float) StrictMath.sqrt(groundDistanceSquared);

            final float invDistance = 1.0f / groundDistance;

            final float aimX2D = toX * invDistance;

            final float aimZ2D = toZ * invDistance;

            final float wallDistance = world.raycastDistance(shooter.positionX(), shooter.positionZ(),
                aimX2D, aimZ2D);

            if (wallDistance < groundDistance)
            {
                // The bot's hit attempt is over; the scatter shot was a
                // bolt drawn from the muzzle and it stopped at a wall. No
                // record past this point is meaningful, and the visible
                // bolt has already been queued above.
                return false;
            }
        }

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

        populateShotSceneFor(shooter, playerFeetX, playerFeetY, playerFeetZ);

        if (!Hitscan.fire(shooter.positionX(), shooter.eyeY(), shooter.positionZ(),
            dirX, dirY, dirZ, shotSceneScratch, shotSceneCount, hit))
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
    // outgoing shot. Fills {@link #shotSceneScratch} in place and writes
    // the populated length into {@link #shotSceneCount} so the caller can
    // pass it to {@link Hitscan#fire} as the count parameter rather than
    // the array's capacity.
    //
    // Allocation-free on the hot path: the player's slot is rewritten via
    // {@link Target#aroundFeetInto} because the player moves every tic,
    // and every bot slot is overwritten with the bot's own pre-allocated
    // {@link Bot#hitbox()} on each call.
    private void populateShotSceneFor(final Bot shooter, final float playerFeetX,
        final float playerFeetY, final float playerFeetZ)
    {
        Target.aroundFeetInto(shotSceneScratch[0], PLAYER_ENTITY_ID, playerFeetX, playerFeetY,
            playerFeetZ, Bot.RADIUS_UNITS, Bot.HEIGHT_UNITS);

        int next = 1;

        for (int index = 0; index < bots.length; index++)
        {
            if (bots[index] != shooter && bots[index].isAlive())
            {
                // Copy the bot's pre-allocated hitbox into the scratch slot
                // because Hitscan is told only the populated prefix — the
                // scratch's tail must not contain stale references that
                // would extend the trace beyond the intended targets.
                final Target box = bots[index].hitbox();

                shotSceneScratch[next].write(box.entityId(), box.minX(), box.minY(), box.minZ(),
                    box.maxX(), box.maxY(), box.maxZ());

                next++;
            }
        }

        shotSceneCount = next;
    }

    // Hitboxes for every living bot, for the player's outgoing shot. Fills
    // {@link #livingSceneScratch} in place and writes the populated length
    // into {@link #livingSceneCount}.
    private void populateLivingBotTargets()
    {
        int next = 0;

        for (int index = 0; index < bots.length; index++)
        {
            if (bots[index].isAlive())
            {
                final Target box = bots[index].hitbox();

                livingSceneScratch[next].write(box.entityId(), box.minX(), box.minY(), box.minZ(),
                    box.maxX(), box.maxY(), box.maxZ());

                next++;
            }
        }

        livingSceneCount = next;
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
