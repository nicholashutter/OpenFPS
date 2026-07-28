/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import com.openfps.engine.common.Constants;
import com.openfps.engine.gameplay.Bot;
import com.openfps.engine.gameplay.BotPattern;
import com.openfps.engine.gameplay.Match;
import com.openfps.engine.gameplay.PlayerController;
import com.openfps.engine.render.adapter.Mat4;
import com.openfps.engine.render.adapter.ModelFormat;
import com.openfps.engine.render.adapter.Scene;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The first-person demo's world: a room to stand in and a weapon to hold.
 *
 * <p>Builds one immutable {@link Scene} — a tiled floor, a closed perimeter
 * wall with a doorway, and props — plus the spawn placement that goes with it.
 * Built <b>once</b>, never per frame: {@link Scene} is immutable and the whole
 * point of it is that rendering one allocates nothing.</p>
 *
 * <h2>THE SCALE PROBLEM — read this before moving anything</h2>
 *
 * <p><b>The two kits are not authored to a common unit, and getting the
 * conversion wrong is the difference between a room and a dolls' house.</b></p>
 *
 * <ul>
 *   <li>{@link PlayerController#EYE_HEIGHT_UNITS} is <b>41</b>, and
 *       {@link Constants#PLAYER_RADIUS} is <b>16</b>. Those are DOOM's numbers,
 *       and this project inherited its geometry constants from that scale.</li>
 *   <li>Kenney's Prototype Kit is authored on a <b>1-unit grid</b>: a floor
 *       tile is 1x1 and a wall is 1 unit tall
 *       ({@code docs/DEMO_ASSETS.md} § 3).</li>
 * </ul>
 *
 * <p>So a Kenney wall placed unscaled would be <b>1/41 of the player's eye
 * height</b> — the player would tower over the level like a god looking into a
 * model railway, and the temptation would be to "fix" it by shrinking the
 * player, which breaks every movement and collision constant at once. The kit
 * is scaled instead, and by exactly one number: {@link #KIT_WORLD_SCALE}.</p>
 *
 * <p><b>The weapon has its own scale and always will.</b>
 * {@link #WEAPON_VIEW_SCALE} has no relationship to
 * {@link #KIT_WORLD_SCALE}, because a viewmodel is not in the world: it lives
 * in view space, it is sized to look right on a screen, and it would be
 * measured in different units even if both kits agreed with each other.
 * Deriving one from the other would be a coincidence dressed as a rule.</p>
 *
 * <h2>Why the viewmodel is a view instance</h2>
 *
 * <p>The weapon is added with {@link Scene.Builder#addViewInstance}, so it is
 * expressed directly in view space and is drawn after a depth clear. That is
 * what stops it clipping into a wall the player is standing against — the
 * classic first-person problem — and it means its transform is a
 * <b>constant</b>: "this far forward, right and down of the eye" is the whole
 * statement, with no dependence on where the player is or is looking.</p>
 */
public final class DemoScene
{
    /**
     * World units per Kenney grid unit for the level kit — <b>64</b>.
     *
     * <p>The arithmetic, because a bare 64 is unfalsifiable:</p>
     *
     * <pre>
     *   Kenney wall height    1.0 grid unit  x 64  =  64 world units
     *   two stacked courses   2.0 grid units x 64  = 128 world units  (ceiling)
     *   Kenney floor tile     1.0 grid unit  x 64  =  64 world units  (tile pitch)
     *   player eye height                             41 world units
     *   player radius                                 16 world units
     * </pre>
     *
     * <p>Three independent reasons this is the right number rather than a
     * round one:</p>
     *
     * <ol>
     *   <li><b>64 is DOOM's map grid.</b> The constants this engine inherited —
     *       a 16-unit player radius and a 41-unit eye — were authored against
     *       64-unit level geometry, so mapping one Kenney grid cell onto one
     *       DOOM grid cell is not an approximation of the intended proportion,
     *       it is the intended proportion.</li>
     *   <li><b>The eye lands where a human eye lands.</b> 41 of a 128-unit
     *       ceiling is 32% of the way up, and a 1.7 m person in a 5.3 m room is
     *       32% of the way up. A single 64-unit course would put the eye at 64%
     *       of the ceiling, which reads as a crawlspace.</li>
     *   <li><b>The player fits through the door.</b> A 32-unit-diameter player
     *       in a 64-unit doorway has a radius of clearance either side.
     *       ({@link Constants#PLAYER_RADIUS} is 16.16 fixed-point, so it reads
     *       16 * 65536; divide by {@link Constants#MAP_SCALE} for world
     *       units.)</li>
     * </ol>
     *
     * <p><b>Corroboration, found after the fact and worth more than the
     * reasoning above:</b> {@link Constants#MAX_OPEN_HEIGHT} — this engine's
     * own inherited constant for the tallest open space, written long before
     * this demo existed — is <b>128 world units</b>. Two Kenney courses at this
     * scale land on it exactly. The number was not chosen to match; it was
     * chosen from the eye height, and then turned out to agree with a constant
     * derived from the same lineage. {@code DemoSceneTest} asserts the equality
     * so the two cannot drift apart silently.</p>
     *
     * <p>Change this and the room changes size around a player who does not,
     * so change {@link #WALL_COURSES} with it if the ceiling is to stay put.</p>
     */
    public static final float KIT_WORLD_SCALE = 64.0f;

    /**
     * World units per generated-room unit for the fallback — <b>32</b>.
     *
     * <p>A different number for a different kit, for the same reason the kit
     * needs one at all: {@code ProceduralRoom} authors its walls <b>4 units
     * tall</b>, not 1. The scale is chosen so the fallback room has the same
     * ceiling as the real one, which is what makes the two demos comparable:</p>
     *
     * <pre>
     *   generated wall height   4.0 units x 32 = 128 world units  (same ceiling)
     *   generated interior     16.0 units x 32 = 512 world units  (16x16 room)
     * </pre>
     */
    public static final float FALLBACK_WORLD_SCALE = 32.0f;

    /** Floor tiles along each side of the room. Ten tiles is 640 world units. */
    public static final int ROOM_TILES = 10;

    /** Stacked wall segments, floor to ceiling. Two courses is 128 world units. */
    public static final int WALL_COURSES = 2;

    /**
     * Scale applied to the weapon, model units to view-space units — 1.9.
     *
     * <p>Nothing to do with {@link #KIT_WORLD_SCALE}; see the class Javadoc.
     * The blaster is 0.42 model units long and 0.31 tall
     * ({@code docs/DEMO_ASSETS.md} § 3), so at 1.9 it is 0.80 x 0.59 view
     * units. Held at {@link #WEAPON_VIEW_FORWARD} that subtends about a
     * quarter of the screen height at the default 60 degree vertical field of
     * view, which is the size a first-person weapon has been since 1993 — big
     * enough to read as held, small enough that the grip is not sliced off by
     * the bottom of the frame.</p>
     */
    public static final float WEAPON_VIEW_SCALE = 1.9f;

    /**
     * Weapon yaw in degrees within view space — 174.
     *
     * <p>Two rotations that happen to compose into one number, so both are
     * written down:</p>
     *
     * <ul>
     *   <li><b>180 degrees</b> because the blaster's muzzle points along model
     *       <b>-z</b> (verified by rendering it, not assumed), and view space
     *       is <b>+z forward</b>. Without the flip the player holds the gun
     *       backwards and sees the grip end-on.</li>
     *   <li><b>-6 degrees</b> of toe-in. The weapon sits right of centre, so
     *       aiming it dead ahead would have it visibly pointing past the
     *       crosshair. Angling it slightly inward is what makes a viewmodel
     *       look aimed rather than carried.</li>
     * </ul>
     *
     * <p>A yaw is a rotation, so its determinant is +1 whatever the angle —
     * which matters, because {@link Scene} rejects a negative determinant
     * outright rather than let a mirrored weapon render inside-out.</p>
     */
    public static final float WEAPON_VIEW_YAW_DEGREES = 174.0f;

    /**
     * How far in front of the eye the weapon sits, in view-space units — 1.85.
     *
     * <p>Must clear the near plane, and does so with room to spare: the
     * weapon's nearest vertex lands at about 1.43 view units against
     * {@link PlayerController#DEFAULT_NEAR_PLANE_UNITS} of 1.0. Too close and
     * the near plane slices the stock off; too far and the perspective
     * flattens it into a decal.</p>
     */
    public static final float WEAPON_VIEW_FORWARD = 1.85f;

    /** How far right of the eye the weapon sits, in view-space units. */
    public static final float WEAPON_VIEW_RIGHT = 0.92f;

    /** How far below the eye the weapon sits, in view-space units. Negative is down. */
    public static final float WEAPON_VIEW_DOWN = -0.38f;

    private static final Logger LOG = LoggerFactory.getLogger(DemoScene.class);

    /** Degrees in half a turn, for the degrees-to-radians conversion. */
    private static final float DEGREES_PER_HALF_TURN = 180.0f;

    /** A quarter turn in radians — the rotation the z-running wall needs to run along x. */
    private static final float QUARTER_TURN_RADIANS = (float) (StrictMath.PI * 0.5);

    /** Half the room's span in world units: five tiles of 64 either side of the origin. */
    private static final float HALF_ROOM_UNITS = ROOM_TILES * KIT_WORLD_SCALE * 0.5f;

    /** Which tile of the south wall is a doorway rather than a wall. */
    private static final int DOORWAY_TILE = 5;

    /**
     * Where the player spawns, as a fraction of the room half-span behind the
     * origin — 0.6, which is 192 units back in a room whose wall is at 320.
     *
     * <p>Not further. At 0.8 the player spawns 64 units from the north wall,
     * which is two player radii: turning round puts a wall across the whole
     * frame and the room stops reading as a room. 0.6 leaves 128 units behind
     * the player and still has the far wall comfortably in frame ahead.</p>
     */
    private static final float SPAWN_DEPTH_FRACTION = 0.6f;

    /** Interior half-extent of the generated fallback room, in its own units. */
    private static final float FALLBACK_INTERIOR_HALF_EXTENT = 8.0f;

    /** Column placement, as a fraction of the room half-span. */
    private static final float COLUMN_FRACTION = 0.4f;

    /**
     * Crate placements as (x, y, z) triples in world units, laid out by hand.
     *
     * <p>Deliberately irregular. Evenly spaced props give a moving camera a
     * repeating pattern that is genuinely hard to read motion against, which is
     * the opposite of what a prop is for here. The fourth entry sits on top of
     * the third — a crate is 32 units tall once scaled — because a stack is the
     * cheapest thing in the room that proves the depth buffer is working
     * vertically as well as horizontally.</p>
     */
    private static final float[] CRATE_PLACEMENTS =
    {
        -192.0f,  0.0f,   0.0f,
         160.0f,  0.0f, -96.0f,
        -128.0f,  0.0f,  64.0f,
        -128.0f, 32.0f,  64.0f,
          96.0f,  0.0f, 192.0f,
         -64.0f,  0.0f, -224.0f,
         224.0f,  0.0f, 224.0f,
        -224.0f,  0.0f, 160.0f,
    };

    /**
     * Height of a character model in its own units, measured from the asset:
     * {@code extent 1.60 x 2.70 x 0.80}.
     *
     * <p>Read off {@code gradlew :tools:verifyModels}, not guessed. If the
     * character pack is ever re-exported at a different size this is the one
     * number that changes, and {@code DemoSceneTest} asserts the scale it
     * produces still matches the player box.</p>
     */
    public static final float CHARACTER_MODEL_HEIGHT = 2.70f;

    /** Player height in world units — {@link Constants#PLAYER_HEIGHT} unscaled. */
    public static final float PLAYER_HEIGHT_UNITS =
        Constants.PLAYER_HEIGHT / (float) Constants.MAP_SCALE;

    /** Player radius in world units — {@link Constants#PLAYER_RADIUS} unscaled. */
    public static final float PLAYER_RADIUS_UNITS =
        Constants.PLAYER_RADIUS / (float) Constants.MAP_SCALE;

    /**
     * Scale from character-model units to world units, so a target stands
     * exactly {@link #PLAYER_HEIGHT_UNITS} tall.
     *
     * <p><b>Derived, not chosen</b>, and it corroborates the same way
     * {@link #KIT_WORLD_SCALE} does. 56 / 2.70 is about 20.74, which puts the
     * model's 1.60-unit shoulder span at roughly 33 world units against a
     * player diameter of 32 — two numbers from unrelated sources, Kenney's
     * proportions and DOOM's collision box, agreeing to within a unit. Nothing
     * was tuned to make that happen.</p>
     *
     * <p>The reason it must be derived rather than eyeballed: this scale sizes
     * what you SEE and {@link Constants#PLAYER_HEIGHT} sizes what you HIT. Set
     * them independently and shots pass through a visible chest, or connect
     * beside a shoulder, and no screenshot would ever show it.</p>
     */
    public static final float CHARACTER_WORLD_SCALE =
        PLAYER_HEIGHT_UNITS / CHARACTER_MODEL_HEIGHT;


    /**
     * Where the bots patrol: route centre {x, z} in world units, in id order.
     *
     * <p>Chosen to exercise the things that are easy to get wrong rather than to
     * look tidy. The first sits straight down the spawn bearing so there is
     * something to sight along without turning. The second and third are placed
     * so they <b>overlap in screen space from the spawn</b> — the case where a
     * naive outline merges two people into one blob. The rest are spread to the
     * flanks and the back of the room so clearing it needs the player to turn
     * around, and so that some of them start outside
     * {@code Match.BOT_RANGE_UNITS} and cannot shoot until they are found.</p>
     *
     * <p>All seven stay inside the perimeter once their amplitude is added: the
     * wall is at 320 and a bot is 16 units in radius, so the furthest legal
     * centre-plus-reach is 304. {@code DemoSceneTest} asserts that rather than
     * trusting the arithmetic below, because a bot that walks through a wall is
     * the sort of thing that looks like a physics bug for a long time before
     * anyone checks the table.</p>
     */
    private static final float[] BOT_ROUTE_CENTRES =
    {
        0.0f, 60.0f,
        -78.0f, 30.0f,
        -88.0f, 108.0f,
        142.0f, -46.0f,
        196.0f, 150.0f,
        -196.0f, 176.0f,
        56.0f, 244.0f,
    };

    /** Stride of {@link #BOT_ROUTE_CENTRES}: x and z. */
    private static final int BOT_STRIDE = 2;

    /**
     * Which route each bot walks, in id order.
     *
     * <p><b>Bots 1 and 2 are sentries, and that is load-bearing.</b> They are the
     * overlapping pair — the case where a naive outline merges two people into
     * one blob — and the case only means anything if it is reproducible. Two
     * <i>moving</i> bodies that happen to overlap on some frame prove nothing;
     * two that always overlap can be asserted and photographed.</p>
     *
     * <p>Bot 0 paces, but with phase zero, so at tic 0 it is exactly on the
     * spawn bearing where {@code DemoSceneTest} sights along it — and a few
     * seconds later it is not, which is what makes the demo a demo. No two
     * adjacent bots move the same way, so the room does not read as a single
     * mechanism.</p>
     */
    private static final BotPattern[] BOT_PATTERNS =
    {
        BotPattern.PACE_X,
        BotPattern.SENTRY,
        BotPattern.SENTRY,
        BotPattern.ORBIT,
        BotPattern.PACE_Z,
        BotPattern.ORBIT,
        BotPattern.PACE_X,
    };

    /** How far each bot's route reaches from its centre, in world units. */
    private static final float[] BOT_AMPLITUDES =
    {
        80.0f, 0.0f, 0.0f, 70.0f, 90.0f, 60.0f, 104.0f,
    };

    /**
     * Tics for one full circuit of each route.
     *
     * <p>Deliberately not a common multiple of each other. Equal periods would
     * have the whole room reach its extremes on the same tic and pulse in
     * unison, which reads as a single machine rather than as several people. The
     * shortest here is 240 tics — four seconds at 60 Hz — which at 104 units of
     * reach is a peak speed of about 163 units a second against the player's
     * 256. Slower than the player on purpose: these are target practice, and a
     * target that outruns you is not.</p>
     */
    private static final int[] BOT_PERIODS =
    {
        300, 300, 300, 420, 360, 480, 240,
    };

    /**
     * Where in its circuit each bot starts, so two on the same period are not
     * shoulder to shoulder.
     */
    private static final int[] BOT_PHASES =
    {
        0, 0, 0, 105, 90, 240, 60,
    };

    private final Scene scene;
    private final Bot[] bots;
    private final int[] botInstances;
    private final float spawnX;
    private final float spawnY;
    private final float spawnZ;
    private final float spawnYawRadians;
    private final DemoModels.Source source;

    // Takes ownership of a finished scene and the spawn that belongs to it.
    private DemoScene(final Scene builtScene, final Bot[] roster, final int[] instanceIndices,
        final float feetX, final float feetY, final float feetZ, final float yawRadians,
        final DemoModels.Source modelSource)
    {
        this.scene = builtScene;
        this.bots = roster;
        this.botInstances = instanceIndices;
        this.spawnX = feetX;
        this.spawnY = feetY;
        this.spawnZ = feetZ;
        this.spawnYawRadians = yawRadians;
        this.source = modelSource;
    }

    /**
     * Assembles the demo world from a loaded model set.
     *
     * <p>Which room is built depends on {@link DemoModels#source()}: the tiled
     * Kenney room, or the single-model generated room at its own scale. The
     * weapon is added as a view instance either way, when there is one.</p>
     *
     * @param models the loaded models; must not be null
     * @return the assembled scene and its spawn placement
     */
    public static DemoScene build(final DemoModels models)
    {
        if (models == null)
        {
            throw new IllegalArgumentException("models must not be null");
        }

        final Scene.Builder builder = Scene.builder();
        final float spawnDepth;
        if (models.isRealArt())
        {
            addKitRoom(builder, models);
            spawnDepth = HALF_ROOM_UNITS * SPAWN_DEPTH_FRACTION;
        }
        else
        {
            builder.addWorldInstance(models.room(), placement(0.0f, 0.0f, 0.0f, 0.0f,
                FALLBACK_WORLD_SCALE));
            spawnDepth =
                FALLBACK_INTERIOR_HALF_EXTENT * FALLBACK_WORLD_SCALE * SPAWN_DEPTH_FRACTION;
        }
        final int[] instanceIndices = new int[BOT_ROUTE_CENTRES.length / BOT_STRIDE];
        final Bot[] roster = addBots(builder, models, instanceIndices);
        addViewmodel(builder, models.weapon());

        final Scene built = builder.build();
        LOG.info("Demo scene built from {}: {} world instances, {} view instances,"
            + " largest {} triangles, world scale {}", models.source(),
            built.worldInstanceCount(), built.viewInstanceCount(),
            built.maxInstanceTriangles(), worldScaleOf(models));
        // Facing +z, which is yaw 0 (PlayerController's convention), standing
        // back from the origin so the whole room is ahead rather than around.
        return new DemoScene(built, roster, instanceIndices, 0.0f, 0.0f, -spawnDepth, 0.0f,
            models.source());
    }

    /**
     * Stands the character models up as bots and records where each one landed
     * in the scene.
     *
     * <p>Each bot gets one scene instance and one {@link Bot}, sharing an entity
     * id, and <b>the two are built here together on purpose</b>. The model is
     * what the outline pass draws around; the bot owns the hitbox
     * {@code Hitscan} tests. Building them in separate places is how they come
     * to disagree, and a disagreement is invisible — you would see a body, shoot
     * it, and nothing would happen.</p>
     *
     * <p>The <b>instance index</b> is captured as the bot is added, because that
     * index is the only handle anything has on a scene instance afterwards:
     * {@code SoftwareRenderPort.setWorldTransform} is how a bot's model follows
     * its patrol, and it addresses instances by position. Reading the builder's
     * count immediately before the {@code add} is what makes that index correct
     * no matter how much furniture is added above this method.</p>
     *
     * <p>Places nothing when no character art is staged, which leaves the scene
     * untagged, the outline pass entirely un-dispatched, and the match won from
     * the first check. That is a degraded demo rather than a broken one, and it
     * is what a checkout with no assets fetched will produce.</p>
     *
     * @param builder the scene under construction; instances are appended
     * @param models the loaded model set
     * @param instanceIndices filled with the scene index of each placed bot;
     *     must be at least as long as the bot roster
     * @return one bot per placement, in id order, never null
     */
    private static Bot[] addBots(final Scene.Builder builder, final DemoModels models,
        final int[] instanceIndices)
    {
        if (!models.hasCharacters())
        {
            return new Bot[0];
        }

        final ModelFormat[] people = models.characters();
        final int count = BOT_ROUTE_CENTRES.length / BOT_STRIDE;
        final Bot[] roster = new Bot[count];
        for (int index = 0; index < count; index++)
        {
            final float homeX = BOT_ROUTE_CENTRES[index * BOT_STRIDE];
            final float homeZ = BOT_ROUTE_CENTRES[index * BOT_STRIDE + 1];
            final int entityId = Match.FIRST_BOT_ENTITY_ID + index;

            final Bot bot = new Bot(entityId, homeX, 0.0f, homeZ, BOT_PATTERNS[index],
                BOT_AMPLITUDES[index], BOT_PERIODS[index], BOT_PHASES[index],
                Match.BOT_FIRE_INTERVAL_TICS, fireOffsetFor(index, count));

            // Cycles through however many models were staged, so a partially
            // staged pack still puts a body at every placement rather than
            // leaving holes in the arrangement. With the full seven it is one
            // distinct person each.
            final ModelFormat model = people[index % people.length];
            instanceIndices[index] = builder.worldInstanceCount();
            builder.addWorldInstance(model, botPlacement(bot), entityId);
            roster[index] = bot;
        }
        LOG.info("Demo bots: {} placed from {} model(s), ids {}..{}, {} world units tall,"
            + " radius {}", count, people.length, Match.FIRST_BOT_ENTITY_ID,
            Match.FIRST_BOT_ENTITY_ID + count - 1, PLAYER_HEIGHT_UNITS, PLAYER_RADIUS_UNITS);
        return roster;
    }

    /**
     * Returns the placement transform for a bot at its current position.
     *
     * <p>Public because it is called every tic from outside — the gameplay port
     * hands the result to {@code SoftwareRenderPort.setWorldTransform} so a
     * bot's model follows its patrol. It is the one piece of the scene that is
     * <b>not</b> built once.</p>
     *
     * <p>A dead bot is toppled rather than removed. Removing it would mean
     * rebuilding the whole {@link Scene} — texture table, stream offsets, entity
     * ids, buffer sizing — for a set of instances that has not otherwise
     * changed, and a body left standing would be indistinguishable from one that
     * was never hit. Falling over costs one transform and reads instantly.</p>
     *
     * @param bot the bot to place; must not be null
     * @return its model-to-world transform this tic
     */
    public static Mat4 botPlacement(final Bot bot)
    {
        if (bot.isAlive())
        {
            return placement(bot.positionX(), bot.positionY(), bot.positionZ(),
                bot.yawRadians(), CHARACTER_WORLD_SCALE);
        }
        return fallenPlacement(bot.positionX(), bot.positionY(), bot.positionZ(),
            bot.yawRadians(), CHARACTER_WORLD_SCALE);
    }

    /**
     * Staggers the bots' firing so seven of them do not volley on the same tic.
     *
     * <p>{@code Bot.wantsToFire} is true when {@code (tic + offset)} is a
     * multiple of the interval, so distinct offsets put distinct bots on
     * distinct tics. Spreading them evenly across the interval turns one shot
     * every 150 tics per bot into one shot every 21 tics somewhere in the room —
     * the same total rate, but a rate the player experiences as pressure rather
     * than as a periodic broadside.</p>
     *
     * @param index which bot, from zero
     * @param count how many bots there are; must be positive
     * @return the firing offset in tics
     */
    private static int fireOffsetFor(final int index, final int count)
    {
        return index * (Match.BOT_FIRE_INTERVAL_TICS / count);
    }

    // The room the demo is actually meant to show: floor, walls, props.
    private static void addKitRoom(final Scene.Builder builder, final DemoModels models)
    {
        addFloor(builder, models.floor());
        addWalls(builder, models.wall(), models.doorway());
        addProps(builder, models);
    }

    // A full grid of floor tiles, and the same grid again upside down at
    // ceiling height. Each tile is a flat quad at y = 0, so the floor surface
    // is exactly the plane the player's feet position sits on.
    //
    // The ceiling is what makes the room an interior rather than a walled yard:
    // without it the frame is a quarter dark sky and the eye reads the walls as
    // free-standing. It costs one more instance per tile and no new model.
    private static void addFloor(final Scene.Builder builder, final ModelFormat tile)
    {
        final float ceiling = WALL_COURSES * KIT_WORLD_SCALE;
        for (int alongX = 0; alongX < ROOM_TILES; alongX++)
        {
            for (int alongZ = 0; alongZ < ROOM_TILES; alongZ++)
            {
                final float x = tileCentre(alongX);
                final float z = tileCentre(alongZ);
                builder.addWorldInstance(tile, placement(x, 0.0f, z, 0.0f, KIT_WORLD_SCALE));
                builder.addWorldInstance(tile, invertedPlacement(x, ceiling, z,
                    KIT_WORLD_SCALE));
            }
        }
    }

    // The perimeter, two courses high.
    //
    // The wall model runs its LENGTH along z and its thickness along x
    // (0.20 x 1.00 x 1.00 — docs/DEMO_ASSETS.md § 3), so the two walls at
    // constant x take it unrotated and the two at constant z need a quarter
    // turn. Corners need no special piece: the east wall's end segment and the
    // south wall's end segment overlap in the corner square, because each runs
    // the full span rather than stopping one tile short. A wall-corner piece
    // placed there as well would z-fight with both.
    private static void addWalls(final Scene.Builder builder, final ModelFormat wall,
        final ModelFormat doorway)
    {
        for (int course = 0; course < WALL_COURSES; course++)
        {
            final float y = course * KIT_WORLD_SCALE;
            for (int tile = 0; tile < ROOM_TILES; tile++)
            {
                final float centre = tileCentre(tile);
                builder.addWorldInstance(wall,
                    placement(-HALF_ROOM_UNITS, y, centre, 0.0f, KIT_WORLD_SCALE));
                builder.addWorldInstance(wall,
                    placement(HALF_ROOM_UNITS, y, centre, 0.0f, KIT_WORLD_SCALE));
                builder.addWorldInstance(wall,
                    placement(centre, y, -HALF_ROOM_UNITS, QUARTER_TURN_RADIANS,
                        KIT_WORLD_SCALE));
                builder.addWorldInstance(southWallPiece(wall, doorway, course, tile),
                    placement(centre, y, HALF_ROOM_UNITS, QUARTER_TURN_RADIANS,
                        KIT_WORLD_SCALE));
            }
        }
    }

    // One tile of the south wall. The doorway replaces the bottom course only,
    // so the course above it becomes the lintel and the opening is a hole in a
    // wall rather than a gap running to the ceiling.
    private static ModelFormat southWallPiece(final ModelFormat wall, final ModelFormat doorway,
        final int course, final int tile)
    {
        if (course == 0 && tile == DOORWAY_TILE)
        {
            return doorway;
        }
        return wall;
    }

    // Columns, crates, a staircase and a ramp.
    //
    // An empty box is very hard to judge movement in: with nothing between the
    // player and the far wall, walking forward changes the image so little that
    // the demo cannot distinguish working movement from broken movement. Props
    // at different depths give parallax, which is the whole diagnostic.
    private static void addProps(final Scene.Builder builder, final DemoModels models)
    {
        final float columnOffset = HALF_ROOM_UNITS * COLUMN_FRACTION;
        builder.addWorldInstance(models.column(),
            placement(-columnOffset, 0.0f, -columnOffset, 0.0f, KIT_WORLD_SCALE));
        builder.addWorldInstance(models.column(),
            placement(columnOffset, 0.0f, -columnOffset, 0.0f, KIT_WORLD_SCALE));
        builder.addWorldInstance(models.column(),
            placement(-columnOffset, 0.0f, columnOffset, 0.0f, KIT_WORLD_SCALE));
        builder.addWorldInstance(models.column(),
            placement(columnOffset, 0.0f, columnOffset, 0.0f, KIT_WORLD_SCALE));

        for (int crate = 0; crate < CRATE_PLACEMENTS.length; crate += 3)
        {
            builder.addWorldInstance(models.crate(),
                placement(CRATE_PLACEMENTS[crate], CRATE_PLACEMENTS[crate + 1],
                    CRATE_PLACEMENTS[crate + 2], 0.0f, KIT_WORLD_SCALE));
        }

        builder.addWorldInstance(models.stairs(),
            placement(0.0f, 0.0f, 224.0f, 0.0f, KIT_WORLD_SCALE));
        builder.addWorldInstance(models.slope(),
            placement(-96.0f, 0.0f, -160.0f, QUARTER_TURN_RADIANS, KIT_WORLD_SCALE));
    }

    // The held weapon, or nothing at all when none was staged. A null model is
    // not an error here — DemoModels has already said so at WARN.
    private static void addViewmodel(final Scene.Builder builder, final ModelFormat weapon)
    {
        if (weapon == null)
        {
            return;
        }
        builder.addViewInstance(weapon, weaponTransform());
    }

    /**
     * Returns the weapon's fixed model-to-view transform.
     *
     * <p>Exposed so a test can assert the two properties that are invisible in
     * a screenshot until they are wrong: that the whole model clears the near
     * plane, and that the transform's determinant is positive so
     * {@link Scene} will not reject it as a mirror.</p>
     *
     * @return the transform placing the blaster in the player's hands
     */
    public static Mat4 weaponTransform()
    {
        return placement(WEAPON_VIEW_RIGHT, WEAPON_VIEW_DOWN, WEAPON_VIEW_FORWARD,
            radians(WEAPON_VIEW_YAW_DEGREES), WEAPON_VIEW_SCALE);
    }

    /**
     * Builds a uniform-scale, yaw-rotated, translated placement.
     *
     * <p>{@code T . R_y(yaw) . S(scale)} — the model is scaled first, then
     * turned about its own +y axis, then moved. Written out as sixteen floats
     * rather than composed from three matrices because {@link Mat4} has no
     * rotation or scale factory, and because the composed result is the thing
     * worth reading:</p>
     *
     * <pre>
     *   [  s.cos    0     s.sin    x ]
     *   [  0        s     0        y ]
     *   [ -s.sin    0     s.cos    z ]
     *   [  0        0     0        1 ]
     * </pre>
     *
     * <p>The yaw convention is deliberately {@link PlayerController}'s own: a
     * yaw of 0 points the model's +z along world +z, and yaw increases from +z
     * toward +x. Using a different sign here from the one the player turns by
     * would be a mirror nobody could see until a door was on the wrong side of
     * a room.</p>
     *
     * <p>The determinant of the upper-left 3x3 is {@code scale^3}, so it is
     * positive for any positive scale and {@link Scene} will accept it. That is
     * not an accident to rely on silently — a negative scale would reverse
     * triangle winding and render the instance inside-out.</p>
     *
     * @param x world (or view) x translation
     * @param y world (or view) y translation
     * @param z world (or view) z translation
     * @param yawRadians rotation about +y, in radians
     * @param scale uniform scale; must be positive
     * @return the placement transform
     */
    public static Mat4 placement(final float x, final float y, final float z,
        final float yawRadians, final float scale)
    {
        if (!(scale > 0.0f))
        {
            throw new IllegalArgumentException(
                "scale must be positive, or the instance is mirrored or collapsed, got " + scale);
        }
        final float sin = (float) StrictMath.sin(yawRadians) * scale;
        final float cos = (float) StrictMath.cos(yawRadians) * scale;
        return Mat4.ofRowMajor(new float[]
        {
            cos, 0.0f, sin, x,
            0.0f, scale, 0.0f, y,
            -sin, 0.0f, cos, z,
            0.0f, 0.0f, 0.0f, 1.0f,
        });
    }

    /**
     * Builds the placement of a body that has fallen over.
     *
     * <p>How a kill is made visible. The alternative — removing the instance —
     * means rebuilding the whole {@link Scene}: texture table, stream offsets,
     * entity ids and buffer sizing, all re-derived because one body stopped
     * existing. This costs one transform, and a body on the floor is a clearer
     * statement than a body that vanished.</p>
     *
     * <p><b>The rotation is about the model's own +x axis, applied before the
     * yaw</b>, so the body falls face-first along whatever direction it was
     * looking rather than always toward world north. The composition is
     * {@code T . R_y(yaw) . R_x(+90) . S(scale)}, whose columns are the images
     * of the model's own axes:</p>
     *
     * <pre>
     *   model +x -&gt; ( cos, 0, -sin)   across the body, unchanged by the pitch
     *   model +y -&gt; ( sin, 0,  cos)   the head, now along the former heading
     *   model +z -&gt; (   0, -1,    0)  the face, now pointing at the floor
     * </pre>
     *
     * <p>The sign of that pitch is the whole difference between falling forwards
     * and falling backwards, and it is invisible in the matrix: {@code R_x(-90)}
     * is just as valid a rotation, has the same determinant, and drops every
     * body the wrong way. The head landing along the heading is what says it is
     * right.</p>
     *
     * <p>Both factors are rotations, so the determinant is {@code scale^3} and
     * stays positive — {@link Scene} rejects a negative one outright rather than
     * render an instance inside-out, so this is a build failure waiting for
     * anyone who reaches for a {@code scale(1, -1, 1)} instead.</p>
     *
     * <p>The body is also raised by half its own height, because a model whose
     * origin is at its feet, rotated flat, ends up with half its bulk below the
     * floor. {@code CHARACTER_MODEL_HEIGHT * scale * 0.5} is exactly the
     * correction, and it is derived from the same constant the standing scale
     * uses rather than eyeballed.</p>
     *
     * @param x world x translation
     * @param y world y translation — the floor the body lies on
     * @param z world z translation
     * @param yawRadians the heading the body was facing when it fell
     * @param scale uniform scale; must be positive
     * @return a placement lying face-down along the heading
     */
    public static Mat4 fallenPlacement(final float x, final float y, final float z,
        final float yawRadians, final float scale)
    {
        if (!(scale > 0.0f))
        {
            throw new IllegalArgumentException("scale must be positive, got " + scale);
        }
        final float sin = (float) StrictMath.sin(yawRadians);
        final float cos = (float) StrictMath.cos(yawRadians);
        final float lift = y + CHARACTER_MODEL_HEIGHT * scale * 0.5f;
        // R_y(yaw) . R_x(+90), scaled. Determinant is scale^3 — both factors are
        // rotations, so nothing here is a reflection and Scene will accept it.
        return Mat4.ofRowMajor(new float[]
        {
            cos * scale, sin * scale, 0.0f, x,
            0.0f, 0.0f, -scale, lift,
            -sin * scale, cos * scale, 0.0f, z,
            0.0f, 0.0f, 0.0f, 1.0f,
        });
    }

    /**
     * Builds a placement that turns a model upside down without mirroring it.
     *
     * <p>A ceiling is a floor tile facing the other way, and the obvious way to
     * write that — {@code scale(1, -1, 1)} — is <b>wrong in a way that looks
     * right</b>. Negating one axis has determinant {@code -s^3}: it is a
     * reflection, it reverses triangle winding, and
     * {@code SoftwareRenderPort.BACKFACE_CULL_MODE} would then keep exactly the
     * faces it should discard. {@link Scene} refuses such a transform outright
     * rather than let it render inside-out, so this is not a theoretical
     * concern — it is a build failure waiting for anyone who tries it.</p>
     *
     * <p>A <b>half turn about the z axis</b> is the honest way to get the same
     * result: it negates x and y, which is two reflections, so the determinant
     * is {@code (-s)(-s)(s) = s^3} and winding survives. The tile ends up
     * face-down, its texture reads the other way round, and the renderer culls
     * it exactly as it culls the floor.</p>
     *
     * <pre>
     *   [ -s   0   0   x ]
     *   [  0  -s   0   y ]
     *   [  0   0   s   z ]
     *   [  0   0   0   1 ]
     * </pre>
     *
     * @param x world x translation
     * @param y world y translation
     * @param z world z translation
     * @param scale uniform scale; must be positive
     * @return a placement rotated a half turn about z
     */
    public static Mat4 invertedPlacement(final float x, final float y, final float z,
        final float scale)
    {
        if (!(scale > 0.0f))
        {
            throw new IllegalArgumentException("scale must be positive, got " + scale);
        }
        return Mat4.ofRowMajor(new float[]
        {
            -scale, 0.0f, 0.0f, x,
            0.0f, -scale, 0.0f, y,
            0.0f, 0.0f, scale, z,
            0.0f, 0.0f, 0.0f, 1.0f,
        });
    }

    // The world-space centre of one floor tile along one axis. ROOM_TILES is
    // even, so no tile is centred on the origin and the centres land on the
    // half-tile offsets either side of it.
    private static float tileCentre(final int tile)
    {
        return (tile - (ROOM_TILES - 1) * 0.5f) * KIT_WORLD_SCALE;
    }

    // Degrees to radians. StrictMath for consistency with the rest of the
    // placement math, though nothing here feeds simulation state.
    private static float radians(final float degrees)
    {
        return (float) (degrees * StrictMath.PI / DEGREES_PER_HALF_TURN);
    }

    // Which scale constant a model set was placed at, for the log line.
    private static float worldScaleOf(final DemoModels models)
    {
        if (models.isRealArt())
        {
            return KIT_WORLD_SCALE;
        }
        return FALLBACK_WORLD_SCALE;
    }

    /** Returns the immutable scene. Build it once and hand it to the render port. */
    public Scene scene()
    {
        return scene;
    }

    /**
     * The opponents standing in this scene, in entity-id order.
     *
     * <p>Empty when no character art is staged. <b>The local player is not among
     * them and must never be added</b> — {@code Hitscan} treats a ray origin
     * inside a box as a hit at distance zero, so a shooter that appears in its
     * own target set shoots itself on every trigger pull.</p>
     *
     * <p>Returns the live objects, not copies, because they are mutable
     * simulation state that {@link Match} advances — handing out clones would
     * give the caller a set of bots that never moved and never died. The array
     * itself is copied so the roster cannot be swapped.</p>
     *
     * @return the bots this scene placed
     */
    public Bot[] bots()
    {
        return bots.clone();
    }

    /**
     * Builds a match against the bots in this scene.
     *
     * @return a fresh match, already won if no character art is staged
     */
    public Match newMatch()
    {
        return new Match(bots);
    }

    /**
     * Returns the scene instance index of one bot.
     *
     * <p>The handle needed to move a bot's model:
     * {@code SoftwareRenderPort.setWorldTransform} addresses world instances by
     * position, and this is where each body landed while the scene was being
     * assembled.</p>
     *
     * @param botIndex which bot, in the same order as {@link #bots()}
     * @return its index among the scene's world instances
     */
    public int botInstanceIndex(final int botIndex)
    {
        return botInstances[botIndex];
    }

    /**
     * How many bots this scene placed.
     *
     * @return the bot count, zero when no character art is staged
     */
    public int botCount()
    {
        return bots.length;
    }

    /**
     * Returns a controller standing at the demo's spawn point.
     *
     * @return a fresh controller placed on the floor, facing down the room
     */
    public PlayerController spawnController()
    {
        return new PlayerController(spawnX, spawnY, spawnZ, spawnYawRadians, 0.0f);
    }

    /** Returns the spawn feet position, world x. */
    public float spawnX()
    {
        return spawnX;
    }

    /** Returns the spawn feet position, world y — the floor, not the eye. */
    public float spawnY()
    {
        return spawnY;
    }

    /** Returns the spawn feet position, world z. */
    public float spawnZ()
    {
        return spawnZ;
    }

    /** Returns the spawn heading in radians. */
    public float spawnYawRadians()
    {
        return spawnYawRadians;
    }

    /** Returns which model set this scene was assembled from. */
    public DemoModels.Source source()
    {
        return source;
    }

    /** Returns a debug rendering of the scene and its spawn. */
    @Override
    public String toString()
    {
        return "DemoScene{" + scene + ", source=" + source + ", spawn=(" + spawnX + ", "
            + spawnY + ", " + spawnZ + "), yaw=" + spawnYawRadians + "}";
    }
}
