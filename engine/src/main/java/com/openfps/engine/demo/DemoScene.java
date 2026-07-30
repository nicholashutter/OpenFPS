/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import com.openfps.engine.common.Constants;
import com.openfps.engine.gameplay.Bot;
import com.openfps.engine.gameplay.BotPattern;
import com.openfps.engine.gameplay.Match;
import com.openfps.engine.gameplay.PhysicsWorld;
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

    /**
     * The instance index meaning "there is no such instance in this scene".
     *
     * <p>Negative, so it can never collide with a real index — those are
     * positions in the builder's list and start at zero. Returned by
     * {@link #botWeaponInstanceIndex} when no bot weapon model was staged, which
     * is a supported state rather than a failure: the demo's art is gitignored
     * and a fresh clone has none of it.</p>
     */
    public static final int NO_INSTANCE = -1;

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

    /** One over root two, for the diagonal spawn bearings. */
    private static final float DIAGONAL = (float) (1.0 / StrictMath.sqrt(2.0));

    /**
     * Which way round the room each player id spawns, as unit {@code (ux, uz)}
     * bearings from the room's centre. Each player stands at that bearing times
     * the spawn radius and <b>faces the centre</b>.
     *
     * <p><b>This exists because two players who cannot see each other make the
     * networking look broken.</b> A remote body is drawn where its inputs put it,
     * so two players sharing one spawn are each at the other's eye, where the near
     * plane clips the body away entirely — and two players merely standing side by
     * side both face the same way, leaving each outside the other's 60° field of
     * view. Either way a freshly launched match showed both players an empty room,
     * which is precisely the symptom a session that never connected produces.</p>
     *
     * <p><b>Ids 1 and 2 are deliberately opposite each other</b>, at
     * {@code (-r, 0)} and {@code (+r, 0)} facing along x. Those are the two ids a
     * two-peer match uses, so the common case opens with each player looking
     * straight at the other across the room. That is an arrangement chosen for
     * what it demonstrates, and it is the one thing here that is tuned rather than
     * derived.</p>
     *
     * <p>Index 0 is {@code (0, -1)}, which is the canonical spawn facing {@code +z}
     * — so {@link #spawnController()} and every existing single-player run are
     * placed exactly where they always were and no test moves.</p>
     *
     * <p>Every point is one spawn radius from the centre, which is
     * {@link #SPAWN_DEPTH_FRACTION} of the room half-span, so all eight sit well
     * inside the walls with room to turn round.</p>
     *
     * <p><b>Both peers must compute the same point for the same id</b>, which is
     * why this is a compile-time constant and the heading is derived from it with
     * {@link StrictMath} rather than read from anything a process could see
     * differently.</p>
     */
    private static final float[] SPAWN_DIRECTIONS =
    {
        0.0f, -1.0f,
        -1.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        -DIAGONAL, -DIAGONAL,
        DIAGONAL, -DIAGONAL,
        -DIAGONAL, DIAGONAL,
        DIAGONAL, DIAGONAL,
    };

    /** Stride of {@link #SPAWN_DIRECTIONS}: ux and uz. */
    private static final int SPAWN_STRIDE = 2;

    /** Interior half-extent of the generated fallback room, in its own units. */
    private static final float FALLBACK_INTERIOR_HALF_EXTENT = 8.0f;

    /**
     * Wall thickness in Kenney grid units — <b>0.20</b>, so 12.8 world units.
     *
     * <p>Read off the model, not chosen: {@code docs/DEMO_ASSETS.md} § 3 measures
     * {@code wall.ofm} at {@code 0.20 x 1.00 x 1.00}, thickness along x and
     * length along z. It exists as a constant because {@link #addWalls} places
     * the wall's <b>centre</b> on {@link #HALF_ROOM_UNITS} while the collision
     * hull has to stop at the face the player can see, and the difference
     * between those two — 6.4 units — is exactly half of this.</p>
     */
    private static final float WALL_THICKNESS_GRID = 0.20f;

    /**
     * Where the inside face of the perimeter wall is, in world units —
     * <b>313.6</b>, not 320.
     *
     * <p>The wall's <i>centre</i> is on {@link #HALF_ROOM_UNITS};
     * {@link #addWalls} places it there, and half its 12.8-unit thickness
     * projects inward. This is the surface a player can touch and the plane
     * {@link #kitRoomPhysics} stops them against, so it is the number to reason
     * about clearances with. Public because it is what a test asserts a stopping
     * position against, and re-deriving it at the assertion would let the test
     * agree with a broken solver.</p>
     */
    public static final float WALL_INNER_FACE_UNITS =
        HALF_ROOM_UNITS - WALL_THICKNESS_GRID * KIT_WORLD_SCALE * 0.5f;

    /**
     * The doorway's low x edge in world units — <b>0</b>.
     *
     * <p>The doorway replaces one floor tile's worth of the south wall, so the
     * opening is exactly {@link #KIT_WORLD_SCALE} wide and centred on
     * {@link #DOORWAY_TILE}'s centre. That tile happens to straddle the origin's
     * east side, which is why this edge lands on zero rather than somewhere
     * memorable.</p>
     */
    public static final float DOORWAY_MIN_X_UNITS =
        tileCentre(DOORWAY_TILE) - KIT_WORLD_SCALE * 0.5f;

    /** The doorway's high x edge in world units — 64. See {@link #DOORWAY_MIN_X_UNITS}. */
    public static final float DOORWAY_MAX_X_UNITS =
        tileCentre(DOORWAY_TILE) + KIT_WORLD_SCALE * 0.5f;

    /** Column footprint in Kenney grid units — {@code column.ofm} is 0.20 x 1.00 x 0.20. */
    private static final float COLUMN_FOOTPRINT_GRID = 0.20f;

    /** Crate footprint in Kenney grid units — {@code crate.ofm} is 0.50 cubed. */
    private static final float CRATE_FOOTPRINT_GRID = 0.50f;

    /** Stride of {@link #CRATE_PLACEMENTS}: x, y and z. */
    private static final int CRATE_STRIDE = 3;

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
     * Scale from the bots' blaster model to world units — the same number as
     * {@link #CHARACTER_WORLD_SCALE}, and <b>that is a finding rather than a
     * shortcut</b>.
     *
     * <p>The two packs turn out to be authored at very nearly one common metre.
     * A Blocky Character is 2.70 model units tall and stands for a person of
     * about 1.7 m, so one unit is 0.63 m. A Blaster Kit pistol
     * ({@code blaster-b}) is 0.42 units long and is plainly a pistol, so one
     * unit is about 0.6 m there too. Nothing was tuned to make that happen — it
     * is the same corroboration {@link #KIT_WORLD_SCALE} and
     * {@link #CHARACTER_WORLD_SCALE} both turned out to have.</p>
     *
     * <p>So the bots' carbine ({@code blaster-p}, 0.86 units long) lands at
     * <b>17.8 world units</b> against a 56-unit body: a third of the holder's
     * height, which is what a two-handed weapon looks like. Deriving it rather
     * than picking a number matters because the failure mode is not subtle —
     * this is the constant that decides between a rifle and a car.</p>
     *
     * <p><b>Unrelated to {@link #WEAPON_VIEW_SCALE}, which sizes the player's
     * own gun.</b> That one is a viewmodel: it lives in view space, it is sized
     * to look right on a screen, and deriving one from the other would be a
     * coincidence dressed as a rule. See the class Javadoc.</p>
     */
    public static final float BOT_WEAPON_WORLD_SCALE = CHARACTER_WORLD_SCALE;

    /**
     * How far right of a bot's centre line its weapon sits, in world units — 9.
     *
     * <p>A Blocky Character is 1.60 model units across, which is 33 world units,
     * so a shoulder is about 16 units out and a hand held in front of the body
     * is a little inside that. The three offsets below place the weapon's origin
     * rather than its muzzle, which is why they are smaller than a look at the
     * body would suggest — the same distinction {@code DemoEffects} had to make
     * for the muzzle smoke, and got wrong first time.</p>
     */
    public static final float BOT_WEAPON_RIGHT_UNITS = 9.0f;

    /**
     * How far in front of a bot its weapon sits, in world units — 7.
     *
     * <p>The body is 0.80 model units deep, which is 17 world units, so its
     * front face is 8 units ahead of its centre. Seven puts the weapon's origin
     * just inside that, so the model reads as held rather than as floating in
     * front of a chest.</p>
     */
    public static final float BOT_WEAPON_FORWARD_UNITS = 7.0f;

    /**
     * How high above a bot's feet its weapon sits, in world units — 30.
     *
     * <p>Just over half of the 56-unit body, which on these proportions is where
     * a lowered pair of hands is. Not at {@link Bot#EYE_HEIGHT_UNITS}: a weapon
     * level with the eyes reads as aimed down a sight, and these are not
     * aiming — the whole of {@code BotSkill} is about how badly they aim.</p>
     */
    public static final float BOT_WEAPON_HEIGHT_UNITS = 30.0f;

    /**
     * Weapon yaw within a bot's own frame, in degrees — <b>240</b>.
     *
     * <p>Two rotations that compose into one number, so both are written down —
     * the same shape of statement {@link #WEAPON_VIEW_YAW_DEGREES} makes for the
     * player's own gun.</p>
     *
     * <ul>
     *   <li><b>180 degrees</b> because a Blaster Kit muzzle points along model
     *       <b>-z</b>, verified by rendering one, while a placement's yaw of zero
     *       puts model +z along the holder's facing. Without the flip every bot in
     *       the room holds its carbine backwards — which is surprisingly hard to
     *       notice in a still, because the grip end is the chunkier one.</li>
     *   <li><b>60 degrees of carry angle</b>, and <b>this is the number that made
     *       the weapon visible at all.</b> Pointed straight down the bot's facing
     *       it was aimed at the camera, so what the player saw was its
     *       <i>cross-section</i> — 3 units by 8, on a 56-unit body, which reads as
     *       a smudge on a shirt rather than as a gun. It is the identical mistake
     *       {@code DemoEffects.TRACER_WIDTH_UNITS} records for the tracer bolt,
     *       and it was made again here.</li>
     * </ul>
     *
     * <p>Sixty rather than ninety: at ninety the weapon is exactly side-on and
     * shows its full 17.8 units, but a body holding a carbine dead across its
     * chest reads as a guard of honour. At sixty the visible length is
     * {@code sin(60) = 87%} of it — about a third of the body's height across the
     * screen, unmistakably a weapon — while the muzzle still points forward
     * enough to read as carried rather than presented.</p>
     *
     * <p><b>Angled rather than scaled up, which was the other candidate fix.</b>
     * The scale is <i>derived</i> ({@link #BOT_WEAPON_WORLD_SCALE}) from two packs
     * that turn out to agree about a metre, and inflating it to solve a
     * foreshortening problem would have thrown that away and given the bots
     * comically large guns to look at from the side. The weapon was the right
     * size all along and pointing the wrong way.</p>
     *
     * <p>It also happens to suit these opponents: a weapon held across the body is
     * a weapon that is not being aimed, and {@link Bot}'s whole skill model is
     * about how badly they aim. The same reasoning put
     * {@link #BOT_WEAPON_HEIGHT_UNITS} below eye level.</p>
     */
    public static final float BOT_WEAPON_YAW_DEGREES = 240.0f;

    /**
     * How far the muzzle is from the weapon's own origin, in world units —
     * <b>8.94</b>.
     *
     * <p>Derived, and from a measurement rather than a guess: the carbine is
     * centred on its origin and {@link BlockCarbine#HALF_LENGTH} of it —
     * {@code 0.431} model units, read out of {@code blaster-p.ofm}'s header —
     * lies ahead of that origin, so at {@link #BOT_WEAPON_WORLD_SCALE} the barrel
     * tip is {@code 0.431 x 20.74} away along the barrel. The generated
     * stand-in is authored to the same half-length precisely so this number does
     * not have to know which model is standing in.</p>
     *
     * <p><b>This is where incoming fire comes from</b>, and it is a long way from
     * where the simulation fires: {@link Bot#eyeY} is 41 units up at the centre of
     * the body, while the muzzle is at {@link #BOT_WEAPON_HEIGHT_UNITS} and a
     * further nine units out to one side and nearly nine along a barrel that is
     * angled across the chest. Fourteen-odd world units of difference, which at
     * the player's distance is most of a player radius — see
     * {@code BotShotLog.record} for what is done about that, since a bolt that
     * left the gun but ran parallel to the shot would make a hit look like a
     * miss.</p>
     */
    public static final float BOT_WEAPON_MUZZLE_UNITS =
        BlockCarbine.HALF_LENGTH * BOT_WEAPON_WORLD_SCALE;

    /**
     * Which way a bot's barrel points, relative to the bot's own facing, in
     * degrees — <b>60</b>.
     *
     * <p>Not a second decision: it is {@link #BOT_WEAPON_YAW_DEGREES} with the
     * muzzle flip taken back out, which is what is left of that constant once its
     * two halves are separated. The model is placed at {@code yaw + 240} and its
     * muzzle points along model {@code -z}, so the barrel points along
     * {@code yaw + 240 + 180} — and adding a half turn is subtracting one, so that
     * is {@code yaw + 60}. The carry angle alone, which is exactly what it should
     * be: the barrel is 60 degrees off the body's facing because the weapon is
     * held across the chest.</p>
     *
     * <p>Written out as its own constant because it is what a muzzle effect needs,
     * and because deriving it at the call site means writing the 180 down a second
     * time somewhere it can be given the wrong sign.</p>
     */
    public static final float BOT_BARREL_YAW_DEGREES =
        BOT_WEAPON_YAW_DEGREES - DEGREES_PER_HALF_TURN;


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
    private final int[] botWeaponInstances;
    private final DemoEffects effects;

    /** The pre-placed bodies a networked peer is simulated into. Never null. */
    private final RemotePlayers remotePlayers;
    private final float spawnX;
    private final float spawnY;
    private final float spawnZ;
    private final float spawnYawRadians;
    private final DemoModels.Source source;
    private final PhysicsWorld physics;

    // Takes ownership of a finished scene and the spawn that belongs to it.
    private DemoScene(final Scene builtScene, final Bot[] roster, final int[] instanceIndices,
        final int[] weaponIndices, final DemoEffects shotEffects, final float feetX,
        final float feetY, final float feetZ, final float yawRadians,
        final DemoModels.Source modelSource, final PhysicsWorld solidGeometry,
        final RemotePlayers peerBodies)
    {
        this.physics = solidGeometry;
        this.scene = builtScene;
        this.bots = roster;
        this.botInstances = instanceIndices;
        this.botWeaponInstances = weaponIndices;
        this.effects = shotEffects;
        this.remotePlayers = peerBodies;
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
        // The solid geometry is built beside the visible geometry, in the same
        // branch, from the same constants. That adjacency is the point: the two
        // descriptions of the room cannot be given different extents without
        // someone editing both halves of one if-statement.
        final PhysicsWorld solidGeometry;
        if (models.isRealArt())
        {
            addKitRoom(builder, models);
            solidGeometry = kitRoomPhysics();
            spawnDepth = HALF_ROOM_UNITS * SPAWN_DEPTH_FRACTION;
        }
        else
        {
            builder.addWorldInstance(models.room(), placement(0.0f, 0.0f, 0.0f, 0.0f,
                FALLBACK_WORLD_SCALE));
            solidGeometry = fallbackRoomPhysics();
            spawnDepth =
                FALLBACK_INTERIOR_HALF_EXTENT * FALLBACK_WORLD_SCALE * SPAWN_DEPTH_FRACTION;
        }
        final int[] instanceIndices = new int[BOT_ROUTE_CENTRES.length / BOT_STRIDE];
        final int[] weaponIndices = new int[instanceIndices.length];
        final Bot[] roster = addBots(builder, models, instanceIndices, weaponIndices);
        // The other players' bodies, placed whether or not this run is networked.
        // Unconditional on purpose: a Scene is immutable, so a body that is not
        // placed here can never be placed at all, and --net is parsed by the
        // launcher long after this. Seven unused instances cost seven hidden
        // transforms and nothing else.
        final RemotePlayers peers = RemotePlayers.addTo(builder, models, solidGeometry,
            0.0f, 0.0f, -spawnDepth, 0.0f);
        // Unconditionally, and independent of which art is staged: the tracer
        // and the smoke are generated geometry, so they are the one part of the
        // demo that cannot be missing from a fresh clone.
        final DemoEffects shots = DemoEffects.addTo(builder);
        addViewmodel(builder, models.weapon());

        final Scene built = builder.build();
        LOG.info("Demo scene built from {}: {} world instances ({} translucent),"
            + " {} view instances, largest {} triangles, world scale {}", models.source(),
            built.worldInstanceCount(), built.translucentInstanceCount(),
            built.viewInstanceCount(), built.maxInstanceTriangles(), worldScaleOf(models));
        LOG.info("Demo collision: {} — walls, doorway jambs, columns and crates are solid;"
            + " the staircase and the ramp are not", solidGeometry);
        // Facing +z, which is yaw 0 (PlayerController's convention), standing
        // back from the origin so the whole room is ahead rather than around.
        return new DemoScene(built, roster, instanceIndices, weaponIndices, shots, 0.0f, 0.0f,
            -spawnDepth, 0.0f, models.source(), solidGeometry, peers);
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
     * <h2>Each bot also gets a weapon instance, for the same reason</h2>
     *
     * <p>A {@link Scene} is immutable, so a bot's blaster cannot be created when
     * it is needed any more than a bot can. One instance per bot is placed here
     * and moved every tic beside the body — the identical pattern
     * {@link DemoEffects} uses for a tracer, and the identical reason. It is
     * tagged with the <b>bot's own entity id</b> rather than left untagged, so
     * the outline pass draws one silhouette round the body and what it is
     * holding, and so the crosshair does not go dead when it crosses the gun.</p>
     *
     * @param builder the scene under construction; instances are appended
     * @param models the loaded model set
     * @param instanceIndices filled with the scene index of each placed bot;
     *     must be at least as long as the bot roster
     * @param weaponIndices filled with the scene index of each bot's weapon, or
     *     {@link #NO_INSTANCE} where no bot weapon model was staged; must be at
     *     least as long as the bot roster
     * @return one bot per placement, in id order, never null
     */
    private static Bot[] addBots(final Scene.Builder builder, final DemoModels models,
        final int[] instanceIndices, final int[] weaponIndices)
    {
        if (!models.hasCharacters())
        {
            return new Bot[0];
        }

        final ModelFormat[] people = models.characters();
        final ModelFormat blaster = models.botWeapon();
        final int count = BOT_ROUTE_CENTRES.length / BOT_STRIDE;
        final Bot[] roster = new Bot[count];
        for (int index = 0; index < count; index++)
        {
            final float homeX = BOT_ROUTE_CENTRES[index * BOT_STRIDE];
            final float homeZ = BOT_ROUTE_CENTRES[index * BOT_STRIDE + 1];
            final int entityId = Match.FIRST_BOT_ENTITY_ID + index;

            final Bot bot = new Bot(entityId, homeX, 0.0f, homeZ, BOT_PATTERNS[index],
                BOT_AMPLITUDES[index], BOT_PERIODS[index], BOT_PHASES[index]);

            // Cycles through however many models were staged, so a partially
            // staged pack still puts a body at every placement rather than
            // leaving holes in the arrangement. With the full seven it is one
            // distinct person each.
            final ModelFormat model = people[index % people.length];
            instanceIndices[index] = builder.worldInstanceCount();
            builder.addWorldInstance(model, botPlacement(bot), entityId);
            weaponIndices[index] = addBotWeapon(builder, blaster, bot);
            roster[index] = bot;
        }
        LOG.info("Demo bots: {} placed from {} model(s), ids {}..{}, {} world units tall,"
            + " radius {}, armed: {}", count, people.length, Match.FIRST_BOT_ENTITY_ID,
            Match.FIRST_BOT_ENTITY_ID + count - 1, PLAYER_HEIGHT_UNITS, PLAYER_RADIUS_UNITS,
            blaster != null);
        return roster;
    }

    // One bot's carbine. DemoModels.botWeapon() is never null — a payload with no
    // blaster-p.ofm gets BlockCarbine instead, for the reason that class records
    // at length: a bot with nothing in its hands measured as zero pixels of
    // weapon, and incoming fire is specified to come out of a muzzle.
    //
    // The null guard stays because it is the cheap half of a contract: the
    // sentinel is still what publishBotPlacements skips on, and one dead branch
    // costs a comparison against a scene silently missing seven instances.
    private static int addBotWeapon(final Scene.Builder builder, final ModelFormat blaster,
        final Bot bot)
    {
        if (blaster == null)
        {
            return NO_INSTANCE;
        }
        final int at = builder.worldInstanceCount();
        builder.addWorldInstance(blaster, botWeaponPlacement(bot), bot.entityId());
        return at;
    }

    /**
     * Returns the placement transform for a bot at its current position.
     *
     * <p>Public because it is called every tic from outside — the gameplay port
     * hands the result to {@code SoftwareRenderPort.setWorldTransform} so a
     * bot's model follows its patrol. It is the one piece of the scene that is
     * <b>not</b> built once.</p>
     *
     * <h2>A dead bot is hidden, not removed and no longer toppled</h2>
     *
     * <p><b>Not removed</b>, because removing an instance means rebuilding the
     * whole {@link Scene} — texture table, stream offsets, entity ids, buffer
     * sizing — for a set of instances that has not otherwise changed. The scene
     * is immutable and that is load-bearing; see
     * {@code SoftwareRenderPort.setWorldTransform}.</p>
     *
     * <p><b>Not toppled either, which it used to be.</b> Laying the body flat
     * was one transform and read instantly, and it was still a body: it stayed
     * in the room, it kept writing entity ids, so the crosshair went red over a
     * corpse and the wireframe marked it as the thing you were aiming at, while
     * {@code Match} had already stopped treating it as a target. A mark that
     * says "you are pointing at this one" pointing at something you cannot
     * shoot is worse than no mark. The body now goes away, which is what
     * "killed" means everywhere else in the genre.</p>
     *
     * <p>{@link DemoEffects#HIDDEN} is the established way to hide a
     * pre-allocated instance here, and it is referenced rather than copied: a
     * degenerate transform collapses every vertex onto one point, so every
     * triangle has zero screen area and {@code Rasterizer} rejects it before it
     * reaches a pixel — no colour, no depth, and <b>no entity id</b>, which is
     * the part that matters for the crosshair. It costs one reference store and
     * changes nothing about the pass structure.</p>
     *
     * @param bot the bot to place; must not be null
     * @return its model-to-world transform this tic, or {@link DemoEffects#HIDDEN}
     *     if it is dead
     */
    public static Mat4 botPlacement(final Bot bot)
    {
        if (!bot.isAlive())
        {
            return DemoEffects.HIDDEN;
        }
        return placement(bot.positionX(), bot.positionY(), bot.positionZ(),
            bot.yawRadians(), CHARACTER_WORLD_SCALE);
    }

    /**
     * Returns the placement transform for one bot's weapon this tic.
     *
     * <p>Public and called every tic from outside, exactly as
     * {@link #botPlacement} is, and it has to be: a held weapon that did not
     * move with its holder would be a blaster hanging in the air where a bot
     * used to be.</p>
     *
     * <p><b>Hidden when the bot is dead, on the same tic and by the same
     * mechanism as the body.</b> That is not a detail — a corpse's weapon left
     * floating over the spot where it fell is the most conspicuous thing in the
     * room, and it is what makes "the body goes away" look like a rendering
     * fault rather than a kill. The reset brings it back with everything
     * else.</p>
     *
     * <h2>The transform, since it is not one of {@link #placement}'s</h2>
     *
     * <p><b>Two angles that are deliberately different, which is why this is
     * written out rather than composed from {@link #placement} alone.</b> The
     * <i>offset</i> is a position in the bot's own frame, so it is rotated by the
     * bot's yaw using {@code PlayerController}'s basis —
     * {@code groundForward = (sin, 0, cos)} and
     * {@code groundRight = groundForward x up = (-cos, 0, sin)}. The <i>model</i>
     * is turned by the bot's yaw plus {@link #BOT_WEAPON_YAW_DEGREES}, which is
     * the muzzle flip and the carry angle together. Using one angle for both would
     * put the gun in the correct hand pointing backwards, or in the wrong hand
     * pointing forwards, depending on which one was picked.</p>
     *
     * @param bot the bot holding it; must not be null
     * @return its model-to-world transform this tic, or {@link DemoEffects#HIDDEN}
     *     if it is dead
     */
    public static Mat4 botWeaponPlacement(final Bot bot)
    {
        if (!bot.isAlive())
        {
            return DemoEffects.HIDDEN;
        }
        return heldWeaponPlacement(bot.positionX(), bot.positionY(), bot.positionZ(),
            bot.yawRadians());
    }

    /**
     * Returns the placement transform for a carbine held by whoever is standing
     * at a given placement.
     *
     * <p><b>Extracted from {@link #botWeaponPlacement} so there is exactly one
     * definition of "where a held weapon is".</b> A remote peer's body holds the
     * same carbine as a bot and has to hold it the same way; the arithmetic is
     * the two-different-angles calculation described on
     * {@link #botWeaponPlacement}, and duplicating it is how the two would come
     * to disagree — one of them putting the gun in the wrong hand, or pointing it
     * backwards, with nothing to say which was correct.</p>
     *
     * <p>Takes loose fields rather than a {@link Bot} precisely because a peer is
     * not one: it is a {@link PlayerController}, which has the same position and
     * yaw and none of the same type.</p>
     *
     * @param feetX the holder's position, world x
     * @param feetY the holder's position, world y — the floor, not the eye
     * @param feetZ the holder's position, world z
     * @param yawRadians the holder's heading, in {@code PlayerController}'s
     *     convention
     * @return the weapon's model-to-world transform, never null
     */
    public static Mat4 heldWeaponPlacement(final float feetX, final float feetY,
        final float feetZ, final float yawRadians)
    {
        final float sinYaw = (float) StrictMath.sin(yawRadians);
        final float cosYaw = (float) StrictMath.cos(yawRadians);
        final float x = feetX
            + BOT_WEAPON_FORWARD_UNITS * sinYaw - BOT_WEAPON_RIGHT_UNITS * cosYaw;
        final float z = feetZ
            + BOT_WEAPON_FORWARD_UNITS * cosYaw + BOT_WEAPON_RIGHT_UNITS * sinYaw;
        return placement(x, feetY + BOT_WEAPON_HEIGHT_UNITS, z,
            yawRadians + radians(BOT_WEAPON_YAW_DEGREES), BOT_WEAPON_WORLD_SCALE);
    }

    /**
     * Writes the world position of the end of a bot's barrel.
     *
     * <h2>Why this is not {@code botWeaponPlacement}'s translation</h2>
     *
     * <p>That is where the weapon's <b>origin</b> goes, and a carbine's origin is
     * in the middle of it — the same distinction {@code DemoEffects} had to make
     * for the player's own muzzle smoke, and got wrong first time by putting the
     * puff over the middle of the gun. The muzzle is a further
     * {@link #BOT_WEAPON_MUZZLE_UNITS} along a barrel that runs
     * {@link #BOT_BARREL_YAW_DEGREES} off the body's facing, and a bolt or a puff
     * of smoke that appeared at the origin instead would come out of the
     * receiver.</p>
     *
     * <p><b>Fills an array rather than returning three values</b>, because it is
     * called on the tic path — once per bot that fired — and three floats cannot
     * be returned from a Java method without allocating something to hold them.
     * {@code DemoGameplayPort} keeps one scratch array for the purpose, which is
     * the pattern {@code DemoEffects.acrossScratch} already uses and for the same
     * reason.</p>
     *
     * @param bot the bot whose barrel to locate; must not be null
     * @param out filled with the muzzle's world x, y and z; must hold at least
     *     three floats
     */
    public static void botMuzzle(final Bot bot, final float[] out)
    {
        final float yaw = bot.yawRadians();
        final float sinYaw = (float) StrictMath.sin(yaw);
        final float cosYaw = (float) StrictMath.cos(yaw);
        // PlayerController's basis, the same one botWeaponPlacement uses to put
        // the weapon's origin in the bot's hand: groundForward = (sin, 0, cos) and
        // groundRight = groundForward x up = (-cos, 0, sin).
        final float originX = bot.positionX()
            + BOT_WEAPON_FORWARD_UNITS * sinYaw - BOT_WEAPON_RIGHT_UNITS * cosYaw;
        final float originZ = bot.positionZ()
            + BOT_WEAPON_FORWARD_UNITS * cosYaw + BOT_WEAPON_RIGHT_UNITS * sinYaw;

        final float barrel = yaw + radians(BOT_BARREL_YAW_DEGREES);
        out[0] = originX + BOT_WEAPON_MUZZLE_UNITS * (float) StrictMath.sin(barrel);
        // Level, because the weapon is: placement() is a yaw and nothing else, so
        // the barrel has no elevation to follow and the muzzle sits at exactly the
        // height the weapon's origin does.
        out[1] = bot.positionY() + BOT_WEAPON_HEIGHT_UNITS;
        out[2] = originZ + BOT_WEAPON_MUZZLE_UNITS * (float) StrictMath.cos(barrel);
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

        for (int crate = 0; crate < CRATE_PLACEMENTS.length; crate += CRATE_STRIDE)
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

    /**
     * Builds the solid geometry of the kit room — the same room
     * {@link #addKitRoom} draws, expressed as boxes a player cannot enter.
     *
     * <h2>Why this lives here and not in {@code PhysicsWorld}</h2>
     *
     * <p>Because this class is the only thing that knows where the room is.
     * Every number below is read from a constant that also places a model:
     * {@link #HALF_ROOM_UNITS} positions the wall instances,
     * {@link #WALL_THICKNESS_GRID} is the wall model's own measured extent,
     * {@link #DOORWAY_TILE} picks which south tile is a hole. Restating any of
     * them inside the physics layer would let the room you can see and the room
     * you can walk in drift apart, and a wall that is solid four units from
     * where it is drawn is the sort of defect that survives for months because
     * every screenshot of it looks correct.</p>
     *
     * <h2>What is solid, and what is deliberately not</h2>
     *
     * <p><b>Solid:</b> all four perimeter walls, the two jambs of the doorway,
     * the four columns and the seven crates.</p>
     *
     * <p><b>The doorway stays open.</b> It is a 64-unit gap in the bottom course
     * of the south wall, so the wall is emitted as two slabs with a hole between
     * them rather than as one. A 32-unit-wide player passes with 16 units of
     * clearance either side, which is the clearance
     * {@link #KIT_WORLD_SCALE}'s Javadoc claimed as a reason for the scale in
     * the first place — this is the first code that actually depends on it being
     * true. Sealing the room would have been one fewer box and a demo that lies
     * about the one piece of level geometry it went to the trouble of
     * modelling.</p>
     *
     * <p><b>Not solid: the staircase and the ramp.</b> Both would have to be
     * full-height boxes, because {@link PhysicsWorld} blocks at every height,
     * and a full-height box around a staircase is an invisible wall in front of
     * something the player can plainly see they should be able to walk up. The
     * honest failure is the one that was already there — you walk through it —
     * rather than a new one that reads as a bug. They become solid the day
     * floor-height queries exist, and not before. The staircase sits at
     * {@code z = 224} on the way to the doorway, so making it a wall would also
     * have blocked the exit.</p>
     *
     * <p><b>Not solid: the bots.</b> They are target practice on fixed routes;
     * bodies that shoved the player would change what {@code Match} means and is
     * a gameplay decision, not a collision one.</p>
     *
     * <h2>Where the walls actually stop</h2>
     *
     * <p>{@link #addWalls} places each wall's <b>centre</b> on
     * {@code +-}{@link #HALF_ROOM_UNITS}, and the model is 12.8 units thick, so
     * the surface the player can see is 6.4 units inside that at +-313.6. The
     * hull uses the surface, not the centre line: a player stopped on the centre
     * line would have a third of their body buried in the wall they are looking
     * at. With the 16-unit half-width that puts the furthest a player may stand
     * at <b>+-297.6</b>, and their shoulder exactly against the plaster.</p>
     *
     * <p>Each wall is <b>one slab spanning the full side</b>, corners included,
     * rather than the ten tile-sized boxes the renderer draws. That is not just
     * cheaper, it is more correct: a row of abutting boxes has nine internal
     * edges, and a player sliding along it who drifts a single ulp inside the
     * surface catches on every one of them. One box has no internal edges, so
     * sliding along a wall is smooth by construction rather than by tolerance.
     * The four slabs overlap in the corner squares, which costs nothing — a
     * point inside two solids is as blocked as a point inside one.</p>
     *
     * @return the room's solid geometry, sized for a player of
     *     {@link PhysicsWorld#PLAYER_HALF_WIDTH_UNITS}
     */
    public static PhysicsWorld kitRoomPhysics()
    {
        final PhysicsWorld.Builder solid =
            PhysicsWorld.builder(PhysicsWorld.PLAYER_HALF_WIDTH_UNITS);
        final float inner = WALL_INNER_FACE_UNITS;
        final float outer = HALF_ROOM_UNITS + WALL_THICKNESS_GRID * KIT_WORLD_SCALE * 0.5f;

        // The two walls at constant x, each spanning the whole side.
        solid.addBox(-outer, -outer, -inner, outer);
        solid.addBox(inner, -outer, outer, outer);
        // The wall at -z, unbroken.
        solid.addBox(-outer, -outer, outer, -inner);
        // The wall at +z, in two pieces with the doorway between them. The gap
        // is one floor tile wide and centred on the tile the doorway model
        // replaced, so it is the opening you can see rather than an
        // approximation of it.
        solid.addBox(-outer, inner, DOORWAY_MIN_X_UNITS, outer);
        solid.addBox(DOORWAY_MAX_X_UNITS, inner, outer, outer);

        final float columnOffset = HALF_ROOM_UNITS * COLUMN_FRACTION;
        final float columnHalf = COLUMN_FOOTPRINT_GRID * KIT_WORLD_SCALE * 0.5f;
        solid.addBoxAt(-columnOffset, -columnOffset, columnHalf, columnHalf);
        solid.addBoxAt(columnOffset, -columnOffset, columnHalf, columnHalf);
        solid.addBoxAt(-columnOffset, columnOffset, columnHalf, columnHalf);
        solid.addBoxAt(columnOffset, columnOffset, columnHalf, columnHalf);

        final float crateHalf = CRATE_FOOTPRINT_GRID * KIT_WORLD_SCALE * 0.5f;
        for (int crate = 0; crate < CRATE_PLACEMENTS.length; crate += CRATE_STRIDE)
        {
            // Only the crates resting on the floor. The stacked one sits at
            // y = 32 directly above another and would add a box identical to the
            // one already there — solids block at every height, so a stack of
            // two is one obstacle, not two.
            if (CRATE_PLACEMENTS[crate + 1] != 0.0f)
            {
                continue;
            }
            solid.addBoxAt(CRATE_PLACEMENTS[crate], CRATE_PLACEMENTS[crate + 2],
                crateHalf, crateHalf);
        }
        return solid.build();
    }

    /**
     * Builds the solid geometry of the generated fallback room — four walls and
     * no way out.
     *
     * <p>{@code ProceduralRoom} authors a sealed 16x16 box with no doorway in
     * it, so this one really is a closed room and the player really is meant to
     * stay in it. Its interior half-extent is the room's own
     * {@code FALLBACK_INTERIOR_HALF_EXTENT} at {@link #FALLBACK_WORLD_SCALE},
     * which is the surface the player stops against; the slabs are given the
     * same thickness the kit walls have purely so the boxes are well formed,
     * since nothing can reach their far side.</p>
     *
     * <p>This is the room a fresh clone with no art staged gets, and it is worth
     * it being solid: it is the only room some contributors will ever see.</p>
     *
     * @return the fallback room's solid geometry
     */
    public static PhysicsWorld fallbackRoomPhysics()
    {
        final PhysicsWorld.Builder solid =
            PhysicsWorld.builder(PhysicsWorld.PLAYER_HALF_WIDTH_UNITS);
        final float inner = FALLBACK_INTERIOR_HALF_EXTENT * FALLBACK_WORLD_SCALE;
        final float outer = inner + WALL_THICKNESS_GRID * KIT_WORLD_SCALE;
        solid.addBox(-outer, -outer, -inner, outer);
        solid.addBox(inner, -outer, outer, outer);
        solid.addBox(-outer, -outer, outer, -inner);
        solid.addBox(-outer, inner, outer, outer);
        return solid.build();
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
     * Returns the scene instance index of one bot's weapon.
     *
     * <p>The same handle as {@link #botInstanceIndex}, for the blaster the bot is
     * holding. A held weapon has to be moved every tic beside the body, so the
     * gameplay port needs both indices for every bot.</p>
     *
     * @param botIndex which bot, in the same order as {@link #bots()}
     * @return its weapon's index among the scene's world instances, or
     *     {@link #NO_INSTANCE} when no bot weapon model was staged
     */
    public int botWeaponInstanceIndex(final int botIndex)
    {
        return botWeaponInstances[botIndex];
    }

    /**
     * Returns whether the bots in this scene are holding anything.
     *
     * <p>True whenever any bot was placed at all, because
     * {@link DemoModels#botWeapon()} no longer has an absent case — see
     * {@link BlockCarbine}. False only for a scene with no character art, which
     * has no hands to put a weapon in. Exposed so a test can say which of the two
     * it is looking at rather than inferring it from an instance index it would
     * then have to interpret.</p>
     *
     * @return true when a carbine was placed in every bot's hands
     */
    public boolean hasBotWeapons()
    {
        return botWeaponInstances.length > 0 && botWeaponInstances[0] != NO_INSTANCE;
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
     * Returns the pool of pre-placed tracer and smoke instances.
     *
     * <p>Never null: the effect geometry is generated rather than loaded, so
     * unlike every other model in this scene it cannot fail to be staged. Hand
     * it to {@code DemoGameplayPort}, which is what actually fires it.</p>
     *
     * @return the effect pool this scene placed
     */
    public DemoEffects effects()
    {
        return effects;
    }

    /**
     * Returns the pool of pre-placed bodies a networked peer is simulated into.
     *
     * <p>Never null, and placed whether or not this run is networked — a
     * {@link Scene} is immutable, so the bodies have to exist before the launcher
     * has parsed {@code --net}. An un-networked run simply never makes one
     * visible. Hand it to {@code DemoGameplayPort}, which drives it from the
     * session's ring.</p>
     *
     * @return the peer body pool this scene placed, never null
     */
    public RemotePlayers remotePlayers()
    {
        return remotePlayers;
    }

    /**
     * Returns a controller standing at the demo's spawn point, already bound to
     * the room's solid geometry.
     *
     * <p>Bound here rather than by the launcher on purpose. This is the only
     * method that produces a player for this room, so a player who can walk
     * through its walls is not constructible — which is the state the demo was
     * in until now, and it was in it because the collision had nowhere obvious
     * to be attached.</p>
     *
     * @return a fresh controller placed on the floor, facing down the room, that
     *     collides with it
     */
    public PlayerController spawnController()
    {
        return spawnControllerFor(0);
    }

    /**
     * Returns a controller standing at the spawn belonging to one player id.
     *
     * <p>Each player id gets its own point, because two players standing on one
     * spawn cannot see each other at all — a body at the observer's own eye is at
     * zero distance and the near plane clips it away. See
     * {@link #SPAWN_OFFSETS}.</p>
     *
     * <p>Id 0 is the canonical spawn, so a single-player run is placed exactly
     * where it always was.</p>
     *
     * @param playerId the player's network id; taken modulo the number of spawn
     *     points, so an id outside the table shares a point rather than throwing
     * @return a fresh controller on that spawn, colliding with the room
     */
    public PlayerController spawnControllerFor(final int playerId)
    {
        return new PlayerController(spawnXFor(playerId), spawnY, spawnZFor(playerId),
            spawnYawFor(playerId), 0.0f, physics);
    }

    /**
     * Returns how far every spawn point sits from the room's centre.
     *
     * <p>The canonical spawn's own distance, so the ring of spawn points passes
     * through it and index 0 is exactly where the player has always started.</p>
     *
     * @return the spawn radius in world units
     */
    public float spawnRadius()
    {
        return StrictMath.abs(spawnZ);
    }

    /**
     * Returns the world x of the spawn belonging to one player id.
     *
     * @param playerId the player's network id
     * @return that player's spawn x
     */
    public float spawnXFor(final int playerId)
    {
        return spawnXFor(playerId, spawnRadius());
    }

    /**
     * Returns the world z of the spawn belonging to one player id.
     *
     * @param playerId the player's network id
     * @return that player's spawn z
     */
    public float spawnZFor(final int playerId)
    {
        return spawnZFor(playerId, spawnRadius());
    }

    /**
     * Returns the world x of a player id's spawn on a ring of a given radius.
     *
     * <p>Static because {@link RemotePlayers} has to place a peer's body on that
     * peer's own spawn the moment its first input arrives, and it holds the spawn
     * geometry rather than the scene. Keeping one table is what stops the two from
     * disagreeing — a peer placed on a different spawn from the one it is actually
     * standing on would be visibly beside itself for the whole match.</p>
     *
     * @param playerId the player's network id
     * @param radius how far the spawn ring sits from the room's centre
     * @return that player's spawn x
     */
    public static float spawnXFor(final int playerId, final float radius)
    {
        return SPAWN_DIRECTIONS[spawnSlot(playerId) * SPAWN_STRIDE] * radius;
    }

    /**
     * Returns the world z of a player id's spawn on a ring of a given radius.
     *
     * @param playerId the player's network id
     * @param radius how far the spawn ring sits from the room's centre
     * @return that player's spawn z
     */
    public static float spawnZFor(final int playerId, final float radius)
    {
        return SPAWN_DIRECTIONS[spawnSlot(playerId) * SPAWN_STRIDE + 1] * radius;
    }

    /**
     * Returns the heading a player id's spawn faces — always the room's centre.
     *
     * <p>Derived from the bearing rather than tabulated beside it, so the two
     * cannot drift into disagreeing and leave a player standing at the edge of the
     * room looking at a wall. {@link StrictMath#atan2} because both peers must
     * compute the same float: a spawn heading feeds straight into the movement
     * basis, so a last-bit difference here is a divergence from the first tic.</p>
     *
     * <p>The arguments are negated because the bearing points <i>outward</i> from
     * the centre and the player looks back along it, and they are in
     * {@code (x, z)} order because this engine's yaw sweeps from world {@code +z}
     * toward world {@code +x} — see {@code PlayerController}.</p>
     *
     * @param playerId the player's network id
     * @return that player's spawn yaw in radians
     */
    public static float spawnYawFor(final int playerId)
    {
        final int slot = spawnSlot(playerId);
        return (float) StrictMath.atan2(-SPAWN_DIRECTIONS[slot * SPAWN_STRIDE],
            -SPAWN_DIRECTIONS[slot * SPAWN_STRIDE + 1]);
    }

    /**
     * How many distinct spawn points the room offers.
     *
     * @return the spawn point count, which is {@code Constants.MAX_PLAYERS}
     */
    public static int spawnPointCount()
    {
        return SPAWN_DIRECTIONS.length / SPAWN_STRIDE;
    }

    // Folds any player id onto a spawn point. Negative ids are folded too rather
    // than throwing: an id is a network identity that arrives from outside, and a
    // hostile one should share a spawn, not crash the room.
    private static int spawnSlot(final int playerId)
    {
        final int count = spawnPointCount();
        final int slot = playerId % count;
        if (slot < 0)
        {
            return slot + count;
        }
        return slot;
    }

    /**
     * Returns the room's solid geometry.
     *
     * <p>Immutable and shared. Whatever else comes to move inside this room —
     * bots, projectiles, a remote peer's body — should clip against this one
     * instance rather than build a second description of the same walls.</p>
     *
     * @return the collision world this scene's players move in, never null
     */
    public PhysicsWorld physics()
    {
        return physics;
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
