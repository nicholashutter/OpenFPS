/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

import com.openfps.engine.gameplay.PlayerController;
import com.openfps.engine.gameplay.port.I_PlayerInput;
import com.openfps.engine.render.adapter.Mat4;
import com.openfps.engine.render.adapter.Scene;
import com.openfps.engine.render.adapter.SoftwareRenderPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P_ The local player's own first-person body — arms, hands, and the bob that
 * makes them feel held rather than mounted.
 *
 * <p>The viewmodel {@code blaster-b.ofm} was always a pistol floating in the
 * lower-right of the frame: a weapon you could see, not one you could believe
 * you were holding. This class is the other half — the arms, hands and bob
 * that turn a held weapon into a held weapon. The arms are
 * {@link FirstPersonArms}; this class is the seam that places them, animates
 * them, and ties them to the player's movement.</p>
 *
 * <h2>The body is one world instance, moved per tic</h2>
 *
 * <p>{@link Scene} is immutable, so a body that wasn't placed at scene build
 * time cannot exist afterwards. {@link #addTo} places it once — {@link
 * FirstPersonArms#model()} at the eye, with the model's own coordinates
 * already in view space — and {@link #publish} moves it every tic to follow
 * the player's eye, yaw and pitch, with a vertical bob in world space.</p>
 *
 * <p>One instance is enough. There is only ever one local player, so a pool
 * is the wrong shape — and {@code Scene}'s immutability means a fresh instance
 * per tic is also the wrong shape, because that would rebuild the scene per
 * tic.</p>
 *
 * <h2>View-space authoring, world-space bob</h2>
 *
 * <p>The arms are authored in <b>view space</b>: each box of the model is at a
 * {@code (x, y, z)} offset from the eye with {@code +x} right, {@code +y} up
 * and {@code +z} forward. The placement built by {@link #armsTransform}
 * applies the player's yaw and pitch as a rotation and the eye as a
 * translation, so the arms appear fixed in the view at exactly the offsets
 * the model was authored to. The bob is added as a <b>world-space Y
 * translation</b> after the rotation, so the arms bounce up and down with the
 * body rather than drifting in the view.</p>
 *
 * <h2>Why the body is a world instance, not a view instance</h2>
 *
 * <p>The viewmodel is a view instance because it has to draw over walls the
 * player is standing against — the depth-cleared buffer is the whole point.
 * The arms do not have that requirement: an arm sticking through a wall is
 * wrong, and a world instance depth-tests against the wall and stops at it.
 * The cost is that the viewmodel always draws on top of the arms, which is
 * why the hands are placed at the back of the grip rather than wrapping
 * around it — see {@link FirstPersonArms}'s class Javadoc for the
 * back-of-hand pose that makes the depth ordering invisible.</p>
 *
 * <h2>Bob: time-based, not stride-based</h2>
 *
 * <p>The phase accumulates at a rate proportional to the magnitude of the
 * player's movement input, and the amplitude scales with the same
 * magnitude. A held-forward walk produces a steady bob; a sprint produces a
 * faster, larger bob; standing still produces no bob at all. The phase is
 * <b>not</b> reset to zero when the player stops — it freezes, so a sudden
 * stop in mid-stride leaves the arms at the right vertical offset rather
 * than snapping back to the centre line.</p>
 *
 * <p>All bob inputs are floats in {@code [-1, +1]} clamped by the controller,
 * so the intensity is in {@code [0, +sqrt(2)]}. {@link #BOB_AMPLITUDE_UNITS}
 * is the peak Y offset at full intensity — a few centimetres at the
 * player's scale, enough to read as a body bouncing and not enough to feel
 * like a vehicle suspension.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>Not thread-safe. {@link #publish} mutates the bob phase and is called
 * from the game loop thread under {@code DemoGameplayPort}'s tic lock, which
 * is the same thread the {@link PlayerController} updates on. Nothing else
 * reads or writes the phase.</p>
 */
public final class LocalPlayerBody
{
    private static final Logger LOG = LoggerFactory.getLogger(LocalPlayerBody.class);

    /**
     * Peak vertical bob in world units, at full movement intensity.
     *
     * <p>Three world units against an eye height of 41, which is about seven
     * percent — visible as a body bouncing, not so much that the held weapon
     * appears to be on a shock absorber. Half of this is on each side of the
     * centre line, so the total swing is six units peak-to-peak.</p>
     */
    public static final float BOB_AMPLITUDE_UNITS = 3.0f;

    /**
     * Step rate at full movement intensity, in Hz — <b>2.5</b>.
     *
     * <p>Just over a full cycle per second at full speed, which is the
     * walking cadence a person actually has. A faster rate reads as jogging
     * and a slower one as creeping, and neither is the default the demo
     * assumes. Doubled for sprint when the input is at full intensity, the
     * same way the controller's speed scales — a separate constant is not
     * worth the extra state, since the bob is decoration, not feedback.</p>
     */
    public static final float BOB_FREQUENCY_HZ = 2.5f;

    /**
     * Maximum combined axis magnitude — {@code sqrt(1 + 1)} for a forward+strafe
     * held together, since each axis is clamped to {@code [-1, +1]} and the
     * controller scales the combined magnitude back to 1 at the end of
     * {@code applyMove}. The bob treats the unclamped magnitude so a held
     * diagonal keeps the same amplitude as a held cardinal.
     */
    private static final float MAX_INTENSITY = (float) StrictMath.sqrt(2.0);

    /** The world instance the arms are at. Set by {@link #addTo}, never moved. */
    private final int armsInstance;

    /** Phase of the bob cycle, in radians. MUTABLE: advanced by {@link #publish}. */
    private float bobPhase;

    /**
     * One-armed helper: takes ownership of the instance the builder handed
     * back.
     */
    private LocalPlayerBody(final int instanceIndex)
    {
        this.armsInstance = instanceIndex;
    }

    /**
     * Places the local player's arms into a scene under construction.
     *
     * <p>Call once, while the scene is being built. The arms are added as a
     * single world instance with an identity transform — they sit on the
     * world origin at the moment of build, which is hidden by the first
     * {@link #publish} before a frame is drawn. A separate transform for the
     * build-time placement would be one more thing to keep in sync with the
     * per-tic one.</p>
     *
     * @param builder the scene under construction; the instance is appended
     * @return the local player body, holding the scene index of the arms
     */
    public static LocalPlayerBody addTo(final Scene.Builder builder)
    {
        if (builder == null)
        {
            throw new IllegalArgumentException("builder must not be null");
        }

        final int at = builder.worldInstanceCount();

        // UNTAGGED: the local player is not a target — it would shoot itself on
        // every trigger pull, and that is the bug Hitscan was built to avoid
        // for the bots. The outline pass would also draw a band of red around
        // a body that is always centred on the camera, which is the most
        // conspicuous possible artefact.
        builder.addWorldInstance(FirstPersonArms.model(), Mat4.identity());

        LOG.info("Local player body: arms placed at world instance {}", at);

        return new LocalPlayerBody(at);
    }

    /**
     * Moves the arms to where the camera is, with a bob in world Y.
     *
     * <p>No scene bound is a silent no-op, because the game loop publishes
     * tics from the moment it starts and that is before the launcher has
     * called {@link SoftwareRenderPort#setScene}. The same reasoning
     * {@code DemoGameplayPort.publishBotPlacements} gives: an override on a
     * renderer with no instance table would throw, and the cost of checking
     * is one reference compare.</p>
     *
     * @param renderer the renderer to publish into; must not be null
     * @param controller the local player, providing eye, yaw and pitch;
     *     must not be null
     * @param input the latest latched input; must not be null. The bob
     *     intensity is derived from its forward and strafe axes
     * @param deltaSeconds the tic duration, in seconds; must be
     *     non-negative
     * @throws IllegalArgumentException if any argument is null, or
     *     {@code deltaSeconds} is negative
     */
    public void publish(final SoftwareRenderPort renderer, final PlayerController controller,
        final I_PlayerInput input, final float deltaSeconds)
    {
        if (renderer == null)
        {
            throw new IllegalArgumentException("renderer must not be null");
        }

        if (controller == null)
        {
            throw new IllegalArgumentException("controller must not be null");
        }

        if (input == null)
        {
            throw new IllegalArgumentException("input must not be null");
        }

        if (!(deltaSeconds >= 0.0f))
        {
            throw new IllegalArgumentException("deltaSeconds must be non-negative, got "
                + deltaSeconds);
        }

        if (renderer.scene() == null)
        {
            return;
        }

        // StrictMath.hypot is bit-exact across JVMs and is what the
        // controller itself uses; consistency is the bar. Clamp to
        // MAX_INTENSITY so the controller's diagonal-scaling does not
        // change the bob: a held forward (1, 0) and a held diagonal
        // (1/√2, 1/√2) both have unit intensity.
        final float forward = clamp(input.forwardAxis());

        final float strafe = clamp(input.strafeAxis());

        final float intensity = (float) StrictMath.hypot(forward, strafe);

        float clamped = intensity;

        if (clamped > MAX_INTENSITY)
        {
            clamped = MAX_INTENSITY;
        }

        // Phase advances only while moving: a held trigger with no movement
        // intent does not bob. The frequency is a per-second rate, multiplied
        // by the intensity so a held forward bobs at full speed and a held
        // diagonal bobs at the same rate.
        this.bobPhase = bobPhase
            + (float) (clamped * BOB_FREQUENCY_HZ * 2.0 * StrictMath.PI * deltaSeconds);

        // Normalise the phase to keep float precision from degrading during
        // a long sprint — at 1e7 radians the spacing between adjacent floats
        // is about a radian, and the bob would visibly quantise.
        this.bobPhase = wrapPhase(bobPhase);

        final float bobY = clamped / MAX_INTENSITY * BOB_AMPLITUDE_UNITS
            * (float) StrictMath.sin(bobPhase);

        final float eyeX = controller.positionX();

        final float eyeY = controller.positionY() + PlayerController.EYE_HEIGHT_UNITS;

        final float eyeZ = controller.positionZ();

        renderer.setWorldTransform(armsInstance, armsTransform(eyeX, eyeY, eyeZ,
            controller.yawRadians(), controller.pitchRadians(), bobY));
    }

    /**
     * Builds the arms' model-to-world transform.
     *
     * <p>The transform is the camera basis in world space, with the model's
     * own view-space origin at the eye. The basis columns are the world
     * directions of the view's right, up and <b>backward</b> axes — the
     * model is in a right-handed view space where {@code +Z} is behind the
     * eye, so the basis is {@code (right, up, -forward)} and the matrix has
     * positive determinant. {@code Scene} refuses a negative determinant
     * outright, and that is the only reason this is not {@code (right, up,
     * forward)}.</p>
     *
     * <p>The mapping for the test points:</p>
     *
     * <ul>
     *   <li>view {@code (1, 0, 0)} lands on world {@code -X} (player's right)</li>
     *   <li>view {@code (0, 1, 0)} lands on world {@code +Y} (up is up)</li>
     *   <li>view {@code (0, 0, 1)} lands on world {@code -Z} (behind the eye)</li>
     *   <li>view {@code (0, 0, -1)} lands on world {@code +Z} (in front of the eye)</li>
     * </ul>
     *
     * <p>Static and pure so a test can call it against a known controller
     * state and assert where the arms landed. It is the only place this
     * matrix is built.</p>
     *
     * @param eyeX eye position, world x
     * @param eyeY eye position, world y
     * @param eyeZ eye position, world z
     * @param yawRadians player yaw, in {@code PlayerController}'s convention
     * @param pitchRadians player pitch, in {@code PlayerController}'s convention
     * @param bobY world-Y offset applied after the rotation, in world units
     * @return the arms' transform this tic
     */
    public static Mat4 armsTransform(final float eyeX, final float eyeY, final float eyeZ,
        final float yawRadians, final float pitchRadians, final float bobY)
    {
        // StrictMath for bit-identical lockstep: see PlayerController. The
        // matrix is built once per tic and reads from the player's
        // already-deterministic state, so this is consistency rather than a
        // new source of divergence.
        final float cosYaw = (float) StrictMath.cos(yawRadians);

        final float sinYaw = (float) StrictMath.sin(yawRadians);

        final float cosPitch = (float) StrictMath.cos(pitchRadians);

        final float sinPitch = (float) StrictMath.sin(pitchRadians);

        final float cosPitchSq = cosPitch * cosPitch;

        // Camera basis in world coordinates, derived as forward x worldUp for
        // right and right x forward for up — the same operand order the
        // engine's other view bases use, so two views of "where is right"
        // cannot come apart. The third column is -forward, not forward,
        // which makes the basis right-handed and the matrix's determinant
        // positive.
        //
        //   right   = forward x worldUp
        //           = (-cosYaw*cosPitch, 0, sinYaw*cosPitch)
        //   up      = right x forward
        //           = (-sinYaw*sinPitch*cosPitch, cosPitch^2, -cosYaw*sinPitch*cosPitch)
        //   -forward = -(sinYaw*cosPitch, sinPitch, cosYaw*cosPitch)
        //           = (-sinYaw*cosPitch, -sinPitch, -cosYaw*cosPitch)
        //
        // At yaw 0, pitch 0: right = (-1, 0, 0), up = (0, 1, 0), -forward = (0, 0, -1).
        // det([right | up | -forward]) = +1.
        return Mat4.ofRowMajor(new float[]
        {
            // row 0: (right.x, up.x, -forward.x, translation.x)
            -cosYaw * cosPitch,
            -sinYaw * sinPitch * cosPitch,
            -sinYaw * cosPitch,
            eyeX,
            // row 1: (right.y, up.y, -forward.y, translation.y)
            0.0f,
            cosPitchSq,
            -sinPitch,
            eyeY + bobY,
            // row 2: (right.z, up.z, -forward.z, translation.z)
            sinYaw * cosPitch,
            -cosYaw * sinPitch * cosPitch,
            -cosYaw * cosPitch,
            eyeZ,
            // row 3
            0.0f, 0.0f, 0.0f, 1.0f,
        });
    }

    /**
     * Returns whether a controller's forward/strafe axis is in range, and
     * clamps it to {@code [-1, +1]} if not. Exists because the controller
     * already clamps but the bob computation trusts its inputs, and a NaN
     * here would propagate to the phase and the sin.
     */
    private static float clamp(final float axis)
    {
        if (axis > 1.0f)
        {
            return 1.0f;
        }

        if (axis < -1.0f)
        {
            return -1.0f;
        }

        return axis;
    }

    /**
     * Reduces a phase to {@code [0, 2pi)} so a long sprint does not run the
     * float's precision out from under the bob.
     */
    private static float wrapPhase(final float phase)
    {
        final float twoPi = (float) (2.0 * StrictMath.PI);

        float reduced = phase - (float) Math.floor(phase / twoPi) * twoPi;

        if (reduced < 0.0f)
        {
            reduced = reduced + twoPi;
        }

        return reduced;
    }
}
