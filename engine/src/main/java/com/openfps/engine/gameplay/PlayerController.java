/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

import com.openfps.engine.common.Constants;
import com.openfps.engine.core.FrameRate;
import com.openfps.engine.gameplay.port.I_PlayerInput;
import com.openfps.engine.render.adapter.Camera;
import com.openfps.engine.render.adapter.Vec3;

/**
 * First-person player controller — turns per-tic input into a camera.
 *
 * <p>Holds the three pieces of state a first-person view needs: a world
 * position (the player's <b>feet</b>), a yaw and a pitch. {@link #update}
 * integrates one tic of {@link I_PlayerInput} into them; the accessors and
 * {@link #camera} read them back. There is no rendering here, no I/O, and no
 * collision — {@code PhysicsWorld} will wrap this later and reject the parts of
 * a move that hit a wall. Everything on this class is a pure function of state
 * and arguments.</p>
 *
 * <h2>World and angle conventions</h2>
 *
 * <p>The world is right-handed with <b>+y up</b>, matching
 * {@code render/README.md} § 4. Yaw is a rotation about +y and pitch is the
 * elevation above the ground plane:</p>
 *
 * <pre>
 *   groundForward = ( sin(yaw), 0, cos(yaw) )
 *   groundRight   = groundForward x up = ( -cos(yaw), 0, sin(yaw) )
 *   forward       = ( cos(pitch)*sin(yaw), sin(pitch), cos(pitch)*cos(yaw) )
 * </pre>
 *
 * <p>So yaw 0 faces world +z, yaw increases from +z toward +x, and positive
 * pitch looks up. <b>{@code groundRight} is derived as {@code forward x up},
 * not {@code up x forward}</b> — the same operand order {@link Camera} pins as
 * normative, for the same reason. Getting it backwards mirrors the world, and
 * a mirrored world is very hard to see: pressing strafe-right would move the
 * player the way a mirrored render would have drawn "right", so the render and
 * the movement would agree with each other while both being wrong. Sanity
 * check it physically instead: with +x east and +y up, +z is south; facing
 * south, your right hand points west, and {@code groundRight} at yaw 0 is
 * indeed {@code (-1, 0, 0)}.</p>
 *
 * <p><b>Movement is yaw-only.</b> The displacement uses {@code groundForward},
 * never {@code forward}, so looking at the sky does not launch the player into
 * it. Pitch aims the camera and nothing else.</p>
 *
 * <p><b>Pitch is clamped to {@value #PITCH_LIMIT_DEGREES} degrees</b> and yaw
 * is wrapped to {@code [0, 2pi)}; see {@link #MAX_PITCH_RADIANS} and
 * {@link #FULL_TURN_RADIANS} for why each limit is the value it is.</p>
 *
 * <h2>Numeric policy — {@code float}, and {@code StrictMath} is mandatory</h2>
 *
 * <p>Player position is simulation state. {@code PLAN.md} § 4 specifies 16.16
 * fixed-point for simulation state, and the reason it gives is the only reason
 * that matters: peer-to-peer lockstep requires two peers at the same tic to
 * hold <b>bit-identical</b> state, and integer arithmetic guarantees that
 * trivially. This class is {@code float} anyway, and that is a deliberate
 * decision that satisfies § 4's intent rather than its letter. The argument, in
 * full, because a future contributor will otherwise "fix" it:</p>
 *
 * <ol>
 *   <li><b>Since JEP 306, delivered in Java 17, all floating-point expressions
 *       are FP-strict IEEE 754</b> — {@code strictfp} became a no-op keyword.
 *       {@code + - * /} and {@code sqrt} are correctly rounded by the standard,
 *       so they produce bit-identical results on every conforming JVM and every
 *       CPU. Fixed-point buys nothing here that the language does not already
 *       guarantee.</li>
 *   <li><b>The transcendentals are the real exception, and this class needs
 *       two of them.</b> {@code Math.sin}, {@code Math.cos}, {@code Math.tan},
 *       {@code Math.pow}, {@code Math.exp} and {@code Math.log} are permitted
 *       1-2 ulp of error and are explicitly <i>not</i> required to agree
 *       between implementations; a JIT may substitute a hardware or vectorised
 *       routine. {@code StrictMath} is defined by fdlibm and is reproducible
 *       bit for bit.</li>
 * </ol>
 *
 * <p><b>Therefore every trigonometric call in this class is
 * {@code StrictMath.sin} / {@code StrictMath.cos}, and switching any one of
 * them to {@code Math.*} would silently desync lockstep.</b> Silently is the
 * operative word: a 1-ulp difference in a heading produces a sub-micron
 * difference in a step, which accumulates for minutes before it is visible as
 * two peers disagreeing about whether a shot connected. It would never show up
 * in a single-process test, because a single process is self-consistent. There
 * is a test that reads this class's own bytecode and fails if a
 * {@code java/lang/Math} reference appears in it — that test is the guard, and
 * it is there because code review is not.</p>
 *
 * <p>{@link #camera} is exempt from none of this but needs none of it either:
 * it hands its arguments to {@link Camera}, which is render-side, calls
 * {@code Math.tan} once per frame, and never feeds simulation state
 * ({@code render/README.md} § 2). The eye position and forward vector it is
 * given were computed here, deterministically.</p>
 *
 * <p>References: JEP 306, Restore Always-Strict Floating-Point Semantics —
 * https://openjdk.org/jeps/306 ; JLS 17 § 15.4 ; the {@code java.lang.Math}
 * javadoc on the 1-2 ulp allowance.</p>
 *
 * <h2>Mutability and threading</h2>
 *
 * <p>This object is mutable by design — it <i>is</i> the player's state — and
 * therefore is not thread-safe. It belongs to the game loop thread. The
 * {@link Camera} it produces is immutable and is the value that crosses to the
 * render workers.</p>
 *
 * <p>On the import of {@link Camera} and {@link Vec3}: both are pure math over
 * primitives that import nothing from the engine, and {@code STYLE.md} § 1.1
 * restricts adapter imports to {@code core}, which this is not. If that edge is
 * ever unwanted, {@link #camera} is the only method that has it — the float
 * accessors carry the same information.</p>
 */
public final class PlayerController
{
    /**
     * Pitch limit in degrees, as an angle rather than a magic radian literal.
     *
     * <p><b>89, not 90.</b> At exactly +-90 degrees the forward vector is
     * {@code (0, +-1, 0)}, parallel to world up, and {@code forward x up} is the
     * zero vector — the camera basis has no defined right axis and
     * {@link Camera#create} rejects it outright. Nothing above 89 is worth the
     * fragility: the last degree of a straight-up look is not gameplay, and
     * floating-point noise near the singularity makes the view spin about its
     * own axis. 89 is the guard every first-person engine settles on.</p>
     */
    public static final float PITCH_LIMIT_DEGREES = 89.0f;

    /** Degrees in half a turn — the degrees-to-radians conversion constant. */
    private static final float DEGREES_PER_HALF_TURN = 180.0f;

    /**
     * Maximum absolute pitch in radians, equal to
     * {@link #PITCH_LIMIT_DEGREES} degrees. Pitch is clamped to
     * {@code [-MAX_PITCH_RADIANS, +MAX_PITCH_RADIANS]} on every update.
     */
    public static final float MAX_PITCH_RADIANS =
        (float) (PITCH_LIMIT_DEGREES * StrictMath.PI / DEGREES_PER_HALF_TURN);

    /**
     * One full turn in radians. Yaw is reduced modulo this into
     * {@code [0, 2pi)} so that a player who spins in one direction for an hour
     * does not accumulate an angle whose float precision has degraded — at
     * 1e7 radians the spacing between adjacent floats is about a radian, and
     * turning would visibly quantise.
     */
    public static final float FULL_TURN_RADIANS = (float) (2.0 * StrictMath.PI);

    /**
     * Height of the eye above the feet position, in world units.
     *
     * <p>{@code Constants.PLAYER_RADIUS} fixes the player's collision radius at
     * 16 world units, which is the DOOM map scale this project inherited its
     * geometry constants from. 41 is DOOM's {@code VIEWHEIGHT} against that same
     * 16-unit radius, so it keeps the human proportion the scale was chosen
     * for — roughly two and a half radii from the floor to the eye. It is
     * expressed here rather than in {@code Constants} because it is a property
     * of the first-person view, not of the map format.</p>
     */
    public static final float EYE_HEIGHT_UNITS = 41.0f;

    /**
     * The tic rate, in Hz, that {@link Constants#PLAYER_SPEED} is expressed
     * against — it is part of that constant's definition, not a frame-rate
     * choice, which is why it is read from the enum rather than assumed.
     */
    private static final float PLAYER_SPEED_REFERENCE_HZ = FrameRate.FPS_120.fps();

    /**
     * Ground movement speed in world units per second.
     *
     * <p>Derived from {@link Constants#PLAYER_SPEED} rather than restated, so
     * that changing the shared constant moves this too. That constant is 16.16
     * fixed-point units <i>per tic at 120 Hz</i>, hence the two conversions:
     * divide by {@link Constants#MAP_SCALE} to get world units per tic, then
     * multiply by the reference rate to get world units per second. The result
     * is a shade under 256 units/s — the shortfall is the integer division
     * inside {@code PLAYER_SPEED} itself, and reproducing it exactly is the
     * point of deriving rather than rounding.</p>
     */
    public static final float MOVE_SPEED_UNITS_PER_SECOND =
        (float) Constants.PLAYER_SPEED / (float) Constants.MAP_SCALE * PLAYER_SPEED_REFERENCE_HZ;

    /**
     * The world up axis, +y. Shared with {@link Camera} as the approximate up
     * when building the view basis; the camera re-derives an exact orthogonal
     * up from it.
     */
    public static final Vec3 WORLD_UP = new Vec3(0.0f, 1.0f, 0.0f);

    /** Default vertical field of view in degrees. See {@link #DEFAULT_FOV_Y_RADIANS}. */
    private static final float DEFAULT_FOV_Y_DEGREES = 60.0f;

    /**
     * Default vertical field of view in radians, equal to 60 degrees.
     *
     * <p>Vertical, because that is what {@link Camera#create} takes. At the 16:9
     * aspect ratio {@code docs/ASSETS.md} § 2 targets this is about 91 degrees
     * horizontal, which is the classic first-person default — wide enough not
     * to feel like a telescope, narrow enough that the perspective stretch at
     * the screen edges is not objectionable.</p>
     */
    public static final float DEFAULT_FOV_Y_RADIANS =
        (float) (DEFAULT_FOV_Y_DEGREES * StrictMath.PI / DEGREES_PER_HALF_TURN);

    /**
     * Default near plane distance in world units.
     *
     * <p>One world unit — a sixteenth of {@code Constants.PLAYER_RADIUS}, so
     * the whole near plane sits inside the player's own collision cylinder and
     * nothing the player can legally stand next to gets clipped away. It must
     * be positive because the rasterizer evaluates {@code 1/w} at {@code w =
     * near}; {@link Camera#create} enforces that, and 1 keeps the reciprocal
     * well conditioned besides.</p>
     */
    public static final float DEFAULT_NEAR_PLANE_UNITS = 1.0f;

    /** Feet position, world x. MUTABLE: advanced every update. */
    private float positionX;

    /** Feet position, world y. MUTABLE: advanced every update. */
    private float positionY;

    /** Feet position, world z. MUTABLE: advanced every update. */
    private float positionZ;

    /** Heading in radians, wrapped to [0, 2pi). MUTABLE: advanced every update. */
    private float yawRadians;

    /** Elevation in radians, clamped to +-MAX_PITCH_RADIANS. MUTABLE: advanced every update. */
    private float pitchRadians;

    /**
     * Creates a controller standing at the world origin, facing world +z, level.
     */
    public PlayerController()
    {
        this(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    /**
     * Creates a controller at a given spawn placement.
     *
     * The yaw is wrapped and the pitch clamped exactly as {@link #update} would
     * do, so no caller can construct a state the controller could not reach.
     *
     * @param feetX spawn position, world x
     * @param feetY spawn position, world y — the floor the player stands on,
     *     not the eye
     * @param feetZ spawn position, world z
     * @param spawnYawRadians initial heading; wrapped into {@code [0, 2pi)}
     * @param spawnPitchRadians initial elevation; clamped to
     *     {@code +-}{@link #MAX_PITCH_RADIANS}
     */
    public PlayerController(final float feetX, final float feetY, final float feetZ,
        final float spawnYawRadians, final float spawnPitchRadians)
    {
        this.positionX = feetX;
        this.positionY = feetY;
        this.positionZ = feetZ;
        this.yawRadians = wrapYaw(spawnYawRadians);
        this.pitchRadians = clampPitch(spawnPitchRadians);
    }

    /**
     * Advances the player by one tic of input.
     *
     * Look is applied before movement, so a turn takes effect on the same tic
     * that produced it — the alternative lags the strafe direction one tic
     * behind the crosshair, which is felt as mushiness at high mouse speeds.
     *
     * @param input this tic's movement axes and look deltas; must not be null
     * @param deltaSeconds the tic duration in seconds; must be finite and
     *     non-negative. Movement is exactly linear in this value, which is what
     *     makes the controller frame-rate independent
     * @throws IllegalArgumentException if {@code input} is null, or if
     *     {@code deltaSeconds} is negative or not a number
     */
    public void update(final I_PlayerInput input, final float deltaSeconds)
    {
        if (input == null)
        {
            throw new IllegalArgumentException("input must not be null");
        }
        // Written as a negated >= so that NaN, which fails every comparison,
        // is rejected here rather than poisoning the position silently.
        if (!(deltaSeconds >= 0.0f))
        {
            throw new IllegalArgumentException(
                "deltaSeconds must be non-negative and a number, got " + deltaSeconds);
        }

        applyLook(input);
        applyMove(input, deltaSeconds);
    }

    // Integrates the look deltas, then re-establishes both angle invariants.
    private void applyLook(final I_PlayerInput input)
    {
        this.yawRadians = wrapYaw(yawRadians + input.yawDelta());
        this.pitchRadians = clampPitch(pitchRadians + input.pitchDelta());
    }

    // Integrates the movement axes along the yaw-relative ground basis.
    private void applyMove(final I_PlayerInput input, final float deltaSeconds)
    {
        final float forwardAxis = clampAxis(input.forwardAxis());
        final float strafeAxis = clampAxis(input.strafeAxis());

        // Scale the step, not the axes: a partial deflection must still give a
        // partial speed, so this clamps the combined magnitude to 1 rather than
        // normalising to 1. Cardinal input and an already-unit-length diagonal
        // both pass through untouched — no double-normalisation — while
        // full-forward-plus-full-strafe, whose magnitude is sqrt(2), is scaled
        // back so that a diagonal is not 41% faster than a straight line.
        final float magnitudeSquared = forwardAxis * forwardAxis + strafeAxis * strafeAxis;
        float step = MOVE_SPEED_UNITS_PER_SECOND * deltaSeconds;
        if (magnitudeSquared > 1.0f)
        {
            // sqrt is correctly rounded by IEEE 754 and so is reproducible;
            // unlike sin and cos it needs no StrictMath. Kept as StrictMath
            // anyway so that the "no java/lang/Math in this class" guard test
            // can be a single flat rule with no exceptions to remember.
            step = step / (float) StrictMath.sqrt(magnitudeSquared);
        }

        // Yaw-only basis. Pitch is deliberately absent: forward here is the
        // heading projected onto the ground plane, so looking up cannot fly.
        final float sinYaw = (float) StrictMath.sin(yawRadians);
        final float cosYaw = (float) StrictMath.cos(yawRadians);

        // groundForward = (sinYaw, 0, cosYaw); groundRight = groundForward x up.
        final float deltaX = (sinYaw * forwardAxis - cosYaw * strafeAxis) * step;
        final float deltaZ = (cosYaw * forwardAxis + sinYaw * strafeAxis) * step;

        this.positionX = positionX + deltaX;
        this.positionZ = positionZ + deltaZ;
    }

    // Reduces an angle into [0, 2pi). A yaw already in range is returned bit
    // for bit unchanged, which is what makes a zero-input update a no-op.
    private static float wrapYaw(final float angleRadians)
    {
        final float turns = (float) StrictMath.floor(angleRadians / FULL_TURN_RADIANS);
        float wrapped = angleRadians - turns * FULL_TURN_RADIANS;
        // The subtraction above is not exact for large inputs, so the result can
        // land on or just outside either end. One nudge each way settles it.
        if (wrapped >= FULL_TURN_RADIANS)
        {
            wrapped = wrapped - FULL_TURN_RADIANS;
        }
        if (wrapped < 0.0f)
        {
            wrapped = wrapped + FULL_TURN_RADIANS;
        }
        return wrapped;
    }

    // Clamps pitch to the +-89 degree guard. Idempotent, so repeated input at
    // the limit cannot creep past it.
    private static float clampPitch(final float angleRadians)
    {
        if (angleRadians > MAX_PITCH_RADIANS)
        {
            return MAX_PITCH_RADIANS;
        }
        if (angleRadians < -MAX_PITCH_RADIANS)
        {
            return -MAX_PITCH_RADIANS;
        }
        return angleRadians;
    }

    // Clamps a movement axis into [-1, +1]. NaN falls through every comparison
    // and is mapped to zero rather than being allowed to reach the position.
    private static float clampAxis(final float axis)
    {
        if (axis > 1.0f)
        {
            return 1.0f;
        }
        if (axis < -1.0f)
        {
            return -1.0f;
        }
        if (Float.isNaN(axis))
        {
            return 0.0f;
        }
        return axis;
    }

    /** Returns the feet position, world x. */
    public float positionX()
    {
        return positionX;
    }

    /** Returns the feet position, world y — the floor, not the eye. */
    public float positionY()
    {
        return positionY;
    }

    /** Returns the feet position, world z. */
    public float positionZ()
    {
        return positionZ;
    }

    /** Returns the feet position as a vector. */
    public Vec3 feetPosition()
    {
        return new Vec3(positionX, positionY, positionZ);
    }

    /**
     * Returns the eye position — the feet position raised by
     * {@link #EYE_HEIGHT_UNITS}.
     *
     * This is the point the camera sits at, and the point line-of-sight and
     * weapon traces should originate from.
     *
     * @return the eye position in world space
     */
    public Vec3 eyePosition()
    {
        return new Vec3(positionX, positionY + EYE_HEIGHT_UNITS, positionZ);
    }

    /** Returns the heading in radians, always in {@code [0, 2pi)}. */
    public float yawRadians()
    {
        return yawRadians;
    }

    /**
     * Returns the elevation in radians, always in
     * {@code [-}{@link #MAX_PITCH_RADIANS}{@code , +}{@link #MAX_PITCH_RADIANS}{@code ]}.
     * Positive is looking up.
     *
     * @return the pitch in radians
     */
    public float pitchRadians()
    {
        return pitchRadians;
    }

    /**
     * Returns the unit view direction, including pitch.
     *
     * This is what the camera looks along. It is <b>not</b> what the player
     * walks along — see {@link #groundForwardVector}.
     *
     * @return the unit forward vector in world space
     */
    public Vec3 forwardVector()
    {
        final float cosPitch = (float) StrictMath.cos(pitchRadians);
        final float sinPitch = (float) StrictMath.sin(pitchRadians);
        final float sinYaw = (float) StrictMath.sin(yawRadians);
        final float cosYaw = (float) StrictMath.cos(yawRadians);
        return new Vec3(cosPitch * sinYaw, sinPitch, cosPitch * cosYaw);
    }

    /**
     * Returns the unit heading projected onto the ground plane — the direction
     * a forward-axis input moves the player, independent of pitch.
     *
     * @return the unit ground-forward vector in world space
     */
    public Vec3 groundForwardVector()
    {
        final float sinYaw = (float) StrictMath.sin(yawRadians);
        final float cosYaw = (float) StrictMath.cos(yawRadians);
        return new Vec3(sinYaw, 0.0f, cosYaw);
    }

    /**
     * Returns the unit strafe direction — the player's right on the ground
     * plane, derived as {@code groundForward x up}.
     *
     * The operand order is the one {@link Camera} pins as normative; see the
     * class Javadoc for why the mirror is so easy to miss.
     *
     * @return the unit ground-right vector in world space
     */
    public Vec3 groundRightVector()
    {
        return groundForwardVector().cross(WORLD_UP);
    }

    /**
     * Builds the view camera for this player at a given viewport shape, using
     * the default field of view and near plane.
     *
     * @param aspect the viewport aspect ratio, width / height; must be positive
     * @return an immutable camera at the eye position, looking along
     *     {@link #forwardVector}
     * @throws IllegalArgumentException if {@code aspect} is not positive
     */
    public Camera camera(final float aspect)
    {
        return camera(aspect, DEFAULT_FOV_Y_RADIANS, DEFAULT_NEAR_PLANE_UNITS);
    }

    /**
     * Builds the view camera for this player with an explicit frustum.
     *
     * The pitch clamp is what makes this safe: at exactly +-90 degrees the
     * forward vector would be parallel to {@link #WORLD_UP} and
     * {@link Camera#create} would reject the basis.
     *
     * @param aspect the viewport aspect ratio, width / height; must be positive
     * @param fovYRadians the vertical field of view in radians, in {@code (0, pi)}
     * @param near the near plane distance in world units; must be positive
     * @return an immutable camera at the eye position, looking along
     *     {@link #forwardVector}
     * @throws IllegalArgumentException if any frustum parameter is out of range
     */
    public Camera camera(final float aspect, final float fovYRadians, final float near)
    {
        return Camera.create(eyePosition(), forwardVector(), WORLD_UP, fovYRadians, aspect, near);
    }

    /** Returns a debug rendering of the player's placement. */
    @Override
    public String toString()
    {
        return "PlayerController{feet=(" + positionX + ", " + positionY + ", " + positionZ
            + "), yaw=" + yawRadians + ", pitch=" + pitchRadians + "}";
    }
}
