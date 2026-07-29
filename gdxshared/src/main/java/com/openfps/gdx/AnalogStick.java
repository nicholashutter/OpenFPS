/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

/**
 * The shape of a thumbstick: dead zone, radial response, and the threshold that
 * turns a trigger into a button.
 *
 * <p>Pure arithmetic, no state, no toolkit import — the same split
 * {@link InputAccumulator} and {@code TouchLayout} are built on, and for the
 * same reason. Every number below is a decision that can be wrong in a way a
 * player feels immediately and CI would otherwise never see, so all of it lives
 * where a headless JVM can measure it.</p>
 *
 * <h2>Why a resting stick is not zero, and what that costs</h2>
 *
 * <p>A physical stick reports small non-zero values at rest — potentiometer
 * noise on a new pad, and a permanent off-centre bias on a worn one. Fed
 * straight through, that is a camera that rotates forever in one direction
 * while nobody is touching the controller, and a movement axis that is never
 * quite zero, so a networked peer receives a non-neutral tic command every
 * single tic of an idle player. {@link #DEAD_ZONE} is the answer, and
 * {@link #responseScale} returns <b>exactly</b> {@code 0.0f} inside it rather
 * than something small — a value that merely rounds to nothing still fails
 * {@code InputState.isNeutral()}.</p>
 *
 * <h2>The dead zone is radial, and that is not a detail</h2>
 *
 * <p>It is applied to the <b>magnitude of the pair</b>, never to each axis on
 * its own. A per-axis dead zone of 0.2 makes the ignored region a square: a
 * stick pushed to (0.19, 0.19) reads as centred, but the same stick pushed the
 * same physical distance along a cardinal — (0.27, 0.0) — reads as moving. The
 * player experiences that as a controller that has a smaller dead zone
 * diagonally than straight ahead, and as diagonals that snap to a cardinal as
 * they cross the boundary. A circle has no corners and no direction is
 * special.</p>
 *
 * <h2>The dead zone is rescaled, so no travel is lost</h2>
 *
 * <p>Clipping alone would leave the stick unable to express anything between
 * "nothing" and 20% — the output would jump from 0 to 0.2 the moment the
 * boundary was crossed, which reads as a stick that is stiff and then suddenly
 * runs away. So the surviving range is stretched back over the full 0..1:
 * {@code t = (magnitude - DEAD_ZONE) / (1 - DEAD_ZONE)}. Output is continuous
 * from 0, full deflection still means 1, and the dead zone costs resolution
 * rather than range.</p>
 *
 * <h2>The response curve</h2>
 *
 * <p>{@code t} is then raised to an exponent. This is what lets one stick do
 * two jobs that pull in opposite directions: a small deflection has to aim
 * precisely, and a large one has to turn fast enough to answer someone behind
 * you. Linear cannot do both — pick a rate that turns quickly and the smallest
 * movement the player can make with a thumb is already too coarse to track a
 * target. At {@link #LOOK_EXPONENT} a half-pushed stick turns at a quarter
 * speed, so the precise half of the range is twice as long as it would
 * otherwise be, and the stops are unchanged.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class AnalogStick
{
    /**
     * Deflection below which a stick reads as centred — <b>0.20</b>, radial.
     *
     * <p>Chosen against the figure the hardware vendor publishes rather than by
     * feel. Microsoft's XInput documentation gives a recommended left-stick
     * dead zone of 7849 out of 32767, which is 0.2395, and 8689/32767 = 0.2652
     * for the right; those are the values the controller most people will plug
     * in was characterised against. 0.20 sits just under the smaller of the two:
     * far enough out to swallow the resting noise of a pad in good condition,
     * and deliberately not so far that a controller in good condition pays for
     * a worn one. Because the range is rescaled rather than clipped, the cost is
     * resolution — about a fifth of the stick's travel worth — and not reach.</p>
     */
    public static final float DEAD_ZONE = 0.20f;

    /**
     * Response exponent for a look stick — <b>2</b>.
     *
     * <p>Square rather than cube. A cubic curve gives finer aim still, but the
     * middle of the range goes so slack that a deliberate medium-speed turn
     * becomes hard to hold steady, and that is the movement a player makes most
     * often. Squared keeps a quarter speed at half deflection, which is enough
     * separation to track a target with the thumb tip while leaving the middle
     * of the range usable. It is also exactly reversible in a test: 0.5 in must
     * give 0.25 out.</p>
     */
    public static final float LOOK_EXPONENT = 2.0f;

    /**
     * Response exponent for a movement stick — <b>1</b>, meaning no curve.
     *
     * <p>Movement is not aiming. There is no precision task at the bottom of
     * this stick's range — the player is choosing a walking direction, and the
     * speed is the character's rather than the thumb's — so a curve here would
     * only make the character feel reluctant to start moving. Named as a
     * constant rather than passed as a bare {@code 1.0f} so the choice is
     * visible at the call site and so the two sticks are stated in the same
     * vocabulary.</p>
     */
    public static final float MOVE_EXPONENT = 1.0f;

    /**
     * How far a trigger must be pulled to count as a press — <b>0.5</b>, on a
     * 0..1 scale where 0 is released.
     *
     * <p>Half. A trigger bound to {@link com.openfps.engine.hal.port.GameAction#FIRE}
     * has to become a boolean somewhere, and half travel is where a player's
     * own sense of "I have pulled it" sits; it is also far enough from the rest
     * position that a trigger with a sloppy return spring does not fire the
     * weapon on its own. Platforms owe this method a normalised 0..1 pull —
     * GLFW reports triggers over −1..1 and Android over 0..1, and reconciling
     * that at the point of the discrepancy is what keeps one threshold
     * meaningful on both.</p>
     */
    public static final float TRIGGER_THRESHOLD = 0.5f;

    private AnalogStick()
    {
        // arithmetic holder
    }

    /**
     * Returns the factor a raw axis pair must be multiplied by to apply the
     * dead zone and the response curve.
     *
     * <p>One scalar for the pair rather than a value per axis, and that is the
     * whole trick: multiplying both components of a vector by the same number
     * changes its length and never its direction, so the dead zone and the
     * curve are radial by construction and cannot be got wrong per-axis. It is
     * also why this is the shape the arithmetic takes at all — a caller cannot
     * accidentally shape x without y.</p>
     *
     * <p>The magnitude is clamped to 1 before the curve is applied. A stick with
     * a square gate reports up to √2 on a diagonal, and {@code 1.41²} is 2 — a
     * curve fed an out-of-range input would <i>amplify</i> a diagonal to twice
     * the speed of a cardinal, which is the exact bug the curve exists to avoid
     * the mirror image of. Clamping the curve's input is not the unit-disc clamp
     * on movement; that one belongs to {@code InputState.of} and is not repeated
     * here.</p>
     *
     * @param rawX one axis of the pair, as the device reports it
     * @param rawY the other axis of the pair, as the device reports it
     * @param exponent the response curve; {@link #MOVE_EXPONENT} for linear,
     *     {@link #LOOK_EXPONENT} for an aiming stick. Must be finite and at
     *     least 1
     * @return the multiplier, 0 inside the dead zone and never negative
     * @throws IllegalArgumentException if either axis is not finite, or the
     *     exponent is not a finite number of at least 1
     */
    public static float responseScale(final float rawX, final float rawY,
        final float exponent)
    {
        requireFinite(rawX, "rawX");
        requireFinite(rawY, "rawY");
        if (!(exponent >= 1.0f) || Float.isInfinite(exponent))
        {
            throw new IllegalArgumentException(
                "exponent must be finite and at least 1, got " + exponent);
        }
        final float magnitude = (float) Math.sqrt(rawX * rawX + rawY * rawY);
        if (magnitude <= DEAD_ZONE)
        {
            // Exactly zero, not merely small. See the class Javadoc: a resting
            // stick that reports 0.0001 is a player who is never neutral.
            return 0.0f;
        }
        final float rescaled = (magnitude - DEAD_ZONE) / (1.0f - DEAD_ZONE);
        final float curved = (float) Math.pow(clampUnit(rescaled), exponent);
        // Back from "how long should the vector be" to "what do I multiply the
        // components by". Safe: magnitude is strictly greater than DEAD_ZONE,
        // which is positive, so this cannot divide by zero.
        return curved / magnitude;
    }

    /**
     * Returns one shaped component of an axis pair.
     *
     * @param rawX the component to shape, as the device reports it
     * @param rawY the other component of the same pair
     * @param exponent the response curve; see {@link #responseScale}
     * @return the shaped component, exactly 0 while the pair rests inside the
     *     dead zone
     * @throws IllegalArgumentException if an argument is out of range
     */
    public static float shape(final float rawX, final float rawY, final float exponent)
    {
        return rawX * responseScale(rawX, rawY, exponent);
    }

    /**
     * Returns whether a trigger is pulled far enough to read as a press.
     *
     * @param pull the trigger's travel, 0 released to 1 fully pulled
     * @return true once {@link #TRIGGER_THRESHOLD} is reached
     */
    public static boolean isTriggerPulled(final float pull)
    {
        return pull >= TRIGGER_THRESHOLD;
    }

    /**
     * Converts a trigger reported over −1..1 into the 0..1 this class expects.
     *
     * <p>GLFW rests its trigger axes at −1, which is not a bug and not a sign
     * convention anyone chose: the axis is a joystick axis, and a joystick axis
     * is centred. Normalising it here rather than picking a second threshold
     * keeps {@link #TRIGGER_THRESHOLD} meaning the same physical pull on every
     * platform.</p>
     *
     * @param centredValue the trigger as reported, −1 released to 1 pulled
     * @return the travel, 0 released to 1 pulled
     */
    public static float triggerFromCentred(final float centredValue)
    {
        return (clampSigned(centredValue) + 1.0f) * 0.5f;
    }

    // Folds a rescaled magnitude back into 0..1. See responseScale on why the
    // curve's input has to be bounded.
    private static float clampUnit(final float value)
    {
        if (value > 1.0f)
        {
            return 1.0f;
        }
        return value;
    }

    // Folds a raw axis reading back into -1..1.
    private static float clampSigned(final float value)
    {
        if (value > 1.0f)
        {
            return 1.0f;
        }
        if (value < -1.0f)
        {
            return -1.0f;
        }
        return value;
    }

    // Rejects NaN and infinity before either can reach the simulation, the same
    // rule InputState.of applies and for the same reason: a NaN axis poisons a
    // simulation permanently, and a driver reporting one is worth failing on.
    private static void requireFinite(final float value, final String name)
    {
        if (Float.isNaN(value) || Float.isInfinite(value))
        {
            throw new IllegalArgumentException(name + " must be finite, got " + value);
        }
    }
}
