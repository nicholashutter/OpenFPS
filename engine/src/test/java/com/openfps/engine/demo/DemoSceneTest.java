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
import com.openfps.engine.gameplay.BotPattern;
import com.openfps.engine.gameplay.BotRng;
import com.openfps.engine.gameplay.BotSkill;
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

    /**
     * Tics to watch the whole roster's firing over.
     *
     * <p>Long enough that every bot has had many opportunities to fire — at
     * {@link BotSkill#DUMB}'s mean interval that is around forty shots each — so
     * a room that WOULD volley has had ample chance to.</p>
     */
    private static final int VOLLEY_SAMPLE_TICS = 6000;

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

    /** The same, plus the blaster the bots carry, so they are placed armed. */
    private static DemoModels kitWithArmedCharacters(final Path root) throws IOException
    {
        DemoModelFixture.write(root.resolve(DemoModels.WEAPON_DIRECTORY)
            .resolve(DemoModels.BOT_WEAPON_MODEL));

        return kitWithCharacters(root);
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
        @DisplayName("every placed bot is tagged, body and carbine, and the room around it is not")
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

            // TWO per bot, and the second one is deliberate: a bot's carbine
            // carries its HOLDER's id rather than being left untagged, so the
            // outline pass draws one silhouette round the body and what it is
            // holding, and the crosshair does not go dead when it crosses the gun.
            // See addBots. This used to be one per bot only because the carbine
            // was never staged and no weapon instance was placed at all.
            //
            // And two per REMOTE PLAYER body, for the identical reason. Those are
            // placed unconditionally — a Scene is immutable, so a peer's body
            // cannot be created when the peer connects — and they are people
            // holding carbines, so they are tagged exactly as the bots are. They
            // are hidden until a peer's first input arrives, but hiding is a
            // render-time override and does not untag anything, which is the
            // distinction this assertion is really pinning down.
            final int expected = demo.botCount() * 2 + demo.remotePlayers().bodyCount() * 2;

            assertThat(tagged)
                .as("a body and a carbine per bot and per peer — the outline and the"
                    + " shot agree on count")
                .isEqualTo(expected);
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
    @DisplayName("spawn points")
    final class Spawns
    {
        @Test
        @DisplayName("id 0 is exactly the canonical spawn, so single player does not move")
        void shouldLeaveTheCanonicalSpawnAlone(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = DemoScene.build(kitWithCharacters(root));

            // The whole reason index 0 is (0, -1) in the direction table. Every
            // existing test, screenshot and muscle memory is anchored on this
            // placement, and a spawn ring that quietly moved it would be a
            // gratuitous change to the single-player game.
            assertThat(demo.spawnXFor(0)).isEqualTo(demo.spawnX());

            assertThat(demo.spawnZFor(0)).isEqualTo(demo.spawnZ());

            assertThat(DemoScene.spawnYawFor(0)).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("ids 1 and 2 stand opposite each other and look at each other")
        void shouldFaceTheTwoPeerCaseTogether(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = DemoScene.build(kitWithCharacters(root));

            final float radius = demo.spawnRadius();

            // These are the two ids a two-peer match uses, so this is the
            // arrangement anyone testing the networking sees first. Opposite ends
            // of one axis, each facing the other: without it both players spawn
            // outside each other's 60-degree view and a working session looks
            // exactly like a broken one.
            assertThat(demo.spawnXFor(1)).isEqualTo(-radius);

            assertThat(demo.spawnXFor(2)).isEqualTo(radius);

            assertThat(demo.spawnZFor(1)).isEqualTo(0.0f);

            assertThat(demo.spawnZFor(2)).isEqualTo(0.0f);

            // Yaw sweeps from +z toward +x, so +pi/2 faces +x and -pi/2 faces -x.
            final float quarter = (float) (StrictMath.PI / 2.0);

            assertThat(DemoScene.spawnYawFor(1)).isCloseTo(quarter, within(1.0e-6f));

            assertThat(DemoScene.spawnYawFor(2)).isCloseTo(-quarter, within(1.0e-6f));
        }

        @Test
        @DisplayName("every spawn faces the room's centre and stands clear of the walls")
        void shouldPlaceEveryoneInsideLookingIn(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = DemoScene.build(kitWithCharacters(root));

            final float radius = demo.spawnRadius();

            for (int id = 0; id < DemoScene.spawnPointCount(); id++)
            {
                final float x = demo.spawnXFor(id);

                final float z = demo.spawnZFor(id);

                // On the ring, so nobody is closer to a wall than the canonical
                // spawn already is.
                assertThat((float) StrictMath.hypot(x, z))
                    .as("spawn %d sits on the spawn ring", id)
                    .isCloseTo(radius, within(0.01f));

                // Facing the centre: the forward vector built from the spawn yaw
                // must point back at the origin. A spawn looking outward would put
                // a player's first frame into a wall.
                final float yaw = DemoScene.spawnYawFor(id);

                final float forwardX = (float) StrictMath.sin(yaw);

                final float forwardZ = (float) StrictMath.cos(yaw);

                assertThat(forwardX * x + forwardZ * z)
                    .as("spawn %d looks toward the centre, not away from it", id)
                    .isLessThan(0.0f);
            }
        }

        @Test
        @DisplayName("an id outside the table shares a spawn rather than throwing")
        void shouldFoldStrayIds(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = DemoScene.build(kitWithCharacters(root));

            final int count = DemoScene.spawnPointCount();

            // A player id arrives from outside the process, so an id past the
            // table is a thing that can happen rather than a thing to assert
            // against. Sharing a spawn is a survivable answer; an exception on the
            // bootstrap path is not.
            assertThat(demo.spawnXFor(count)).isEqualTo(demo.spawnXFor(0));

            assertThat(demo.spawnXFor(-1)).isEqualTo(demo.spawnXFor(count - 1));
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
        @DisplayName("the room never volleys — no tic has the whole roster firing")
        void shouldStaggerTheWholeRosterWhenFiring(@TempDir final Path root) throws IOException
        {
            // Seven bots on one shared cadence would volley together, which is
            // both harder to survive and much harder to read than the same total
            // rate spread out. The old scene staggered them with arithmetic
            // offsets; the firing is now a per-bot, per-tic draw, which
            // decorrelates the room by construction rather than by bookkeeping —
            // so this asserts the property the player experiences instead of the
            // mechanism that used to produce it.
            final DemoScene demo = DemoScene.build(kitWithCharacters(root));

            final Bot[] roster = demo.bots();

            final BotRng rng = new BotRng();

            int busiestTic = 0;

            for (int tic = 0; tic < VOLLEY_SAMPLE_TICS; tic++)
            {
                int firing = 0;

                for (final Bot bot : roster)
                {
                    if (bot.wantsToFire(tic, rng, BotSkill.DUMB))
                    {
                        firing++;
                    }
                }

                busiestTic = Math.max(busiestTic, firing);
            }

            assertThat(busiestTic)
                .as("%d of %d bots fired on one tic — that is a broadside",
                    busiestTic, roster.length)
                .isLessThan(roster.length);
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
    @DisplayName("a killed body disappears")
    final class Killed
    {
        @Test
        @DisplayName("a live bot stands where the simulation put it")
        void shouldStandALiveBot(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = DemoScene.build(kitWithCharacters(root));

            final Bot bot = demo.bots()[0];

            final Mat4 standing = DemoScene.botPlacement(bot);

            assertThat(standing.get(1, 1)).as("model up is world up, at the character scale")
                .isCloseTo(DemoScene.CHARACTER_WORLD_SCALE, within(EPSILON));

            assertThat(standing.get(0, 3)).as("placed at its own x")
                .isCloseTo(bot.positionX(), within(EPSILON));

            assertThat(standing.get(2, 3)).as("placed at its own z")
                .isCloseTo(bot.positionZ(), within(EPSILON));
        }

        @Test
        @DisplayName("a killed bot is hidden outright, not laid on the floor")
        void shouldHideAKilledBot(@TempDir final Path root) throws IOException
        {
            final DemoScene demo = DemoScene.build(kitWithCharacters(root));

            final Bot victim = demo.bots()[0];

            victim.damage(Bot.MAX_HEALTH);

            assertThat(DemoScene.botPlacement(victim))
                .as("the established hidden transform, shared rather than copied")
                .isSameAs(DemoEffects.HIDDEN);
        }

        @Test
        @DisplayName("the hidden transform really does collapse the model to a point")
        void shouldCollapseEveryAxis(@TempDir final Path root) throws IOException
        {
            // The property, not the identity of the constant: every model axis
            // maps to the zero vector, so every triangle has zero area and the
            // rasterizer rejects it before any pixel — no colour, no depth and
            // no entity id. That last one is what stops a corpse turning the
            // crosshair red.
            final DemoScene demo = DemoScene.build(kitWithCharacters(root));

            final Bot victim = demo.bots()[0];

            victim.damage(Bot.MAX_HEALTH);

            final Mat4 hidden = DemoScene.botPlacement(victim);

            for (int row = 0; row < 3; row++)
            {
                for (int column = 0; column < 4; column++)
                {
                    assertThat(hidden.get(row, column))
                        .as("hidden transform element (%d,%d)", row, column)
                        .isZero();
                }
            }

            assertThat(determinantOf(hidden))
                .as("a collapsed basis has no volume at all")
                .isZero();
        }

        @Test
        @DisplayName("a body that is merely hurt is still drawn standing")
        void shouldKeepAWoundedBotVisible(@TempDir final Path root) throws IOException
        {
            // The gate is death, not damage. A bot two hits down must still be
            // in the room, or the third hit has nothing to land on.
            final DemoScene demo = DemoScene.build(kitWithCharacters(root));

            final Bot victim = demo.bots()[0];

            victim.damage(Bot.MAX_HEALTH - 1);

            assertThat(victim.isAlive()).isTrue();

            assertThat(DemoScene.botPlacement(victim)).isNotSameAs(DemoEffects.HIDDEN);

            assertThat(DemoScene.botPlacement(victim).get(1, 1))
                .isCloseTo(DemoScene.CHARACTER_WORLD_SCALE, within(EPSILON));
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

            // Floor and ceiling are the same grid twice, plus the tracer and
            // smoke instances every scene carries, plus the local player's
            // arms — procedurally generated, so present whether or not art
            // was staged.
            assertThat(scene.worldInstanceCount())
                .isEqualTo(2 * tiles + walls + props + demo.effects().instanceCount() + 1);

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

            // The room, plus the effect pool — generated geometry, present
            // even when no art at all was staged — plus the local player's
            // arms. The fallback is the only scene with no character art
            // and so the only one with neither bots nor peer bodies; the
            // arms are still placed because they are procedural.
            assertThat(demo.scene().worldInstanceCount())
                .isEqualTo(1 + demo.effects().instanceCount() + 1);

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

    @Nested
    @DisplayName("the weapon each bot carries")
    final class BotWeapons
    {
        /**
         * The bots' blaster along its own axis, in model units.
         *
         * <p>{@code blaster-p.ofm} measures {@code 0.16 x 0.37 x 0.86}, read off
         * {@code gradlew :tools:verifyModels} rather than guessed. Length is the z
         * extent, because a Blaster Kit muzzle points down model -z.</p>
         */
        private static final float BOT_BLASTER_LENGTH = 0.86f;

        @Test
        @DisplayName("is a DIFFERENT model from the player's, which is the whole requirement")
        void shouldNotBeThePlayersOwnBlaster()
        {
            // An opponent holding what looks like your own gun tells you nothing.
            // The two files are the assertion: same pack, so no new attribution,
            // and deliberately not the same piece.
            assertThat(DemoModels.BOT_WEAPON_MODEL).isNotEqualTo(DemoModels.WEAPON_MODEL);
        }

        @Test
        @DisplayName("one instance per bot, tagged with that bot's own entity id")
        void shouldPlaceOneWeaponPerBot(@TempDir final Path root) throws IOException
        {
            // Tagged rather than left untagged, and it matters twice: the outline
            // pass draws one silhouette round a body and what it is holding, and
            // the crosshair does not go dead when it crosses the gun.
            final DemoScene demo = DemoScene.build(kitWithArmedCharacters(root));

            final Scene scene = demo.scene();

            assertThat(demo.hasBotWeapons()).isTrue();

            for (int index = 0; index < demo.botCount(); index++)
            {
                final int instance = demo.botWeaponInstanceIndex(index);

                assertThat(instance).isNotEqualTo(DemoScene.NO_INSTANCE);

                assertThat(instance).isNotEqualTo(demo.botInstanceIndex(index));

                assertThat(scene.worldEntityId(instance))
                    .as("weapon instance %d must carry bot %d's id", instance, index)
                    .isEqualTo(demo.bots()[index].entityId());
            }
        }

        @Test
        @DisplayName("goes away with the body, so no gun floats over a corpse")
        void shouldHideTheWeaponWhenTheBotDies()
        {
            // The two have to agree about death on the same tic. A weapon left
            // visible over the spot where a bot fell is the most conspicuous object
            // in the room, and it would make "the body goes away" read as a
            // rendering fault rather than as a kill.
            final Bot victim = new Bot(2, 40.0f, 0.0f, 120.0f, BotPattern.SENTRY,
                0.0f, 300, 0);

            assertThat(DemoScene.botWeaponPlacement(victim)).isNotSameAs(DemoEffects.HIDDEN);

            victim.damage(Bot.MAX_HEALTH);

            assertThat(DemoScene.botWeaponPlacement(victim))
                .as("the weapon is still drawn after its holder died")
                .isSameAs(DemoEffects.HIDDEN);

            assertThat(DemoScene.botPlacement(victim)).isSameAs(DemoEffects.HIDDEN);
        }

        @Test
        @DisplayName("is a plausible size against the body holding it")
        void shouldBeSizedAgainstTheHolder()
        {
            // The derived scale, checked against the thing it is derived FROM. A
            // weapon is somewhere between a fifth and two thirds of its holder's
            // height; outside that it is a pistol on a giant or a cannon on a
            // child, and this is the constant that decides between a rifle and a
            // car.
            final float lengthUnits = BOT_BLASTER_LENGTH * DemoScene.BOT_WEAPON_WORLD_SCALE;

            assertThat(lengthUnits / DemoScene.PLAYER_HEIGHT_UNITS)
                .as("the blaster is %f units long against a %f-unit body",
                    lengthUnits, DemoScene.PLAYER_HEIGHT_UNITS)
                .isBetween(0.2f, 0.65f);
        }

        @Test
        @DisplayName("is held ACROSS the body, so its length is visible rather than end-on")
        void shouldNotPointAtTheViewer()
        {
            // THE REGRESSION, and it is the whole reason the carry angle exists.
            // Pointed straight down the bot's facing, the weapon is aimed at
            // whoever it is facing — so what they see is its CROSS-SECTION, three
            // units by eight on a 56-unit body, which reads as a smudge on a shirt.
            // It was drawn correctly, every frame, and it was invisible.
            //
            // Asserted as a PROJECTED LENGTH in world units, not as an angle: the
            // question is how much of the weapon the viewer can see, and only a
            // length can answer that. An assertion that the yaw differed from the
            // bot's would have passed at one degree of difference.
            final Bot bot = new Bot(2, 0.0f, 0.0f, 200.0f, BotPattern.SENTRY, 0.0f, 300, 0);

            bot.observePlayer(0, 0.0f, 0.0f, BotSkill.MARKSMAN);

            bot.faceRemembered();

            final Mat4 held = DemoScene.botWeaponPlacement(bot);

            // Column 2 is the image of the model's +z axis — the weapon's own long
            // axis — scaled by BOT_WEAPON_WORLD_SCALE. The viewer is at the origin
            // looking up +z, so what they see across the screen is the x component.
            final float acrossScreen = StrictMath.abs(held.get(0, 2)) * BOT_BLASTER_LENGTH;

            final float alongTheView = StrictMath.abs(held.get(2, 2)) * BOT_BLASTER_LENGTH;

            assertThat(acrossScreen)
                .as("only %f units of the weapon's %f-unit length face the viewer;"
                    + " %f units are pointing away and cannot be seen",
                    acrossScreen, BOT_BLASTER_LENGTH * DemoScene.BOT_WEAPON_WORLD_SCALE,
                    alongTheView)
                .isGreaterThan(Bot.RADIUS_UNITS * 0.5f);
        }

        @Test
        @DisplayName("sits at a hand's height, below the eyes — these bots are not aiming")
        void shouldSitBelowEyeHeight()
        {
            // A weapon level with the eyes reads as aimed down a sight, and the
            // whole of BotSkill is about how badly these opponents aim.
            assertThat(DemoScene.BOT_WEAPON_HEIGHT_UNITS)
                .isLessThan(Bot.EYE_HEIGHT_UNITS)
                .isGreaterThan(DemoScene.PLAYER_HEIGHT_UNITS * 0.4f);
        }

        @Test
        @DisplayName("follows its holder, rather than staying where the bot started")
        void shouldFollowTheBotAlongItsRoute()
        {
            final Bot walker = new Bot(2, 0.0f, 0.0f, 200.0f, BotPattern.PACE_X,
                80.0f, 300, 0);

            final float atSpawn = DemoScene.botWeaponPlacement(walker).get(0, 3);

            walker.moveTo(75);

            assertThat(DemoScene.botWeaponPlacement(walker).get(0, 3))
                .as("the weapon stayed behind when the bot walked away")
                .isNotEqualTo(atSpawn);
        }

        @Test
        @DisplayName("is not mirrored, so the model does not render inside-out")
        void shouldKeepAPositiveDeterminant()
        {
            // Scene refuses a negative determinant outright rather than let an
            // instance render inside-out, so a mirrored transform here is a build
            // failure at scene-build time rather than something to look at.
            final Bot bot = new Bot(2, 30.0f, 0.0f, 90.0f, BotPattern.SENTRY, 0.0f, 300, 0);

            bot.observePlayer(0, -100.0f, 0.0f, BotSkill.MARKSMAN);

            bot.faceRemembered();

            assertThat(determinant(DemoScene.botWeaponPlacement(bot))).isPositive();
        }

        @Test
        @DisplayName("an unstaged carbine still puts a weapon in every bot's hands")
        void shouldArmTheBotsWithoutTheRealCarbine(@TempDir final Path root) throws IOException
        {
            // THIS IS THE REGRESSION for "the carbines are not visible". The demo's
            // art is gitignored, so a fresh clone — and any clone whose payload
            // predates blaster-p joining the curated list — has no bot carbine. It
            // used to place no instance at all, which measured as zero pixels of
            // weapon. A bot with nothing in its hands has nowhere for incoming fire
            // to come from, so absent art now costs the ART and not the feature.
            final DemoScene demo = DemoScene.build(kitWithCharacters(root));

            assertThat(demo.hasBotWeapons()).isTrue();

            for (int index = 0; index < demo.botCount(); index++)
            {
                assertThat(demo.botWeaponInstanceIndex(index))
                    .as("bot %d was placed empty-handed", index)
                    .isNotEqualTo(DemoScene.NO_INSTANCE);
            }
        }
    }

    /**
     * The player-side muzzle helper, mirrored from
     * {@link DemoScene#botMuzzle}. The aim point the visible tracer
     * spawns from has to be the viewmodel's barrel tip in world space,
     * not the eye, or the bolt visibly comes from the camera rather
     * than the gun.
     */
    @Nested
    @DisplayName("playerMuzzle — the player's viewmodel barrel tip in world space")
    class PlayerMuzzle
    {
        /**
         * Tolerance for the muzzle-position assertions. The viewmodel
         * constants are exact, so the muzzle at yaw=0, pitch=0 collapses
         * to a closed form. 1e-4f is plenty to catch a sign flip or a
         * wrong basis vector without flaking on strictfp rounding.
         */
        private static final float MUZZLE_EPSILON = 1.0e-4f;

        @Test
        @DisplayName("at yaw=0, pitch=0 the muzzle sits in front of the eye, below it, and to the player's right")
        void muzzleAtDefaultAim()
        {
            // Default PlayerController faces world +z, so the viewmodel's
            // view-space +z (which is the gun barrel direction after the
            // 174-degree carry) maps to world +z. The view-space +x (to
            // the right of the camera) maps to world -x, because the
            // Camera basis the PlayerController hands out is the one
            // Camera.create builds and the test below pins that.
            final PlayerController player = new PlayerController(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);

            final float[] out = new float[3];

            DemoScene.playerMuzzle(player, out, 0);

            // Gun barrel in view space at yaw=0, pitch=0:
            //   view_x = WEAPON_VIEW_RIGHT - sin(174 deg) * 0.86 * WEAPON_VIEW_SCALE
            //   view_y = WEAPON_VIEW_DOWN
            //   view_z = WEAPON_VIEW_FORWARD - cos(174 deg) * 0.86 * WEAPON_VIEW_SCALE
            // Transformed to world (right = -x, up = +y, forward = +z):
            //   world_x = -view_x = -0.92 + 0.105 * 0.86 * 1.9
            //   world_y = eyeY + view_y = EYE_HEIGHT + (-0.38)
            //   world_z = view_z = 1.85 - (-0.995) * 0.86 * 1.9
            //                              = 1.85 + 1.625
            final float expectedX = -DemoScene.WEAPON_VIEW_RIGHT
                + (float) StrictMath.sin(Math.toRadians(DemoScene.WEAPON_VIEW_YAW_DEGREES))
                * DemoScene.WEAPON_BARREL_LENGTH_MODEL_UNITS * DemoScene.WEAPON_VIEW_SCALE;

            final float expectedY = PlayerController.EYE_HEIGHT_UNITS + DemoScene.WEAPON_VIEW_DOWN;

            final float expectedZ = DemoScene.WEAPON_VIEW_FORWARD
                - (float) StrictMath.cos(Math.toRadians(DemoScene.WEAPON_VIEW_YAW_DEGREES))
                * DemoScene.WEAPON_BARREL_LENGTH_MODEL_UNITS * DemoScene.WEAPON_VIEW_SCALE;

            assertThat(out[0]).isCloseTo(expectedX, within(MUZZLE_EPSILON));

            assertThat(out[1]).isCloseTo(expectedY, within(MUZZLE_EPSILON));

            assertThat(out[2]).isCloseTo(expectedZ, within(MUZZLE_EPSILON));

            // Sanity: the muzzle is in front of the player (z > 0), a
            // EYE_HEIGHT worth above the floor, and a few units to the
            // side. None of these is exact — a hand-wavy "out the gun"
            // check.
            assertThat(out[2]).as("the muzzle is forward of the eye").isPositive();

            assertThat(out[1]).as("the muzzle is above the floor").isGreaterThan(0.0f);
        }

        @Test
        @DisplayName("turning 90 degrees to the right rotates the muzzle around the eye in world space")
        void muzzleRotatesWithYaw()
        {
            final PlayerController facing0 =
                new PlayerController(10.0f, 0.0f, 20.0f, 0.0f, 0.0f);

            final PlayerController facingPi2 =
                new PlayerController(10.0f, 0.0f, 20.0f, (float) (Math.PI / 2.0), 0.0f);

            final float[] out0 = new float[3];

            DemoScene.playerMuzzle(facing0, out0, 0);

            final float[] outPi2 = new float[3];

            DemoScene.playerMuzzle(facingPi2, outPi2, 0);

            // Eye is unchanged between the two players (same position,
            // same EYE_HEIGHT); the offset rotates. The yaw=0 offset is
            // (deltaX, deltaY, deltaZ) — the muzzle minus the eye — and
            // at yaw=pi/2 the same offset is (-deltaZ, deltaY, -deltaX)
            // by a +90 rotation about +y. Assert the structural
            // identity rather than the exact rotation: same length, and
            // the vertical component (muzzle.y - eye.y) is the only one
            // the camera basis does not touch.
            final float dx0 = out0[0] - 10.0f;

            final float dy0 = out0[1] - (0.0f + PlayerController.EYE_HEIGHT_UNITS);

            final float dz0 = out0[2] - 20.0f;

            final float dx1 = outPi2[0] - 10.0f;

            final float dy1 = outPi2[1] - (0.0f + PlayerController.EYE_HEIGHT_UNITS);

            final float dz1 = outPi2[2] - 20.0f;

            final float len0 = (float) Math.sqrt(dx0 * dx0 + dy0 * dy0 + dz0 * dz0);

            final float len1 = (float) Math.sqrt(dx1 * dx1 + dy1 * dy1 + dz1 * dz1);

            assertThat(len1)
                .as("the muzzle offset's length is the same after a yaw rotation")
                .isCloseTo(len0, within(MUZZLE_EPSILON));

            assertThat(dy1)
                .as("a pure yaw does not change the muzzle's drop below the eye")
                .isCloseTo(dy0, within(MUZZLE_EPSILON));
        }

        @Test
        @DisplayName("pitching up lifts the muzzle in world space")
        void muzzleChangesWithPitch()
        {
            final PlayerController flat =
                new PlayerController(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);

            final PlayerController pitched =
                new PlayerController(0.0f, 0.0f, 0.0f, 0.0f, 0.5f);

            final float[] outFlat = new float[3];

            DemoScene.playerMuzzle(flat, outFlat, 0);

            final float[] outPitched = new float[3];

            DemoScene.playerMuzzle(pitched, outPitched, 0);

            // Pitching up moves the camera basis's "up" component, so
            // the muzzle's world y rises. A flat assertion (no trig
            // expansion) is enough — the bar is "pitch changes the
            // muzzle's vertical position in world space", not "by how
            // much".
            assertThat(outPitched[1])
                .as("pitching up lifts the muzzle in world y")
                .isGreaterThan(outFlat[1]);
        }

        @Test
        @DisplayName("a null buffer is rejected")
        void rejectsNullBuffer()
        {
            final PlayerController player = new PlayerController(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);

            assertThatThrownBy(() -> DemoScene.playerMuzzle(player, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a too-small buffer is rejected")
        void rejectsTooSmallBuffer()
        {
            final PlayerController player = new PlayerController(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);

            final float[] tooSmall = new float[2];

            assertThatThrownBy(() -> DemoScene.playerMuzzle(player, tooSmall, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a non-zero offset is honoured, prefix left untouched")
        void honoursOffset()
        {
            final PlayerController player = new PlayerController(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);

            final float[] out = new float[6];

            out[0] = 99.0f;

            out[1] = 99.0f;

            out[2] = 99.0f;

            DemoScene.playerMuzzle(player, out, 3);

            assertThat(out[0]).isEqualTo(99.0f);

            assertThat(out[1]).isEqualTo(99.0f);

            assertThat(out[2]).isEqualTo(99.0f);

            // The written block is at offsets 3..5. The values are
            // asserted for finiteness rather than for the exact number;
            // a closed-form comparison lives in muzzleAtDefaultAim.
            assertThat(out[3]).isFinite();

            assertThat(out[4]).isFinite();

            assertThat(out[5]).isFinite();
        }
    }
}
