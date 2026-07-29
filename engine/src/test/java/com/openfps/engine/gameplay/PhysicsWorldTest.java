/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.openfps.engine.demo.DemoScene;
import com.openfps.engine.gameplay.port.I_PlayerInput;

/**
 * Tests for {@link PhysicsWorld} and for the collision it gives
 * {@link PlayerController}.
 *
 * <p>Two halves, deliberately. The first drives the solver against a hand-built
 * world of one or two boxes, where every expected coordinate can be worked out
 * on paper. The second drives a real {@link PlayerController} around the real
 * demo room from {@link DemoScene#kitRoomPhysics()}, because the properties that
 * actually matter - you cannot leave the room, you can still get through the
 * door - are properties of <i>that</i> room and not of the algorithm.</p>
 *
 * <p>Expected stopping positions are computed from
 * {@link DemoScene#WALL_INNER_FACE_UNITS} and
 * {@link PhysicsWorld#PLAYER_HALF_WIDTH_UNITS} rather than written as literals
 * ({@code STYLE.md} section 13.3), so moving the room moves the assertions with
 * it - but the arithmetic relating the two is restated here rather than borrowed
 * from the solver, which is what stops a broken solver from agreeing with
 * itself.</p>
 */
@DisplayName("PhysicsWorld")
class PhysicsWorldTest
{
    /**
     * Absolute tolerance for coordinates. The room is 640 units across and a
     * float carries seven digits there, so 1e-3 has four orders of magnitude of
     * headroom while still failing on any real contact-plane error: the
     * smallest mistake worth catching is half a wall thickness, 6.4 units.
     */
    private static final float EPSILON = 1.0e-3f;

    /** A representative tic duration: 60 Hz. */
    private static final float TIC_60HZ = 1.0f / 60.0f;

    /** Half the player's footprint - the collision hull's only dimension. */
    private static final float HALF = PhysicsWorld.PLAYER_HALF_WIDTH_UNITS;

    /** Where a player's centre comes to rest against the room's wall: 313.6 - 16. */
    private static final float WALL_STOP = DemoScene.WALL_INNER_FACE_UNITS - HALF;

    /** The demo room, built once - it is immutable, so sharing it is free. */
    private static final PhysicsWorld ROOM = DemoScene.kitRoomPhysics();

    /**
     * A single solid box spanning x 100..200 and z 100..200, for a 16-unit body.
     *
     * <p>Round numbers on purpose: every contact plane in the first half of this
     * file is 84 or 216, and can be checked without a calculator.</p>
     *
     * @return a world with one box in it
     */
    private static PhysicsWorld oneBox()
    {
        return PhysicsWorld.builder(HALF).addBox(100.0f, 100.0f, 200.0f, 200.0f).build();
    }

    /** Immutable {@link I_PlayerInput} for driving a controller. */
    private static final class Input implements I_PlayerInput
    {
        private final float forward;
        private final float strafe;

        private Input(final float forwardAxis, final float strafeAxis)
        {
            this.forward = forwardAxis;
            this.strafe = strafeAxis;
        }

        @Override
        public float forwardAxis()
        {
            return forward;
        }

        @Override
        public float strafeAxis()
        {
            return strafe;
        }

        @Override
        public float yawDelta()
        {
            return 0.0f;
        }

        @Override
        public float pitchDelta()
        {
            return 0.0f;
        }

        /**
         * Never jumps. Every test here is about the horizontal plane, and a
         * player in mid-air would confuse the one thing being measured - the
         * position the world permitted - with the arc gravity was drawing.
         *
         * @return false, always
         */
        @Override
        public boolean jump()
        {
            return false;
        }
    }

    @Nested
    @DisplayName("blocking")
    class Blocking
    {
        @Test
        @DisplayName("a move straight into a face stops exactly at the face")
        void shouldStopAtTheContactPlaneWhenMovingIntoAFace()
        {
            final PhysicsWorld world = oneBox();

            // Standing level with the box at z = 150, walking east into its
            // west face at x = 100. A 16-unit half-width stops the centre at 84.
            assertThat(world.slideX(0.0f, 150.0f, 500.0f)).isEqualTo(100.0f - HALF);
        }

        @Test
        @DisplayName("a move into the far face stops on the far side")
        void shouldStopAtTheFarContactPlaneWhenMovingTheOtherWay()
        {
            final PhysicsWorld world = oneBox();

            assertThat(world.slideX(500.0f, 150.0f, -500.0f)).isEqualTo(200.0f + HALF);
        }

        @Test
        @DisplayName("both axes are blocked the same way")
        void shouldBlockTheZAxisTheSameWayAsTheX()
        {
            final PhysicsWorld world = oneBox();

            assertThat(world.slideZ(150.0f, 0.0f, 500.0f)).isEqualTo(100.0f - HALF);
            assertThat(world.slideZ(150.0f, 500.0f, -500.0f)).isEqualTo(200.0f + HALF);
        }

        @Test
        @DisplayName("a single step cannot tunnel through a thin wall")
        void shouldNotTunnelWhenOneStepWouldCrossTheWholeSolid()
        {
            // A 4-unit slab, thinner than any wall in the demo room, and a step
            // of 200 units - far more than a tic can produce. The clip is swept
            // rather than a sample at the destination, so it still holds.
            final PhysicsWorld thin =
                PhysicsWorld.builder(HALF).addBox(0.0f, -50.0f, 4.0f, 50.0f).build();

            assertThat(thin.slideX(-100.0f, 0.0f, 200.0f)).isEqualTo(-HALF);
        }

        @Test
        @DisplayName("a solid the body is not level with does not block it")
        void shouldIgnoreASolidTheBodyIsNotBesideOnTheOtherAxis()
        {
            final PhysicsWorld world = oneBox();

            // z = -500 is nowhere near the box's 100..200 span, so travelling
            // east past it is unobstructed.
            assertThat(world.slideX(0.0f, -500.0f, 500.0f)).isEqualTo(500.0f);
        }
    }

    @Nested
    @DisplayName("sliding")
    class Sliding
    {
        @Test
        @DisplayName("a move along a wall the body is resting against still moves")
        void shouldKeepMovingAlongAWallItIsTouching()
        {
            final PhysicsWorld world = oneBox();
            final float resting = 100.0f - HALF;

            // Pinned against the west face, now walking north along it. Nothing
            // may take this away: a solver whose overlap test is inclusive
            // rather than strict glues the body to the wall it is touching.
            assertThat(world.slideZ(resting, 0.0f, 50.0f)).isEqualTo(50.0f);
        }

        @Test
        @DisplayName("a diagonal into a wall slides along it instead of stopping")
        void shouldSlideWhenTheMoveIsDiagonalIntoAWall()
        {
            final PhysicsWorld world = oneBox();

            // Approaching the west face from (0, 150) heading north-east. The
            // east component is blocked at 84; the north component is free, and
            // it is that survival which is the whole of "slide".
            final float permittedX = world.slideX(0.0f, 150.0f, 500.0f);
            final float permittedZ = world.slideZ(permittedX, 150.0f, 30.0f);

            assertThat(permittedX).isEqualTo(100.0f - HALF);
            assertThat(permittedZ)
                .as("the component parallel to the wall must survive")
                .isEqualTo(180.0f);
        }

        @Test
        @DisplayName("a player pushing diagonally into a room wall keeps moving sideways")
        void shouldSlideAlongTheRoomWallWhenAPlayerPushesIntoItDiagonally()
        {
            // Facing +z at the south wall, holding forward and strafe together.
            // Forward is into the wall; strafe is along it. A solver that zeroed
            // the whole blocked move would leave x exactly where it started.
            final PlayerController player =
                new PlayerController(0.0f, 0.0f, WALL_STOP, 0.0f, 0.0f, ROOM);
            final float startX = player.positionX();

            for (int tic = 0; tic < 60; tic++)
            {
                player.update(new Input(1.0f, 1.0f), TIC_60HZ);
            }

            assertThat(player.positionZ())
                .as("pinned against the wall")
                .isCloseTo(WALL_STOP, within(EPSILON));
            assertThat(player.positionX())
                .as("but still travelling along it")
                .isLessThan(startX - 100.0f);
        }
    }

    @Nested
    @DisplayName("corners")
    class Corners
    {
        @Test
        @DisplayName("an inside corner does not leak diagonally")
        void shouldHoldWhenPushedIntoAnInsideCorner()
        {
            // Two boxes meeting at (100, 100): one running east, one running
            // north. The body sits in the corner they enclose and pushes into
            // both at once. Per-axis resolution that tests the second axis from
            // the ORIGINAL position lets a body straight through here.
            final PhysicsWorld corner = PhysicsWorld.builder(HALF)
                .addBox(100.0f, -100.0f, 300.0f, 100.0f)
                .addBox(-100.0f, 100.0f, 100.0f, 300.0f)
                .build();
            final float restX = 100.0f - HALF;
            final float restZ = 100.0f - HALF;

            final float permittedX = corner.slideX(restX, restZ, 50.0f);
            final float permittedZ = corner.slideZ(permittedX, restZ, 50.0f);

            assertThat(permittedX).isEqualTo(restX);
            assertThat(permittedZ).isEqualTo(restZ);
            assertThat(corner.isBlocked(permittedX, permittedZ)).isFalse();
        }

        @Test
        @DisplayName("all four of the room's corners hold a player driven into them")
        void shouldSettleExactlyInEveryCornerWhenDrivenIntoIt()
        {
            // Each corner in turn, approached from 40 units out along its own
            // diagonal and walked into for a second. All four rather than the
            // one that happened to be tried by hand: a corner is where per-axis
            // resolution leaks, and a solver can easily hold three of them.
            //
            // The player must end EXACTLY in the corner on both axes. That is
            // the sharp form of the property - "still inside the room" would
            // also pass for a player who had snagged on a crate on the way.
            final float[] cornerYawTurns =
            {
                0.125f, 0.375f, 0.625f, 0.875f,
            };
            final float[] towardX =
            {
                1.0f, 1.0f, -1.0f, -1.0f,
            };
            final float[] towardZ =
            {
                1.0f, -1.0f, -1.0f, 1.0f,
            };
            // Clear of every crate: the furthest prop reaches 256 once widened
            // by the body, and the corner itself is at 297.6.
            final float approach = 280.0f;

            for (int index = 0; index < cornerYawTurns.length; index++)
            {
                final float yaw = cornerYawTurns[index] * PlayerController.FULL_TURN_RADIANS;
                final float startX = approach * towardX[index];
                final float startZ = approach * towardZ[index];
                final PlayerController player =
                    new PlayerController(startX, 0.0f, startZ, yaw, 0.0f, ROOM);

                for (int tic = 0; tic < 60; tic++)
                {
                    player.update(new Input(1.0f, 0.0f), TIC_60HZ);
                }

                assertThat(player.positionX())
                    .as("corner %s, x", Integer.valueOf(index))
                    .isCloseTo(WALL_STOP * towardX[index], within(EPSILON));
                assertThat(player.positionZ())
                    .as("corner %s, z", Integer.valueOf(index))
                    .isCloseTo(WALL_STOP * towardZ[index], within(EPSILON));
                assertThat(ROOM.isBlocked(player.positionX(), player.positionZ()))
                    .as("corner %s left the body inside the wall", Integer.valueOf(index))
                    .isFalse();
            }
        }
    }

    @Nested
    @DisplayName("the demo room")
    class TheDemoRoom
    {
        @Test
        @DisplayName("a player walking at the south wall stops inside the room")
        void shouldStopTheWalkingPlayerAtTheSouthWall()
        {
            // The demo's own spawn placement and heading, held for ten seconds:
            // long enough to cross the room twice at 256 units a second. This is
            // the run the screenshot harness performs in the real application.
            final PlayerController player =
                new PlayerController(0.0f, 0.0f, -192.0f, 0.0f, 0.0f, ROOM);

            for (int tic = 0; tic < 600; tic++)
            {
                player.update(new Input(1.0f, 0.0f), TIC_60HZ);
            }

            assertThat(player.positionZ()).isCloseTo(WALL_STOP, within(EPSILON));
        }

        @Test
        @DisplayName("the doorway is still walkable")
        void shouldLetThePlayerThroughTheDoorway()
        {
            // Lined up on the middle of the opening. The doorway has to stay a
            // doorway: sealing the room would pass every other test in this file
            // and would quietly delete the one piece of level geometry the demo
            // went to the trouble of modelling.
            final float middle =
                (DemoScene.DOORWAY_MIN_X_UNITS + DemoScene.DOORWAY_MAX_X_UNITS) * 0.5f;
            final PlayerController player =
                new PlayerController(middle, 0.0f, 0.0f, 0.0f, 0.0f, ROOM);

            for (int tic = 0; tic < 300; tic++)
            {
                player.update(new Input(1.0f, 0.0f), TIC_60HZ);
            }

            assertThat(player.positionZ())
                .as("the player should have walked out through the door")
                .isGreaterThan(DemoScene.WALL_INNER_FACE_UNITS);
        }

        @Test
        @DisplayName("the doorway jambs are solid")
        void shouldBlockTheJambsBesideTheDoorway()
        {
            // One unit either side of the exact clearance the doorway affords.
            // The opening is 64 wide and the body 32, so the window a centre may
            // pass through runs from doorMinX + 16 to doorMaxX - 16.
            final float overlappingTheJamb = DemoScene.DOORWAY_MIN_X_UNITS + HALF - 1.0f;
            final float clearOfTheJamb = DemoScene.DOORWAY_MIN_X_UNITS + HALF + 1.0f;

            assertThat(ROOM.slideZ(overlappingTheJamb, 0.0f, 500.0f))
                .as("a body overlapping the jamb must be stopped by the wall")
                .isCloseTo(WALL_STOP, within(EPSILON));
            assertThat(ROOM.slideZ(clearOfTheJamb, 0.0f, 500.0f))
                .as("a body clear of the jamb must pass")
                .isEqualTo(500.0f);
        }

        @Test
        @DisplayName("the columns and the crates are solid")
        void shouldBlockTheProps()
        {
            // A column stands at (-128, -128) and is 12.8 units across; a crate
            // stands at (-192, 0) and is 32 across. Walking due east at each
            // one's z must stop short of it rather than pass through.
            assertThat(ROOM.slideX(-300.0f, -128.0f, 400.0f))
                .as("the south-west column")
                .isLessThan(-128.0f);
            assertThat(ROOM.slideX(-300.0f, 0.0f, 400.0f))
                .as("the crate at x -192")
                .isLessThan(-192.0f);
        }

        @Test
        @DisplayName("the room is sixteen boxes and the spawn is clear of all of them")
        void shouldPlaceTheSpawnOutsideEverySolid()
        {
            // Five wall slabs, four columns, seven floor-standing crates. The
            // eighth crate is stacked on the third and adds no footprint.
            assertThat(ROOM.solidCount()).isEqualTo(16);
            assertThat(ROOM.halfWidth()).isEqualTo(PhysicsWorld.PLAYER_HALF_WIDTH_UNITS);
            assertThat(ROOM.isBlocked(0.0f, -192.0f))
                .as("the demo spawns the player at (0, -192)")
                .isFalse();
        }

        @Test
        @DisplayName("the collision half-width is the same 16 the scene sizes bodies with")
        void shouldAgreeWithTheSceneAboutTheBodyRadius()
        {
            // Two constants derived from Constants.PLAYER_RADIUS by two
            // different classes. If they ever disagree, what you shoot and what
            // you bump into are different sizes and no screenshot shows it.
            assertThat(PhysicsWorld.PLAYER_HALF_WIDTH_UNITS)
                .isEqualTo(DemoScene.PLAYER_RADIUS_UNITS);
        }

        @Test
        @DisplayName("the generated fallback room is sealed")
        void shouldSealTheGeneratedFallbackRoom()
        {
            // The room a fresh clone with no art staged gets. It really is a
            // closed box - ProceduralRoom models no doorway - so here sealing
            // the player in is the correct answer rather than a shortcut.
            final PhysicsWorld fallback = DemoScene.fallbackRoomPhysics();
            final PlayerController player =
                new PlayerController(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, fallback);

            for (int tic = 0; tic < 300; tic++)
            {
                player.update(new Input(1.0f, 0.0f), TIC_60HZ);
            }

            assertThat(fallback.solidCount()).isEqualTo(4);
            assertThat(player.positionZ()).isLessThan(256.0f);
        }
    }

    @Nested
    @DisplayName("the contract a permitted move must keep")
    class ThePermittedMove
    {
        @Test
        @DisplayName("the permitted move never exceeds the requested one")
        void shouldNeverPermitMoreThanWasAsked()
        {
            // Swept across the whole room in both directions from a range of
            // starting points, because the property has to hold everywhere and
            // not only where a wall happens to be.
            for (int start = -300; start <= 300; start += 10)
            {
                for (int step = -40; step <= 40; step += 5)
                {
                    final float from = start;
                    final float delta = step;
                    final float permittedX = ROOM.slideX(from, from, delta);
                    final float permittedZ = ROOM.slideZ(from, from, delta);

                    assertThat(Math.abs(permittedX - from))
                        .as("x from %s by %s", Float.valueOf(from), Float.valueOf(delta))
                        .isLessThanOrEqualTo(Math.abs(delta));
                    assertThat(Math.abs(permittedZ - from))
                        .as("z from %s by %s", Float.valueOf(from), Float.valueOf(delta))
                        .isLessThanOrEqualTo(Math.abs(delta));
                }
            }
        }

        @Test
        @DisplayName("a permitted move never lands the body inside a solid")
        void shouldNeverPermitAMoveThatEndsInsideASolid()
        {
            for (int start = -300; start <= 300; start += 7)
            {
                for (int step = -30; step <= 30; step += 3)
                {
                    final float from = start;
                    final float delta = step;
                    if (ROOM.isBlocked(from, from))
                    {
                        // The invariant is that a LEGAL position stays legal.
                        // A body that starts embedded is deliberately allowed to
                        // walk out through the solid it is already in, so it has
                        // nothing to say here.
                        continue;
                    }
                    final float permittedX = ROOM.slideX(from, from, delta);
                    final float permittedZ = ROOM.slideZ(permittedX, from, delta);

                    assertThat(ROOM.isBlocked(permittedX, permittedZ))
                        .as("from (%s, %s) by %s", Float.valueOf(from), Float.valueOf(from),
                            Float.valueOf(delta))
                        .isFalse();
                }
            }
        }

        @Test
        @DisplayName("repeating the same blocked input changes nothing at all")
        void shouldBeIdempotentWhenTheSameInputRepeatsAgainstAWall()
        {
            final PlayerController player =
                new PlayerController(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, ROOM);
            for (int tic = 0; tic < 300; tic++)
            {
                player.update(new Input(1.0f, 0.0f), TIC_60HZ);
            }
            final int settledX = Float.floatToRawIntBits(player.positionX());
            final int settledZ = Float.floatToRawIntBits(player.positionZ());

            for (int tic = 0; tic < 120; tic++)
            {
                player.update(new Input(1.0f, 0.0f), TIC_60HZ);
            }

            // Bit-exact, not within a tolerance. A body resting against a wall
            // that creeps by an ulp a tic is a body that has desynced two peers
            // by the end of a match, and a tolerance is exactly what would hide
            // it.
            assertThat(Float.floatToRawIntBits(player.positionX())).isEqualTo(settledX);
            assertThat(Float.floatToRawIntBits(player.positionZ())).isEqualTo(settledZ);
        }

        @Test
        @DisplayName("a zero move returns the position bit for bit")
        void shouldReturnThePositionUnchangedWhenNothingIsAsked()
        {
            assertThat(ROOM.slideX(123.5f, -45.25f, 0.0f)).isEqualTo(123.5f);
            assertThat(ROOM.slideZ(123.5f, -45.25f, 0.0f)).isEqualTo(-45.25f);
        }

        @Test
        @DisplayName("a body already inside a solid can walk out of it")
        void shouldLetAnEmbeddedBodyEscapeRatherThanPinIt()
        {
            final PhysicsWorld world = oneBox();

            // Standing at the box's centre - an impossible position that a bad
            // spawn or a future teleport could still produce. Both directions
            // must stay open: resolving it would teleport the body to a face,
            // and choosing the wrong face would put it outside the level.
            assertThat(world.slideX(150.0f, 150.0f, 10.0f)).isEqualTo(160.0f);
            assertThat(world.slideX(150.0f, 150.0f, -10.0f)).isEqualTo(140.0f);
        }

        @Test
        @DisplayName("the open world permits everything")
        void shouldPermitEveryMoveWhenTheWorldIsOpen()
        {
            assertThat(PhysicsWorld.OPEN.slideX(1.0f, 2.0f, 1.0e6f)).isEqualTo(1.0f + 1.0e6f);
            assertThat(PhysicsWorld.OPEN.slideZ(1.0f, 2.0f, -1.0e6f)).isEqualTo(2.0f - 1.0e6f);
            assertThat(PhysicsWorld.OPEN.isBlocked(0.0f, 0.0f)).isFalse();
            assertThat(PhysicsWorld.OPEN.solidCount()).isZero();
        }

        @Test
        @DisplayName("a controller with no world moves as it did before collision existed")
        void shouldMatchTheUnclippedControllerWhenNoWorldIsGiven()
        {
            final PlayerController open = new PlayerController(0.0f, 0.0f, 0.0f, 0.3f, 0.0f);
            final PlayerController explicit =
                new PlayerController(0.0f, 0.0f, 0.0f, 0.3f, 0.0f, PhysicsWorld.OPEN);

            for (int tic = 0; tic < 200; tic++)
            {
                open.update(new Input(1.0f, -0.5f), TIC_60HZ);
                explicit.update(new Input(1.0f, -0.5f), TIC_60HZ);
            }

            assertThat(Float.floatToRawIntBits(explicit.positionX()))
                .isEqualTo(Float.floatToRawIntBits(open.positionX()));
            assertThat(Float.floatToRawIntBits(explicit.positionZ()))
                .isEqualTo(Float.floatToRawIntBits(open.positionZ()));
        }
    }

    @Nested
    @DisplayName("the floor still holds")
    class TheFloor
    {
        @Test
        @DisplayName("a player pressed against a wall does not fall through the floor")
        void shouldKeepThePlayerOnTheFloorWhileBlockedHorizontally()
        {
            final PlayerController player =
                new PlayerController(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, ROOM);

            for (int tic = 0; tic < 600; tic++)
            {
                player.update(new Input(1.0f, 0.0f), TIC_60HZ);
            }

            assertThat(player.positionY()).isEqualTo(PlayerController.GROUND_LEVEL_UNITS);
            assertThat(player.isOnGround()).isTrue();
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        @DisplayName("a null box table is rejected")
        void shouldRejectANullTable()
        {
            assertThatThrownBy(() -> new PhysicsWorld(HALF, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
        }

        @Test
        @DisplayName("a table that is not a whole number of boxes is rejected")
        void shouldRejectARaggedTable()
        {
            assertThatThrownBy(() -> new PhysicsWorld(HALF, new float[]
            {
                0.0f, 0.0f, 1.0f,
            }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole number");
        }

        @Test
        @DisplayName("an inside-out box is rejected rather than left silently permeable")
        void shouldRejectAnInsideOutBox()
        {
            assertThatThrownBy(() -> PhysicsWorld.builder(HALF)
                .addBox(200.0f, 0.0f, 100.0f, 100.0f)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not below max");
        }

        @Test
        @DisplayName("a NaN half-width is rejected")
        void shouldRejectANotANumberHalfWidth()
        {
            assertThatThrownBy(() -> PhysicsWorld.builder(Float.NaN).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite and non-negative");
        }

        @Test
        @DisplayName("a null world is rejected by the controller")
        void shouldRejectANullWorldOnTheController()
        {
            assertThatThrownBy(
                () -> new PlayerController(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PhysicsWorld.OPEN");
        }

        @Test
        @DisplayName("the builder grows past its initial capacity")
        void shouldGrowWhenMoreBoxesAreAddedThanItStartedWith()
        {
            final PhysicsWorld.Builder builder = PhysicsWorld.builder(HALF);
            for (int index = 0; index < 40; index++)
            {
                builder.addBoxAt(index * 100.0f, 0.0f, 10.0f, 10.0f);
            }

            assertThat(builder.build().solidCount()).isEqualTo(40);
        }

        @Test
        @DisplayName("a solid can be read back exactly as it was added")
        void shouldReportTheBoxesItWasGiven()
        {
            final PhysicsWorld world =
                PhysicsWorld.builder(HALF).addBoxAt(50.0f, -50.0f, 10.0f, 20.0f).build();

            assertThat(world.solid(0)).containsExactly(40.0f, -70.0f, 60.0f, -30.0f);
            assertThatThrownBy(() -> world.solid(1))
                .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism
    {
        @Test
        @DisplayName("two peers clipping the same script agree bit for bit")
        void shouldProduceBitIdenticalStateWhenTheSameScriptIsReplayed()
        {
            final PlayerController peerA =
                new PlayerController(0.0f, 0.0f, -192.0f, 0.4f, 0.0f, ROOM);
            final PlayerController peerB =
                new PlayerController(0.0f, 0.0f, -192.0f, 0.4f, 0.0f, ROOM);

            for (int tic = 0; tic < 1000; tic++)
            {
                final float forward = (float) ((tic % 7) - 3) / 3.0f;
                final float strafe = (float) ((tic % 5) - 2) / 2.0f;
                peerA.update(new Input(forward, strafe), TIC_60HZ);
                peerB.update(new Input(forward, strafe), TIC_60HZ);
            }

            assertThat(Float.floatToRawIntBits(peerB.positionX()))
                .isEqualTo(Float.floatToRawIntBits(peerA.positionX()));
            assertThat(Float.floatToRawIntBits(peerB.positionZ()))
                .isEqualTo(Float.floatToRawIntBits(peerA.positionZ()));
        }

        @Test
        @DisplayName("no java/lang/Math reference appears anywhere in the compiled class")
        void shouldNotReferenceMathWhenCompiled()
        {
            // The same guard PlayerController carries, for the same reason: this
            // class runs inside the lockstep simulation, so anything in it that
            // a JVM is permitted to round differently is a silent desync.
            // Nothing here needs a transcendental, so the rule is absolute
            // rather than "StrictMath only" - no sqrt, no abs, no min.
            assertThat(constantPoolOf(PhysicsWorld.class))
                .as("PhysicsWorld must not reference java.lang.Math anywhere")
                .doesNotContain("java/lang/Math");
        }
    }

    // The compiled class file as text - enough to see which types it names.
    // Reading the constant pool is the only check a plausible-looking edit
    // cannot defeat.
    private static String constantPoolOf(final Class<?> type)
    {
        final String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource))
        {
            if (in == null)
            {
                throw new IllegalStateException("could not read " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
        catch (final IOException e)
        {
            throw new IllegalStateException("could not read " + resource, e);
        }
    }
}
