/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.port;

/**
 * The exact slice of per-tic input that
 * {@link com.openfps.engine.gameplay.PlayerController} consumes.
 *
 * <p><b>Why this interface exists, and what is meant to happen to it.</b> The
 * HAL is growing an {@code InputState} value type in
 * {@code com.openfps.engine.hal.port} that carries movement axes, look deltas
 * <i>and</i> action flags (fire, jump, use, ...). The controller needs four
 * numbers out of that and nothing else. Declaring those four here rather than
 * depending on the full type keeps the controller a pure function of its
 * arguments, keeps {@code gameplay} off the HAL for a value object, and — the
 * immediate reason — lets this class be written and tested before the HAL type
 * lands. When it does, the adapter is one class: an {@code InputState} wrapper
 * (or an {@code implements} clause on {@code InputState} itself, since the
 * method names were chosen to match). <b>Do not grow a second copy of
 * {@code InputState} here.</b> Anything that is not consumed by the movement
 * and look integration does not belong on this interface.</p>
 *
 * <p><b>Units and ranges are normative</b>, because the controller trusts them
 * and clamps rather than validates:</p>
 *
 * <ul>
 *   <li>The two axes are dimensionless, nominally in {@code [-1, +1]}. They are
 *       an <i>intent</i>, not a velocity: the controller supplies the speed.
 *       Values outside the range are clamped, and a combined magnitude above 1
 *       is scaled back to 1 so that holding forward and strafe together is not
 *       faster than either alone.</li>
 *   <li>The two look deltas are in <b>radians</b>, already accumulated for this
 *       tic. Converting mouse counts or stick deflection into radians is the
 *       input layer's job, including sensitivity and any invert-Y preference —
 *       the controller applies what it is given, with no scaling of its own.</li>
 * </ul>
 *
 * <p><b>Sign conventions</b> match the controller's, and are restated on each
 * method. They follow the right-handed, +y-up world described in
 * {@code render/README.md} § 4.</p>
 */
public interface I_PlayerInput
{
    /**
     * Returns the forward/back movement intent, nominally in {@code [-1, +1]}.
     *
     * Positive walks toward the direction the player is facing, projected onto
     * the ground plane; negative walks backwards.
     *
     * @return the forward axis; clamped by the controller if out of range
     */
    float forwardAxis();

    /**
     * Returns the strafe movement intent, nominally in {@code [-1, +1]}.
     *
     * Positive strafes to the player's right — the same "right" the camera
     * basis derives as {@code forward x up} — and negative to the left.
     *
     * @return the strafe axis; clamped by the controller if out of range
     */
    float strafeAxis();

    /**
     * Returns this tic's accumulated yaw change, in radians.
     *
     * Positive turns from world +z toward world +x, which is the direction the
     * controller's yaw angle increases in.
     *
     * @return the yaw delta in radians
     */
    float yawDelta();

    /**
     * Returns this tic's accumulated pitch change, in radians.
     *
     * Positive looks <b>up</b>. If a platform reports mouse motion with y
     * growing downwards, the input layer negates it; the controller does not
     * guess.
     *
     * @return the pitch delta in radians
     */
    float pitchDelta();

    /**
     * Returns whether the jump control was held during this tic.
     *
     * <p><b>This is on the interface because the controller integrates it</b>,
     * which is the bar the class Javadoc sets — it is not an action flag passed
     * through for someone else's benefit. Vertical position is part of the same
     * movement integration as the horizontal axes, and the controller cannot
     * compute it without knowing whether the player asked to leave the
     * ground.</p>
     *
     * <p>A <i>level</i>, not an edge: true on every tic the control is held.
     * {@code PlayerController} takes the rising edge itself, by refusing to jump
     * unless the player is standing on something, so holding the key does not
     * pogo.</p>
     *
     * @return true if the jump control was active during the sampled tic
     */
    boolean jump();
}
