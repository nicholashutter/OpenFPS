/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.file.Path;

import com.openfps.engine.common.Constants;
import com.openfps.engine.gameplay.Bot;
import com.openfps.engine.gameplay.HitResult;
import com.openfps.engine.gameplay.Hitscan;
import com.openfps.engine.gameplay.Match;
import com.openfps.engine.gameplay.MatchState;
import com.openfps.engine.gameplay.PlayerController;
import com.openfps.engine.gameplay.Target;
import com.openfps.engine.render.adapter.Mat4;
import com.openfps.engine.render.adapter.Scene;
import com.openfps.engine.render.adapter.Vec3;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the demo world: the scale decision, the placement algebra, and the
 * viewmodel.
 *
 * <p>Two of these are guarding against defects that are <b>invisible in a
 * screenshot until they are catastrophic</b>, which is why they are asserted
 * numerically rather than looked at:</p>
 *
 * <ul>
 *   <li><b>The near plane.</b> A viewmodel that pokes through the near plane
 *       loses its stock, and it does so gradually as the field of view or the
 *       weapon scale changes — so it is asserted against every corner of the
 *       blaster's documented bounding box.</li>
 *   <li><b>The determinant.</b> A mirrored transform renders inside-out and
 *       looks like a plausible model. {@link Scene} rejects one, so every
 *       transform this class produces has to be orientation-preserving by
 *       construction, not by luck.</li>
 * </ul>
 */
@DisplayName("DemoScene")
final class DemoSceneTest
{
    /** Floating-point slack for placement algebra, in world units. */
    private static final float EPSILON = 1.0e-4f;

    /**
     * The player's collision radius in <b>world units</b> — 16.
     *
     * <p>{@link Constants#PLAYER_RADIUS} is 16.16 fixed-point
     * ({@code PLAN.md} § 4), so it holds 16 * 65536 and comparing it directly
     * against a world-unit length is off by four orders of magnitude. Converted
     * here once rather than inline, because getting it wrong made two of these
     * tests pass for the wrong reason.</p>
     */
    private static final float PLAYER_RADIUS_UNITS =
        (float) Constants.PLAYER_RADIUS / (float) Constants.MAP_SCALE;

    /**
     * The blaster's model-space bounding box, from {@code docs/DEMO_ASSETS.md}
     * § 3 and confirmed by reading the converted model.
     *
     * <p>Written as literals on purpose. Reading them from the asset would make
     * this test pass on any machine with no assets, which is precisely when the
     * near-plane guarantee most needs stating.</p>
     */
    private static final float[] BLASTER_BOUNDS = {-0.078f, 0.078f, -0.197f, 0.113f,
        -0.260f, 0.160f};

    /** Builds a full kit model set in a temporary directory. */
    private static DemoModels kit(final Path root) throws IOException
    {
        for (final String piece : new String[] {"floor-square.ofm", "wall.ofm",
            "wall-doorway.ofm", "column.ofm", "crate.ofm", "stairs.ofm", "shape-slope.ofm"})
        {
            DemoModelFixture.write(root.resolve(DemoModels.LEVEL_DIRECTORY).resolve(piece));
        }
        DemoModelFixture.write(root.resolve(DemoModels.WEAPON_DIRECTORY)
            .resolve(DemoModels.WEAPON_MODEL));
        return DemoModels.load(root);
    }

    /**
     * Enough tics to sweep every bot through a full circuit of its route.
     *
     * <p>The longest period in {@code DemoScene} is 480 tics; this is comfortably
     * past it, so a route that only leaves the room near the end of its cycle
     * still gets caught.</p>
     */
    private static final int LONGEST_ROUTE_TICS = 600;

    /** Every bot's hitbox where it currently stands, for a Hitscan sweep. */
    private static Target[] hitboxesOf(final DemoScene demo)
    {
        final Bot[] roster = demo.bots();
        final Target[] boxes = new Target[roster.length];
        for (int index = 0; index < roster.length; index++)
        {
            boxes[index] = roster[index].hitbox();
        }
        return boxes;
    }

    // The compass bearing of a bot as seen from the spawn point, in radians.
    // Two bots on nearly the same bearing overlap in screen space.
    private static float bearingFromSpawn(final DemoScene demo, final Bot bot)
    {
        return (float) StrictMath.atan2(bot.positionX() - demo.spawnX(),
            bot.positionZ() - demo.spawnZ());
    }

    // How wide a bot appears from the spawn, in radians, measured from its
    // centre to its edge. A body is two radii across, so this is the small-angle
    // ratio of one radius to the viewing distance.
    private static float angularHalfWidth(final DemoScene demo, final Bot bot)
    {
        final float toX = bot.positionX() - demo.spawnX();
        final float toZ = bot.positionZ() - demo.spawnZ();
        final float distance = (float) StrictMath.sqrt(toX * toX + toZ * toZ);
        return DemoScene.PLAYER_RADIUS_UNITS / distance;
    }

    // The determinant of a transform's upper-left 3x3 — negative means a mirror,
    // which Scene refuses.
    private static float determinantOf(final Mat4 matrix)
    {
        return matrix.get(0, 0)
                * (matrix.get(1, 1) * matrix.get(2, 2) - matrix.get(1, 2) * matrix.get(2, 1))
            - matrix.get(0, 1)
                * (matrix.get(1, 0) * matrix.get(2, 2) - matrix.get(1, 2) * matrix.get(2, 0))
            + matrix.get(0, 2)
                * (matrix.get(1, 0) * matrix.get(2, 1) - matrix.get(1, 1) * matrix.get(2, 0));
    }

    /** Builds a full kit plus the seven character models, so bots are placed. */
    private static DemoModels kitWithCharacters(final Path root) throws IOException
    {
        for (final String person : new String[] {"character-a.ofm", "character-d.ofm",
            "character-h.ofm", "character-k.ofm", "character-n.ofm", "character-q.ofm",
            "character-r.ofm"})
        {
            DemoModelFixture.write(root.resolve(DemoModels.CHARACTER_DIRECTORY)
                .resolve(person));
        }
        return kit(root);
    }

    @Nested
    @DisplayName("shootable targets")
    final class Targets
    {
        @Test
        @DisplayName("the model scale and the hitbox height cannot drift apart")
        void shouldScaleTheModelToTheHitbox()
        {
            // CHARACTER_WORLD_SCALE sizes what you SEE; Constants.PLAYER_HEIGHT
            // sizes what you HIT. They are consumed in different files by
            // different subsystems, and a disagreement between them is
            // invisible — shots pass through a visible chest, or connect beside
            // a shoulder, and no screenshot shows either. This is the same
            // guard, and for the same reason, as the KIT_WORLD_SCALE ==
            // MAX_OPEN_HEIGHT assertion below.
            assertThat(DemoScene.CHARACTER_WORLD_SCALE * DemoScene.CHARACTER_MODEL_HEIGHT)
                .as("a character model scaled into the world stands exactly one player tall")
                .isCloseTo(DemoScene.PLAYER_HEIGHT_UNITS, within(EPSILON));

            // And that the world-unit conversions are the fixed-point
            // constants, not numbers that happen to look like them.
            assertThat(DemoScene.PLAYER_HEIGHT_UNITS)
                .isCloseTo((float) Constants.PLAYER_HEIGHT / (float) Constants.MAP_SCALE,
                    within(EPSILON));
            assertThat(DemoScene.PLAYER_RADIUS_UNITS)
                .isCloseTo(PLAYER_RADIUS_UNITS, within(EPSILON));
        }

        @Test
        @DisplayName("the player box keeps DOOM's proportions, which the room was built for")
        void shouldKeepTheInheritedPlayerProportions()
        {
            // 16 radius, 56 tall, 41 to the eye are DOOM's numbers, and the
            // whole room is scaled around them (see KIT_WORLD_SCALE). Pin the
            // relationship rather than the literals: the eye must be inside the
            // body, and the body must fit through a 64-unit doorway.
            assertThat(PlayerController.EYE_HEIGHT_UNITS)
                .as("the eye is inside the head, not above it")
                .isLessThan(DemoScene.PLAYER_HEIGHT_UNITS);
            assertThat(DemoScene.PLAYER_RADIUS_UNITS * 2.0f)
                .as("a player fits through a one-cell doorway with clearance")
                .isLessThan(DemoScene.KIT_WORLD_SCALE);
        }

        @Test
        @DisplayName("what the crosshair covers is what the shot hits")
        void shouldHitTheTargetTheSpawnIsAimedAt(@TempDir final Path root) throws IOException
        {
            // The guarantee this whole feature rests on, and the one no
            // screenshot can establish: the body you SEE under the reticle and
            // the box the simulation TESTS are the same object. They are built
            // from two different things — a scaled model transform and an
            // axis-aligned box — so nothing but an assertion keeps them
            // together. If they drift you see a target, shoot it, and nothing
            // happens.
            final DemoScene demo = DemoScene.build(kitWithCharacters(root));
            final PlayerController player = demo.spawnController();
            final Vec3 eye = player.eyePosition();
            final Vec3 aim = player.forwardVector();

            final Target[] boxes = hitboxesOf(demo);
            final HitResult result = new HitResult();
            final boolean connected = Hitscan.fire(eye.x(), eye.y(), eye.z(),
                aim.x(), aim.y(), aim.z(), boxes, boxes.length, result);

            assertThat(connected)
                .as("the spawn faces +z down the room, and a bot is placed on that bearing")
                .isTrue();
            assertThat(result.entityId())
                .as("the nearest bot on the spawn bearing is the first one placed")
                .isEqualTo(Match.FIRST_BOT_ENTITY_ID);
            assertThat(result.distance())
                .as("hit somewhere in front of the shooter, not behind or at zero")
                .isGreaterThan(0.0f);
        }

        @Test
        @DisplayName("the shooter is never among its own targets")
        void shouldNotPlaceATargetOnTheSpawn(@TempDir final Path root) throws IOException
        {
            // Hitscan treats a ray origin inside a box as a hit at distance
            // zero — a deliberate decision, since standing inside someone is
            // reachable with no inter-player collision. The consequence is that
            // a shooter listed among its own targets shoots itself every tic,
            // so the spawn must be clear of every box.
            //
            // Bots MOVE, so it is not enough that the spawn is clear at tic 0.
            // A full circuit of the longest route is swept below.
            final DemoScene demo = DemoScene.build(kitWithCharacters(root));
            for (int tic = 0; tic <= LONGEST_ROUTE_TICS; tic++)
            {
                for (final Bot bot : demo.bots())
                {
                    bot.moveTo(tic);
                    final Target box = bot.hitbox();
                    final boolean spawnInside = demo.spawnX() >= box.minX()
                        && demo.spawnX() <= box.maxX()
                        && demo.spawnZ() >= box.minZ()
                        && demo.spawnZ() <= box.maxZ();
                    assertThat(spawnInside)
                        .as("bot %d walked over the spawn point at tic %d", box.entityId(), tic)
                        .isFalse();
                }
            }
        }

        @Test
        @DisplayName("every placed bot is tagged, and the room around it is not")
        void shouldTagOnlyTheCharacters(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = DemoScene.build(kitWithCharacters(root));
            final Scene scene = demo.scene();

            int tagged = 0;
            for (int index = 0; index < scene.worldInstanceCount(); index++)
            {
                if (scene.worldEntityId(index) != Scene.UNTAGGED)
                {
                    tagged++;
                }
            }
            assertThat(tagged)
                .as("one tagged instance per bot — the outline and the shot agree on count")
                .isEqualTo(demo.botCount());
        }

        @Test
        @DisplayName("no character art means no opponents and nothing to outline")
        void shouldPlaceNoTargetsWithoutCharacterArt(@TempDir final Path root)
            throws IOException
        {
            // The staged-art question is independent of the level, exactly as
            // the weapon's is. A demo with no people is still a demo.
            final DemoScene demo = DemoScene.build(kit(root));

            assertThat(demo.botCount()).isZero();
            assertThat(demo.scene().hasTaggedEntities())
                .as("an untagged scene skips the outline pass entirely")
                .isFalse();
            assertThat(demo.newMatch().state())
                .as("a room with nobody in it is won, not unwinnable")
                .isEqualTo(MatchState.WON);
        }
    }

    @Nested
    @DisplayName("the bot roster")
    final class Bots
    {
        @Test
        @DisplayName("places exactly Match.DEFAULT_BOT_COUNT opponents, one per character model")
        void shouldPlaceTheFullRosterWhenAllArtIsStaged(@TempDir final Path root)
            throws IOException
        {
            final DemoScene demo = DemoScene.build(kitWithCharacters(root));

            assertThat(demo.botCount()).isEqualTo(Match.DEFAULT_BOT_COUNT);
            assertThat(demo.newMatch().state()).isEqualTo(MatchState.IN_PROGRESS);
        }

        @Test
        @DisplayName("gives every bot a distinct entity id, none of them the player's")
        void shouldGiveEveryBotADistinctIdWhenPlacing(@TempDir final Path root)
            throws IOException
        {
            final DemoScene demo = DemoScene.build(kitWithCharacters(root));
            final Bot[] roster = demo.bots();

            for (int index = 0; index < roster.length; index++)
            {
                assertThat(roster[index].entityId())
                    .as("no bot may hold the reserved player id")
                    .isNotEqualTo(Match.PLAYER_ENTITY_ID);
                for (int other = 0; other < index; other++)
                {
                    assertThat(roster[index].entityId()).isNotEqualTo(roster[other].entityId());
                }
            }
            // Match's own constructor enforces this; that it accepts the roster
            // is the end-to-end statement.
            assertThat(demo.newMatch().botCount()).isEqualTo(roster.length);
        }

        @Test
        @DisplayName("no route leaves the room, at any point in its cycle")
        void shouldKeepEveryRouteInsideThePerimeter(@TempDir final Path root) throws IOException
        {
            // A bot that walks through a wall looks like a physics bug for a
            // long time before anyone thinks to check the placement table. The
            // wall is at half the room span and a body is one radius wide, so
            // this is the exact limit rather than a comfortable margin.
            final DemoScene demo = DemoScene.build(kitWithCharacters(root));
            final float wall = DemoScene.ROOM_TILES * DemoScene.KIT_WORLD_SCALE * 0.5f;
            final float limit = wall - DemoScene.PLAYER_RADIUS_UNITS;

            for (int tic = 0; tic <= LONGEST_ROUTE_TICS; tic++)
            {
                for (final Bot bot : demo.bots())
                {
                    bot.moveTo(tic);
                    assertThat(StrictMath.abs(bot.positionX()))
                        .as("bot %d left the room on x at tic %d", bot.entityId(), tic)
                        .isLessThanOrEqualTo(limit);
                    assertThat(StrictMath.abs(bot.positionZ()))
                        .as("bot %d left the room on z at tic %d", bot.entityId(), tic)
                        .isLessThanOrEqualTo(limit);
                }
            }
        }

        @Test
        @DisplayName("no two bots fire on the same tic")
        void shouldStaggerTheWholeRosterWhenFiring(@TempDir final Path root) throws IOException
        {
            // Seven bots on one cadence with no offsets would volley together,
            // which is both harder to survive and much harder to read than the
            // same total rate spread out.
            final DemoScene demo = DemoScene.build(kitWithCharacters(root));
            final Bot[] roster = demo.bots();

            for (int tic = 0; tic < Match.BOT_FIRE_INTERVAL_TICS * 2; tic++)
            {
                int firing = 0;
                for (final Bot bot : roster)
                {
                    if (bot.wantsToFire(tic))
                    {
                        firing++;
                    }
                }
                assertThat(firing).as("tic %d had %d bots fire at once", tic, firing)
                    .isLessThanOrEqualTo(1);
            }
        }

        @Test
        @DisplayName("each bot's recorded scene index really is its own model")
        void shouldRecordTheCorrectSceneIndexForEachBot(@TempDir final Path root)
            throws IOException
        {
            // The index is the ONLY handle the gameplay port has on a bot's
            // model. Off by one and every body walks somebody else's patrol,
            // while the hitboxes stay where they should be — so shots would
            // connect with thin air next to a visible bot.
            final DemoScene demo = DemoScene.build(kitWithCharacters(root));
            final Scene scene = demo.scene();

            for (int index = 0; index < demo.botCount(); index++)
            {
                assertThat(scene.worldEntityId(demo.botInstanceIndex(index)))
                    .as("scene instance %d must carry bot %d's id",
                        demo.botInstanceIndex(index), index)
                    .isEqualTo(demo.bots()[index].entityId());
            }
        }

        @Test
        @DisplayName("two bots overlap in screen space from the spawn, which is the outline case")
        void shouldPlaceTwoBotsThatOverlapFromTheSpawn(@TempDir final Path root)
            throws IOException
        {
            // The case a naive outline merges into one blob. It has to be two
            // SENTRIES: two moving bodies that happen to overlap on one frame
            // prove nothing repeatable.
            final DemoScene demo = DemoScene.build(kitWithCharacters(root));
            final Bot[] roster = demo.bots();

            assertThat(roster[1].pattern().moves()).isFalse();
            assertThat(roster[2].pattern().moves())
                .as("the second of the overlapping pair must also hold still")
                .isFalse();

            final float bearingOne = bearingFromSpawn(demo, roster[1]);
            final float bearingTwo = bearingFromSpawn(demo, roster[2]);
            final float separation = StrictMath.abs(bearingOne - bearingTwo);

            // Overlap is a comparison of angles, not a guess at pixels: two
            // bodies overlap in screen space exactly when their bearings differ
            // by less than the sum of their angular half-widths. Deriving the
            // threshold rather than picking one means it stays correct if the
            // placements or the player radius move.
            final float halfWidthOne = angularHalfWidth(demo, roster[1]);
            final float halfWidthTwo = angularHalfWidth(demo, roster[2]);
            assertThat(separation)
                .as("bots 1 and 2 must overlap from the spawn: %f apart, %f + %f wide",
                    separation, halfWidthOne, halfWidthTwo)
                .isLessThan(halfWidthOne + halfWidthTwo);
            // But not so completely that the far one is hidden — a fully
            // occluded body tests nothing about telling two outlines apart.
            assertThat(separation).isGreaterThan(0.0f);
        }
    }

    @Nested
    @DisplayName("a fallen body")
    final class Fallen
    {
        @Test
        @DisplayName("is a rotation, not a reflection, so Scene will accept it")
        void shouldKeepAPositiveDeterminantWhenToppling()
        {
            // Scene refuses a negative determinant outright rather than let an
            // instance render inside-out. The obvious way to lay a body down —
            // scaling y by -1 — has determinant -s^3 and is a build failure
            // waiting for whoever tries it.
            final Mat4 fallen = DemoScene.fallenPlacement(10.0f, 0.0f, -5.0f, 1.1f, 3.0f);

            assertThat(determinantOf(fallen)).isCloseTo(27.0f, within(1.0e-3f));
        }

        @Test
        @DisplayName("puts the head along the heading, not behind it")
        void shouldFallFaceFirstWhenToppling()
        {
            // The sign of the pitch is invisible in the matrix: the opposite
            // rotation is equally valid, has the same determinant, and drops
            // every body backwards. Where the head lands is what says it is
            // right. At yaw 0 a standing model faces world +z, so a face-first
            // fall puts its up axis — column 1 — along +z.
            final Mat4 fallen = DemoScene.fallenPlacement(0.0f, 0.0f, 0.0f, 0.0f, 1.0f);

            assertThat(fallen.get(0, 1)).as("head x").isCloseTo(0.0f, within(EPSILON));
            assertThat(fallen.get(1, 1)).as("head y — flat on the floor")
                .isCloseTo(0.0f, within(EPSILON));
            assertThat(fallen.get(2, 1)).as("head z — along the heading")
                .isCloseTo(1.0f, within(EPSILON));
        }

        @Test
        @DisplayName("lifts the body by half its height, so it does not sink into the floor")
        void shouldLiftTheBodyWhenToppling()
        {
            // A model whose origin is at its feet, laid flat, has half its bulk
            // below y = 0 unless it is raised. The correction is derived from
            // the same constant the standing scale uses.
            final Mat4 fallen = DemoScene.fallenPlacement(0.0f, 0.0f, 0.0f, 0.0f,
                DemoScene.CHARACTER_WORLD_SCALE);

            assertThat(fallen.get(1, 3)).isCloseTo(
                DemoScene.PLAYER_HEIGHT_UNITS * 0.5f, within(EPSILON));
        }

        @Test
        @DisplayName("a dead bot is drawn lying down and a live one standing")
        void shouldSwitchPlacementWhenABotDies(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = DemoScene.build(kitWithCharacters(root));
            final Bot victim = demo.bots()[0];

            final Mat4 standing = DemoScene.botPlacement(victim);
            victim.damage(Bot.MAX_HEALTH);
            final Mat4 down = DemoScene.botPlacement(victim);

            assertThat(standing.get(1, 1)).as("standing: model up is world up")
                .isCloseTo(DemoScene.CHARACTER_WORLD_SCALE, within(EPSILON));
            assertThat(down.get(1, 1)).as("fallen: model up is horizontal")
                .isCloseTo(0.0f, within(EPSILON));
        }
    }

    @Nested
    @DisplayName("the scale decision")
    final class Scale
    {
        @Test
        @DisplayName("puts the eye about a third of the way to the ceiling")
        void proportionIsHuman()
        {
            final float ceiling = DemoScene.KIT_WORLD_SCALE * DemoScene.WALL_COURSES;

            assertThat(ceiling).isEqualTo(128.0f);
            // 41 of 128. A human eye is about 1.7 m in a 5.3 m room, which is
            // the same fraction; one 64-unit course would be 64% and would read
            // as a crawlspace.
            assertThat(PlayerController.EYE_HEIGHT_UNITS / ceiling)
                .isCloseTo(0.32f, within(0.01f));
        }

        @Test
        @DisplayName("makes a wall tall enough to be a wall, not a kerb")
        void wallDwarfsThePlayer()
        {
            // The trap this constant exists to avoid: a Kenney wall is 1 unit,
            // so unscaled it would be 1/41 of the eye height — ankle-high.
            assertThat(1.0f * DemoScene.KIT_WORLD_SCALE)
                .isGreaterThan(PlayerController.EYE_HEIGHT_UNITS);
            // And a doorway one tile wide must admit a player, with clearance.
            assertThat(DemoScene.KIT_WORLD_SCALE)
                .isGreaterThan(2.0f * PLAYER_RADIUS_UNITS);
        }

        @Test
        @DisplayName("puts the ceiling exactly on Constants.MAX_OPEN_HEIGHT")
        void ceilingMatchesTheEngineConstant()
        {
            // Independent corroboration that 64 is the right scale, and the
            // strongest evidence available: MAX_OPEN_HEIGHT is the engine's own
            // inherited DOOM constant for the tallest open space, in world
            // units, and it was written long before this demo existed. Two
            // Kenney courses at KIT_WORLD_SCALE land on it exactly.
            final float ceiling = DemoScene.KIT_WORLD_SCALE * DemoScene.WALL_COURSES;
            final float maxOpen =
                (float) Constants.MAX_OPEN_HEIGHT / (float) Constants.MAP_SCALE;

            assertThat(ceiling).isEqualTo(maxOpen);
        }

        @Test
        @DisplayName("gives the fallback room the same ceiling as the real one")
        void fallbackMatchesTheKitCeiling()
        {
            // ProceduralRoom authors 4-unit walls, the Kenney kit 1-unit walls,
            // so the two scales differ by exactly that factor of four and the
            // two demos are comparable.
            assertThat(4.0f * DemoScene.FALLBACK_WORLD_SCALE)
                .isEqualTo(DemoScene.KIT_WORLD_SCALE * DemoScene.WALL_COURSES);
        }
    }

    @Nested
    @DisplayName("placement algebra")
    final class Placement
    {
        @Test
        @DisplayName("uses PlayerController's yaw convention, not its mirror")
        void yawMatchesTheController()
        {
            // Yaw 90 degrees must send model +z to world +x, because
            // PlayerController's groundForward is (sin yaw, 0, cos yaw). The
            // opposite sign is a mirror nobody sees until a door is on the
            // wrong wall.
            final Mat4 turned = DemoScene.placement(0.0f, 0.0f, 0.0f,
                (float) (Math.PI * 0.5), 1.0f);
            final float[] out = new float[4];
            turned.transformPoint(0.0f, 0.0f, 1.0f, out, 0);

            assertThat(out[0]).isCloseTo(1.0f, within(EPSILON));
            assertThat(out[1]).isCloseTo(0.0f, within(EPSILON));
            assertThat(out[2]).isCloseTo(0.0f, within(EPSILON));
        }

        @Test
        @DisplayName("is orientation-preserving at every yaw, so Scene accepts it")
        void determinantIsPositive()
        {
            for (int degrees = 0; degrees < 360; degrees += 15)
            {
                final Mat4 placed = DemoScene.placement(1.0f, 2.0f, 3.0f,
                    (float) Math.toRadians(degrees), 64.0f);
                assertThat(determinant(placed))
                    .as("yaw %d degrees", degrees)
                    .isCloseTo(64.0f * 64.0f * 64.0f, within(1.0f));
            }
        }

        @Test
        @DisplayName("refuses a non-positive scale rather than emitting a mirror")
        void rejectsNegativeScale()
        {
            assertThatThrownBy(() -> DemoScene.placement(0.0f, 0.0f, 0.0f, 0.0f, -1.0f))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> DemoScene.placement(0.0f, 0.0f, 0.0f, 0.0f, 0.0f))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the ceiling flip turns a tile over WITHOUT mirroring it")
        void invertedPlacementIsAHalfTurn()
        {
            final Mat4 flipped = DemoScene.invertedPlacement(0.0f, 128.0f, 0.0f, 64.0f);
            final float[] out = new float[4];
            flipped.transformPoint(0.0f, 1.0f, 0.0f, out, 0);

            // Model +y ends up pointing down: the tile faces the floor.
            assertThat(out[1]).isLessThan(128.0f);
            // And it is still a rotation, not a reflection — scale(1,-1,1)
            // would have determinant -s^3 and Scene would refuse it.
            assertThat(determinant(flipped)).isGreaterThan(0.0f);
        }
    }

    @Nested
    @DisplayName("the viewmodel")
    final class Viewmodel
    {
        @Test
        @DisplayName("clears the near plane at every corner of the blaster")
        void neverPokesThroughTheNearPlane()
        {
            final Mat4 transform = DemoScene.weaponTransform();
            final float[] out = new float[4];
            // MUTABLE local — the nearest view-space z any corner reaches.
            float nearest = Float.MAX_VALUE;

            for (int corner = 0; corner < 8; corner++)
            {
                transform.transformPoint(axis(corner, 0), axis(corner, 1), axis(corner, 2),
                    out, 0);
                nearest = Math.min(nearest, out[2]);
            }

            // View space is +z forward and the clipper keeps w > near, so the
            // whole weapon has to sit beyond it or the stock gets sliced off.
            assertThat(nearest).isGreaterThan(PlayerController.DEFAULT_NEAR_PLANE_UNITS);
        }

        @Test
        @DisplayName("points the muzzle forward, not back at the player")
        void muzzlePointsAwayFromTheEye()
        {
            final Mat4 transform = DemoScene.weaponTransform();
            final float[] muzzle = new float[4];
            final float[] grip = new float[4];

            // The blaster's muzzle is at model -z and its grip end at +z,
            // established by rendering it. After the transform the muzzle must
            // be the FARTHER of the two, or the player is holding it backwards.
            transform.transformPoint(0.0f, 0.0f, BLASTER_BOUNDS[4], muzzle, 0);
            transform.transformPoint(0.0f, 0.0f, BLASTER_BOUNDS[5], grip, 0);

            assertThat(muzzle[2]).isGreaterThan(grip[2]);
        }

        @Test
        @DisplayName("sits right of centre and below the eye")
        void isHeldLowerRight()
        {
            final Mat4 transform = DemoScene.weaponTransform();
            final float[] out = new float[4];
            transform.transformPoint(0.0f, 0.0f, 0.0f, out, 0);

            assertThat(out[0]).isGreaterThan(0.0f);
            assertThat(out[1]).isLessThan(0.0f);
        }

        @Test
        @DisplayName("is angled inward, toward the crosshair")
        void toesIn()
        {
            final Mat4 transform = DemoScene.weaponTransform();
            final float[] muzzle = new float[4];
            final float[] grip = new float[4];
            transform.transformPoint(0.0f, 0.0f, BLASTER_BOUNDS[4], muzzle, 0);
            transform.transformPoint(0.0f, 0.0f, BLASTER_BOUNDS[5], grip, 0);

            // The far end is closer to the screen's centre line than the near
            // end, which is what makes a viewmodel read as aimed.
            assertThat(muzzle[0]).isLessThan(grip[0]);
        }
    }

    @Nested
    @DisplayName("the assembled world")
    final class World
    {
        @Test
        @DisplayName("builds floor, ceiling, walls, props and one viewmodel")
        void hasEveryPart(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = DemoScene.build(kit(root));
            final Scene scene = demo.scene();

            final int tiles = DemoScene.ROOM_TILES * DemoScene.ROOM_TILES;
            final int walls = DemoScene.ROOM_TILES * DemoScene.WALL_COURSES * 4;
            final int props = 4 + 8 + 1 + 1;

            // Floor and ceiling are the same grid twice.
            assertThat(scene.worldInstanceCount()).isEqualTo(2 * tiles + walls + props);
            assertThat(scene.viewInstanceCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("spawns the player inside the room, on the floor")
        void spawnIsInside(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = DemoScene.build(kit(root));
            final float half = DemoScene.ROOM_TILES * DemoScene.KIT_WORLD_SCALE * 0.5f;

            assertThat(Math.abs(demo.spawnX())).isLessThan(half);
            assertThat(Math.abs(demo.spawnZ())).isLessThan(half);
            // Feet on the floor plane, which is where the floor tiles are.
            assertThat(demo.spawnY()).isEqualTo(0.0f);
            // And far enough from the wall behind to be able to turn round
            // without the wall filling the whole frame.
            assertThat(half - Math.abs(demo.spawnZ()))
                .isGreaterThan(4.0f * PLAYER_RADIUS_UNITS);
        }

        @Test
        @DisplayName("spawns a controller standing at that point")
        void spawnControllerAgrees(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = DemoScene.build(kit(root));
            final PlayerController controller = demo.spawnController();

            assertThat(controller.positionX()).isEqualTo(demo.spawnX());
            assertThat(controller.positionZ()).isEqualTo(demo.spawnZ());
            // The eye is above the feet by the controller's own constant, so
            // the camera is at eye height and not on the floor.
            assertThat(controller.eyePosition().y())
                .isEqualTo(demo.spawnY() + PlayerController.EYE_HEIGHT_UNITS);
        }

        @Test
        @DisplayName("builds the fallback as one instance with no viewmodel")
        void fallbackWorld(@TempDir final Path root) throws IOException
        {
            DemoModelFixture.write(root.resolve(DemoModels.FALLBACK_MODEL));

            final DemoScene demo = DemoScene.build(DemoModels.load(root));

            assertThat(demo.source()).isEqualTo(DemoModels.Source.GENERATED_ROOM);
            assertThat(demo.scene().worldInstanceCount()).isEqualTo(1);
            assertThat(demo.scene().viewInstanceCount()).isZero();
        }

        @Test
        @DisplayName("rejects a null model set")
        void rejectsNull()
        {
            assertThatThrownBy(() -> DemoScene.build(null))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("every transform survives Scene's mirror and affine checks")
        void sceneAcceptsEveryInstance(@TempDir final Path root) throws IOException
        {
            // Scene.Builder throws on a negative determinant or a projective
            // bottom row, so a clean build IS the assertion — but say so, or a
            // later reader will delete this as an empty test.
            final DemoModels models = kit(root);
            assertThatCode(() -> DemoScene.build(models)).doesNotThrowAnyException();
        }
    }

    // One corner of the blaster's bounding box, axis by axis.
    private static float axis(final int corner, final int index)
    {
        if ((corner & (1 << index)) == 0)
        {
            return BLASTER_BOUNDS[index * 2];
        }
        return BLASTER_BOUNDS[index * 2 + 1];
    }

    // Determinant of the upper-left 3x3 — the sign is exactly whether triangle
    // winding survives the transform.
    private static float determinant(final Mat4 m)
    {
        return m.get(0, 0) * (m.get(1, 1) * m.get(2, 2) - m.get(1, 2) * m.get(2, 1))
            - m.get(0, 1) * (m.get(1, 0) * m.get(2, 2) - m.get(1, 2) * m.get(2, 0))
            + m.get(0, 2) * (m.get(1, 0) * m.get(2, 1) - m.get(1, 1) * m.get(2, 0));
    }
}
