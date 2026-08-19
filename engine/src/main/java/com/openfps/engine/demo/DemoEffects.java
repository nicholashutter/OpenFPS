/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import com.openfps.engine.render.adapter.Mat4;
import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.engine.render.adapter.Rgba;
import com.openfps.engine.render.adapter.Scene;
import com.openfps.engine.render.adapter.SoftwareRenderPort;

/**
 * What a shot looks like: a tracer flying down the aim ray and a puff of smoke
 * at the muzzle.
 *
 * <h2>Pre-allocated instances, moved — never added</h2>
 *
 * <p>{@link Scene} is immutable and nothing can be added to one after it is
 * built. That is load-bearing rather than inconvenient: it is what makes
 * rendering a scene allocate nothing and share safely across the render
 * workers. So an effect is not created when it is fired — <b>every instance it
 * will ever need is placed in the scene before the loop starts</b>, and firing
 * moves one, exactly as a walking bot moves one
 * ({@code SoftwareRenderPort.setWorldTransform}).</p>
 *
 * <p>An instance that is not currently part of a live effect is <b>hidden with
 * a degenerate transform</b> — {@link #HIDDEN}, which is all zeroes bar the
 * bottom row. It collapses every vertex onto one point, so every triangle has
 * zero screen area and {@code Rasterizer} rejects it before it reaches a pixel.
 * {@link Scene.Builder} would refuse such a transform outright, and rightly:
 * as a <i>placement</i> it is meaningless. As an override it is the cheapest
 * possible "not right now" — one reference store, no pass structure changed,
 * nothing re-derived.</p>
 *
 * <h2>The counts are fixed, and the pool wraps rather than fails</h2>
 *
 * <p>{@link #MAX_TRACERS} and {@link #MAX_PUFFS} are hard maxima, chosen
 * against the weapon's rate of fire rather than picked round:
 * {@code DemoGameplayPort.FIRE_INTERVAL_TICS} is 12, so a shot lands every 12
 * tics and {@link #PUFF_LIFE_TICS} of 36 means at most three puffs overlap. The
 * pools are one larger than that arithmetic needs, and a spawn that finds every
 * slot busy <b>overwrites the oldest</b> rather than being dropped. Round-robin
 * makes that O(1) and total: there is no failure path to test, and the worst
 * case is that a tracer nobody was looking at disappears a few tics early.</p>
 *
 * <p><b>That overlap is the point and not a cost.</b> A tracer lives 8 tics
 * against a 12-tic cadence, so roughly one frame in three legitimately has
 * nothing in the air — which is correct for a bolt, since a bolt has left. Smoke
 * has not left. Sized under the gap it would strobe at five hertz; at 36 tics it
 * always has two older puffs behind it, and a held trigger reads as one cloud
 * being fed rather than as a row of separate ones.</p>
 *
 * <h2>Why the smoke is thirty instances per puff</h2>
 *
 * <p>Two independent multipliers, and each of them is forced by something the
 * renderer will not let a single instance do.</p>
 *
 * <p><b>{@link #PUFF_STAGES}, because a puff has to fade</b>, and coverage is
 * fixed when a {@link Scene} is built — see {@code Scene}'s own note on why that
 * is a deliberate immutability rather than an oversight. So one puff owns one
 * set of instances per rung of a descending coverage ladder, and exactly one
 * rung is visible at a time: the puff ages, the stage advances, the previous
 * stage is hidden and the next is shown. The fade is a staircase rather than a
 * ramp, which at 60 Hz over a step every six tics nobody can see, and it costs
 * the render port nothing per frame — the hidden stages are culled before the
 * rasterizer.</p>
 *
 * <p><b>{@link #PUFF_LOBES}, because a puff has to be soft</b>, and a single
 * instance at a single coverage has a hard edge and a flat middle by
 * construction. Five overlapping spheres composite over each other, so one
 * coverage becomes five densities on screen. That constant's Javadoc has the
 * argument; {@link #PUFF_COVERAGE} has the arithmetic.</p>
 *
 * <p>Thirty instances a puff and a hundred and twenty in the pool sounds like a
 * lot and costs almost nothing: at most one puff's worth is ever visible per
 * rung, all five lobes of a rung share a coverage so they are one run in the
 * back-to-front sort and one batched pass between them, and everything else in
 * the pool is a degenerate transform {@code sortBackToFront} drops on a frustum
 * test before the rasterizer is ever asked. What the pool size does buy is a
 * per-frame cull over 120 entries instead of 36 — an insertion sort's worth of
 * comparisons over the handful that survive, which is not a number worth
 * optimising against a room that submits thousands of triangles.</p>
 *
 * <p>Three puffs alive at three different ages is three different coverages,
 * hence three runs and three batched passes rather than the one a single puff
 * costs. That is the price of the overlap that makes the effect continuous, and
 * it is paid only while the trigger is held.</p>
 *
 * <p>The <b>expansion</b> is continuous, because scale lives in the transform
 * and the transform is the thing that is allowed to change.</p>
 *
 * <h2>Allocation</h2>
 *
 * <p>All effect state is primitive arrays sized at construction. A tic
 * allocates one {@link Mat4} per <i>visible</i> effect and nothing else —
 * {@code setWorldTransform} takes a matrix, so that is the price of the seam,
 * and it is the same price {@code DemoScene.botPlacement} already pays per bot.
 * A slot that is hidden and was hidden last tic is not republished at all, so
 * an idle trigger costs nothing whatsoever.</p>
 */
public final class DemoEffects
{
    /**
     * Smoke-puff generation is off by default while the implementation is
     * being reworked. Set to {@code true} to bring the puffs back.
     *
     * <p>Non-final so a test that exercises the puff path can flip it
     * on for the duration of one test. The smoke tests do exactly
     * that — without the flag, a regression in {@code spawnPuff}
     * would be invisible because nothing would ever call it. Production
     * code leaves it alone.</p>
     *
     * <p>The puff instances are still pre-allocated in the scene (the
     * pool shape is fixed at build time, so removing the pool would
     * also remove the call sites that depend on its size), but with
     * the flag off every spawn call returns without touching the
     * pool and every existing puff ages out within
     * {@link #PUFF_LIFE_TICS} tics. The per-tic pressure on the
     * back-to-front sort — the part the sort's own comment identifies
     * as "the largest single barrier saving in the demo scene" —
     * disappears entirely once the existing puffs have died.</p>
     */
    public static boolean SMOKE_ENABLED = false;

    /** Tracers of the PLAYER'S that can be in the air at once. */
    public static final int MAX_TRACERS = 3;

    /**
     * Tracers of the BOTS' that can be in the air at once - <b>64</b>.
     *
     * <p><b>2026-08:</b> raised from 8 to 64 along with the
     * {@code Match.DEFAULT_BOT_COUNT} bump from 7 to 32 (commit pending).
     * The 16 shipped maps have 21-30 waypoints, and the visual scene
     * in {@code MapScene} now renders all of them while the simulation
     * also drives all of them - a cap of 8 tracers across 30+ bots
     * meant the round-robin was overwriting the player's view of
     * incoming fire every tic.</p>
     *
     * <p>The derivation that drove 8 - "no bot can have two bolts in
     * the air at once, so the whole room caps at 7, plus one spare" -
     * is stale: {@link #BOT_TRACER_LIFE_TICS} was bumped to 80 tics
     * (see the constant's own Javadoc for why) and 80 is greater than
     * {@code BotSkill.DUMB.cooldownTics()} = 45, so a bot CAN have
     * two bolts alive at once. With 32 bots the worst case is 64
     * bolts, which is what the pool is sized for.</p>
     *
     * <p>32 at once is a volley the map rooms produce by coincidence:
     * measured, {@link com.openfps.engine.gameplay.BotSkill#DUMB} bots
     * fire roughly once every 18 tics <i>between them</i>, so the
     * ordinary picture is well under half a bolt per bot in the air.
     * The pool is sized for the worst case because the alternative is
     * that the busiest moment in a round - the one moment the player
     * most needs to read - is the one that silently drops bolts.</p>
     */
    public static final int MAX_BOT_TRACERS = 64;

    /**
     * Smoke puffs of the BOTS' that can be visible at once - <b>80</b>.
     *
     * <p><b>2026-08:</b> raised from 15 to 80 along with the
     * {@code Match.DEFAULT_BOT_COUNT} bump from 7 to 32 (commit pending).
     * Same arithmetic as {@link #MAX_BOT_TRACERS}: with
     * {@link #PUFF_LIFE_TICS} at 48 and {@code BotSkill.DUMB.cooldownTics()}
     * at <b>45</b>, a bot's second puff is born before the first has
     * expired, so a single bot can have two puffs alive. 32 bodies times
     * two is 64, plus headroom for the round-robin's "overwrite the
     * oldest" policy is 80.</p>
     *
     * <p>The trade is exact: a pool of 64 would let the round-robin eat
     * the older puff on the busiest tic, and the player would see a smoke
     * that blinks rather than the cloud they expected. 80 keeps the
     * headroom without becoming a memory anchor, and the per-frame cost
     * is still 80 degenerate transforms the cull drops before the
     * rasterizer.</p>
     */
    public static final int MAX_BOT_PUFFS = 80;

    /**
     * Muzzle flashes of the PLAYER'S that can be in the air at once — <b>2</b>.
     *
     * <p>Two slots, because a flash lives {@link #FLASH_LIFE_TICS} of 2 tics
     * and the trigger can fire every {@code FIRE_INTERVAL_TICS} of 12. The
     * probability of overlap is roughly one in six — the trigger has to come
     * up on the second tic of a live flash — and one spare slot is what turns
     * "the muzzle goes dark on a held trigger" into a thing that can only
     * happen under deliberately bad luck. The same shape {@link #MAX_TRACERS}
     * follows for the same reason.</p>
     */
    public static final int MAX_PLAYER_FLASHES = 2;

    /**
     * Muzzle flashes of the BOTS' that can be in the air at once - <b>32</b>.
     *
     * <p><b>2026-08:</b> raised from 8 to 32 along with the
     * {@code Match.DEFAULT_BOT_COUNT} bump from 7 to 32 (commit pending).
     * A flash lives 2 tics and the slowest bot cooldown is 45, so a
     * single bot cannot have two flashes alive. 32 bodies is the upper
     * bound, with no spare needed because the round-robin can only
     * overwrite a flash the player never saw for more than one
     * tic.</p>
     */
    public static final int MAX_BOT_FLASHES = 32;

    /** Tics a tracer flies before it is hidden. */
    public static final int TRACER_LIFE_TICS = 8;

    /**
     * Smoke puffs that can be visible at once — <b>4</b>.
     *
     * <p>Derived from the other two numbers rather than chosen:
     * {@code DemoGameplayPort.FIRE_INTERVAL_TICS} is 12 and
     * {@link #PUFF_LIFE_TICS} is 36, so a held trigger keeps three puffs in the
     * air at all times, and the pool is one larger than that arithmetic needs
     * for the reason the class Javadoc gives.</p>
     */
    public static final int MAX_PUFFS = 4;

    /**
     * Tics a puff lives — <b>48</b>, which is eight tenths of a second.
     *
     * <p><b>Raised from 36 along with the size bump below, and for the same
     * reason.</b> A held trigger fires every 12 tics, and at 36 the smoke was
     * three puffs deep — a cloud that is present in motion but thins out
     * before the magazine runs dry. At 48 there is always a fresh puff
     * arriving into three older ones, and the cloud is the dominant feature
     * of the muzzle for the whole burst rather than a brief punctuation. A
     * puff that hangs on the air for four fifths of a second is the kind of
     * effect a held trigger reads as.</p>
     */
    public static final int PUFF_LIFE_TICS = 48;

    /**
     * Coverage rungs a puff fades down — <b>6</b>.
     *
     * <p>Raised from 4 with {@link #PUFF_LIFE_TICS}, to hold the step interval
     * where it was: 36 tics over six rungs is a step every six tics, near enough
     * the 4-5 of the old ladder. Keeping four rungs over a doubled life would
     * have made each step nine tics apart, which is long enough to see the fade
     * <i>tick</i> — and a staircase you can count is worse than no fade.</p>
     */
    public static final int PUFF_STAGES = 6;

    /**
     * Overlapping spheres one puff is built from — <b>5</b>.
     *
     * <p><b>This is what stopped the smoke reading as a block.</b> A puff used
     * to be one cube, and a cube composited at a uniform coverage has a hard
     * straight edge and a flat interior — it is a translucent <i>object</i>, and
     * the eye reads objects. Smoke has no edge; it has a dense middle that
     * thins out.</p>
     *
     * <p>Overlapping lobes produce that for free, because {@code Rgba.srcOver}
     * is applied per instance and therefore <b>compounds where they
     * overlap</b>. A pixel covered by one lobe composites once, by two lobes
     * twice, by all five five times, so a single coverage per stage becomes a
     * five-step radial falloff on screen without the per-instance coverage ever
     * changing — which it cannot, because {@link Scene} fixes it at build time.
     * The compounding is the feature rather than a hazard to be avoided; the
     * whole of {@link #PUFF_COVERAGE} is chosen against it.</p>
     *
     * <p><b>Raised from three, because the cloud got bigger and three lumps do
     * not fill a 230-pixel silhouette.</b> At the old size three spheres read as
     * a cloud; at {@link #PUFF_RADIUS_END} they read as three balls in a bag,
     * with visible straight gaps between them where the falloff should have
     * been. Five is also what buys the density back: a five-deep compound
     * reaches the 0.82 core {@link #PUFF_COVERAGE} is solved for while its
     * outermost lobe still only composites once, so the cloud gets both a
     * thicker middle and a softer rim from the same change.</p>
     *
     * <p>Five and not more: every lobe is another 24 instances in the pool
     * ({@code MAX_PUFFS x PUFF_STAGES x PUFF_LOBES}), another sphere in the
     * back-to-front sort, and one more {@code Mat4} per tic per visible puff.
     * Five already gives a lopsided silhouette and five density levels, which is
     * as much structure as a grey blob can carry at any size.</p>
     */
    public static final int PUFF_LOBES = 5;

    /**
     * World units a tracer covers per tic — 60.
     *
     * <p><b>Bounded by the room, not by realism.</b> The demo room's interior
     * is 640 units across and the player spawns 192 back from its centre, so
     * the far wall is about 512 units down the spawn bearing. Speed times
     * {@link #TRACER_LIFE_TICS} therefore has to stay under that or the bolt
     * spends most of its life outside the room, correctly depth-tested away by
     * the wall, and the effect looks broken. 60 x 8 is 480, which lands just
     * short. It first shipped at 140, which put the bolt through the wall after
     * three tics and made it invisible in exactly the screenshot taken to
     * check it.</p>
     *
     * <p>60 units a tic at 60 Hz is 3,600 units a second, about fourteen times
     * the player's own speed, which is what makes it read as fired rather than
     * thrown.</p>
     */
    public static final float TRACER_SPEED_UNITS = 60.0f;

    /**
     * Tics an INCOMING tracer flies before it is hidden — <b>80</b>.
     *
     * <p><b>2026-08:</b> raised from 20 to 80 along with the
     * {@code Match.BOT_RANGE_UNITS} bump from 512 to 2048 (commits
     * 15f00cb / c419c58 / 7675496 / ec7be2e scaled the 16 maps from
     * 20m to 200-350m). The original 20 was picked against the
     * original 32 x 20 = 640-unit reach, which was 25% past the
     * 512-unit range; the new 80 keeps the 32 x 80 = 2560-unit
     * reach 25% past the 2048-unit range. The 10x of TRACER_LIFE_TICS
     * falls out of the 4x range bump, not a deliberate design choice
     * — an incoming bolt's life is dominated by "how long does it
     * take to reach the eye", and the eye is further away now.</p>
     *
     * <p>Two and a half times {@link #TRACER_LIFE_TICS} in the demo's
     * 320x320 room has to become ten times the outgoing life in the
     * 3200-5600 map rooms, and it has to be: an outgoing bolt and an
     * incoming one have opposite problems. The player's recedes, so
     * it is at its most visible on the tic it is born and the only
     * question is how long it stays interesting. An incoming bolt is
     * a dot at the far wall that grows as it arrives, so <b>the whole
     * of its life is approach</b>, and the flight has to be long
     * enough to watch.</p>
     *
     * <p>With {@link #BOT_TRACER_SPEED_UNITS} this is what puts it on screen for a
     * length of time proportional to how far away the shooter is — which is what a
     * real projectile does, and is the property that lets a player tell a distant
     * threat from a close one without counting anything.</p>
     */
    public static final int BOT_TRACER_LIFE_TICS = 80;

    /**
     * How far short of where the shot ended an incoming bolt stops being drawn,
     * in world units — <b>64</b>, which is one body length.
     *
     * <h2>The part of the flight that is a screen flash rather than a tell</h2>
     *
     * <p>{@link #BOT_TRACER_LIFE_TICS} is a <b>ceiling</b>, not the life every
     * incoming bolt gets. The actual life is however many tics it takes to fly to
     * within this distance of the point the shot was aimed at, because a bolt
     * drawn all the way in keeps growing after it has stopped meaning anything.
     * The capture that produced this number is unambiguous: the last two frames
     * of an unbounded flight covered <b>62,000 and 73,600 pixels</b> — 7% and 8%
     * of the window — because by then the bolt was a few units from the eye and a
     * 16-unit box a few units away fills the screen. Two frames of a violet wall
     * is not a projectile, and the frames before it, where the actual information
     * is, were 5,700 and 32,600.</p>
     *
     * <p>64 units is roughly {@code Bot.HEIGHT_UNITS}, so the bolt is dropped a
     * body length out. Its last drawn frame is then about 156 pixels across —
     * large, unmistakably close, and still a bolt rather than a flash. Everything
     * closer than that happens inside the player's own head, and the shot's
     * outcome was decided by {@code Hitscan} long before it.</p>
     *
     * <p><b>This does not shorten a distant shot at all</b>, which is the point: a
     * bolt from the far wall still flies for thirteen tics. It removes the last
     * two tics of a flight that was going to end in the camera regardless of where
     * it started.</p>
     */
    public static final float INCOMING_STANDOFF_UNITS = 64.0f;

    /**
     * Fewest tics an incoming bolt is drawn for — <b>2</b>.
     *
     * <p>A floor under the arithmetic {@link #INCOMING_STANDOFF_UNITS} describes,
     * for the point-blank case where a bot is already inside the standoff and that
     * arithmetic gives zero or one. A bolt shown for a single frame is a flicker
     * a player cannot attribute to anything, and no bolt at all would make the one
     * shot they most need to notice — the one from two paces away — the only shot
     * with no tell.</p>
     */
    public static final int BOT_TRACER_MIN_LIFE_TICS = 2;

    /**
     * World units an INCOMING tracer covers per tic — <b>32</b>.
     *
     * <h2>It first shipped at 80, and the capture is what caught it</h2>
     *
     * <p>The reasoning for 80 was correct and the number was still wrong.
     * {@code Match.BOT_RANGE_UNITS} is 512, so a bolt does have to average at
     * least {@code 512 / life} units a tic or it stops short and hangs in the air
     * — which reads as a shot that gave up rather than one that missed. At the
     * player's 60 over an 8-tic life it fell 32 units short, so 80 fixed the
     * stated problem.</p>
     *
     * <p>Then a run of <b>ninety consecutive frames</b> was captured and the
     * violet counted on each one. Bolts appeared on <i>one frame each</i>, and
     * their coverage on that one frame ran from 441 pixels to <b>87,204</b> — a
     * ninth of the window. At 80 units a tic a shot from a bot 160 units away is
     * over in two frames: it exists as a dot, then as a wall of colour, then it is
     * gone. That is a strobe, not a tell, and no player could read a direction off
     * it. It would have passed every test in this file, because every one of them
     * asks where the bolt is and none of them asks how long it is there.</p>
     *
     * <p>32 with the original 20-tic life reached 640 units — past the far
     * end of the original 512-unit engagement envelope with 25% to spare —
     * while giving the flight a duration that scales with the range. The
     * 2026-08 map resize pushed the engagement envelope to 2048 units, so
     * the life was bumped to 80 tics for a 2560-unit reach — 25% past the
     * new range — and the duration-vs-range table the demo-era comment
     * lists now reads:</p>
     *
     * <pre>
     *   shooter at   time on screen
     *     100 u       3 tics    50 ms
     *     250 u       8 tics   130 ms
     *     400 u      13 tics   210 ms
     *    1000 u      31 tics   520 ms
     *    2000 u      63 tics  1050 ms
     * </pre>
     *
     * <p><b>2026-08:</b> the bot's range was bumped from 512 to 2048
     * world units (commits 15f00cb / c419c58 / 7675496 / ec7be2e
     * scaled the 16 maps from 20m to 200-350m), so the bolt's
     * reach (speed x life) must scale with it. The speed stays at
     * 32 units a tic (so a 250-unit shot is on screen for 7-8
     * tics, what a player reads as a flight) and the life was
     * bumped from 20 to 80 tics, for a 2560-unit reach — 25% past
     * the new range.</p>
     *
     * <p>1,920 units a second is seven and a half times the player's
     * own speed, which is what keeps it reading as fired rather
     * than thrown.</p>
     */
    public static final float BOT_TRACER_SPEED_UNITS = 32.0f;

    /** How long the tracer bolt is, along its direction of travel. */
    public static final float TRACER_LENGTH_UNITS = 46.0f;

    /**
     * How wide the tracer bolt is across that direction — 14 units.
     *
     * <p>Wide relative to its length on purpose. A shot travels <b>away</b>
     * from the eye that fired it, so the bolt is seen almost end-on and its
     * length is foreshortened to nearly nothing; what the player actually sees
     * is its cross-section, shrinking as it recedes. Sizing this from how a
     * tracer looks from the side would make it invisible from the one angle
     * that matters.</p>
     *
     * <p><b>Raised from 11 to 14 with the muzzle flash and the wider bolt
     * model.</b> A bolt that is 11 units wide is a smudge on screen at the
     * default 60 degree vertical field of view: 11 units subtends about
     * 30 pixels at 60 units away, which is what the eye sees on a typical
     * shot. 14 widens that to about 38 pixels — still a bolt rather than a
     * bloomy tracer, but one the eye reads as a real shot leaving the
     * muzzle. The smoke test in {@code DemoSmokeInMotionTest} tolerates
     * the wider bolt because the bolt is drawn in the opaque pass and
     * the depth test culls the smoke fragments that are at the same
     * depth as the bolt, which is what kept the test stable before the
     * halo was tried and dropped.</p>
     */
    public static final float TRACER_WIDTH_UNITS = 14.0f;

    /**
     * Tics a muzzle flash is visible — <b>2</b>.
     *
     * <p><b>Short on purpose.</b> A flash is a frame, not a glow: it marks
     * the instant the shot left the barrel, and a flash that lingered for a
     * quarter of a second would be a glowing ball that follows the muzzle
     * around between shots, which is the wrong effect. Two tics at 60 Hz is
     * 33 ms — shorter than the trigger cooldown at any sensible rate of
     * fire, so the flash never overlaps its own successor for the same
     * shooter.</p>
     */
    public static final int FLASH_LIFE_TICS = 2;

    /**
     * World-units radius of the player's own muzzle flash — <b>0.10</b>.
     *
     * <p><b>Smaller than the smoke's smallest lobe on purpose.</b> The flash
     * and the smoke are born at the same position, and a flash as wide as
     * the smoke draws in the opaque pass before the translucent smoke, so
     * the depth buffer records the flash's depth and the smoke's pixels
     * fail the depth test where the two overlap. The test that noticed this
     * failure counted the deepest pixel in the frame and found it
     * <i>lighter</i> than the wall — meaning no smoke pixel survived. A
     * 0.10-unit flash is the largest spot that fits inside the main lobe
     * of a fresh puff (the main lobe grows from 0.16 to 0.30), so the
     * offset lobes and the rim of the main lobe are still drawn.</p>
     *
     * <p>About 26 pixels across at 720p, which is the size a first-person
     * muzzle flash has had since the 1990s — large enough to read as a
     * flash and not a dust mote, small enough that the smoke around it
     * carries the rest of the visual.</p>
     */
    public static final float PLAYER_FLASH_RADIUS = 0.10f;

    /**
     * World-units radius of a bot's muzzle flash — <b>5</b>.
     *
     * <p>Sized the way {@link #BOT_PUFF_RADIUS_START} is: against the body
     * the flash comes out of, not against the pixel. A bot's body is 33
     * world units across, so a 5-unit flash reaches a seventh of the
     * shoulders at birth — a noticeable bloom that is plainly not the
     * trigger glow. A pixel-sized flash on a 250-unit-distant body is
     * invisible; this is what the eye is already looking at, and any
     * relative size scales itself correctly with the range for free.</p>
     */
    public static final float BOT_FLASH_RADIUS = 5.0f;

    /**
     * How wide an INCOMING tracer bolt is — <b>16</b> units.
     *
     * <p>Sized against the far end of the room rather than against the near end,
     * because that is where it is born. The same end-on argument
     * {@link #TRACER_WIDTH_UNITS} makes applies with the sign flipped: an
     * incoming bolt is also seen almost end-on, so what the player sees is its
     * cross-section — but it starts at up to {@code Match.BOT_RANGE_UNITS} = 512
     * units away and <b>grows</b> as it comes, where the player's own shrinks as
     * it goes.</p>
     *
     * <p>At 720p and the demo's 60 degree vertical field of view a world unit at
     * distance {@code d} is about {@code 624 / d} pixels, so 16 units is 19 px at
     * 512, 50 px at 200 and 100 px at 100 — a spot at the far wall that swells
     * into something unmissable as it arrives. At the player's 11 it would be 13
     * px at the far wall, which is a dust mote on a busy grey room.</p>
     */
    public static final float BOT_TRACER_WIDTH_UNITS = 20.0f;

    /**
     * How far in front of the eye the muzzle is, in world units — 2.4.
     *
     * <p>Small, and deliberately so: it is where the weapon <b>appears</b> to
     * be, not where a rifle barrel would be on a body. The viewmodel is a view
     * instance sized for the screen rather than for the world
     * ({@code DemoScene.WEAPON_VIEW_SCALE}), and it sits 1.85 view units ahead
     * of the eye. View units and world units are the same units once past the
     * camera — view space is the world, rotated and translated — so 2.4 puts
     * the effect just beyond the drawn muzzle. Anything larger would have the
     * smoke appear detached, out in the room ahead of the gun.</p>
     */
    public static final float MUZZLE_FORWARD_UNITS = 2.4f;

    /**
     * How far right of the eye the muzzle is, in world units — 1.55.
     *
     * <p><b>Measured off the rendered weapon, not copied from its origin.</b>
     * This used to be 0.9, to match {@code DemoScene.WEAPON_VIEW_RIGHT} of
     * 0.92 — but that constant places the weapon model's <i>origin</i>, and a
     * pistol's muzzle is at the far end of a barrel that runs forward and to
     * the right of it. At 0.9 the effect appeared over the middle of the gun,
     * which is not where a shot comes from and is the part of the frame the gun
     * body itself covers.</p>
     *
     * <p>1.55 puts it at the end of the drawn slide: at 720p and the demo's
     * field of view that is about 400 px right of centre, and the weapon's
     * barrel tip renders at about 430. Taken from a capture rather than from
     * the model's bounds, because {@code WEAPON_VIEW_SCALE} sizes the viewmodel
     * for the screen rather than for the world — where the muzzle
     * <i>appears</i> is the only definition that makes the smoke look attached
     * to the gun.</p>
     */
    public static final float MUZZLE_RIGHT_UNITS = 1.55f;

    /**
     * How far below the eye the muzzle is, in world units — 0.22.
     *
     * <p>Raised from 0.30 along with {@link #MUZZLE_RIGHT_UNITS} and for the
     * same reason: the barrel sits along the <i>top</i> of the slide, so the
     * muzzle is higher than the weapon's origin. It also lifts the puff clear
     * of the gun body, which was covering its lower half.</p>
     */
    public static final float MUZZLE_DROP_UNITS = 0.22f;

    /**
     * Half-extent of the <b>main lobe</b> when a puff is born, in world units —
     * 0.20.
     *
     * <p>Sub-unit numbers, which look wrong beside a 41-unit eye height until
     * you notice what they are measured against: the puff sits
     * {@link #MUZZLE_FORWARD_UNITS} from the eye, so its <i>apparent</i> size
     * is the ratio of the two. At 720p and the demo's field of view a world unit
     * at 2.4 units out is about 262 px, and the cloud reaches
     * {@link #cloudExtentRadii()} — roughly 1.46 — times this radius. So 0.20
     * is a cloud about 152 px across at the moment of the shot. Sized in the
     * room's units instead it would fill the screen.</p>
     *
     * <p><b>Raised from 0.16 with the lifetime bump.</b> A puff that lives
     * longer and starts no bigger would shrink the silhouette of the cloud
     * relative to its lifetime — the densest moment is the first one, and it
     * needs to be the largest one too. The growth ratio to
     * {@link #PUFF_RADIUS_END} stays the same, so the dispersal shape is
     * preserved.</p>
     */
    public static final float PUFF_RADIUS_START = 0.20f;

    /**
     * Half-extent of the <b>main lobe</b> at the end of a puff's life — 0.30.
     * See {@link #PUFF_RADIUS_START} for why these numbers are so small.
     *
     * <p>Kept at 0.30 with the {@link #PUFF_RADIUS_START} bump: the cloud
     * already starts at a denser size than before, and a bigger end radius
     * would push the silhouette past the "puff, not fog bank" ceiling the
     * lifetime-and-radius math defends. The growth ratio is now
     * {@code 0.30 / 0.20} = 1.5, down from 1.875 — slightly less dispersal,
     * paid for the bigger start. See {@link DemoEffectsTest$Visibility}
     * for the assertion that holds the line.</p>
     */
    public static final float PUFF_RADIUS_END = 0.30f;

    /**
     * Half-extent of the main lobe when a BOT'S puff is born, in world units —
     * <b>9</b>, which is forty-five times the player's.
     *
     * <h2>This is the number the whole feature would have died on</h2>
     *
     * <p>{@link #PUFF_RADIUS_START} is 0.20 <b>because the player's puff is 2.4
     * units from the eye</b> — its Javadoc says so at length, and reusing it for a
     * bot would have been the third time this project shipped an effect that was
     * measurably present and perceptually absent. A bot is not 2.4 units away. It
     * is somewhere between 60 and 512, and at 720p a world unit at distance
     * {@code d} subtends about {@code 624 / d} pixels. A 0.20-unit puff on a bot
     * 250 units off is <b>1.2 pixels across</b>. It would have been drawn, every
     * frame, exactly as designed, and it would have been one grey pixel.</p>
     *
     * <h2>Measured against the body, not against a pixel target</h2>
     *
     * <p>A pixel figure was tried first and it is the wrong instrument, because a
     * bot's distance is not a constant. Sizing for "40 px of cloud at a typical 250
     * units" gave 4.5, and the capture of that came back at <b>10 to 97 pixels</b>
     * per puff — the smoke bug again, at a twentieth of the size the very same
     * measurement had been used to reject once already. A cloud sized against one
     * nominal distance is badly sized at every other one.</p>
     *
     * <p>What does hold at every distance is the cloud's size <b>relative to the
     * body it comes out of</b> — because the body is what the eye is already
     * looking at, and it is what the player has to attribute the shot to. The cloud
     * reaches {@link #cloudExtentRadii()}, about 1.38, times the puff radius, so 9
     * is a cloud 25 units across against a body 33 wide and 56 tall: three quarters
     * of the shoulders at birth, growing past them by the end. That is a muzzle
     * bloom on a person rather than a smokescreen, and it scales itself correctly
     * with range for free.</p>
     *
     * <p><b>Raised from 7 along with the player's start radius</b>, keeping the
     * same body-fraction so a bot's muzzle reads as the same shape it did, just
     * bigger. The growth ratio to {@link #BOT_PUFF_RADIUS_END} is preserved.</p>
     */
    public static final float BOT_PUFF_RADIUS_START = 9.0f;

    /**
     * Half-extent of the main lobe at the end of a BOT'S puff, in world units —
     * <b>13</b>.
     *
     * <p>{@code 13 / 9} is 1.44, deliberately the same growth ratio the
     * player's puff has ({@code 0.30 / 0.20}). The expansion is what makes a
     * cloud read as dispersing rather than as a decal, and how much of it
     * there should be is a property of smoke rather than of distance — so the
     * ratio is shared and only the absolute size is re-derived. See
     * {@link #BOT_PUFF_RADIUS_START} for that derivation and the pixel
     * figures.</p>
     */
    public static final float BOT_PUFF_RADIUS_END = 13.0f;

    /**
     * World units a BOT'S puff drifts upward per tic — <b>0.26</b>.
     *
     * <p>Scaled with the cloud rather than with the room: over
     * {@link #PUFF_LIFE_TICS} the puff climbs about 9 units, which is a sixth of a
     * bot's height and about half the cloud's own diameter. That is the same
     * proportion the player's 0.006 has to the player's cloud — enough to say the
     * smoke is not a decal stuck to the world, not enough to turn it into a balloon
     * leaving the barrel.</p>
     */
    public static final float BOT_PUFF_RISE_UNITS = 0.26f;

    /**
     * Each lobe's offset from the puff centre <b>across</b> the shooter's view,
     * in multiples of the current puff radius.
     *
     * <p>Across and up rather than along world axes, and that is deliberate: a
     * spread laid out in world x and z would collapse into a single blob
     * whenever the player happened to be shooting along one of them, so the
     * cloud's shape would depend on which way they were facing. The offsets are
     * expressed in the shot's own basis — the same {@code across} the muzzle
     * offset uses, and {@code up = aim x across} — so the lumps are spread
     * across the screen from every angle.</p>
     *
     * <p>The first lobe is at the centre and is the largest; the rest sit off it
     * in four different directions, no two of them opposite, so the silhouette
     * is lopsided. A symmetric arrangement would read as a flower.</p>
     *
     * <p><b>Every offset was pulled IN when the fourth and fifth lobes arrived,
     * and the outriders were made bigger.</b> Kept at the three-lobe spacing,
     * five spheres of decreasing size read as bubbles: each one's circular edge
     * survived as an edge because there was nothing else covering it. Overlapping
     * them harder is what fuses the outlines — a lobe boundary that runs through
     * the middle of a neighbour is a density step and not a silhouette. The
     * measure of it is {@link #cloudExtentRadii()}, which comes down to about
     * 1.38 from 1.46: the extra lobes are here to fill the shape in, not to make
     * it larger. {@link #PUFF_RADIUS_END} owns the size, and it is the only thing
     * that should.</p>
     */
    public static final float[] LOBE_ACROSS = {0.00f, 0.50f, -0.42f, 0.24f, -0.56f};

    /** Each lobe's offset <b>up</b> the shooter's view. See {@link #LOBE_ACROSS}. */
    public static final float[] LOBE_UP = {0.00f, 0.30f, -0.26f, -0.52f, 0.18f};

    /**
     * Each lobe's radius as a fraction of the puff's. The centre lobe is full
     * size and the outriders get progressively smaller, which is what makes the
     * cloud read as one thing with bulges rather than as five balls.
     */
    public static final float[] LOBE_SCALE = {1.00f, 0.80f, 0.74f, 0.66f, 0.60f};

    /**
     * World units a puff drifts upward per tic — 0.005.
     *
     * <p>Tiny, and it has to be. The puff sits 2.4 units from the eye, so a
     * rise is magnified by the same ratio that makes
     * {@link #PUFF_RADIUS_START} small: over {@link #PUFF_LIFE_TICS} this is
     * 0.24 units, which is about 63 screen pixels at 720p. The drift is there
     * to say the cloud is not a decal stuck to the screen; a puff that climbed
     * a hundred pixels while it faded would be a balloon.</p>
     *
     * <p><b>Cut from 0.006 when the life grew from 36 to 48 tics</b>, so the
     * total travel is about what it always was. Without the cut, the cloud
     * would rise a third further over its longer life and end above the muzzle
     * rather than at it.</p>
     */
    public static final float PUFF_RISE_UNITS = 0.005f;

    /**
     * How many colour variants a puff can be — <b>3</b>.
     *
     * <p>Each variant is its own baked-in swatch: a warm grey, the neutral
     * grey, and a cool blue-grey. A puff claims one variant on spawn (round
     * robin, deterministic) and only the lobes of that variant are visible
     * for the puff's whole life, so two puffs in the air at once read as
     * slightly different clouds rather than as clones.</p>
     *
     * <p>Three is what buys visible variation without the pool tripling
     * beyond what a quiet trigger ever needs. Five variants would
     * distinguish every puff in a burst, but a magazine emptying into a
     * grey wall is not a thing the player is going to count, and a larger
     * variant count costs more scene instances up front.</p>
     */
    public static final int PUFF_COLOR_VARIANTS = 3;

    /** Sentinel age meaning "this slot holds no effect". */
    public static final int DEAD = -1;

    /**
     * The override that makes an instance invisible: everything zero but the
     * bottom row.
     *
     * <p>See the class Javadoc. Immutable and therefore shared — hiding costs
     * one reference store and no allocation at all, which matters because most
     * of these instances are hidden most of the time.</p>
     */
    public static final Mat4 HIDDEN = Mat4.ofRowMajor(new float[]
    {
        0.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 1.0f,
    });

    /** Hot amber, the colour of the player's own bolt in flight. */
    private static final int TRACER_COLOUR = Rgba.pack(255, 216, 112, 255);

    /**
     * Bright yellow-white, the colour of a muzzle flash.
     *
     * <p>Chosen by what a flash has to do, not from a palette. The flash
     * marks the moment a shot left the barrel, and what it has to be is
     * <i>brighter than anything else in the frame for a few frames</i> —
     * the lit walls sample at about {@code (141, 147, 177)} and a barrel
     * glow has to be plainly above that, not in it. The hue is warm to
     * read as "muzzle powder" rather than as "energy bolt"; the value
     * is at the ceiling so the rendered pixel carries every channel
     * clipped, and the blue channel being much lower than the red and
     * green is what stops it from being pure white and reading as a
     * frame artefact.</p>
     */
    private static final int FLASH_COLOUR = Rgba.pack(255, 244, 196, 255);

    /**
     * Electric violet, the colour of a bolt coming the other way.
     *
     * <p><b>Chosen by surveying what is already on screen, not from a palette.</b>
     * "A player must be able to tell at a glance that something is shooting at
     * them" is the requirement, and a glance is a hue judgement made in
     * peripheral vision — so the only question that matters is what else this
     * could be confused with:</p>
     *
     * <ul>
     *   <li><b>Hot amber</b> {@code (255, 216, 112)} is the player's own bolt, and
     *       the pair has to be unmistakable in <i>both</i> directions. Violet and
     *       amber differ by 144 levels of green and 143 of blue — the two channels
     *       the eye resolves best — while sharing a red channel, so they read as
     *       equally hot and completely different colours.</li>
     *   <li><b>Pure red</b> {@code (255, 0, 0)} is taken, by {@code OutlinePass}:
     *       it means "this is the one you are aiming at". Incoming fire is the
     *       opposite statement and must not borrow its colour, which rules out the
     *       obvious choice for hostile fire.</li>
     *   <li><b>Orange</b> is the crate stripes and the player's own pistol,
     *       <b>green</b> is the carbines the bots are holding, and the room itself
     *       is a desaturated blue-grey around {@code (141, 147, 177)}.</li>
     * </ul>
     *
     * <p>Violet is the one saturated hue this scene does not otherwise contain,
     * and it is nearly the complement of the room's own blue-grey, so a bolt is
     * high chroma against low chroma everywhere it can appear — including against
     * a wall, which is where a bolt aimed at the player spends most of its
     * flight.</p>
     */
    private static final int INCOMING_COLOUR = Rgba.pack(236, 72, 255, 255);

    /**
     * Dark warm grey, the colour of powder smoke — and it is dark on purpose.
     *
     * <p><b>Measured against the room it is composited over, not chosen from a
     * palette.</b> The Kenney room's lit walls and floor sample at about
     * {@code (141, 147, 177)}, a pale grey-blue. This used to be
     * {@code (158, 158, 152)}, which is that colour with the blue taken out and
     * almost nothing else changed — the whole puff resolved to within about ten
     * levels of the background at its most opaque rung. It was drawn correctly,
     * every frame, and it was invisible; the player's report was "the smoke is
     * not there", and they were right.</p>
     *
     * <p>Dark rather than light because the room is light: there are only 114
     * levels of headroom upward from the background and 141 downward, and the
     * pale end of that range is where the walls, the ceiling and the doorway
     * trim already live. But <b>not as dark as it can be</b> — {@code (74, 72,
     * 68)} was tried and overshot, resolving to about {@code (84, 84, 91)} at
     * the first rung, which reads as a solid black block rather than as smoke.
     * Something you cannot see through is not a cloud, whatever its
     * coverage.</p>
     *
     * <p>{@code (92, 90, 86)} lands the freshest puff's <b>core</b> near
     * {@code (101, 100, 102)} against that wall: a drop of some forty levels,
     * plainly visible, with nearly a fifth of the background still showing
     * through it. Its rim lands near {@code (127, 130, 151)}, a wisp rather than
     * a boundary.</p>
     *
     * <p>The colour has now survived the move from one cube to three spheres and
     * then to five, and a doubling of both the life and the size, unchanged —
     * which is the point of having derived it. Every time the shape of the effect
     * changed, {@link #PUFF_COVERAGE} was solved to put the composited core back
     * where this colour had already been judged to work, rather than the colour
     * being re-tuned around a new ladder.</p>
     */
    private static final int SMOKE_COLOUR = Rgba.pack(92, 90, 86, 255);

    /**
     * Variant 0 of the smoke colour: a touch warmer than the neutral
     * default. The slight red shift reads as "freshly fired" against the
     * pale blue-grey room.
     */
    private static final int SMOKE_COLOUR_WARM = Rgba.pack(108, 96, 80, 255);

    /**
     * Variant 1 of the smoke colour: the neutral grey, identical to the
     * previous single value. The middle rung of the variant ladder.
     */
    private static final int SMOKE_COLOUR_NEUTRAL = Rgba.pack(92, 90, 86, 255);

    /**
     * Variant 2 of the smoke colour: a touch cooler, with the blue shift
     * reading as "settling" against the same room.
     */
    private static final int SMOKE_COLOUR_COOL = Rgba.pack(80, 96, 108, 255);

    /**
     * Every smoke colour variant, indexed by variant. The size is
     * {@link #PUFF_COLOR_VARIANTS} and the contents are the three constants
     * above. A new variant is one new constant and one new array entry.
     */
    private static final int[] SMOKE_COLOURS =
    {
        SMOKE_COLOUR_WARM,
        SMOKE_COLOUR_NEUTRAL,
        SMOKE_COLOUR_COOL,
    };

    /**
     * Coverage of <b>one lobe</b> at each puff stage, faintest last.
     *
     * <p>Every rung is a distinct blended {@code SpanRenderer} in the render
     * port and a potential extra batched pass, so this is six values rather
     * than thirty-six. Six steps over {@link #PUFF_LIFE_TICS} is a step every
     * six tics, which reads as a fade. All {@link #PUFF_LOBES} lobes of a puff
     * share the stage's coverage, so they are one contiguous run in the
     * back-to-front sort and cost one pass between them.</p>
     *
     * <p><b>These are per-lobe figures and they are far lower than the
     * coverage anyone would write down for the density they produce, because
     * the lobes compound.</b> Coverage {@code a} applied {@code n} times leaves
     * {@code 1 - (1 - a)^n} of the smoke, so the numbers are solved backwards
     * from what the <i>composite</i> should be rather than picked:</p>
     *
     * <pre>
     *   stage  per lobe   1 lobe   2 lobes   3 lobes   4 lobes   5 lobes
     *     0        74       0.29     0.50      0.64      0.75      0.82
     *     1        59       0.23     0.41      0.55      0.65      0.73
     *     2        45       0.18     0.32      0.44      0.54      0.62
     *     3        32       0.13     0.24      0.33      0.41      0.49
     *     4        20       0.08     0.15      0.22      0.28      0.34
     *     5        10       0.04     0.08      0.11      0.15      0.18
     * </pre>
     *
     * <p>Composited over the demo room's lit wall at {@code (141, 147, 177)},
     * the core of a fresh puff lands near {@code (101, 100, 102)} — forty levels
     * down on red, forty-seven on green and seventy-five on blue, which is the
     * whole reason {@code smokeColour} is warm: it does not just darken the
     * wall, it takes the blue out of it, and that is a second signal on top of
     * the first. Its rim lands near {@code (127, 130, 151)}, a wisp rather than
     * a boundary. The final rung is about {@code (132, 137, 161)} — a faint
     * haze, so the staircase ends in a fade rather than in a disappearance.</p>
     *
     * <p><b>The top rung deliberately does not go higher.</b> Something you
     * cannot see through is a hole in the room and not a cloud, and that
     * overshoot has already happened once here — 228 on an older ladder, with a
     * dark enough colour, produced a black block. At 0.82 composite, nearly a
     * fifth of the wall still shows through the thickest point of the thickest
     * rung, and that point is the small five-deep overlap at the middle rather
     * than the whole cloud.</p>
     */
    private static final int[] PUFF_COVERAGE = {74, 59, 45, 32, 20, 10};

    /** Coordinates per position or direction triple. */
    private static final int AXES = 3;

    /**
     * Below this, a direction is treated as parallel to world up and the tracer
     * basis is built against a different reference axis.
     *
     * <p>Not a tolerance to be tuned: looking straight up or straight down is a
     * thing a player does constantly, and {@code cross(up, aim)} is exactly zero
     * there. Without this the tracer's basis would be all NaN and the instance
     * would vanish — silently, and only while aiming at the ceiling.</p>
     */
    private static final float PARALLEL_EPSILON = 1.0e-4f;

    /** Corners of a box. */
    private static final int BOX_VERTICES = 8;

    /**
     * Meridians round the smoke sphere — <b>12</b>.
     *
     * <p>Coarse on purpose, and sized against the picture rather than against
     * taste. <b>Sized against the DENSE rung, which is the small one.</b> The
     * only lobe edge a player can resolve is one with enough coverage behind it
     * to be a boundary, and coverage falls as the puff grows: a fresh puff's main
     * lobe is about 84 px across at 720p, so twelve facets put a corner every
     * 22 px of its silhouette. The 157 px lobe at the end of a puff's life gets a
     * corner every 40 px instead, and it composites at 0.29 where a straight edge
     * has nothing to be straight against.</p>
     *
     * <p>Raised from 8 when {@link #PUFF_RADIUS_END} nearly doubled, because the
     * old count was sized against a cloud half this size and eight facets on the
     * new one put a visible flat down the side of the outermost wisp.</p>
     */
    private static final int SPHERE_MERIDIANS = 12;

    /**
     * Stacks from pole to pole — <b>8</b>. With
     * {@link #SPHERE_MERIDIANS} that is 86 vertices and 168 triangles, against
     * the cube's 8 and 12. The whole pool is 120 spheres and at most fifteen are
     * ever visible, so the pass carries about 2,500 triangles at its busiest —
     * beside a room that submits thousands. Blended fill is set by the area
     * covered rather than by the triangle count, so this is the cheap half of
     * making the cloud bigger.
     */
    private static final int SPHERE_STACKS = 8;

    /** Half-turn in radians, for the sphere's polar sweep. */
    private static final float HALF_TURN_RADIANS = (float) StrictMath.PI;

    /** Full turn in radians, for the sphere's azimuthal sweep. */
    private static final float FULL_TURN_RADIANS = (float) (2.0 * StrictMath.PI);

    /**
     * Radius the smoke sphere is authored at — a half unit, so it occupies
     * exactly the {@code -0.5 .. +0.5} box the tracer does and the same
     * {@code scale = diameter} placement arithmetic applies unchanged.
     */
    private static final float SPHERE_RADIUS = 0.5f;

    /**
     * Total tracer slots — the player's, then the bots'.
     *
     * <h2>Why one pool with a boundary and not two of everything</h2>
     *
     * <p>A bolt coming the other way differs from one going out in three
     * numbers — its colour, its width and its speed — and in nothing else. It
     * ages the same way, it is hidden the same way, it is placed by the same
     * matrix. Two parallel pools would have meant two of {@link #advance()}'s
     * loops, two of {@link #publish}'s, and two of {@link #clear()}'s, so every
     * future change to how a tracer behaves would have to be made twice and
     * would be correct once.</p>
     *
     * <p>So the pool is one array and the boundary is an index:
     * {@code slot < MAX_TRACERS} is the player's, everything above is incoming.
     * The three numbers that differ are functions of the slot
     * ({@link #tracerSpeedOf}, {@link #tracerWidthOf}) or are baked into the
     * instance when the scene is built, which is where a colour has to be fixed
     * anyway — {@link Scene} is immutable.</p>
     */
    private static final int TRACER_SLOTS = MAX_TRACERS + MAX_BOT_TRACERS;

    /** Total puff slots — the player's, then the bots'. See {@link #TRACER_SLOTS}. */
    private static final int PUFF_SLOTS = MAX_PUFFS + MAX_BOT_PUFFS;

    /** Total flash slots — the player's, then the bots'. See {@link #TRACER_SLOTS}. */
    private static final int FLASH_SLOTS = MAX_PLAYER_FLASHES + MAX_BOT_FLASHES;

    /** Scene instance index of each tracer. */
    private final int[] tracerInstance;

    /**
     * Scene instance index of each puff lobe, addressed by
     * {@code (puff * PUFF_STAGES + stage) * PUFF_LOBES + lobe}.
     */
    private final int[] puffInstance;

    /** Tics each tracer has left, or {@link #DEAD}. MUTABLE: aged every tic. */
    private final int[] tracerRemaining;

    /** Where each tracer is, {@link #AXES} floats per slot. MUTABLE. */
    private final float[] tracerPosition;

    /** Which way each tracer flies, unit length, {@link #AXES} floats per slot. MUTABLE. */
    private final float[] tracerDirection;

    /** Whether each tracer's instance is currently shown. MUTABLE. */
    private final boolean[] tracerShown;

    /** Tics each puff has lived, or {@link #DEAD}. MUTABLE. */
    private final int[] puffAge;

    /** Where each puff sits, {@link #AXES} floats per slot. MUTABLE. */
    private final float[] puffPosition;

    /**
     * The shot basis each puff's lobes are laid out in — across the view, then
     * up it, {@link #AXES} floats each per slot. MUTABLE: written once at spawn.
     *
     * <p>Stored rather than recomputed at publish time because the aim it came
     * from is gone by then: the puff stays where it was made while the player
     * carries on turning, and a cloud that reshuffled its lumps as the camera
     * moved would be a very odd thing to watch.</p>
     */
    private final float[] puffAcross;

    /** The other half of that basis. See {@link #puffAcross}. MUTABLE. */
    private final float[] puffUp;

    /** Which stage instance of each puff is shown, or {@link #DEAD}. MUTABLE. */
    private final int[] puffShownStage;

    /**
     * Which colour variant each puff is currently using, or {@link #DEAD} when
     * the slot holds no effect. MUTABLE: written by {@link #spawnPuff} on
     * spawn and consulted by {@link #publishPuff} to know which variant's
     * lobes to make visible. Initialised to {@link #DEAD} for every slot, so
     * a fresh pool's first publish never shows lobes of an unclaimed variant
     * — the {@link #hidden} flag handles the very first call.
     */
    private final int[] puffColorVariant;

    /**
     * Scratch for the vector across the aim direction. MUTABLE, and a field
     * rather than a local so that spawning and publishing allocate nothing.
     *
     * <p>Safe to share between the two because both run under
     * {@code DemoGameplayPort}'s tic lock and neither is re-entrant — one tic
     * is atomic, which is the same guarantee the controller relies on.</p>
     */
    private final float[] acrossScratch = new float[AXES];

    /** Next tracer slot the PLAYER'S next shot will claim. MUTABLE: round-robin. */
    private int tracerCursor;

    /** Next puff slot the PLAYER'S next shot will claim. MUTABLE: round-robin. */
    private int puffCursor;

    /**
     * Next colour variant a puff will claim on spawn. MUTABLE: round-robin
     * over {@code [0, PUFF_COLOR_VARIANTS)}, deterministic, so a held trigger
     * walks the variant ladder rather than picking the same one every time.
     * "Random" would look more varied but is the wrong default for the same
     * reason {@link BotRng} is seeded: a peer replaying the same shots gets
     * the same smoke, and a test asserting the variant pick is one the test
     * can compute.
     */
    private int variantCursor;

    /**
     * Next tracer slot an INCOMING shot will claim. MUTABLE: round-robin over
     * {@code [MAX_TRACERS, TRACER_SLOTS)}.
     *
     * <p>A second cursor rather than one shared, because the two halves of the
     * pool must not be able to evict each other. One cursor walking the whole
     * array would let a busy room overwrite the bolt the player fired half a tic
     * ago — the effect they are looking straight at, from the trigger they just
     * pulled — which is the one bolt in the room that must never disappear.</p>
     */
    private int incomingTracerCursor = MAX_TRACERS;

    /** Next puff slot an INCOMING shot will claim. See {@link #incomingTracerCursor}. */
    private int incomingPuffCursor = MAX_PUFFS;

    /**
     * Whether every instance has been hidden once. MUTABLE: set by the first
     * {@link #publish}.
     *
     * <p><b>Without this the whole pool is visible, piled on the world origin,
     * until each slot is first used.</b> {@link Scene.Builder} validates the
     * placement of everything it is given and refuses a degenerate one — see
     * {@link #HIDDEN} — so these instances have to enter the scene at some
     * legal transform, and identity is the only honest choice. That leaves
     * fourteen unit boxes stacked at the origin, which in the demo room is a
     * few pixels of amber and grey on the floor ahead of the spawn: small
     * enough to look like a texture artefact, and visible in the very first
     * screenshot taken with the trigger released.</p>
     *
     * <p>The first publish is the earliest moment an override can exist, so it
     * is where the pool puts itself out of sight. A flag rather than a separate
     * {@code hideAll()} the caller must remember to call, because a caller that
     * forgets gets exactly the bug this replaced.</p>
     */
    private boolean hidden;

    /** Scene instance index of each muzzle flash. */
    private final int[] flashInstance;

    /** Tics each flash has left, or {@link #DEAD}. MUTABLE: aged every tic. */
    private final int[] flashRemaining;

    /** Where each flash sits, {@link #AXES} floats per slot. MUTABLE. */
    private final float[] flashPosition;

    /** Whether each flash's instance is currently shown. MUTABLE. */
    private final boolean[] flashShown;

    /** Next flash slot the PLAYER'S next shot will claim. MUTABLE: round-robin. */
    private int flashCursor;

    /** Next flash slot an INCOMING shot will claim. See {@link #incomingTracerCursor}. */
    private int incomingFlashCursor = MAX_PLAYER_FLASHES;

    // Takes ownership of the index tables the builder handed back.
    private DemoEffects(final int[] tracerIndices, final int[] puffIndices,
        final int[] flashIndices)
    {
        this.tracerInstance = tracerIndices;

        this.puffInstance = puffIndices;

        this.flashInstance = flashIndices;

        this.tracerRemaining = new int[TRACER_SLOTS];

        this.tracerPosition = new float[TRACER_SLOTS * AXES];

        this.tracerDirection = new float[TRACER_SLOTS * AXES];

        this.tracerShown = new boolean[TRACER_SLOTS];

        this.puffAge = new int[PUFF_SLOTS];

        this.puffPosition = new float[PUFF_SLOTS * AXES];

        this.puffAcross = new float[PUFF_SLOTS * AXES];

        this.puffUp = new float[PUFF_SLOTS * AXES];

        this.puffShownStage = new int[PUFF_SLOTS];

        this.puffColorVariant = new int[PUFF_SLOTS];

        this.flashRemaining = new int[FLASH_SLOTS];

        this.flashPosition = new float[FLASH_SLOTS * AXES];

        this.flashShown = new boolean[FLASH_SLOTS];

        for (int slot = 0; slot < TRACER_SLOTS; slot++)
        {
            tracerRemaining[slot] = DEAD;
        }

        for (int slot = 0; slot < PUFF_SLOTS; slot++)
        {
            puffAge[slot] = DEAD;

            puffShownStage[slot] = DEAD;

            puffColorVariant[slot] = DEAD;
        }

        for (int slot = 0; slot < FLASH_SLOTS; slot++)
        {
            flashRemaining[slot] = DEAD;
        }
    }

    /**
     * Places every effect instance this demo will ever need into a scene under
     * construction, and returns the pool that drives them.
     *
     * <p>Call once, while the scene is being built. Each instance is added at
     * its own home placement — a legal transform, because {@link Scene.Builder}
     * validates every one it is given — and is hidden by the first
     * {@link #publish} before a frame is ever drawn.</p>
     *
     * @param builder the scene under construction; instances are appended
     * @return the effect pool, holding the scene index of everything it placed
     */
    public static DemoEffects addTo(final Scene.Builder builder)
    {
        if (builder == null)
        {
            throw new IllegalArgumentException("builder must not be null");
        }

        final ModelFormat bolt = box(TRACER_COLOUR);

        // A second model for the same box, because a bolt's colour is BAKED into
        // its vertices and Scene is immutable — there is nowhere later to change
        // it. Two ModelFormats is the whole cost of telling outgoing fire from
        // incoming, and it is twelve triangles.
        final ModelFormat incoming = box(INCOMING_COLOUR);

        // One sphere shared by every lobe instance in the pool.
        // SoftwareRenderPort.prepare keys on reference identity, so the flattened
        // submesh table and the mip chains are built once for the whole pool
        // rather than once per lobe — which is what keeps the bots' 240 extra
        // lobes from costing 240 mip chains. Shared across BOTH halves for the
        // same reason: smoke is smoke, and the two differ only in how big they
        // are, which lives in the transform.
        // One sphere per smoke colour variant. The three models are baked
        // once and shared by every lobe of every puff — see
        // SoftwareRenderPort.prepare, which keys on reference identity and so
        // produces one submesh table and one mip chain per variant rather
        // than per lobe. A puff claims one variant on spawn and only the
        // lobes of that variant are visible for the puff's life, so two
        // puffs in the air at once read as slightly different clouds rather
        // than as clones. The variant assignment is round-robin and
        // deterministic — see spawnPuff for why a "different every time"
        // rule would not be.
        final ModelFormat[] cloudModels = new ModelFormat[PUFF_COLOR_VARIANTS];

        for (int variant = 0; variant < PUFF_COLOR_VARIANTS; variant++)
        {
            cloudModels[variant] = sphere(SMOKE_COLOURS[variant]);
        }

        // A second sphere for the muzzle flash, smaller and baked in the
        // flash colour. The radius is a per-shot property — player 0.30
        // view units, bot 5 world units — so a single model of radius 0.5
        // is the right shape: it is the transform that sets the size, and
        // a single model means one submesh table and one mip chain for the
        // whole flash pool.
        final ModelFormat flashSphere = sphere(FLASH_COLOUR);

        final int[] tracers = new int[TRACER_SLOTS];

        for (int slot = 0; slot < TRACER_SLOTS; slot++)
        {
            tracers[slot] = builder.worldInstanceCount();

            builder.addWorldInstance(boltFor(slot, bolt, incoming), Mat4.identity());
        }

        final int[] puffs = new int[PUFF_SLOTS * PUFF_STAGES * PUFF_LOBES * PUFF_COLOR_VARIANTS];

        for (int puff = 0; puff < PUFF_SLOTS; puff++)
        {
            for (int stage = 0; stage < PUFF_STAGES; stage++)
            {
                for (int lobe = 0; lobe < PUFF_LOBES; lobe++)
                {
                    for (int variant = 0; variant < PUFF_COLOR_VARIANTS; variant++)
                    {
                        puffs[stageOffset(puff, stage) + lobe * PUFF_COLOR_VARIANTS + variant]
                            = builder.worldInstanceCount();

                        builder.addTranslucentWorldInstance(cloudModels[variant], Mat4.identity(),
                            Scene.UNTAGGED, PUFF_COVERAGE[stage]);
                    }
                }
            }
        }

        // One opaque instance per flash slot. The flash is bright but small
        // and short-lived, so a translucent composite would still be a hard
        // pixel against the wall it is in front of — opaque with a high-value
        // colour is what the eye reads as "flash", and the lifetime of
        // FLASH_LIFE_TICS = 2 keeps it from being a glowing ball.
        final int[] flashes = new int[FLASH_SLOTS];

        for (int slot = 0; slot < FLASH_SLOTS; slot++)
        {
            flashes[slot] = builder.worldInstanceCount();

            builder.addWorldInstance(flashSphere, Mat4.identity());
        }

        return new DemoEffects(tracers, puffs, flashes);
    }

    /**
     * Starts a tracer and a puff of smoke for one shot.
     *
     * <p>The muzzle is derived here from the eye and the aim rather than passed
     * in, so there is one definition of where a shot comes from. Both effects
     * start there; the tracer then leaves along {@code aim} and the smoke stays
     * put and rises.</p>
     *
     * @param eyeX eye position, world x
     * @param eyeY eye position, world y
     * @param eyeZ eye position, world z
     * @param aimX aim direction, world x; should be unit length
     * @param aimY aim direction, world y
     * @param aimZ aim direction, world z
     */
    public void spawn(final float eyeX, final float eyeY, final float eyeZ,
        final float aimX, final float aimY, final float aimZ)
    {
        // Compute the muzzle once. Both the flash and the tracer want
        // to start at the muzzle — the tracer visibly leaves the gun,
        // and the flash sits where the player is looking — so the
        // muzzle is computed before either of them. The puff, when
        // SMOKE_ENABLED is true, also wants the muzzle, but is gated
        // further down so the muzzle math is only paid for by code
        // that uses it.
        final float[] right = acrossScratch;

        crossWithReference(aimX, aimY, aimZ, right);

        final float muzzleX = eyeX + aimX * MUZZLE_FORWARD_UNITS + right[0] * MUZZLE_RIGHT_UNITS;

        final float muzzleY = eyeY + aimY * MUZZLE_FORWARD_UNITS + right[1] * MUZZLE_RIGHT_UNITS
            - MUZZLE_DROP_UNITS;

        final float muzzleZ = eyeZ + aimZ * MUZZLE_FORWARD_UNITS + right[2] * MUZZLE_RIGHT_UNITS;

        spawnFlash(claimPlayerFlash(), muzzleX, muzzleY, muzzleZ);

        spawnTracer(claimPlayerTracer(), muzzleX, muzzleY, muzzleZ, aimX, aimY, aimZ, TRACER_LIFE_TICS);

        if (!SMOKE_ENABLED)
        {
            // The puff pool would only hide what we never spawned; the
            // tracer is the visible proof of the shot for now. See
            // the SMOKE_ENABLED Javadoc for why this is a flag rather
            // than a permanent change.
            return;
        }

        spawnPuff(claimPlayerPuff(), muzzleX, muzzleY, muzzleZ, aimX, aimY, aimZ, right);
    }

    /**
     * Starts a violet tracer and a puff of smoke for one shot <b>coming the other
     * way</b>.
     *
     * <h2>The muzzle and the ray are two different things, and both are given</h2>
     *
     * <p>{@link Match} fires from a bot's <i>eye</i> — the middle of its body, 41
     * units up — because that is where a shot has to come from for cover to work
     * the way a player expects. The bolt has to leave the end of a <i>barrel</i>,
     * which is some fourteen units away from that: nine to one side, eleven down,
     * nine along a carbine held across the chest. So the caller supplies both, and
     * they are used for different things.</p>
     *
     * <p><b>Simply firing along {@code dir} from the muzzle would be wrong in a
     * way the player would feel.</b> It produces a bolt <i>parallel</i> to the
     * shot, fourteen units to one side of it, all the way — and a player is 16
     * units in radius, so a shot that struck them would draw a bolt going past
     * their shoulder and a shot that missed by a foot would draw one going through
     * their chest. The whole reason for showing incoming fire is that it can be
     * read and avoided; a bolt that disagrees with the damage is worse than no
     * bolt.</p>
     *
     * <p>So the bolt is aimed from the muzzle at the point the <b>real ray</b>
     * reaches at {@code rangeUnits} — the distance the shot was actually taken at,
     * which {@code Match} records because it is the distance to what the shooter
     * was aiming at. The two lines converge exactly there and are within a few
     * units of each other for the last third of the flight. Near the bot they
     * differ by fourteen units and nobody can tell, because near the bot the whole
     * bolt is fourteen units of a 512-unit journey.</p>
     *
     * @param muzzleX where the barrel ends, world x
     * @param muzzleY where the barrel ends, world y
     * @param muzzleZ where the barrel ends, world z
     * @param originX the simulation's ray origin, world x — the shooter's eye
     * @param originY the simulation's ray origin, world y
     * @param originZ the simulation's ray origin, world z
     * @param dirX the SCATTERED ray direction, world x; unit length
     * @param dirY the scattered ray direction, world y
     * @param dirZ the scattered ray direction, world z
     * @param rangeUnits how far along that ray the shot was aimed; a non-positive
     *     value falls back to firing straight along {@code dir}, which is the only
     *     honest answer when there is no distance to converge at
     */
    public void spawnIncoming(final float muzzleX, final float muzzleY, final float muzzleZ,
        final float originX, final float originY, final float originZ,
        final float dirX, final float dirY, final float dirZ, final float rangeUnits)
    {
        // Compute the flight direction before claiming any slot. The
        // flash and the tracer both use it, and the puff does too when
        // SMOKE_ENABLED is true.
        final float[] flight = acrossScratch;

        flight[0] = dirX;

        flight[1] = dirY;

        flight[2] = dirZ;

        if (rangeUnits > 0.0f)
        {
            flight[0] = originX + dirX * rangeUnits - muzzleX;

            flight[1] = originY + dirY * rangeUnits - muzzleY;

            flight[2] = originZ + dirZ * rangeUnits - muzzleZ;

            normalise(flight);
        }

        final float alongX = flight[0];

        final float alongY = flight[1];

        final float alongZ = flight[2];

        // Flash and tracer first; both are independent of SMOKE_ENABLED.
        spawnFlash(claimIncomingFlash(), muzzleX, muzzleY, muzzleZ);

        final int slot = claimIncomingTracer();

        spawnTracer(slot, muzzleX, muzzleY, muzzleZ, alongX, alongY, alongZ,
            incomingLifeFor(rangeUnits));

        if (!SMOKE_ENABLED)
        {
            // See the SMOKE_ENABLED Javadoc for why this is a flag
            // rather than a permanent change.
            return;
        }

        // The lobe basis is rebuilt from the flight direction, reusing the scratch
        // the convergence above has now finished with. spawnPuff copies what it
        // needs, so the two uses cannot tread on each other.
        final float[] across = acrossScratch;

        crossWithReference(alongX, alongY, alongZ, across);

        spawnPuff(claimIncomingPuff(), muzzleX, muzzleY, muzzleZ, alongX, alongY, alongZ,
            across);
    }

    // The next slot in the player's half of the tracer pool.
    private int claimPlayerTracer()
    {
        final int slot = tracerCursor;

        this.tracerCursor = (tracerCursor + 1) % MAX_TRACERS;

        return slot;
    }

    // The next slot in the player's half of the puff pool.
    private int claimPlayerPuff()
    {
        final int slot = puffCursor;

        this.puffCursor = (puffCursor + 1) % MAX_PUFFS;

        return slot;
    }

    // The next slot in the bots' half of the tracer pool, which starts where the
    // player's half ends and wraps within its own range — see
    // incomingTracerCursor for why the two halves may not evict each other.
    private int claimIncomingTracer()
    {
        final int slot = incomingTracerCursor;

        this.incomingTracerCursor = MAX_TRACERS + (slot + 1 - MAX_TRACERS) % MAX_BOT_TRACERS;

        return slot;
    }

    // The next slot in the bots' half of the puff pool.
    private int claimIncomingPuff()
    {
        final int slot = incomingPuffCursor;

        this.incomingPuffCursor = MAX_PUFFS + (slot + 1 - MAX_PUFFS) % MAX_BOT_PUFFS;

        return slot;
    }

    // The next slot in the player's half of the flash pool.
    private int claimPlayerFlash()
    {
        final int slot = flashCursor;

        this.flashCursor = (flashCursor + 1) % MAX_PLAYER_FLASHES;

        return slot;
    }

    // The next slot in the bots' half of the flash pool.
    private int claimIncomingFlash()
    {
        final int slot = incomingFlashCursor;

        this.incomingFlashCursor = MAX_PLAYER_FLASHES + (slot + 1 - MAX_PLAYER_FLASHES)
            % MAX_BOT_FLASHES;

        return slot;
    }

    // One muzzle flash, at the muzzle and stationary for its whole life.
    //
    // The flash does not move, so unlike the tracer this is the one and only
    // place its position is written. A flash lives FLASH_LIFE_TICS and then
    // publish() hides it; the position array only needs to be set here.
    //
    // The radius lives in the publish, not here, so a slot's geometry is
    // always the same sphere model and the size is whichever the renderer
    // was told last.
    private void spawnFlash(final int slot, final float x, final float y, final float z)
    {
        final int at = slot * AXES;

        flashPosition[at] = x;

        flashPosition[at + 1] = y;

        flashPosition[at + 2] = z;

        flashRemaining[slot] = FLASH_LIFE_TICS;
    }

    // One bolt, at the muzzle and not yet moving.
    //
    // It is never DRAWN there: the caller advances before it publishes, which
    // matters because the player's muzzle is 2.4 units from the eye and the bolt
    // is 46 units long — drawn at the muzzle it would enclose the camera, and most
    // of it would be behind the near plane. See DemoGameplayPort.tick, which owns
    // that ordering. An incoming bolt gets the same first step for free, which
    // also puts it clear of the body that fired it.
    private void spawnTracer(final int slot, final float x, final float y, final float z,
        final float aimX, final float aimY, final float aimZ, final int lifeTics)
    {
        final int at = slot * AXES;

        tracerPosition[at] = x;

        tracerPosition[at + 1] = y;

        tracerPosition[at + 2] = z;

        tracerDirection[at] = aimX;

        tracerDirection[at + 1] = aimY;

        tracerDirection[at + 2] = aimZ;

        tracerRemaining[slot] = lifeTics;
    }

    // One puff, at rest at the muzzle, with the basis its lobes are laid out
    // in frozen at the moment of the shot.
    //
    // `across` is the caller's scratch and is copied rather than kept, because
    // the caller reuses it — see acrossScratch.
    private void spawnPuff(final int slot, final float x, final float y, final float z,
        final float aimX, final float aimY, final float aimZ, final float[] across)
    {
        final int at = slot * AXES;

        puffPosition[at] = x;

        puffPosition[at + 1] = y;

        puffPosition[at + 2] = z;

        puffAcross[at] = across[0];

        puffAcross[at + 1] = across[1];

        puffAcross[at + 2] = across[2];

        // up = aim x across, the same operand order tracerPlacement uses to
        // complete its (across, up, forward) frame. Any unit vector
        // perpendicular to both would do here — the lobes only need two
        // independent screen-plane directions — but reusing the one definition
        // keeps a second cross-product convention out of the file.
        puffUp[at] = aimY * across[2] - aimZ * across[1];

        puffUp[at + 1] = aimZ * across[0] - aimX * across[2];

        puffUp[at + 2] = aimX * across[1] - aimY * across[0];

        puffAge[slot] = 0;

        // Claim one colour variant for the puff's whole life. The cursor walks
        // the variant ladder so a held trigger does not stamp out identical
        // puffs. The variant is fixed at spawn — a puff that shifts colour as
        // it ages reads as a different smoke, not as the same smoke in motion,
        // and the "same smoke in motion" is the feature.
        puffColorVariant[slot] = variantCursor;

        variantCursor = (variantCursor + 1) % PUFF_COLOR_VARIANTS;
    }

    /**
     * Kills every tracer and puff outright, as though nothing had ever been
     * fired.
     *
     * <p>For the world <b>reset</b> when the player leaves the game-over screen.
     * A rematch that inherited three bolts and a cloud of smoke from the last
     * round would be a room that visibly remembered a match that is supposed to
     * have been forgotten — and the bolts would be halfway across it, in the
     * air, going nowhere.</p>
     *
     * <p><b>Deliberately not called on a respawn</b>, which is the other place
     * it plausibly belongs. The distinction is the one {@code DemoGameplayPort}
     * already draws for the menu: an effect is a consequence of a shot that has
     * already happened, so what a <i>pause</i> owes it is to let it finish. A
     * death is a pause. Restarting the world is not, and this is the difference
     * between the two.</p>
     *
     * <p>The instances are not hidden here — this only forgets the state. The
     * next {@link #publish} does the hiding, which is the same division of
     * labour every other transition in this class uses.</p>
     */
    public void clear()
    {
        for (int slot = 0; slot < TRACER_SLOTS; slot++)
        {
            tracerRemaining[slot] = DEAD;
        }

        for (int slot = 0; slot < PUFF_SLOTS; slot++)
        {
            puffAge[slot] = DEAD;
        }

        for (int slot = 0; slot < FLASH_SLOTS; slot++)
        {
            flashRemaining[slot] = DEAD;
        }

        // The cursors go back to zero too, so a fresh round claims slots in the
        // same order the first one did. Nothing looks different either way, and
        // "indistinguishable from a fresh start" is easier to assert than
        // "indistinguishable apart from two cursors nobody can see".
        this.tracerCursor = 0;

        this.puffCursor = 0;

        this.flashCursor = 0;

        this.incomingTracerCursor = MAX_TRACERS;

        this.incomingPuffCursor = MAX_PUFFS;

        this.incomingFlashCursor = MAX_PLAYER_FLASHES;

        this.variantCursor = 0;
    }

    /**
     * Ages every live effect by one tic, expiring those that are finished.
     *
     * <p>Purely simulation: nothing is drawn and no transform is written. Call
     * it once per tic, then {@link #publish}.</p>
     */
    public void advance()
    {
        for (int slot = 0; slot < TRACER_SLOTS; slot++)
        {
            if (tracerRemaining[slot] == DEAD)
            {
                continue;
            }

            final int at = slot * AXES;

            final float speed = tracerSpeedOf(slot);

            tracerPosition[at] += tracerDirection[at] * speed;

            tracerPosition[at + 1] += tracerDirection[at + 1] * speed;

            tracerPosition[at + 2] += tracerDirection[at + 2] * speed;

            tracerRemaining[slot]--;

            if (tracerRemaining[slot] <= 0)
            {
                tracerRemaining[slot] = DEAD;
            }
        }

        for (int slot = 0; slot < PUFF_SLOTS; slot++)
        {
            if (puffAge[slot] == DEAD)
            {
                continue;
            }

            // Existing puffs keep aging out even when SMOKE_ENABLED is
            // off, so a flag flip mid-match leaves no on-screen
            // debris. New puffs are not spawned when the flag is
            // off — see spawn() and spawnIncoming().
            puffPosition[slot * AXES + 1] += puffRiseOf(slot);

            puffAge[slot]++;

            if (puffAge[slot] >= PUFF_LIFE_TICS)
            {
                puffAge[slot] = DEAD;
            }
        }

        for (int slot = 0; slot < FLASH_SLOTS; slot++)
        {
            if (flashRemaining[slot] == DEAD)
            {
                continue;
            }

            flashRemaining[slot]--;

            if (flashRemaining[slot] <= 0)
            {
                flashRemaining[slot] = DEAD;
            }
        }
    }

    /**
     * Writes every effect instance's placement into the render port.
     *
     * <p>Only what changed: a live effect is republished because it moved, and
     * a slot that has just died or just changed stage is hidden once. A slot
     * that was already hidden is left entirely alone, which is why an idle
     * trigger costs nothing.</p>
     *
     * @param renderer the port holding the scene these instances live in; must
     *     already have that scene bound
     */
    public void publish(final SoftwareRenderPort renderer)
    {
        if (!hidden)
        {
            hideEverything(renderer);

            this.hidden = true;
        }

        for (int slot = 0; slot < TRACER_SLOTS; slot++)
        {
            if (tracerRemaining[slot] != DEAD)
            {
                renderer.setWorldTransform(tracerInstance[slot], tracerPlacement(slot));

                tracerShown[slot] = true;
            }
            else if (tracerShown[slot])
            {
                renderer.setWorldTransform(tracerInstance[slot], HIDDEN);

                tracerShown[slot] = false;
            }
        }

        for (int slot = 0; slot < PUFF_SLOTS; slot++)
        {
            // publishPuff hides a slot whose puff just died and shows a
            // slot whose puff is alive. With SMOKE_ENABLED off, no new
            // puffs are born, so the hide branch runs at most once per
            // slot and the rest of the loop is a no-op.
            publishPuff(renderer, slot);
        }

        for (int slot = 0; slot < FLASH_SLOTS; slot++)
        {
            if (flashRemaining[slot] != DEAD)
            {
                renderer.setWorldTransform(flashInstance[slot], flashPlacement(slot));

                flashShown[slot] = true;
            }
            else if (flashShown[slot])
            {
                renderer.setWorldTransform(flashInstance[slot], HIDDEN);

                flashShown[slot] = false;
            }
        }
    }

    // Puts the whole pool out of sight. Runs once, on the first publish — see
    // the `hidden` field for why it cannot be done when the scene is built.
    private void hideEverything(final SoftwareRenderPort renderer)
    {
        for (final int instance : tracerInstance)
        {
            renderer.setWorldTransform(instance, HIDDEN);
        }

        for (final int instance : puffInstance)
        {
            renderer.setWorldTransform(instance, HIDDEN);
        }

        for (final int instance : flashInstance)
        {
            renderer.setWorldTransform(instance, HIDDEN);
        }
    }

    // One puff: show every lobe of the stage its age selects, in the variant
    // it claimed at spawn, and hide whichever stage it was showing before if
    // that has moved on.
    //
    // A stage is all-or-nothing. Showing one lobe of a rung and one of the next
    // would put two coverages in the same cloud, which is not a fade — it is a
    // seam. A variant is also all-or-nothing: the lobes of the other variants
    // are kept hidden so a single puff never paints itself in three colours at
    // once.
    private void publishPuff(final SoftwareRenderPort renderer, final int slot)
    {
        final int age = puffAge[slot];

        if (age == DEAD)
        {
            if (puffShownStage[slot] != DEAD)
            {
                hideStage(renderer, slot, puffShownStage[slot]);

                puffShownStage[slot] = DEAD;
            }

            return;
        }

        final int stage = stageFor(age);

        if (puffShownStage[slot] != DEAD && puffShownStage[slot] != stage)
        {
            hideStage(renderer, slot, puffShownStage[slot]);
        }

        final int variant = puffColorVariant[slot];

        for (int lobe = 0; lobe < PUFF_LOBES; lobe++)
        {
            renderer.setWorldTransform(puffInstanceIndex(slot, stage, lobe, variant),
                puffPlacement(slot, lobe, age));
        }

        puffShownStage[slot] = stage;
    }

    // Puts every lobe of one rung out of sight.
    private void hideStage(final SoftwareRenderPort renderer, final int slot, final int stage)
    {
        for (int lobe = 0; lobe < PUFF_LOBES; lobe++)
        {
            renderer.setWorldTransform(puffInstanceIndex(slot, stage, lobe), HIDDEN);
        }
    }

    /**
     * Returns whether a tracer slot belongs to the bots rather than the player.
     *
     * <p>The boundary the whole single-pool arrangement rests on — see
     * {@link #TRACER_SLOTS}. Public because a test asserting that incoming bolts
     * are wider, faster and a different colour has to be able to say which slots
     * it means, and re-deriving {@code slot >= MAX_TRACERS} in the test would let
     * it agree with a broken implementation.</p>
     *
     * @param slot tracer slot in {@code [0, tracerSlotCount())}
     * @return true for a bot's bolt
     */
    public static boolean isIncomingTracer(final int slot)
    {
        return slot >= MAX_TRACERS;
    }

    /**
     * Returns whether a puff slot belongs to the bots rather than the player.
     *
     * @param slot puff slot in {@code [0, puffSlotCount())}
     * @return true for a bot's smoke
     */
    public static boolean isIncomingPuff(final int slot)
    {
        return slot >= MAX_PUFFS;
    }

    /** Returns how many tracer slots the pool holds, both halves together. */
    public static int tracerSlotCount()
    {
        return TRACER_SLOTS;
    }

    /** Returns how many puff slots the pool holds, both halves together. */
    public static int puffSlotCount()
    {
        return PUFF_SLOTS;
    }

    /** Returns how many flash slots the pool holds, both halves together. */
    public static int flashSlotCount()
    {
        return FLASH_SLOTS;
    }

    /**
     * Returns whether a flash slot belongs to the bots rather than the player.
     *
     * <p>The boundary the single-pool arrangement rests on, exactly the
     * shape {@link #isIncomingTracer} and {@link #isIncomingPuff} use.</p>
     *
     * @param slot flash slot in {@code [0, flashSlotCount())}
     * @return true for a bot's flash
     */
    public static boolean isIncomingFlash(final int slot)
    {
        return slot >= MAX_PLAYER_FLASHES;
    }

    /**
     * Returns how fast the bolt in one slot travels, in world units per tic.
     *
     * @param slot tracer slot in {@code [0, tracerSlotCount())}
     * @return {@link #BOT_TRACER_SPEED_UNITS} for an incoming bolt, else
     *     {@link #TRACER_SPEED_UNITS}
     */
    public static float tracerSpeedOf(final int slot)
    {
        if (isIncomingTracer(slot))
        {
            return BOT_TRACER_SPEED_UNITS;
        }

        return TRACER_SPEED_UNITS;
    }

    /**
     * Returns how many tics an incoming bolt fired at a given range is drawn for.
     *
     * <p>However long it takes to fly to within {@link #INCOMING_STANDOFF_UNITS}
     * of where the shot ended — see that constant for the two frames of violet
     * wall this exists to remove — bounded below by
     * {@link #BOT_TRACER_MIN_LIFE_TICS} and above by
     * {@link #BOT_TRACER_LIFE_TICS}.</p>
     *
     * <p>Public because it is the one piece of arithmetic here that a screenshot
     * cannot check: a test can assert that a shot from across the room is drawn
     * for four times as long as one from the next crate, and no capture would ever
     * be able to say so.</p>
     *
     * @param rangeUnits how far away what the shooter aimed at was; a
     *     non-positive range gets the full ceiling, since there is no distance to
     *     shorten against
     * @return the bolt's life in tics
     */
    public static int incomingLifeFor(final float rangeUnits)
    {
        if (!(rangeUnits > 0.0f))
        {
            return BOT_TRACER_LIFE_TICS;
        }

        final int drawn =
            (int) ((rangeUnits - INCOMING_STANDOFF_UNITS) / BOT_TRACER_SPEED_UNITS);

        return Math.min(BOT_TRACER_LIFE_TICS, Math.max(BOT_TRACER_MIN_LIFE_TICS, drawn));
    }

    /**
     * Returns how wide the bolt in one slot is, across its direction of travel.
     *
     * @param slot tracer slot in {@code [0, tracerSlotCount())}
     * @return {@link #BOT_TRACER_WIDTH_UNITS} for an incoming bolt, else
     *     {@link #TRACER_WIDTH_UNITS}
     */
    public static float tracerWidthOf(final int slot)
    {
        if (isIncomingTracer(slot))
        {
            return BOT_TRACER_WIDTH_UNITS;
        }

        return TRACER_WIDTH_UNITS;
    }

    /**
     * Returns how big a flash in one slot is, in world units across.
     *
     * <p>A sphere of the given radius around the muzzle position. Player and
     * bot flashes differ in size: the player's flash is 0.30 view units (a
     * fixed distance from the eye), and a bot's flash is 5 world units
     * (sized against the body, the way the smoke is). The two halves of
     * the pool do not share a size; this is the only place the boundary
     * between them matters.</p>
     *
     * @param slot flash slot in {@code [0, flashSlotCount())}
     * @return the flash's world-units radius
     */
    public static float flashRadiusOf(final int slot)
    {
        if (slot >= MAX_PLAYER_FLASHES)
        {
            return BOT_FLASH_RADIUS;
        }

        return PLAYER_FLASH_RADIUS;
    }

    /**
     * Returns the main lobe's radius when the puff in one slot is born.
     *
     * @param slot puff slot in {@code [0, puffSlotCount())}
     * @return {@link #BOT_PUFF_RADIUS_START} for a bot's smoke, else
     *     {@link #PUFF_RADIUS_START}
     */
    public static float puffStartRadiusOf(final int slot)
    {
        if (isIncomingPuff(slot))
        {
            return BOT_PUFF_RADIUS_START;
        }

        return PUFF_RADIUS_START;
    }

    /**
     * Returns the main lobe's radius at the end of the puff in one slot.
     *
     * @param slot puff slot in {@code [0, puffSlotCount())}
     * @return {@link #BOT_PUFF_RADIUS_END} for a bot's smoke, else
     *     {@link #PUFF_RADIUS_END}
     */
    public static float puffEndRadiusOf(final int slot)
    {
        if (isIncomingPuff(slot))
        {
            return BOT_PUFF_RADIUS_END;
        }

        return PUFF_RADIUS_END;
    }

    /**
     * Returns how far the puff in one slot drifts upward per tic.
     *
     * @param slot puff slot in {@code [0, puffSlotCount())}
     * @return {@link #BOT_PUFF_RISE_UNITS} for a bot's smoke, else
     *     {@link #PUFF_RISE_UNITS}
     */
    public static float puffRiseOf(final int slot)
    {
        if (isIncomingPuff(slot))
        {
            return BOT_PUFF_RISE_UNITS;
        }

        return PUFF_RISE_UNITS;
    }

    // Which of the two baked bolt models a slot gets. The colour cannot be
    // changed after the Scene is built, so this is the only moment it can be
    // decided — which is why the boundary is an index rather than a flag.
    private static ModelFormat boltFor(final int slot, final ModelFormat outgoing,
        final ModelFormat incoming)
    {
        if (isIncomingTracer(slot))
        {
            return incoming;
        }

        return outgoing;
    }

    /**
     * Returns the scene instance index of one specific lobe of one stage of
     * one puff, in one of the {@link #PUFF_COLOR_VARIANTS} colour variants.
     *
     * <p>Every puff has one instance per lobe per stage per variant, the
     * whole pool pre-allocated at scene-build time and addressed by this
     * method. The shorter {@link #puffInstanceIndex(int, int)} and
     * {@link #puffInstanceIndex(int, int, int)} overloads name the warm
     * variant (index 0), the same way a held trigger used to before the
     * colour-shift pass — old callers keep working unchanged.</p>
     *
     * @param slot puff slot in {@code [0, puffSlotCount())}
     * @param stage stage index in {@code [0, PUFF_STAGES)}
     * @param lobe lobe index in {@code [0, PUFF_LOBES)}
     * @param variant colour variant in {@code [0, PUFF_COLOR_VARIANTS)}
     * @return its index among the scene's world instances
     */
    public int puffInstanceIndex(final int slot, final int stage, final int lobe,
        final int variant)
    {
        if (variant < 0 || variant >= PUFF_COLOR_VARIANTS)
        {
            throw new IndexOutOfBoundsException("variant " + variant
                + " is outside [0, " + PUFF_COLOR_VARIANTS + ")");
        }

        return puffInstance[stageOffset(slot, stage) + lobe * PUFF_COLOR_VARIANTS + variant];
    }

    /**
     * Returns which coverage rung a puff of a given age is drawn at.
     *
     * @param age tics the puff has lived, in {@code [0, PUFF_LIFE_TICS)}
     * @return the stage index, in {@code [0, PUFF_STAGES)}
     */
    public static int stageFor(final int age)
    {
        final int stage = age * PUFF_STAGES / PUFF_LIFE_TICS;

        return Math.min(stage, PUFF_STAGES - 1);
    }

    /**
     * Returns how far the finished cloud reaches from its centre, in multiples
     * of the puff radius.
     *
     * <p>The largest {@code |offset| + scale} over the lobes — the radius of the
     * smallest sphere containing all of them. It is what decides the puff's
     * <b>apparent size</b>, and {@link #PUFF_RADIUS_END} is set against it
     * rather than against a lone lobe: the thing that has to stay under control
     * is how much of the window the cloud covers, and that is a property of the
     * arrangement and not of any one sphere in it.</p>
     *
     * @return the cloud's bounding radius, as a multiple of the puff radius
     */
    public static float cloudExtentRadii()
    {
        // MUTABLE local — the farthest reach found so far.
        float reach = 0.0f;

        for (int lobe = 0; lobe < PUFF_LOBES; lobe++)
        {
            final float offset = (float) StrictMath.sqrt(
                LOBE_ACROSS[lobe] * LOBE_ACROSS[lobe] + LOBE_UP[lobe] * LOBE_UP[lobe]);

            reach = Math.max(reach, offset + LOBE_SCALE[lobe]);
        }

        return reach;
    }

    /**
     * Returns the coverage one puff stage composites at.
     *
     * @param stage stage index in {@code [0, PUFF_STAGES)}
     * @return the coverage, 0-255, decreasing with the stage
     */
    public static int coverageFor(final int stage)
    {
        return PUFF_COVERAGE[stage];
    }

    /**
     * Returns the packed RGBA8888 colour a puff composites in.
     *
     * <p>Exposed so a test can assert the thing that actually went wrong:
     * whether the composited result is far enough from the room behind it to be
     * seen. The colour on its own says nothing — the previous one was a
     * perfectly reasonable grey, and it was invisible against this particular
     * room. Only the pair of (colour, background) means anything, so the test
     * needs both.</p>
     *
     * @return the smoke colour, packed {@code 0xRRGGBBAA}
     */
    public static int smokeColour()
    {
        return SMOKE_COLOUR;
    }

    // Where a tracer's stretched box sits and which way it points.
    //
    // The columns are the images of the model's own axes: model +x and +y go to
    // the two axes across the bolt, scaled by its width, and model +z goes along
    // the direction of travel, scaled by its length. That basis is orthonormal
    // and right-handed by construction — see crossWithReference — so the
    // determinant is width^2 * length, which is positive. It has to be: a
    // negative one reverses triangle winding and renders the box inside-out.
    private Mat4 tracerPlacement(final int slot)
    {
        final int at = slot * AXES;

        final float dirX = tracerDirection[at];

        final float dirY = tracerDirection[at + 1];

        final float dirZ = tracerDirection[at + 2];

        final float[] across = acrossScratch;

        crossWithReference(dirX, dirY, dirZ, across);

        // up = forward x across, which completes a right-handed
        // (across, up, forward) — see crossWithReference for why that is the
        // order rather than the more obvious (across, forward, up).
        final float upX = dirY * across[2] - dirZ * across[1];

        final float upY = dirZ * across[0] - dirX * across[2];

        final float upZ = dirX * across[1] - dirY * across[0];

        final float wide = tracerWidthOf(slot);

        final float along = TRACER_LENGTH_UNITS;

        return Mat4.ofRowMajor(new float[]
        {
            across[0] * wide, upX * wide, dirX * along, tracerPosition[at],
            across[1] * wide, upY * wide, dirY * along, tracerPosition[at + 1],
            across[2] * wide, upZ * wide, dirZ * along, tracerPosition[at + 2],
            0.0f, 0.0f, 0.0f, 1.0f,
        });
    }

    // Where one lobe of a puff sits and how big it has grown. A uniform scale,
    // so the determinant is radius^3 and stays positive for any positive radius.
    //
    // The expansion is continuous even though the fade is a staircase, because
    // scale lives in the transform and the transform is the thing that is
    // allowed to change per tic — see the class Javadoc. The lobe offsets scale
    // with the puff, so the cloud grows as one shape rather than spreading its
    // lumps apart.
    private Mat4 puffPlacement(final int slot, final int lobe, final int age)
    {
        final int at = slot * AXES;

        final float start = puffStartRadiusOf(slot);

        final float end = puffEndRadiusOf(slot);

        final float grown = start + (end - start) * age / PUFF_LIFE_TICS;

        final float outAcross = LOBE_ACROSS[lobe] * grown;

        final float outUp = LOBE_UP[lobe] * grown;

        final float centreX = puffPosition[at]
            + puffAcross[at] * outAcross + puffUp[at] * outUp;

        final float centreY = puffPosition[at + 1]
            + puffAcross[at + 1] * outAcross + puffUp[at + 1] * outUp;

        final float centreZ = puffPosition[at + 2]
            + puffAcross[at + 2] * outAcross + puffUp[at + 2] * outUp;

        return DemoScene.placement(centreX, centreY, centreZ, 0.0f,
            grown * LOBE_SCALE[lobe] * 2.0f);
    }

    // Where one muzzle flash sits. A uniform-scale placement at the muzzle
    // position, sized to flashRadiusOf for the slot — the two halves of the
    // pool get different sizes for the reason flashRadiusOf records, and
    // routing both through the same placement is the cheapest way to keep
    // them in lockstep.
    private Mat4 flashPlacement(final int slot)
    {
        final int at = slot * AXES;

        return DemoScene.placement(flashPosition[at], flashPosition[at + 1],
            flashPosition[at + 2], 0.0f, flashRadiusOf(slot) * 2.0f);
    }

    // The unit vector to the shooter's RIGHT, perpendicular to the aim. Also
    // the axis the tracer's box is widened across.
    //
    // `cross(aim, worldUp)` with worldUp = (0, 1, 0), written out because two
    // thirds of a cross product against an axis vector is multiplying by zero.
    //
    // <b>The order of the operands is the whole of it, and it was wrong first
    // time.</b> `cross(worldUp, aim)` is just as plausible on the page and
    // points the other way, which put the muzzle smoke off the player's left
    // shoulder while the weapon was drawn on the right. This order is not a
    // preference: {@code Camera} defines right as {@code normalize(forward x
    // up)}, and the muzzle has to agree with the camera or the effect detaches
    // from the gun. That is the same basis whose sign flipped once already —
    // see SoftwareRenderPort's note on the backface convention — so it is
    // copied from Camera rather than re-derived.
    //
    // The eventual (across, up', forward) is right-handed whichever way this
    // points: up' is forward x across, so across x up' = forward for any unit
    // `across` perpendicular to the aim. The determinant is positive and the
    // box is not mirrored.
    private static void crossWithReference(final float dx, final float dy, final float dz,
        final float[] out)
    {
        out[0] = -dz;

        out[1] = 0.0f;

        out[2] = dx;

        if (lengthOf(out) > PARALLEL_EPSILON)
        {
            normalise(out);

            return;
        }

        // Straight up or straight down, where the aim IS world up and the cross
        // above is exactly zero. `cross(aim, worldForward)` instead, which
        // cannot also degenerate because the two references are perpendicular
        // to each other.
        out[0] = dy;

        out[1] = -dx;

        out[2] = 0.0f;

        normalise(out);
    }

    private static float lengthOf(final float[] vector)
    {
        return (float) StrictMath.sqrt(
            vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]);
    }

    // Scales a vector to unit length. A zero vector is left alone rather than
    // divided into NaN; crossWithReference has already made that unreachable,
    // and this is what keeps it unreachable if anyone gives it a second caller.
    private static void normalise(final float[] vector)
    {
        final float length = lengthOf(vector);

        if (!(length > 0.0f))
        {
            return;
        }

        vector[0] /= length;

        vector[1] /= length;

        vector[2] /= length;
    }

    /**
     * Returns the scene instance index of one tracer.
     *
     * @param slot tracer slot in {@code [0, MAX_TRACERS)}
     * @return its index among the scene's world instances
     */
    public int tracerInstanceIndex(final int slot)
    {
        return tracerInstance[slot];
    }

    /**
     * Returns the scene instance index of the <b>main lobe</b> of one stage of
     * one puff.
     *
     * <p>Lobe zero, which is the one at the puff's own centre and the largest —
     * the whole of what a puff used to be. Callers that mean the cloud rather
     * than a lobe want {@link #PUFF_LOBES} of them; see the three-argument
     * form.</p>
     *
     * @param slot puff slot in {@code [0, MAX_PUFFS)}
     * @param stage stage index in {@code [0, PUFF_STAGES)}
     * @return its index among the scene's world instances
     */
    public int puffInstanceIndex(final int slot, final int stage)
    {
        return puffInstanceIndex(slot, stage, 0, 0);
    }

    /**
     * Returns the scene instance index of one lobe of one stage of one puff.
     *
     * @param slot puff slot in {@code [0, MAX_PUFFS)}
     * @param stage stage index in {@code [0, PUFF_STAGES)}
     * @param lobe lobe index in {@code [0, PUFF_LOBES)}
     * @return its index among the scene's world instances
     */
    public int puffInstanceIndex(final int slot, final int stage, final int lobe)
    {
        return puffInstanceIndex(slot, stage, lobe, 0);
    }

    /**
     * Returns how many tics one tracer has left, or {@link #DEAD}.
     *
     * @param slot tracer slot in {@code [0, MAX_TRACERS)}
     * @return the remaining life
     */
    public int tracerRemaining(final int slot)
    {
        return tracerRemaining[slot];
    }

    /**
     * Returns how many tics one puff has lived, or {@link #DEAD}.
     *
     * @param slot puff slot in {@code [0, MAX_PUFFS)}
     * @return the age
     */
    public int puffAge(final int slot)
    {
        return puffAge[slot];
    }

    /**
     * Returns how many tics one flash has left, or {@link #DEAD}.
     *
     * @param slot flash slot in {@code [0, flashSlotCount())}
     * @return the remaining life
     */
    public int flashRemaining(final int slot)
    {
        return flashRemaining[slot];
    }

    /**
     * Returns the world-space x of a flash's position.
     *
     * <p>The y and z accessors follow the same shape, and all three are
     * the position the flash was spawned at, in world space. The flash
     * does not move over its life, so the position is the muzzle
     * position the spawner passed in.</p>
     *
     * @param slot flash slot in {@code [0, flashSlotCount())}
     * @return the flash's world-space x
     */
    public float flashPositionX(final int slot)
    {
        return flashPosition[slot * AXES];
    }

    /** Returns the world-space y of a flash's position. See {@link #flashPositionX}. */
    public float flashPositionY(final int slot)
    {
        return flashPosition[slot * AXES + 1];
    }

    /** Returns the world-space z of a flash's position. See {@link #flashPositionX}. */
    public float flashPositionZ(final int slot)
    {
        return flashPosition[slot * AXES + 2];
    }

    /** Returns how many tracers are in the air, both directions together. */
    public int liveTracerCount()
    {
        return liveCount(tracerRemaining, 0, TRACER_SLOTS);
    }

    /** Returns how many of the player's own tracers are in the air. */
    public int liveOutgoingTracerCount()
    {
        return liveCount(tracerRemaining, 0, MAX_TRACERS);
    }

    /** Returns how many bolts fired BY the bots are in the air. */
    public int liveIncomingTracerCount()
    {
        return liveCount(tracerRemaining, MAX_TRACERS, TRACER_SLOTS);
    }

    /** Returns how many puffs are visible, both directions together. */
    public int livePuffCount()
    {
        return liveCount(puffAge, 0, PUFF_SLOTS);
    }

    /** Returns how many puffs at the player's own muzzle are visible. */
    public int liveOutgoingPuffCount()
    {
        return liveCount(puffAge, 0, MAX_PUFFS);
    }

    /** Returns how many puffs at the bots' muzzles are visible. */
    public int liveIncomingPuffCount()
    {
        return liveCount(puffAge, MAX_PUFFS, PUFF_SLOTS);
    }

    /** Returns how many flashes are in the air, both directions together. */
    public int liveFlashCount()
    {
        return liveCount(flashRemaining, 0, FLASH_SLOTS);
    }

    /** Returns how many of the player's own flashes are in the air. */
    public int liveOutgoingFlashCount()
    {
        return liveCount(flashRemaining, 0, MAX_PLAYER_FLASHES);
    }

    /** Returns how many flashes fired BY the bots are in the air. */
    public int liveIncomingFlashCount()
    {
        return liveCount(flashRemaining, MAX_PLAYER_FLASHES, FLASH_SLOTS);
    }

    private static int liveCount(final int[] slots, final int from, final int to)
    {
        // MUTABLE local — running count.
        int live = 0;

        for (int slot = from; slot < to; slot++)
        {
            if (slots[slot] != DEAD)
            {
                live++;
            }
        }

        return live;
    }

    /** Returns how many world instances this pool occupies in the scene. */
    public int instanceCount()
    {
        return tracerInstance.length + puffInstance.length + flashInstance.length;
    }

    // Where one stage's PUFF_LOBES * PUFF_COLOR_VARIANTS consecutive instance
    // indices begin. Lobe is added by the caller because the variant is
    // innermost in the layout — see puffInstanceIndex(int, int, int, int) for
    // the full address arithmetic.
    private static int stageOffset(final int slot, final int stage)
    {
        return (slot * PUFF_STAGES + stage) * PUFF_LOBES * PUFF_COLOR_VARIANTS;
    }

    /**
     * Builds a unit box, one colour throughout, centred on the model origin.
     *
     * <p>Eight vertices and twelve triangles, wound <b>counter-clockwise seen
     * from outside</b> — the glTF 2.0 rule, which is what
     * {@code SoftwareRenderPort.BACKFACE_CULL_MODE} was measured against. Get
     * the winding backwards and the box renders inside-out, which looks like a
     * plausible shape rather than like an error.</p>
     *
     * <p>Eight vertices rather than twenty-four because there is one colour and
     * no texture, so no corner needs to disagree with itself about anything. No
     * submesh table either, which is what puts every triangle on the flat path
     * and gives it the baked vertex colour — see
     * {@link ModelFormat#ofGeometry}.</p>
     *
     * @param colour the baked vertex colour, packed RGBA8888
     * @return a 1x1x1 box from -0.5 to +0.5 on every axis
     */
    static ModelFormat box(final int colour)
    {
        final float low = -0.5f;

        final float high = 0.5f;

        final int[] vertices = new int[BOX_VERTICES * ModelFormat.VERTEX_STRIDE_INTS];

        ModelFormat.writeVertex(vertices, 0, low, low, low, 0.0f, 0.0f, colour);

        ModelFormat.writeVertex(vertices, 1, high, low, low, 0.0f, 0.0f, colour);

        ModelFormat.writeVertex(vertices, 2, high, high, low, 0.0f, 0.0f, colour);

        ModelFormat.writeVertex(vertices, 3, low, high, low, 0.0f, 0.0f, colour);

        ModelFormat.writeVertex(vertices, 4, low, low, high, 0.0f, 0.0f, colour);

        ModelFormat.writeVertex(vertices, 5, high, low, high, 0.0f, 0.0f, colour);

        ModelFormat.writeVertex(vertices, 6, high, high, high, 0.0f, 0.0f, colour);

        ModelFormat.writeVertex(vertices, 7, low, high, high, 0.0f, 0.0f, colour);

        final int[] indices =
        {
            4, 5, 6, 4, 6, 7,
            1, 0, 3, 1, 3, 2,
            5, 1, 2, 5, 2, 6,
            0, 4, 7, 0, 7, 3,
            7, 6, 2, 7, 2, 3,
            0, 1, 5, 0, 5, 4,
        };

        return ModelFormat.ofGeometry(vertices, indices);
    }

    /**
     * Builds a coarse sphere of radius {@link #SPHERE_RADIUS}, one colour
     * throughout, centred on the model origin.
     *
     * <p><b>Built in memory rather than staged as an asset, for the reason
     * {@link ModelFormat#ofGeometry} exists:</b> nobody is going to model a grey
     * ball in Blender, and routing one through the offline converter would mean
     * an extra {@code .ofm} file, a build step to produce it and a staging
     * failure mode, for geometry that is two nested loops of trigonometry.</p>
     *
     * <p>A sphere and not a box because a box has <b>corners and flat faces</b>,
     * and a translucent flat face composited at a uniform coverage is a pane of
     * tinted glass — the exact thing the puff kept being reported as. A sphere
     * has no straight silhouette anywhere, and three overlapping ones have no
     * flat interior either.</p>
     *
     * <p>Wound <b>counter-clockwise seen from outside</b>, the glTF 2.0 rule
     * that {@code SoftwareRenderPort.BACKFACE_CULL_MODE} was measured against.
     * That is what culls the far hemisphere, which matters more here than it
     * does for opaque geometry: a translucent sphere whose back faces also drew
     * would composite twice everywhere and be twice as dense as the coverage
     * ladder says.</p>
     *
     * <p>No submesh table and no texture, so every triangle takes the flat path
     * and the baked vertex colour — the same contract the tracer's box uses, and
     * what the translucent phase requires, since it binds no texture table.</p>
     *
     * @param colour the baked vertex colour, packed RGBA8888
     * @return a sphere of radius {@link #SPHERE_RADIUS} about the origin
     */
    static ModelFormat sphere(final int colour)
    {
        final int ringVertices = (SPHERE_STACKS - 1) * SPHERE_MERIDIANS;

        final int south = ringVertices + 1;

        final int[] vertices = new int[(south + 1) * ModelFormat.VERTEX_STRIDE_INTS];

        ModelFormat.writeVertex(vertices, 0, 0.0f, SPHERE_RADIUS, 0.0f, 0.0f, 0.0f, colour);

        ModelFormat.writeVertex(vertices, south, 0.0f, -SPHERE_RADIUS, 0.0f, 0.0f, 0.0f, colour);

        for (int stack = 1; stack < SPHERE_STACKS; stack++)
        {
            final float polar = HALF_TURN_RADIANS * stack / SPHERE_STACKS;

            final float height = SPHERE_RADIUS * (float) StrictMath.cos(polar);

            final float ring = SPHERE_RADIUS * (float) StrictMath.sin(polar);

            for (int meridian = 0; meridian < SPHERE_MERIDIANS; meridian++)
            {
                final float azimuth = FULL_TURN_RADIANS * meridian / SPHERE_MERIDIANS;

                ModelFormat.writeVertex(vertices, ringVertex(stack, meridian),
                    ring * (float) StrictMath.cos(azimuth), height,
                    ring * (float) StrictMath.sin(azimuth), 0.0f, 0.0f, colour);
            }
        }

        // Two caps of MERIDIANS triangles and STACKS-2 bands of twice that.
        final int triangles = SPHERE_MERIDIANS * 2 * (SPHERE_STACKS - 1);

        final int[] indices = new int[triangles * ModelFormat.INDICES_PER_TRIANGLE];

        // MUTABLE local — the write cursor into the index array.
        int at = 0;

        for (int meridian = 0; meridian < SPHERE_MERIDIANS; meridian++)
        {
            final int next = (meridian + 1) % SPHERE_MERIDIANS;

            // North cap. The pole is the degenerate case of the band below with
            // its upper ring collapsed to a point, so the order is the band's
            // second triangle with u[j] and u[j+1] both replaced by the pole.
            at = triangle(indices, at, 0, ringVertex(1, next), ringVertex(1, meridian));

            for (int stack = 1; stack < SPHERE_STACKS - 1; stack++)
            {
                final int upperHere = ringVertex(stack, meridian);

                final int upperNext = ringVertex(stack, next);

                final int lowerHere = ringVertex(stack + 1, meridian);

                final int lowerNext = ringVertex(stack + 1, next);

                at = triangle(indices, at, upperHere, upperNext, lowerNext);

                at = triangle(indices, at, upperHere, lowerNext, lowerHere);
            }

            // South cap, the same degeneracy at the other end.
            at = triangle(indices, at, ringVertex(SPHERE_STACKS - 1, meridian),
                ringVertex(SPHERE_STACKS - 1, next), south);
        }

        return ModelFormat.ofGeometry(vertices, indices);
    }

    // The vertex index of one point on one ring. Ring `stack` runs 1 to
    // STACKS-1; the two poles are 0 and (STACKS-1) * MERIDIANS + 1.
    private static int ringVertex(final int stack, final int meridian)
    {
        return 1 + (stack - 1) * SPHERE_MERIDIANS + meridian;
    }

    // Writes one triangle and returns the next write position.
    private static int triangle(final int[] indices, final int at, final int a, final int b,
        final int c)
    {
        indices[at] = a;

        indices[at + 1] = b;

        indices[at + 2] = c;

        return at + ModelFormat.INDICES_PER_TRIANGLE;
    }

    /** Returns a debug rendering of the pool's state. */
    @Override
    public String toString()
    {
        return "DemoEffects{tracers=" + liveOutgoingTracerCount() + "/" + MAX_TRACERS
            + " out, " + liveIncomingTracerCount() + "/" + MAX_BOT_TRACERS + " in"
            + ", puffs=" + liveOutgoingPuffCount() + "/" + MAX_PUFFS
            + " out, " + liveIncomingPuffCount() + "/" + MAX_BOT_PUFFS + " in"
            + ", instances=" + instanceCount() + "}";
    }
}
