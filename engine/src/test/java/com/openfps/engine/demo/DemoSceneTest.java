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
import com.openfps.engine.gameplay.HitResult;
import com.openfps.engine.gameplay.Hitscan;
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

    /** Builds a full kit plus the three character models, so targets are placed. */
    private static DemoModels kitWithCharacters(final Path root) throws IOException
    {
        for (final String person : new String[] {"character-a.ofm", "character-d.ofm",
            "character-h.ofm"})
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

            final HitResult result = new HitResult();
            final boolean connected = Hitscan.fire(eye.x(), eye.y(), eye.z(),
                aim.x(), aim.y(), aim.z(), demo.targets(), demo.targetCount(), result);

            assertThat(connected)
                .as("the spawn faces +z down the room, and a target is placed on that bearing")
                .isTrue();
            assertThat(result.entityId())
                .as("the nearest target on the spawn bearing is the first one placed")
                .isEqualTo(DemoScene.FIRST_TARGET_ID);
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
            final DemoScene demo = DemoScene.build(kitWithCharacters(root));
            for (final Target target : demo.targets())
            {
                final boolean spawnInside = demo.spawnX() >= target.minX()
                    && demo.spawnX() <= target.maxX()
                    && demo.spawnZ() >= target.minZ()
                    && demo.spawnZ() <= target.maxZ();
                assertThat(spawnInside)
                    .as("target %d must not contain the spawn point", target.entityId())
                    .isFalse();
            }
        }

        @Test
        @DisplayName("every placed target is tagged, and the room around it is not")
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
                .as("one tagged instance per hitbox — the outline and the shot agree on count")
                .isEqualTo(demo.targetCount());
        }

        @Test
        @DisplayName("no character art means no targets and nothing to outline")
        void shouldPlaceNoTargetsWithoutCharacterArt(@TempDir final Path root)
            throws IOException
        {
            // The staged-art question is independent of the level, exactly as
            // the weapon's is. A demo with no people is still a demo.
            final DemoScene demo = DemoScene.build(kit(root));

            assertThat(demo.targetCount()).isZero();
            assertThat(demo.scene().hasTaggedEntities())
                .as("an untagged scene skips the outline pass entirely")
                .isFalse();
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
