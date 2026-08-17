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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.openfps.engine.common.Constants;
import com.openfps.engine.gameplay.port.I_PlayerInput;
import com.openfps.engine.render.adapter.Camera;
import com.openfps.engine.render.adapter.Vec3;

/**
 * Tests for {@link PlayerController}.
 *
 * <p>Every assertion reads the controller's own constants rather than restating
 * their literal values, so forking {@code MOVE_SPEED_UNITS_PER_SECOND} or
 * {@code EYE_HEIGHT_UNITS} breaks the behaviour tests rather than silently
 * passing them ({@code STYLE.md} § 13.3).</p>
 *
 * <p>Two tolerances are used deliberately. Direction assertions use
 * {@link #EPSILON}, because a cardinal yaw such as pi/2 is not exactly
 * representable in {@code float} and so {@code cos(yaw)} is about -4.4e-8
 * rather than 0. Determinism and linearity assertions use exact equality —
 * those are the properties the class exists to guarantee, and a tolerance there
 * would hide exactly the bug being hunted.</p>
 */
@DisplayName("PlayerController")
class PlayerControllerTest
{
    /**
     * Absolute tolerance for world-space direction and coordinate assertions.
     * Displacements here are on the order of 256 units, and the largest error
     * from an inexact cardinal angle is about 256 * 4.4e-8 = 1.1e-5, so 1e-3
     * has three orders of magnitude of headroom while still failing on any
     * real basis or sign error.
     */
    private static final float EPSILON = 1.0e-3f;

    private static final float SPEED = PlayerController.MOVE_SPEED_UNITS_PER_SECOND;
    private static final float QUARTER_TURN = PlayerController.FULL_TURN_RADIANS * 0.25f;
    private static final float HALF_TURN = PlayerController.FULL_TURN_RADIANS * 0.5f;
    private static final float THREE_QUARTER_TURN = PlayerController.FULL_TURN_RADIANS * 0.75f;

    /** One second, so that a displacement is numerically the speed. */
    private static final float ONE_SECOND = 1.0f;

    /** A representative tic duration: 60 Hz. */
    private static final float TIC_60HZ = 1.0f / 60.0f;

    /**
     * Immutable {@link I_PlayerInput} for tests.
     *
     * Deliberately minimal and deliberately not shared with production code —
     * the whole point of {@code I_PlayerInput} is that the controller consumes
     * four floats and nothing else, and this proves it.
     */
    private static final class Input implements I_PlayerInput
    {
        private final float forward;
        private final float strafe;
        private final float yaw;
        private final float pitch;
        private final boolean jumping;
        private final boolean sprinting;

        private Input(final float forward, final float strafe, final float yaw, final float pitch)
        {
            this(forward, strafe, yaw, pitch, false, false);
        }

        private Input(final float forward, final float strafe, final float yaw, final float pitch,
            final boolean jump)
        {
            this(forward, strafe, yaw, pitch, jump, false);
        }

        private Input(final float forward, final float strafe, final float yaw, final float pitch,
            final boolean jump, final boolean sprint)
        {
            this.forward = forward;

            this.strafe = strafe;

            this.yaw = yaw;

            this.pitch = pitch;

            this.jumping = jump;

            this.sprinting = sprint;
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
            return yaw;
        }

        @Override
        public float pitchDelta()
        {
            return pitch;
        }

        @Override
        public boolean jump()
        {
            return jumping;
        }

        @Override
        public boolean sprint()
        {
            return sprinting;
        }
    }

    private static final Input NONE = new Input(0.0f, 0.0f, 0.0f, 0.0f);

    private static final Input JUMP = new Input(0.0f, 0.0f, 0.0f, 0.0f, true);

    private static Input move(final float forward, final float strafe)
    {
        return new Input(forward, strafe, 0.0f, 0.0f);
    }

    private static Input moveSprinting(final float forward, final float strafe)
    {
        return new Input(forward, strafe, 0.0f, 0.0f, false, true);
    }

    private static Input look(final float yaw, final float pitch)
    {
        return new Input(0.0f, 0.0f, yaw, pitch);
    }

    private static PlayerController facing(final float yaw)
    {
        return new PlayerController(0.0f, 0.0f, 0.0f, yaw, 0.0f);
    }

    // Horizontal distance travelled from the origin.
    private static float horizontalDistance(final PlayerController player)
    {
        final float x = player.positionX();

        final float z = player.positionZ();

        return (float) StrictMath.sqrt(x * x + z * z);
    }

    @Nested
    @DisplayName("construction")
    class Construction
    {
        @Test
        @DisplayName("default constructor stands at the origin facing world +z, level")
        void shouldStandAtOriginFacingPositiveZWhenDefaultConstructed()
        {
            final PlayerController player = new PlayerController();

            assertThat(player.positionX()).isEqualTo(0.0f);

            assertThat(player.positionY()).isEqualTo(0.0f);

            assertThat(player.positionZ()).isEqualTo(0.0f);

            assertThat(player.yawRadians()).isEqualTo(0.0f);

            assertThat(player.pitchRadians()).isEqualTo(0.0f);

            assertThat(player.forwardVector().z()).isCloseTo(1.0f, within(EPSILON));
        }

        @Test
        @DisplayName("spawn placement is kept verbatim when already legal")
        void shouldKeepSpawnPlacementWhenWithinLimits()
        {
            final PlayerController player =
                new PlayerController(10.0f, -3.0f, 7.5f, 1.25f, 0.5f);

            assertThat(player.positionX()).isEqualTo(10.0f);

            assertThat(player.positionY()).isEqualTo(-3.0f);

            assertThat(player.positionZ()).isEqualTo(7.5f);

            assertThat(player.yawRadians()).isEqualTo(1.25f);

            assertThat(player.pitchRadians()).isEqualTo(0.5f);
        }

        @Test
        @DisplayName("spawn yaw is wrapped and spawn pitch clamped, so no illegal state exists")
        void shouldNormalizeSpawnAnglesWhenOutOfRange()
        {
            final PlayerController player = new PlayerController(
                0.0f, 0.0f, 0.0f,
                PlayerController.FULL_TURN_RADIANS * 3.0f + 1.0f,
                HALF_TURN);

            assertThat(player.yawRadians())
                .isGreaterThanOrEqualTo(0.0f)
                .isLessThan(PlayerController.FULL_TURN_RADIANS);

            assertThat(player.yawRadians()).isCloseTo(1.0f, within(EPSILON));

            assertThat(player.pitchRadians()).isEqualTo(PlayerController.MAX_PITCH_RADIANS);
        }

        @Test
        @DisplayName("move speed is derived from Constants.PLAYER_SPEED, not restated")
        void shouldDeriveMoveSpeedFromSharedConstantWhenComputingUnitsPerSecond()
        {
            // PLAYER_SPEED is 16.16 units per tic at 120 Hz. If someone forks
            // the shared constant, this is the assertion that notices.
            final float expected =
                (float) Constants.PLAYER_SPEED / (float) Constants.MAP_SCALE * 120.0f;

            assertThat(SPEED).isEqualTo(expected);

            assertThat(SPEED).isCloseTo(256.0f, within(0.1f));
        }
    }

    @Nested
    @DisplayName("pitch clamp")
    class PitchClamp
    {
        @Test
        @DisplayName("the limit is 89 degrees, not 90 — 90 degenerates the camera basis")
        void shouldLimitPitchToEightyNineDegreesWhenExpressedInRadians()
        {
            assertThat(PlayerController.PITCH_LIMIT_DEGREES).isEqualTo(89.0f);

            assertThat(PlayerController.MAX_PITCH_RADIANS)
                .isCloseTo((float) StrictMath.toRadians(89.0), within(1.0e-6f));
        }

        @Test
        @DisplayName("a forward vector at exactly 90 degrees is rejected by Camera.create")
        void shouldRejectStraightUpForwardWhenBuildingACameraBasis()
        {
            // This is the whole reason the clamp exists, asserted against the
            // real Camera rather than described in a comment.
            assertThatThrownBy(() -> Camera.create(
                    Vec3.ZERO, PlayerController.WORLD_UP, PlayerController.WORLD_UP,
                    PlayerController.DEFAULT_FOV_Y_RADIANS, 1.0f,
                    PlayerController.DEFAULT_NEAR_PLANE_UNITS))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("clamps at the upper limit under one large input")
        void shouldClampPitchAtUpperLimitWhenLookingFarUp()
        {
            final PlayerController player = new PlayerController();

            player.update(look(0.0f, 10.0f), TIC_60HZ);

            assertThat(player.pitchRadians()).isEqualTo(PlayerController.MAX_PITCH_RADIANS);
        }

        @Test
        @DisplayName("clamps at the lower limit under one large input")
        void shouldClampPitchAtLowerLimitWhenLookingFarDown()
        {
            final PlayerController player = new PlayerController();

            player.update(look(0.0f, -10.0f), TIC_60HZ);

            assertThat(player.pitchRadians()).isEqualTo(-PlayerController.MAX_PITCH_RADIANS);
        }

        @Test
        @DisplayName("does not creep past the upper limit under repeated input")
        void shouldNotCreepPastUpperLimitWhenLookingUpRepeatedly()
        {
            final PlayerController player = new PlayerController();

            for (int i = 0; i < 500; i++)
            {
                player.update(look(0.0f, 0.05f), TIC_60HZ);

                assertThat(player.pitchRadians())
                    .isLessThanOrEqualTo(PlayerController.MAX_PITCH_RADIANS);
            }

            assertThat(player.pitchRadians()).isEqualTo(PlayerController.MAX_PITCH_RADIANS);
        }

        @Test
        @DisplayName("does not creep past the lower limit under repeated input")
        void shouldNotCreepPastLowerLimitWhenLookingDownRepeatedly()
        {
            final PlayerController player = new PlayerController();

            for (int i = 0; i < 500; i++)
            {
                player.update(look(0.0f, -0.05f), TIC_60HZ);

                assertThat(player.pitchRadians())
                    .isGreaterThanOrEqualTo(-PlayerController.MAX_PITCH_RADIANS);
            }

            assertThat(player.pitchRadians()).isEqualTo(-PlayerController.MAX_PITCH_RADIANS);
        }

        @Test
        @DisplayName("releases from the clamp immediately when looking back the other way")
        void shouldLeaveTheClampWhenLookingBackDown()
        {
            final PlayerController player = new PlayerController();

            player.update(look(0.0f, 10.0f), TIC_60HZ);

            player.update(look(0.0f, -0.5f), TIC_60HZ);

            assertThat(player.pitchRadians())
                .isEqualTo(PlayerController.MAX_PITCH_RADIANS - 0.5f);
        }

        @Test
        @DisplayName("pitch inside the range is applied verbatim")
        void shouldApplyPitchVerbatimWhenInsideTheRange()
        {
            final PlayerController player = new PlayerController();

            player.update(look(0.0f, 0.25f), TIC_60HZ);

            player.update(look(0.0f, 0.25f), TIC_60HZ);

            assertThat(player.pitchRadians()).isEqualTo(0.5f);
        }

        @Test
        @DisplayName("a camera can still be built at either pitch extreme")
        void shouldBuildACameraWhenPitchIsAtEitherExtreme()
        {
            final PlayerController up = new PlayerController();

            up.update(look(0.0f, 10.0f), TIC_60HZ);

            final PlayerController down = new PlayerController();

            down.update(look(0.0f, -10.0f), TIC_60HZ);

            assertThat(up.camera(16.0f / 9.0f)).isNotNull();

            assertThat(down.camera(16.0f / 9.0f)).isNotNull();
        }
    }

    /**
     * The sign of a turn, stated in terms nothing here can define away.
     *
     * <p>Every other yaw test in this file is satisfied by a controller that
     * turns the wrong way, because they all compare a yaw against another yaw
     * and a mirror is self-consistent. These compare the turn against the
     * player's OWN right-hand direction, which the movement tests pin
     * independently — so a controller that turns the wrong way has to disagree
     * with its own strafe to pass, and it cannot.</p>
     *
     * <p>They exist because the sign was wrong, in shipped code, on both
     * platforms, until someone dragged a thumb across an emulator and watched
     * the room slide the wrong way.</p>
     */
    @Nested
    @DisplayName("turn direction")
    class TurnDirection
    {
        @Test
        @DisplayName("a positive yaw delta turns the view toward the player's own right")
        void shouldFaceTheOldRightAfterAQuarterTurnRight()
        {
            final PlayerController player = facing(0.0f);

            final Vec3 rightBefore = player.groundRightVector();

            player.update(look(QUARTER_TURN, 0.0f), TIC_60HZ);

            // A quarter turn to the right puts the nose exactly where the
            // right hand was pointing. No renderer, no basis, no sign
            // convention needed to read this.
            assertThat(player.groundForwardVector().x())
                .isCloseTo(rightBefore.x(), within(EPSILON));

            assertThat(player.groundForwardVector().z())
                .isCloseTo(rightBefore.z(), within(EPSILON));
        }

        @Test
        @DisplayName("turning right then walking forward goes where strafing right went")
        void shouldWalkWhereStrafeWentAfterTurningRight()
        {
            final PlayerController strafed = facing(0.0f);

            strafed.update(move(0.0f, 1.0f), ONE_SECOND);

            final PlayerController turned = facing(0.0f);

            turned.update(look(QUARTER_TURN, 0.0f), TIC_60HZ);

            turned.update(move(1.0f, 0.0f), ONE_SECOND);

            // The two ways of getting to the same place. If look and strafe
            // disagreed about which way "right" is, these would land on
            // opposite sides of the origin — which is exactly the failure the
            // Android emulator showed as a camera that panned backwards.
            assertThat(turned.positionX()).isCloseTo(strafed.positionX(), within(EPSILON));

            assertThat(turned.positionZ()).isCloseTo(strafed.positionZ(), within(EPSILON));
        }

        @Test
        @DisplayName("a negative yaw delta turns the other way")
        void shouldFaceTheOldLeftAfterAQuarterTurnLeft()
        {
            final PlayerController player = facing(0.0f);

            final Vec3 rightBefore = player.groundRightVector();

            player.update(look(-QUARTER_TURN, 0.0f), TIC_60HZ);

            assertThat(player.groundForwardVector().x())
                .isCloseTo(-rightBefore.x(), within(EPSILON));

            assertThat(player.groundForwardVector().z())
                .isCloseTo(-rightBefore.z(), within(EPSILON));
        }
    }

    @Nested
    @DisplayName("look direction — which way a turn actually goes")
    class LookDirection
    {
        /**
         * A turn big enough to be unambiguous and small enough to stay well
         * inside a quarter circle, so "is the new heading on the right-hand
         * side" is a question with an obvious answer.
         */
        private static final float QUARTER_TURN = 0.5f;

        /**
         * <b>Every assertion in this nest measures a turn against the player's
         * own STRAFE DISPLACEMENT, and nothing here compares one angle with
         * another.</b>
         *
         * <p>That is not a stylistic choice. The horizontal look axis shipped
         * inverted twice, and every yaw test in this file passed both times,
         * because a mirrored heading is <i>self-consistent</i>: the forward
         * vector, the strafe basis and the rendered frame all derive from the
         * same yaw, so they agree with each other while all being wrong — which
         * is exactly what {@code PlayerController}'s class Javadoc warns about
         * for {@code groundRight}. An assertion of the form "yaw went up by the
         * delta" is satisfied by both signs of the bug and by neither
         * definition of right.</p>
         *
         * <p>Walking is the independent reference. {@code strafeAxis > 0} is
         * documented as "to the player's right" and is integrated through
         * {@code applyMove}, which never touches {@link I_PlayerInput#yawDelta}.
         * So: note where a strafe-right step goes, turn right, and check the new
         * heading leans that way. Flip the sign in {@code applyLook} and this
         * fails; flip it in an input adapter instead and this still fails,
         * because the controller is what it tests.</p>
         */
        private static Vec3 strafeRightStep(final float yaw)
        {
            final PlayerController walker = facing(yaw);

            walker.update(move(0.0f, 1.0f), TIC_60HZ);

            return walker.feetPosition();
        }

        @Test
        @DisplayName("a positive yaw delta turns the view toward where strafe-right walks")
        void shouldTurnTowardTheStrafeRightDirection()
        {
            final Vec3 right = strafeRightStep(0.0f);

            final PlayerController player = facing(0.0f);

            player.update(look(QUARTER_TURN, 0.0f), TIC_60HZ);

            assertThat(player.groundForwardVector().dot(right))
                .as("the new heading must lean the way a right-strafe step went")
                .isPositive();
        }

        @Test
        @DisplayName("a negative yaw delta turns the other way")
        void shouldTurnAwayFromTheStrafeRightDirection()
        {
            final Vec3 right = strafeRightStep(0.0f);

            final PlayerController player = facing(0.0f);

            player.update(look(-QUARTER_TURN, 0.0f), TIC_60HZ);

            assertThat(player.groundForwardVector().dot(right)).isNegative();
        }

        @ParameterizedTest
        @CsvSource({"0.0", "0.7", "1.9", "3.4", "5.8"})
        @DisplayName("it turns toward strafe-right from every starting heading")
        void shouldTurnRightFromAnyHeading(final float startYaw)
        {
            // A sign error that happened to cancel at yaw 0 would be a very
            // strange one, but a basis error would not — a mirror about one
            // world axis leaves that axis alone. Sweeping the circle rules it
            // out at no cost.
            final Vec3 right = strafeRightStep(startYaw);

            final Vec3 origin = facing(startYaw).feetPosition();

            final PlayerController player = facing(startYaw);

            player.update(look(QUARTER_TURN, 0.0f), TIC_60HZ);

            final Vec3 rightward = new Vec3(right.x() - origin.x(), 0.0f,
                right.z() - origin.z());

            assertThat(player.groundForwardVector().dot(rightward))
                .as("starting at yaw %s", Float.valueOf(startYaw))
                .isPositive();
        }

        @Test
        @DisplayName("turning right then strafing right walks BACKWARDS relative to the "
            + "original facing")
        void shouldEndUpBehindTheOriginalHeadingAfterTurningRight()
        {
            // The same fact stated as something a player would notice: after a
            // right turn, "right" is where you were facing and "forward" is
            // where right used to be. Purely positional — not one angle is read.
            final PlayerController player = new PlayerController();

            player.update(look((float) (Math.PI / 2.0), 0.0f), TIC_60HZ);

            player.update(move(1.0f, 0.0f), TIC_60HZ);

            // Started facing +z; a right turn faces -x, so forward walks -x.
            assertThat(player.positionX()).isNegative();

            assertThat(player.positionZ()).isCloseTo(0.0f, within(1.0e-4f));
        }

        @Test
        @DisplayName("a full turn's worth of rightward look comes back to the same heading")
        void shouldReturnToTheStartAfterAFullRightTurn()
        {
            final PlayerController player = facing(1.25f);

            final Vec3 before = player.groundForwardVector();

            player.update(look(PlayerController.FULL_TURN_RADIANS, 0.0f), TIC_60HZ);

            final Vec3 after = player.groundForwardVector();

            assertThat(after.x()).isCloseTo(before.x(), within(EPSILON));

            assertThat(after.z()).isCloseTo(before.z(), within(EPSILON));
        }
    }

    @Nested
    @DisplayName("yaw wrap")
    class YawWrap
    {
        // Every expectation in this nest is the NEGATIVE of the delta fed in,
        // and that is the point rather than an oddity: a look delta is
        // "positive turns right" and this angle increases leftward, so update()
        // subtracts. These tests are about the wrap and cannot see the sign —
        // they passed with it either way round — so LookDirection and
        // TurnDirection carry that.

        @Test
        @DisplayName("stays inside [0, 2pi) after a large rightward turn")
        void shouldWrapYawIntoRangeWhenTurningFarPositive()
        {
            final PlayerController player = new PlayerController();

            player.update(
                look(-(PlayerController.FULL_TURN_RADIANS * 5.0f + 2.0f), 0.0f), TIC_60HZ);

            assertThat(player.yawRadians())
                .isGreaterThanOrEqualTo(0.0f)
                .isLessThan(PlayerController.FULL_TURN_RADIANS);

            assertThat(player.yawRadians()).isCloseTo(2.0f, within(EPSILON));
        }

        @Test
        @DisplayName("stays inside [0, 2pi) after a large negative turn")
        void shouldWrapYawIntoRangeWhenTurningFarNegative()
        {
            final PlayerController player = new PlayerController();

            player.update(
                look(PlayerController.FULL_TURN_RADIANS * 5.0f + 2.0f, 0.0f), TIC_60HZ);

            assertThat(player.yawRadians())
                .isGreaterThanOrEqualTo(0.0f)
                .isLessThan(PlayerController.FULL_TURN_RADIANS);

            assertThat(player.yawRadians())
                .isCloseTo(PlayerController.FULL_TURN_RADIANS - 2.0f, within(EPSILON));
        }

        @Test
        @DisplayName("stays inside [0, 2pi) after a large leftward turn")
        void shouldWrapYawIntoRangeWhenTurningFarLeft()
        {
            final PlayerController player = new PlayerController();

            player.update(look(-PlayerController.FULL_TURN_RADIANS * 5.0f - 2.0f, 0.0f), TIC_60HZ);

            assertThat(player.yawRadians())
                .isGreaterThanOrEqualTo(0.0f)
                .isLessThan(PlayerController.FULL_TURN_RADIANS);

            assertThat(player.yawRadians()).isCloseTo(2.0f, within(EPSILON));
        }

        @Test
        @DisplayName("crosses the 2pi boundary upward without a discontinuity")
        void shouldWrapAcrossUpperBoundaryWhenTurningLeft()
        {
            // Leftward, because that is the direction this angle increases in.
            final PlayerController player =
                facing(PlayerController.FULL_TURN_RADIANS - 0.1f);

            player.update(look(-0.2f, 0.0f), TIC_60HZ);

            assertThat(player.yawRadians()).isCloseTo(0.1f, within(EPSILON));
        }

        @Test
        @DisplayName("crosses the 0 boundary downward without a discontinuity")
        void shouldWrapAcrossLowerBoundaryWhenTurningRight()
        {
            final PlayerController player = facing(0.1f);

            player.update(look(0.2f, 0.0f), TIC_60HZ);

            assertThat(player.yawRadians())
                .isCloseTo(PlayerController.FULL_TURN_RADIANS - 0.1f, within(EPSILON));
        }

        @Test
        @DisplayName("wrapping does not change the forward vector")
        void shouldKeepForwardVectorUnchangedWhenYawWrapsAroundZero()
        {
            // The one place the wrap could be observed is the direction it
            // produces. It must not be.
            final PlayerController unwrapped = facing(0.3f);

            final PlayerController wrapped = facing(0.3f);

            wrapped.update(look(PlayerController.FULL_TURN_RADIANS, 0.0f), TIC_60HZ);

            final Vec3 a = unwrapped.forwardVector();

            final Vec3 b = wrapped.forwardVector();

            assertThat(b.x()).isCloseTo(a.x(), within(EPSILON));

            assertThat(b.y()).isCloseTo(a.y(), within(EPSILON));

            assertThat(b.z()).isCloseTo(a.z(), within(EPSILON));
        }

        @Test
        @DisplayName("wrapping does not change the movement direction")
        void shouldKeepMovementDirectionUnchangedWhenYawWraps()
        {
            final PlayerController unwrapped = facing(1.1f);

            final PlayerController wrapped = facing(1.1f);

            wrapped.update(look(-PlayerController.FULL_TURN_RADIANS, 0.0f), TIC_60HZ);

            unwrapped.update(move(1.0f, 0.0f), ONE_SECOND);

            wrapped.update(move(1.0f, 0.0f), ONE_SECOND);

            assertThat(wrapped.positionX()).isCloseTo(unwrapped.positionX(), within(EPSILON));

            assertThat(wrapped.positionZ()).isCloseTo(unwrapped.positionZ(), within(EPSILON));
        }

        @Test
        @DisplayName("many small turns totalling ten revolutions land back near the start")
        void shouldAccumulateSmallTurnsWithoutDriftWhenSpinningManyRevolutions()
        {
            final PlayerController player = new PlayerController();

            final float stepAngle = PlayerController.FULL_TURN_RADIANS / 360.0f;

            for (int i = 0; i < 3600; i++)
            {
                player.update(look(stepAngle, 0.0f), TIC_60HZ);

                assertThat(player.yawRadians())
                    .isGreaterThanOrEqualTo(0.0f)
                    .isLessThan(PlayerController.FULL_TURN_RADIANS);
            }

            assertThat(player.forwardVector().z()).isCloseTo(1.0f, within(1.0e-2f));
        }

        @Test
        @DisplayName("a yaw already in range is left bit-identical")
        void shouldLeaveYawBitIdenticalWhenAlreadyInRange()
        {
            final PlayerController player = facing(4.2f);

            final int before = Float.floatToRawIntBits(player.yawRadians());

            player.update(NONE, TIC_60HZ);

            assertThat(Float.floatToRawIntBits(player.yawRadians())).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("movement axes")
    class MovementAxes
    {
        @Test
        @DisplayName("forward at yaw 0 walks along world +z")
        void shouldWalkAlongPositiveZWhenFacingYawZero()
        {
            final PlayerController player = facing(0.0f);

            player.update(move(1.0f, 0.0f), ONE_SECOND);

            assertThat(player.positionX()).isCloseTo(0.0f, within(EPSILON));

            assertThat(player.positionZ()).isCloseTo(SPEED, within(EPSILON));
        }

        @Test
        @DisplayName("forward at yaw 90 degrees walks along world +x")
        void shouldWalkAlongPositiveXWhenFacingQuarterTurn()
        {
            final PlayerController player = facing(QUARTER_TURN);

            player.update(move(1.0f, 0.0f), ONE_SECOND);

            assertThat(player.positionX()).isCloseTo(SPEED, within(EPSILON));

            assertThat(player.positionZ()).isCloseTo(0.0f, within(EPSILON));
        }

        @Test
        @DisplayName("forward at yaw 180 degrees walks along world -z")
        void shouldWalkAlongNegativeZWhenFacingHalfTurn()
        {
            final PlayerController player = facing(HALF_TURN);

            player.update(move(1.0f, 0.0f), ONE_SECOND);

            assertThat(player.positionX()).isCloseTo(0.0f, within(EPSILON));

            assertThat(player.positionZ()).isCloseTo(-SPEED, within(EPSILON));
        }

        @Test
        @DisplayName("forward at yaw 270 degrees walks along world -x")
        void shouldWalkAlongNegativeXWhenFacingThreeQuarterTurn()
        {
            final PlayerController player = facing(THREE_QUARTER_TURN);

            player.update(move(1.0f, 0.0f), ONE_SECOND);

            assertThat(player.positionX()).isCloseTo(-SPEED, within(EPSILON));

            assertThat(player.positionZ()).isCloseTo(0.0f, within(EPSILON));
        }

        @Test
        @DisplayName("backwards at yaw 0 walks along world -z")
        void shouldWalkAlongNegativeZWhenMovingBackwardsAtYawZero()
        {
            final PlayerController player = facing(0.0f);

            player.update(move(-1.0f, 0.0f), ONE_SECOND);

            assertThat(player.positionX()).isCloseTo(0.0f, within(EPSILON));

            assertThat(player.positionZ()).isCloseTo(-SPEED, within(EPSILON));
        }

        @Test
        @DisplayName("strafe right at yaw 0 walks along world -x, matching forward x up")
        void shouldStrafeAlongNegativeXWhenFacingYawZero()
        {
            // Facing +z (south, with +x east and +y up), the player's right
            // hand points west, which is -x. The mirror of this convention is
            // what render/README.md § 4 was corrected twice over.
            final PlayerController player = facing(0.0f);

            player.update(move(0.0f, 1.0f), ONE_SECOND);

            assertThat(player.positionX()).isCloseTo(-SPEED, within(EPSILON));

            assertThat(player.positionZ()).isCloseTo(0.0f, within(EPSILON));
        }

        @Test
        @DisplayName("strafe left at yaw 0 walks along world +x")
        void shouldStrafeAlongPositiveXWhenFacingYawZeroAndStrafingLeft()
        {
            final PlayerController player = facing(0.0f);

            player.update(move(0.0f, -1.0f), ONE_SECOND);

            assertThat(player.positionX()).isCloseTo(SPEED, within(EPSILON));

            assertThat(player.positionZ()).isCloseTo(0.0f, within(EPSILON));
        }

        @Test
        @DisplayName("strafe right at yaw 90 degrees walks along world +z")
        void shouldStrafeAlongPositiveZWhenFacingQuarterTurn()
        {
            final PlayerController player = facing(QUARTER_TURN);

            player.update(move(0.0f, 1.0f), ONE_SECOND);

            assertThat(player.positionX()).isCloseTo(0.0f, within(EPSILON));

            assertThat(player.positionZ()).isCloseTo(SPEED, within(EPSILON));
        }

        @Test
        @DisplayName("strafe right at yaw 180 degrees walks along world +x")
        void shouldStrafeAlongPositiveXWhenFacingHalfTurn()
        {
            final PlayerController player = facing(HALF_TURN);

            player.update(move(0.0f, 1.0f), ONE_SECOND);

            assertThat(player.positionX()).isCloseTo(SPEED, within(EPSILON));

            assertThat(player.positionZ()).isCloseTo(0.0f, within(EPSILON));
        }

        @Test
        @DisplayName("strafe right at yaw 270 degrees walks along world -z")
        void shouldStrafeAlongNegativeZWhenFacingThreeQuarterTurn()
        {
            final PlayerController player = facing(THREE_QUARTER_TURN);

            player.update(move(0.0f, 1.0f), ONE_SECOND);

            assertThat(player.positionX()).isCloseTo(0.0f, within(EPSILON));

            assertThat(player.positionZ()).isCloseTo(-SPEED, within(EPSILON));
        }

        @Test
        @DisplayName("the strafe axis is exactly groundForward x up")
        void shouldDeriveStrafeAxisAsForwardCrossUpWhenAtAnArbitraryYaw()
        {
            final PlayerController player = facing(1.234f);

            final Vec3 expected = player.groundForwardVector().cross(PlayerController.WORLD_UP);

            final Vec3 actual = player.groundRightVector();

            assertThat(actual.x()).isEqualTo(expected.x());

            assertThat(actual.y()).isEqualTo(expected.y());

            assertThat(actual.z()).isEqualTo(expected.z());
        }

        @Test
        @DisplayName("movement never changes the y coordinate")
        void shouldLeaveHeightUnchangedWhenMovingOnTheGroundPlane()
        {
            final PlayerController grounded = new PlayerController(0.0f, 0.0f, 0.0f, 0.9f, 0.0f);

            grounded.update(move(1.0f, 1.0f), ONE_SECOND);

            assertThat(grounded.positionY()).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("the walk axes contribute nothing to height, even in mid-air")
        void shouldLeaveTheVerticalArcUntouchedWhenWalkingWhileAirborne()
        {
            // The stronger form of the test above, and the one that survived
            // gravity landing. Comparing two runs from the same altitude
            // separates "movement does not touch y" from "gravity does": a fixed
            // spawn height can no longer be asserted, because falling is now
            // correct behaviour, but the two arcs must still agree exactly.
            final PlayerController walking = new PlayerController(0.0f, 200.0f, 0.0f, 0.9f, 0.0f);

            final PlayerController still = new PlayerController(0.0f, 200.0f, 0.0f, 0.9f, 0.0f);

            for (int tic = 0; tic < 30; tic++)
            {
                walking.update(move(1.0f, 1.0f), TIC_60HZ);

                still.update(NONE, TIC_60HZ);
            }

            assertThat(walking.positionY()).isEqualTo(still.positionY());

            assertThat(walking.positionY()).isLessThan(200.0f);
        }

        @Test
        @DisplayName("displacement accumulates across updates")
        void shouldAccumulateDisplacementWhenMovingOverSeveralTics()
        {
            final PlayerController player = facing(0.0f);

            player.update(move(1.0f, 0.0f), ONE_SECOND);

            player.update(move(1.0f, 0.0f), ONE_SECOND);

            player.update(move(1.0f, 0.0f), ONE_SECOND);

            assertThat(player.positionZ()).isCloseTo(SPEED * 3.0f, within(EPSILON));
        }
    }

    @Nested
    @DisplayName("movement is yaw-only")
    class YawOnlyMovement
    {
        @Test
        @DisplayName("looking up does not make the player fly")
        void shouldNotChangeHeightWhenMovingForwardWhileLookingUp()
        {
            final PlayerController player = new PlayerController();

            player.update(look(0.0f, 10.0f), TIC_60HZ);

            player.update(move(1.0f, 0.0f), ONE_SECOND);

            assertThat(player.pitchRadians()).isEqualTo(PlayerController.MAX_PITCH_RADIANS);

            assertThat(player.positionY()).isEqualTo(0.0f);
        }

        @Test
        @DisplayName("pitch does not shorten the horizontal step")
        void shouldKeepHorizontalSpeedWhenPitchedFarUp()
        {
            // The classic bug is using the pitched forward vector, which makes
            // the horizontal step cos(pitch) times too short. At 89 degrees
            // that is a 98% slowdown, so it cannot hide inside a tolerance.
            final PlayerController level = facing(0.0f);

            final PlayerController pitched = new PlayerController();

            pitched.update(look(0.0f, 10.0f), TIC_60HZ);

            level.update(move(1.0f, 0.0f), ONE_SECOND);

            pitched.update(move(1.0f, 0.0f), ONE_SECOND);

            assertThat(horizontalDistance(pitched))
                .isCloseTo(horizontalDistance(level), within(EPSILON));
        }

        @Test
        @DisplayName("movement is identical at pitch up, level and pitch down")
        void shouldProduceIdenticalMovementWhenPitchDiffersOnly()
        {
            final PlayerController up = new PlayerController(0.0f, 0.0f, 0.0f, 0.8f, 0.0f);

            final PlayerController level = new PlayerController(0.0f, 0.0f, 0.0f, 0.8f, 0.0f);

            final PlayerController down = new PlayerController(0.0f, 0.0f, 0.0f, 0.8f, 0.0f);

            up.update(look(0.0f, 1.5f), TIC_60HZ);

            down.update(look(0.0f, -1.5f), TIC_60HZ);

            up.update(move(1.0f, 0.5f), ONE_SECOND);

            level.update(move(1.0f, 0.5f), ONE_SECOND);

            down.update(move(1.0f, 0.5f), ONE_SECOND);

            assertThat(up.positionX()).isEqualTo(level.positionX());

            assertThat(up.positionZ()).isEqualTo(level.positionZ());

            assertThat(down.positionX()).isEqualTo(level.positionX());

            assertThat(down.positionZ()).isEqualTo(level.positionZ());
        }

        @Test
        @DisplayName("the ground forward vector has no vertical component at any pitch")
        void shouldHaveZeroVerticalComponentWhenPitchedAtTheLimit()
        {
            final PlayerController player = new PlayerController();

            player.update(look(0.0f, 10.0f), TIC_60HZ);

            assertThat(player.groundForwardVector().y()).isEqualTo(0.0f);

            assertThat(player.groundRightVector().y()).isEqualTo(0.0f);

            assertThat(player.forwardVector().y()).isGreaterThan(0.99f);
        }
    }

    @Nested
    @DisplayName("diagonal speed")
    class DiagonalSpeed
    {
        @Test
        @DisplayName("full forward plus full strafe is no faster than full forward alone")
        void shouldNotExceedCardinalSpeedWhenBothAxesAreFullyDeflected()
        {
            final PlayerController diagonal = facing(0.0f);

            diagonal.update(move(1.0f, 1.0f), ONE_SECOND);

            assertThat(horizontalDistance(diagonal)).isCloseTo(SPEED, within(EPSILON));
        }

        @Test
        @DisplayName("a diagonal still points diagonally after the magnitude clamp")
        void shouldPreserveDirectionWhenClampingDiagonalMagnitude()
        {
            final PlayerController player = facing(0.0f);

            player.update(move(1.0f, 1.0f), ONE_SECOND);

            // Forward is +z, strafe right is -x, so an equal mix is (-k, 0, +k).
            assertThat(player.positionZ()).isCloseTo(-player.positionX(), within(EPSILON));

            assertThat(player.positionZ()).isGreaterThan(0.0f);
        }

        @Test
        @DisplayName("does not double-normalise an already unit-length diagonal")
        void shouldLeaveUnitLengthDiagonalUnscaledWhenTheInputLayerNormalised()
        {
            // If the input layer normalises, the controller must be a no-op on
            // magnitude. sqrt(0.5) each gives magnitude 1 already.
            final float component = (float) StrictMath.sqrt(0.5);

            final PlayerController player = facing(0.0f);

            player.update(move(component, component), ONE_SECOND);

            assertThat(horizontalDistance(player)).isCloseTo(SPEED, within(EPSILON));
        }

        @Test
        @DisplayName("does not normalise a partial deflection up to full speed")
        void shouldMoveAtPartialSpeedWhenTheAxisIsPartiallyDeflected()
        {
            // The other half of "no double-normalisation": an analogue stick at
            // half deflection must walk, not run.
            final PlayerController player = facing(0.0f);

            player.update(move(0.5f, 0.0f), ONE_SECOND);

            assertThat(horizontalDistance(player)).isCloseTo(SPEED * 0.5f, within(EPSILON));
        }

        @Test
        @DisplayName("a partial diagonal below unit magnitude is left alone")
        void shouldLeavePartialDiagonalUnscaledWhenBelowUnitMagnitude()
        {
            final PlayerController player = facing(0.0f);

            player.update(move(0.3f, 0.4f), ONE_SECOND);

            assertThat(horizontalDistance(player)).isCloseTo(SPEED * 0.5f, within(EPSILON));
        }

        @Test
        @DisplayName("out-of-range axes are clamped rather than trusted")
        void shouldClampAxesWhenTheInputLayerOverruns()
        {
            final PlayerController overrun = facing(0.0f);

            final PlayerController full = facing(0.0f);

            overrun.update(move(50.0f, 0.0f), ONE_SECOND);

            full.update(move(1.0f, 0.0f), ONE_SECOND);

            assertThat(overrun.positionZ()).isEqualTo(full.positionZ());
        }

        @Test
        @DisplayName("a NaN axis moves the player nowhere instead of poisoning the position")
        void shouldIgnoreAxisWhenItIsNotANumber()
        {
            final PlayerController player = facing(0.0f);

            player.update(move(Float.NaN, 0.0f), ONE_SECOND);

            assertThat(player.positionX()).isEqualTo(0.0f);

            assertThat(player.positionZ()).isEqualTo(0.0f);
        }
    }

    @Nested
    @DisplayName("frame-rate independence")
    class FrameRateIndependence
    {
        @Test
        @DisplayName("a 2x timestep moves exactly 2x as far")
        void shouldMoveTwiceAsFarWhenTheTimestepDoubles()
        {
            // Exact, not approximate: scaling a float by two is lossless, so a
            // controller that is truly linear in dt gives bit-identical halves.
            final float singleStep = 0.01f;

            final float doubleStep = singleStep * 2.0f;

            final PlayerController slow = facing(0.7f);

            final PlayerController fast = facing(0.7f);

            slow.update(move(1.0f, 0.3f), singleStep);

            fast.update(move(1.0f, 0.3f), doubleStep);

            assertThat(fast.positionX()).isEqualTo(slow.positionX() * 2.0f);

            assertThat(fast.positionZ()).isEqualTo(slow.positionZ() * 2.0f);
        }

        @Test
        @DisplayName("two half-steps land where one whole step does, to within rounding")
        void shouldMatchOneWholeStepWhenTakingTwoHalfSteps()
        {
            final PlayerController whole = facing(0.4f);

            final PlayerController halves = facing(0.4f);

            whole.update(move(1.0f, 0.0f), TIC_60HZ);

            halves.update(move(1.0f, 0.0f), TIC_60HZ * 0.5f);

            halves.update(move(1.0f, 0.0f), TIC_60HZ * 0.5f);

            assertThat(halves.positionX()).isCloseTo(whole.positionX(), within(EPSILON));

            assertThat(halves.positionZ()).isCloseTo(whole.positionZ(), within(EPSILON));
        }

        @Test
        @DisplayName("120 Hz for a second travels the same distance as 30 Hz for a second")
        void shouldTravelTheSameDistanceWhenTheTicRateDiffers()
        {
            final PlayerController fast = facing(0.0f);

            final PlayerController slow = facing(0.0f);

            for (int i = 0; i < 120; i++)
            {
                fast.update(move(1.0f, 0.0f), 1.0f / 120.0f);
            }

            for (int i = 0; i < 30; i++)
            {
                slow.update(move(1.0f, 0.0f), 1.0f / 30.0f);
            }

            // A looser tolerance here than elsewhere, and deliberately: this is
            // the one test that accumulates 120 additions into a value of
            // order 256, where a single ulp is already 1.5e-5. 0.05 is still
            // 0.02% of the distance travelled — far tighter than any real
            // frame-rate dependence, which would show up as a whole ratio.
            assertThat(fast.positionZ()).isCloseTo(slow.positionZ(), within(0.05f));

            assertThat(fast.positionZ()).isCloseTo(SPEED, within(0.05f));
        }

        @Test
        @DisplayName("a zero timestep moves nobody, but still turns the head")
        void shouldNotMoveWhenTheTimestepIsZero()
        {
            // The look delta is NEGATIVE where the stored yaw is positive: a
            // yawDelta turns the view right and a larger stored yaw faces
            // left, so the two carry opposite signs. See applyLook.
            final PlayerController player = facing(0.0f);

            player.update(new Input(1.0f, 1.0f, -0.5f, 0.25f), 0.0f);

            assertThat(player.positionX()).isEqualTo(0.0f);

            assertThat(player.positionZ()).isEqualTo(0.0f);

            // A LEFTWARD look of 0.5 — the delta is negative — and applyLook
            // subtracts it, so the stored angle grows to +0.5 and never
            // approaches the wrap. See YawWrap for the boundary cases.
            assertThat(player.yawRadians()).isEqualTo(0.5f);

            assertThat(player.pitchRadians()).isEqualTo(0.25f);
        }

        @Test
        @DisplayName("rejects a negative timestep")
        void shouldRejectUpdateWhenTheTimestepIsNegative()
        {
            final PlayerController player = new PlayerController();

            assertThatThrownBy(() -> player.update(NONE, -0.01f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deltaSeconds");
        }

        @Test
        @DisplayName("rejects a NaN timestep")
        void shouldRejectUpdateWhenTheTimestepIsNotANumber()
        {
            final PlayerController player = new PlayerController();

            assertThatThrownBy(() -> player.update(NONE, Float.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deltaSeconds");
        }

        @Test
        @DisplayName("rejects a null input")
        void shouldRejectUpdateWhenInputIsNull()
        {
            final PlayerController player = new PlayerController();

            assertThatThrownBy(() -> player.update(null, TIC_60HZ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("input");
        }
    }

    @Nested
    @DisplayName("zero input")
    class ZeroInput
    {
        @Test
        @DisplayName("leaves every field bit-identical")
        void shouldLeaveStateBitIdenticalWhenInputIsAllZero()
        {
            // Feet ON the ground. Above it, zero input is correctly NOT a no-op
            // any more — the player falls, which is what FallingAndJumping
            // asserts. The invariant this test is about is horizontal drift.
            final PlayerController player =
                new PlayerController(13.25f, 0.0f, 91.75f, 2.345f, -0.678f);

            player.update(move(1.0f, 0.7f), TIC_60HZ);

            player.update(look(0.13f, 0.07f), TIC_60HZ);

            final int[] before = snapshot(player);

            for (int i = 0; i < 100; i++)
            {
                player.update(NONE, TIC_60HZ);
            }

            assertThat(snapshot(player)).isEqualTo(before);
        }

        @Test
        @DisplayName("leaves the origin state untouched")
        void shouldLeaveDefaultStateUntouchedWhenInputIsAllZero()
        {
            final PlayerController player = new PlayerController();

            player.update(NONE, TIC_60HZ);

            assertThat(snapshot(player)).isEqualTo(snapshot(new PlayerController()));
        }
    }

    @Nested
    @DisplayName("falling and jumping")
    class FallingAndJumping
    {
        /**
         * Tics of 60 Hz simulation to run — two seconds, comfortably past a
         * landing. Must exceed the jump's own hang time
         * ({@code 2 * JUMP_SPEED_UNITS_PER_SECOND / GRAVITY_UNITS_PER_SECOND_SQUARED},
         * about 1.72 s at the current constants) with margin, or a test that
         * asserts the arc has finished would instead catch the player mid-air.
         */
        private static final int LANDING_TICS = 120;

        @Test
        @DisplayName("a grounded player who presses jump leaves the floor")
        void shouldLeaveTheFloorWhenJumpIsPressedOnTheGround()
        {
            final PlayerController player = new PlayerController();

            assertThat(player.isOnGround()).isTrue();

            player.update(JUMP, TIC_60HZ);

            assertThat(player.positionY()).isGreaterThan(0.0f);

            assertThat(player.isOnGround()).isFalse();
        }

        @Test
        @DisplayName("the apex is JUMP_APEX_UNITS, to within one integration step")
        void shouldReachTheDeclaredApexWhenJumping()
        {
            final PlayerController player = new PlayerController();

            float peak = 0.0f;

            for (int tic = 0; tic < LANDING_TICS; tic++)
            {
                player.update(NONE, TIC_60HZ);

                peak = StrictMath.max(peak, player.positionY());
            }

            // Jump on the first tic, not before the loop, so the peak search
            // sees the whole arc.
            final PlayerController jumper = new PlayerController();

            jumper.update(JUMP, TIC_60HZ);

            float jumpPeak = jumper.positionY();

            for (int tic = 1; tic < LANDING_TICS; tic++)
            {
                jumper.update(NONE, TIC_60HZ);

                jumpPeak = StrictMath.max(jumpPeak, jumper.positionY());
            }

            assertThat(peak).as("a player who never jumps never leaves the floor").isEqualTo(0.0f);

            // Semi-implicit Euler UNDERSHOOTS the analytic apex, by at most the
            // distance one step of the launch velocity covers. Asserting that
            // two-sided bound rather than a tolerance means the test states the
            // integrator's actual error budget instead of a number tuned until
            // it passed.
            final float stepError = PlayerController.JUMP_SPEED_UNITS_PER_SECOND * TIC_60HZ;

            assertThat(jumpPeak)
                .isLessThanOrEqualTo(PlayerController.JUMP_APEX_UNITS)
                .isGreaterThan(PlayerController.JUMP_APEX_UNITS - stepError);
        }

        @Test
        @DisplayName("holding jump does not re-launch in mid-air")
        void shouldNotRelaunchWhileAirborneWhenJumpIsHeld()
        {
            final PlayerController held = new PlayerController();

            final PlayerController tapped = new PlayerController();

            held.update(JUMP, TIC_60HZ);

            tapped.update(JUMP, TIC_60HZ);

            for (int tic = 1; tic < 20; tic++)
            {
                held.update(JUMP, TIC_60HZ);

                tapped.update(NONE, TIC_60HZ);
            }

            // Identical arcs. If the level-triggered jump were not gated on
            // being grounded, the held player would be re-launched every tic and
            // would still be climbing.
            assertThat(held.positionY()).isEqualTo(tapped.positionY());

            assertThat(held.velocityY()).isEqualTo(tapped.velocityY());
        }

        @Test
        @DisplayName("the player lands exactly on the ground plane, with no residual velocity")
        void shouldLandExactlyOnTheGroundWhenTheArcCompletes()
        {
            final PlayerController player = new PlayerController();

            player.update(JUMP, TIC_60HZ);

            for (int tic = 1; tic < LANDING_TICS; tic++)
            {
                player.update(NONE, TIC_60HZ);
            }

            // Exactly, not approximately. A residual fraction below the floor
            // would be invisible and would make isOnGround false, silently
            // refusing the next jump.
            assertThat(player.positionY()).isEqualTo(PlayerController.GROUND_LEVEL_UNITS);

            assertThat(player.velocityY()).isEqualTo(0.0f);

            assertThat(player.isOnGround()).isTrue();
        }

        @Test
        @DisplayName("a player spawned above the floor falls to it")
        void shouldFallToTheFloorWhenSpawnedAboveIt()
        {
            final PlayerController player =
                new PlayerController(5.0f, 500.0f, -9.0f, 0.0f, 0.0f);

            for (int tic = 0; tic < LANDING_TICS * 2; tic++)
            {
                player.update(NONE, TIC_60HZ);
            }

            assertThat(player.positionY()).isEqualTo(PlayerController.GROUND_LEVEL_UNITS);

            // Falling is vertical only — nothing about gravity moves the player
            // sideways, and a basis error here would be very hard to see.
            assertThat(player.positionX()).isEqualTo(5.0f);

            assertThat(player.positionZ()).isEqualTo(-9.0f);
        }

        @Test
        @DisplayName("jump is refused in mid-air")
        void shouldRefuseToJumpWhenAirborne()
        {
            final PlayerController player = new PlayerController();

            player.update(JUMP, TIC_60HZ);

            final float afterLaunch = player.velocityY();

            player.update(JUMP, TIC_60HZ);

            // Strictly slower than the tic before: gravity applied and no second
            // launch replaced the velocity.
            assertThat(player.velocityY()).isLessThan(afterLaunch);

            assertThat(player.velocityY())
                .isLessThan(PlayerController.JUMP_SPEED_UNITS_PER_SECOND);
        }

        @Test
        @DisplayName("jumping does not change where a walk takes the player")
        void shouldNotAlterHorizontalTravelWhenJumping()
        {
            final PlayerController walking = new PlayerController();

            final PlayerController hopping = new PlayerController();

            for (int tic = 0; tic < LANDING_TICS; tic++)
            {
                walking.update(move(1.0f, 0.0f), TIC_60HZ);

                hopping.update(new Input(1.0f, 0.0f, 0.0f, 0.0f, tic == 0), TIC_60HZ);
            }

            // No air control model and no air friction: this engine has neither,
            // so the horizontal paths must be bit-identical. If one is ever
            // added, this is the test that will say so.
            assertThat(hopping.positionX()).isEqualTo(walking.positionX());

            assertThat(hopping.positionZ()).isEqualTo(walking.positionZ());
        }

        @Test
        @DisplayName("the launch speed is derived from the apex, not restated")
        void shouldDeriveLaunchSpeedFromTheApexWhenComputingJumpSpeed()
        {
            final float expected = (float) StrictMath.sqrt(
                2.0 * PlayerController.GRAVITY_UNITS_PER_SECOND_SQUARED
                    * PlayerController.JUMP_APEX_UNITS);

            assertThat(PlayerController.JUMP_SPEED_UNITS_PER_SECOND).isEqualTo(expected);
        }

        @Test
        @DisplayName("two identical jump scripts give bit-identical arcs")
        void shouldProduceBitIdenticalArcsWhenTheJumpScriptIsRepeated()
        {
            assertThat(jumpArc()).isEqualTo(jumpArc());
        }

        // One jump, sampled every tic, as raw bits.
        private int[] jumpArc()
        {
            final PlayerController player = new PlayerController();

            final int[] samples = new int[LANDING_TICS];

            for (int tic = 0; tic < LANDING_TICS; tic++)
            {
                player.update(new Input(0.3f, -0.7f, 0.01f, 0.002f, tic == 0), TIC_60HZ);

                samples[tic] = Float.floatToRawIntBits(player.positionY());
            }

            return samples;
        }
    }

    @Nested
    @DisplayName("sprint")
    class Sprint
    {
        @Test
        @DisplayName("sprinting straight forward covers exactly SPRINT_MULTIPLIER times the ground")
        void shouldMoveFartherWhenSprintingForward()
        {
            final PlayerController walker = new PlayerController();

            final PlayerController sprinter = new PlayerController();

            walker.update(move(1.0f, 0.0f), ONE_SECOND);

            sprinter.update(moveSprinting(1.0f, 0.0f), ONE_SECOND);

            // Exact, not approximate: at yaw 0 every trig factor involved is 0
            // or +-1, so the sprinter's step is the walker's step scaled by one
            // multiplication and nothing rounds differently between the two.
            assertThat(sprinter.positionZ())
                .isEqualTo(walker.positionZ() * PlayerController.SPRINT_MULTIPLIER);
        }

        @Test
        @DisplayName("a forward-diagonal sprint is boosted on both axes")
        void shouldBoostAForwardDiagonalSprint()
        {
            final PlayerController walker = new PlayerController();

            final PlayerController sprinter = new PlayerController();

            walker.update(move(1.0f, 1.0f), ONE_SECOND);

            sprinter.update(moveSprinting(1.0f, 1.0f), ONE_SECOND);

            assertThat(sprinter.positionZ())
                .isEqualTo(walker.positionZ() * PlayerController.SPRINT_MULTIPLIER);

            assertThat(sprinter.positionX())
                .isEqualTo(walker.positionX() * PlayerController.SPRINT_MULTIPLIER);
        }

        @Test
        @DisplayName("holding sprint with no forward component gives no boost when strafing")
        void shouldGiveNoBoostWhenSprintingWithoutForwardInput()
        {
            final PlayerController strafer = new PlayerController();

            final PlayerController sprintingStrafer = new PlayerController();

            strafer.update(move(0.0f, 1.0f), ONE_SECOND);

            sprintingStrafer.update(moveSprinting(0.0f, 1.0f), ONE_SECOND);

            assertThat(sprintingStrafer.positionX()).isEqualTo(strafer.positionX());

            assertThat(sprintingStrafer.positionZ()).isEqualTo(strafer.positionZ());
        }

        @Test
        @DisplayName("holding sprint while moving backward gives no boost")
        void shouldGiveNoBoostWhenSprintingBackward()
        {
            final PlayerController walker = new PlayerController();

            final PlayerController sprinter = new PlayerController();

            walker.update(move(-1.0f, 0.0f), ONE_SECOND);

            sprinter.update(moveSprinting(-1.0f, 0.0f), ONE_SECOND);

            assertThat(sprinter.positionZ()).isEqualTo(walker.positionZ());
        }

        @Test
        @DisplayName("sprint does not affect vertical motion")
        void shouldNotAlterTheJumpWhenSprinting()
        {
            final PlayerController jumper = new PlayerController();

            final PlayerController sprintingJumper = new PlayerController();

            jumper.update(JUMP, TIC_60HZ);

            sprintingJumper.update(new Input(0.0f, 0.0f, 0.0f, 0.0f, true, true), TIC_60HZ);

            assertThat(sprintingJumper.positionY()).isEqualTo(jumper.positionY());

            assertThat(sprintingJumper.velocityY()).isEqualTo(jumper.velocityY());
        }
    }

    @Nested
    @DisplayName("view basis")
    class ViewBasis
    {
        @Test
        @DisplayName("the eye sits EYE_HEIGHT_UNITS above the feet")
        void shouldRaiseTheEyeAboveTheFeetWhenReadingTheEyePosition()
        {
            final PlayerController player = new PlayerController(3.0f, 20.0f, -8.0f, 0.0f, 0.0f);

            final Vec3 eye = player.eyePosition();

            assertThat(eye.x()).isEqualTo(3.0f);

            assertThat(eye.y()).isEqualTo(20.0f + PlayerController.EYE_HEIGHT_UNITS);

            assertThat(eye.z()).isEqualTo(-8.0f);

            assertThat(PlayerController.EYE_HEIGHT_UNITS).isGreaterThan(0.0f);
        }

        @Test
        @DisplayName("the eye follows the feet as the player walks")
        void shouldTrackTheFeetWhenThePlayerMoves()
        {
            final PlayerController player = facing(0.0f);

            player.update(move(1.0f, 0.0f), ONE_SECOND);

            final Vec3 eye = player.eyePosition();

            final Vec3 feet = player.feetPosition();

            assertThat(eye.x()).isEqualTo(feet.x());

            assertThat(eye.z()).isEqualTo(feet.z());

            assertThat(eye.y() - feet.y()).isEqualTo(PlayerController.EYE_HEIGHT_UNITS);
        }

        @Test
        @DisplayName("the forward vector is unit length at any pitch and yaw")
        void shouldProduceAUnitForwardVectorWhenPitchedAndTurned()
        {
            final PlayerController player = new PlayerController(0.0f, 0.0f, 0.0f, 2.1f, 0.0f);

            player.update(look(0.0f, 1.2f), TIC_60HZ);

            final Vec3 forward = player.forwardVector();

            assertThat(forward.length()).isCloseTo(1.0f, within(1.0e-5f));
        }

        @Test
        @DisplayName("positive pitch aims the forward vector upward")
        void shouldAimForwardUpwardWhenPitchIsPositive()
        {
            final PlayerController player = new PlayerController();

            player.update(look(0.0f, 0.5f), TIC_60HZ);

            assertThat(player.forwardVector().y()).isGreaterThan(0.0f);
        }

        @Test
        @DisplayName("negative pitch aims the forward vector downward")
        void shouldAimForwardDownwardWhenPitchIsNegative()
        {
            final PlayerController player = new PlayerController();

            player.update(look(0.0f, -0.5f), TIC_60HZ);

            assertThat(player.forwardVector().y()).isLessThan(0.0f);
        }

        @Test
        @DisplayName("the ground forward vector is the forward vector with pitch removed")
        void shouldMatchForwardHeadingWhenPitchIsZero()
        {
            final PlayerController player = facing(1.7f);

            final Vec3 forward = player.forwardVector();

            final Vec3 ground = player.groundForwardVector();

            assertThat(ground.x()).isCloseTo(forward.x(), within(EPSILON));

            assertThat(ground.z()).isCloseTo(forward.z(), within(EPSILON));

            assertThat(ground.length()).isCloseTo(1.0f, within(1.0e-5f));
        }
    }

    @Nested
    @DisplayName("camera")
    class CameraBuilding
    {
        private static final float ASPECT = 16.0f / 9.0f;

        @Test
        @DisplayName("sits at the eye position, not the feet")
        void shouldPlaceTheCameraAtTheEyeWhenBuilt()
        {
            final PlayerController player = new PlayerController(5.0f, 2.0f, -1.0f, 0.0f, 0.0f);

            final Camera camera = player.camera(ASPECT);

            assertThat(camera.eye().x()).isEqualTo(5.0f);

            assertThat(camera.eye().y()).isEqualTo(2.0f + PlayerController.EYE_HEIGHT_UNITS);

            assertThat(camera.eye().z()).isEqualTo(-1.0f);
        }

        @Test
        @DisplayName("looks along the controller's forward vector")
        void shouldLookAlongTheForwardVectorWhenBuilt()
        {
            final PlayerController player = new PlayerController(0.0f, 0.0f, 0.0f, 1.1f, 0.0f);

            player.update(look(0.0f, 0.4f), TIC_60HZ);

            final Vec3 forward = player.forwardVector();

            final Camera camera = player.camera(ASPECT);

            assertThat(camera.forward().x()).isCloseTo(forward.x(), within(1.0e-5f));

            assertThat(camera.forward().y()).isCloseTo(forward.y(), within(1.0e-5f));

            assertThat(camera.forward().z()).isCloseTo(forward.z(), within(1.0e-5f));
        }

        @Test
        @DisplayName("its right axis agrees with the strafe direction")
        void shouldAgreeWithTheStrafeDirectionWhenLevel()
        {
            // If these ever disagree, pressing strafe-right moves the player
            // toward the left of the screen. Asserted at yaw 0 with exact
            // expected values so the sign cannot hide.
            final PlayerController player = facing(0.0f);

            final Camera camera = player.camera(ASPECT);

            final Vec3 strafe = player.groundRightVector();

            assertThat(camera.right().x()).isCloseTo(-1.0f, within(EPSILON));

            assertThat(strafe.x()).isCloseTo(-1.0f, within(EPSILON));

            assertThat(camera.right().x()).isCloseTo(strafe.x(), within(EPSILON));

            assertThat(camera.right().z()).isCloseTo(strafe.z(), within(EPSILON));
        }

        @Test
        @DisplayName("carries the requested aspect and the default frustum")
        void shouldCarryTheDefaultFrustumWhenOnlyAspectIsGiven()
        {
            final Camera camera = new PlayerController().camera(ASPECT);

            assertThat(camera.aspect()).isEqualTo(ASPECT);

            assertThat(camera.fovY()).isEqualTo(PlayerController.DEFAULT_FOV_Y_RADIANS);

            assertThat(camera.near()).isEqualTo(PlayerController.DEFAULT_NEAR_PLANE_UNITS);

            assertThat(PlayerController.DEFAULT_NEAR_PLANE_UNITS).isGreaterThan(0.0f);
        }

        @Test
        @DisplayName("accepts an explicit frustum")
        void shouldUseTheGivenFrustumWhenSpecified()
        {
            final Camera camera = new PlayerController().camera(ASPECT, 1.0f, 0.5f);

            assertThat(camera.fovY()).isEqualTo(1.0f);

            assertThat(camera.near()).isEqualTo(0.5f);
        }

        @Test
        @DisplayName("rejects a non-positive aspect ratio")
        void shouldRejectTheCameraWhenAspectIsNotPositive()
        {
            final PlayerController player = new PlayerController();

            assertThatThrownBy(() -> player.camera(0.0f))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("projects a point straight ahead to the centre of the view")
        void shouldProjectAPointAheadToTheViewCentreWhenLevel()
        {
            // End-to-end: the controller's basis fed through the real camera.
            final PlayerController player = facing(0.0f);

            final Camera camera = player.camera(ASPECT);

            final float[] clip = new float[Camera.CLIP_FLOATS];

            camera.transformToClip(
                0.0f, PlayerController.EYE_HEIGHT_UNITS, 100.0f, clip, 0);

            assertThat(clip[0]).isCloseTo(0.0f, within(EPSILON));

            assertThat(clip[1]).isCloseTo(0.0f, within(EPSILON));

            assertThat(clip[2]).isCloseTo(100.0f, within(EPSILON));
        }
    }

    @Nested
    @DisplayName("collision world — the seam the map runtime uses to inject per-scene physics")
    class CollisionWorld
    {
        @Test
        @DisplayName("a fresh controller starts on the open world, so the demo and --model= paths collide with nothing")
        void shouldDefaultToTheOpenWorldWhenConstructed()
        {
            // The 5-arg constructor leaves the world at OPEN; the 0-arg form
            // chains to it. A controller built for the demo or for a
            // camera-only --model= run should walk through walls exactly as
            // the pre-collision build did, and OPEN is the world that gives
            // back every move unchanged.
            final PlayerController player = new PlayerController();

            assertThat(player.world()).isSameAs(PhysicsWorld.OPEN);

            // And the open world really is a no-op on a move: one second of
            // walking forward at yaw 0 advances z by the move speed, with
            // no clipping from a phantom solid.
            player.update(move(1.0f, 0.0f), ONE_SECOND);

            assertThat(player.positionZ()).isCloseTo(SPEED, within(EPSILON));
        }

        @Test
        @DisplayName("setCollisionWorld replaces the world consulted by every later update")
        void shouldReplaceTheWorldWhenSet()
        {
            // The seam: a controller built with the default 5-arg form
            // (no collision) is given a real world between construction
            // and the first update, and the world() getter reflects it.
            final PlayerController player = new PlayerController();

            final PhysicsWorld custom = PhysicsWorld.builder(
                PhysicsWorld.PLAYER_HALF_WIDTH_UNITS)
                .addBox(50.0f, -100.0f, 100.0f, 100.0f)
                .build();

            player.setCollisionWorld(custom);

            assertThat(player.world()).isSameAs(custom);
        }

        @Test
        @DisplayName("setCollisionWorld rejects null, so a port that forgot to inject a world is loud")
        void shouldRejectANullWorldWhenSet()
        {
            final PlayerController player = new PlayerController();

            assertThatThrownBy(() -> player.setCollisionWorld(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collisionWorld");

            // The world stays where it was: the failed call must not
            // silently drop the player onto OPEN.
            assertThat(player.world()).isSameAs(PhysicsWorld.OPEN);
        }

        @Test
        @DisplayName("a controller with a wall in front of it cannot pass through it")
        void shouldStopAtAWallWhenSet()
        {
            // Player at the origin, facing +z (yaw 0, forward axis +1),
            // wall on the +z side at z = 50..100, body half-width 16
            // (PLAYER_HALF_WIDTH_UNITS). The south face of the wall is
            // at z=50; the contact plane is 50 - 16 = 34. A sustained
            // forward run ends with the player standing against the
            // wall at exactly z=34, the same float the slide returned.
            //
            // addBox(minX, minZ, maxX, maxZ): the wall spans the full
            // x range so the player cannot route around it.
            final PhysicsWorld world = PhysicsWorld.builder(
                PhysicsWorld.PLAYER_HALF_WIDTH_UNITS)
                .addBox(-100.0f, 50.0f, 100.0f, 100.0f)
                .build();

            final PlayerController blocked = facing(0.0f);

            blocked.setCollisionWorld(world);

            // One second of forward input is enough to overshoot the
            // unclipped distance (256 units) by 5x, so the slide has
            // every chance to fire.
            blocked.update(move(1.0f, 0.0f), ONE_SECOND);

            // Contact plane for the south face of a wall at z=50, body
            // half-width 16: 50 - 16 = 34. Exact, not approximate:
            // "stops at the contact plane" is the contract PhysicsWorld
            // promises and the whole of this fix.
            assertThat(blocked.positionZ()).isEqualTo(34.0f);
        }

        @Test
        @DisplayName("a controller slides along a wall, instead of stopping dead against it")
        void shouldSlideAlongAWallWhenDiagonallyApproaching()
        {
            // Player at origin, wall on the +x side at x = 50..100
            // (a north-south barrier), body half-width 16. The slide
            // is x-then-z, so a forward-left diagonal at yaw 0
            // moves the player in +x (left at yaw 0 is the +x
            // direction) and +z. The wall blocks the +x component
            // and the +z component survives. Without sliding the
            // controller would refuse the whole move and end the
            // tic where it started; the assertion below fails for
            // the "stuck" case and passes for the "slid" case.
            final PhysicsWorld world = PhysicsWorld.builder(
                PhysicsWorld.PLAYER_HALF_WIDTH_UNITS)
                .addBox(50.0f, -200.0f, 100.0f, 200.0f)
                .build();

            final PlayerController player = facing(0.0f);

            player.setCollisionWorld(world);

            // Forward + left at yaw 0: blocks on x, free on z. The
            // free component survives, so z advances by the diagonal
            // step. The diagonal clamp divides step by sqrt(2), so
            // the per-axis contribution is SPEED / sqrt(2) * dt;
            // with dt=ONE_SECOND the unclipped per-axis step is
            // SPEED / sqrt(2).
            player.update(move(1.0f, -1.0f), ONE_SECOND);

            // z advances: the wall is to the +x side, not the +z side.
            assertThat(player.positionZ()).isCloseTo(SPEED / (float) StrictMath.sqrt(2.0),
                within(EPSILON));

            // x is clipped: standing against the inner face of the wall.
            assertThat(player.positionX()).isEqualTo(50.0f - PhysicsWorld.PLAYER_HALF_WIDTH_UNITS);
        }
    }

    /**
     * The {@code *Into} accessors - the per-tic hot path that avoids
     * the per-shot {@link Vec3} allocation the {@code eyePosition()} /
     * {@code forwardVector()} methods still pay.
     *
     * <p>Pair-tests against the {@link Vec3}-returning methods would be
     * sufficient, but the bounds checks on the buffer are tested in
     * isolation too: they are the only place a future caller can pass a
     * too-small array and the cost of one branch on the hot path is
     * cheaper than finding out three writes later.</p>
     */
    @Nested
    @DisplayName("scratch accessors — *Into methods that write into a caller-owned buffer")
    class ScratchAccessors
    {
        @Test
        @DisplayName("eyePositionInto writes the same three values as eyePosition, with EYE_HEIGHT added to y")
        void eyePositionIntoMatchesEyePosition()
        {
            final PlayerController player = new PlayerController(3.0f, 20.0f, -8.0f, 0.0f, 0.0f);

            final Vec3 expected = player.eyePosition();

            final float[] out = new float[3];

            player.eyePositionInto(out, 0);

            assertThat(out[0]).isEqualTo(expected.x());

            assertThat(out[1]).isEqualTo(expected.y());

            assertThat(out[2]).isEqualTo(expected.z());
        }

        @Test
        @DisplayName("eyePositionInto honours a non-zero offset, leaving the prefix untouched")
        void eyePositionIntoHonoursOffset()
        {
            final PlayerController player = new PlayerController(3.0f, 20.0f, -8.0f, 0.0f, 0.0f);

            final float[] out = new float[6];

            out[0] = 99.0f;

            out[1] = 99.0f;

            out[2] = 99.0f;

            player.eyePositionInto(out, 3);

            assertThat(out[0]).isEqualTo(99.0f);

            assertThat(out[1]).isEqualTo(99.0f);

            assertThat(out[2]).isEqualTo(99.0f);

            final Vec3 expected = player.eyePosition();

            assertThat(out[3]).isEqualTo(expected.x());

            assertThat(out[4]).isEqualTo(expected.y());

            assertThat(out[5]).isEqualTo(expected.z());
        }

        @Test
        @DisplayName("feetPositionInto writes the three coordinates without raising to eye height")
        void feetPositionIntoWritesFeet()
        {
            final PlayerController player = new PlayerController(3.0f, 20.0f, -8.0f, 0.0f, 0.0f);

            final float[] out = new float[3];

            player.feetPositionInto(out, 0);

            assertThat(out[0]).isEqualTo(3.0f);

            assertThat(out[1]).isEqualTo(20.0f);

            assertThat(out[2]).isEqualTo(-8.0f);
        }

        @Test
        @DisplayName("forwardVectorInto writes the same three values as forwardVector")
        void forwardVectorIntoMatchesForwardVector()
        {
            final PlayerController player = facing(0.0f);

            final Vec3 expected = player.forwardVector();

            final float[] out = new float[3];

            player.forwardVectorInto(out, 0);

            assertThat(out[0]).isCloseTo(expected.x(), within(EPSILON));

            assertThat(out[1]).isCloseTo(expected.y(), within(EPSILON));

            assertThat(out[2]).isCloseTo(expected.z(), within(EPSILON));
        }

        @Test
        @DisplayName("forwardVectorInto writes a unit-length vector for any yaw and pitch")
        void forwardVectorIntoIsUnitLength()
        {
            final PlayerController player = new PlayerController(0.0f, 0.0f, 0.0f, 1.7f, 0.4f);

            final float[] out = new float[3];

            player.forwardVectorInto(out, 0);

            final float length = (float) Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]);

            assertThat(length).isCloseTo(1.0f, within(1.0e-5f));
        }

        @Test
        @DisplayName("a null buffer is rejected rather than crashing three writes later")
        void nullBufferIsRejected()
        {
            final PlayerController player = new PlayerController(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);

            assertThatThrownBy(() -> player.eyePositionInto(null, 0))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> player.feetPositionInto(null, 0))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> player.forwardVectorInto(null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a too-small buffer is rejected rather than overflowing")
        void tooSmallBufferIsRejected()
        {
            final PlayerController player = new PlayerController(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);

            final float[] tooSmall = new float[2];

            assertThatThrownBy(() -> player.eyePositionInto(tooSmall, 0))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> player.feetPositionInto(tooSmall, 0))
                .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> player.forwardVectorInto(tooSmall, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a negative offset is rejected")
        void negativeOffsetIsRejected()
        {
            final PlayerController player = new PlayerController(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);

            final float[] out = new float[6];

            assertThatThrownBy(() -> player.eyePositionInto(out, -1))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism
    {
        @Test
        @DisplayName("the same input sequence from the same start is bit-identical")
        void shouldReproduceStateBitForBitWhenReplayingTheSameInputs()
        {
            final int[] first = runScript(new PlayerController(1.5f, 0.0f, -2.5f, 0.75f, 0.1f));

            final int[] second = runScript(new PlayerController(1.5f, 0.0f, -2.5f, 0.75f, 0.1f));

            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("two peers stepping the same script agree on the camera basis")
        void shouldAgreeOnTheCameraBasisWhenTwoPeersReplayTheSameScript()
        {
            final PlayerController peerA = new PlayerController(0.0f, 0.0f, 0.0f, 0.3f, 0.0f);

            final PlayerController peerB = new PlayerController(0.0f, 0.0f, 0.0f, 0.3f, 0.0f);

            runScript(peerA);

            runScript(peerB);

            final Vec3 a = peerA.forwardVector();

            final Vec3 b = peerB.forwardVector();

            assertThat(Float.floatToRawIntBits(b.x())).isEqualTo(Float.floatToRawIntBits(a.x()));

            assertThat(Float.floatToRawIntBits(b.y())).isEqualTo(Float.floatToRawIntBits(a.y()));

            assertThat(Float.floatToRawIntBits(b.z())).isEqualTo(Float.floatToRawIntBits(a.z()));
        }

        @Test
        @DisplayName("no java/lang/Math reference appears anywhere in the compiled class")
        void shouldNotReferenceMathWhenCompiled()
        {
            // The guard the class Javadoc promises. Math.sin and Math.cos are
            // permitted 1-2 ulp of error and are NOT required to agree between
            // JVM implementations, so one of them in the update path desyncs
            // lockstep silently: sub-micron per step, invisible for minutes,
            // and impossible to reproduce in a single-process test because a
            // single process is self-consistent. StrictMath is fdlibm and is
            // reproducible. Reading the constant pool is the only check that
            // cannot be defeated by a plausible-looking edit.
            final String constantPool = constantPoolOf(PlayerController.class);

            assertThat(constantPool)
                .as("PlayerController must not reference java.lang.Math anywhere")
                .doesNotContain("java/lang/Math");
        }

        @Test
        @DisplayName("the compiled class does reference StrictMath sin and cos")
        void shouldReferenceStrictMathTrigonometryWhenCompiled()
        {
            // The negative test above passes trivially if someone deletes the
            // trigonometry altogether, so pin the positive too.
            final String constantPool = constantPoolOf(PlayerController.class);

            assertThat(constantPool).contains("java/lang/StrictMath");

            assertThat(constantPool).contains("sin");

            assertThat(constantPool).contains("cos");
        }

        @Test
        @DisplayName("the input interface stays a pure value view with no Math of its own")
        void shouldKeepTheInputInterfaceFreeOfMathWhenCompiled()
        {
            assertThat(constantPoolOf(I_PlayerInput.class)).doesNotContain("java/lang/Math");
        }
    }

    // Five raw bit patterns: the whole of the controller's state.
    private static int[] snapshot(final PlayerController player)
    {
        return new int[]
        {
            Float.floatToRawIntBits(player.positionX()),
            Float.floatToRawIntBits(player.positionY()),
            Float.floatToRawIntBits(player.positionZ()),
            Float.floatToRawIntBits(player.yawRadians()),
            Float.floatToRawIntBits(player.pitchRadians()),
        };
    }

    // A fixed, varied, allocation-free input script. Deterministic by
    // construction rather than by seeding a generator, per STYLE.md § 10.
    private static int[] runScript(final PlayerController player)
    {
        for (int i = 0; i < 1000; i++)
        {
            final float forward = (float) ((i % 7) - 3) / 3.0f;

            final float strafe = (float) ((i % 5) - 2) / 2.0f;

            final float yaw = (float) ((i % 11) - 5) * 0.013f;

            final float pitch = (float) ((i % 13) - 6) * 0.011f;

            player.update(new Input(forward, strafe, yaw, pitch), TIC_60HZ);
        }

        return snapshot(player);
    }

    // The class file bytes read as Latin-1, so that every constant-pool UTF8
    // entry is searchable as a plain substring.
    private static String constantPoolOf(final Class<?> type)
    {
        final String resource = type.getSimpleName() + ".class";

        try (InputStream in = type.getResourceAsStream(resource))
        {
            assertThat(in).as("class file for %s must be readable", type).isNotNull();

            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
        catch (final IOException e)
        {
            throw new IllegalStateException("could not read the class file for " + type, e);
        }
    }
}
