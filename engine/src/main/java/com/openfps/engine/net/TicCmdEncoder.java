/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.net;

import com.openfps.engine.hal.port.InputState;

/**
 * Turns a tic of live input into the integers {@link TicCmd} carries.
 *
 * <p>The one place a {@code float} becomes a wire value. That makes it the one
 * place a quantisation bug can live, and quantisation bugs are the subtle kind:
 * they do not break the connection, they make a remote player's aim drift a
 * fraction of a degree per tic until two peers disagree about whether a shot
 * connected — and only in a networked game, where reproducing it means running
 * two processes.</p>
 *
 * <h2>What each field costs and why</h2>
 *
 * <table border="1">
 *   <caption>Wire quantisation</caption>
 *   <tr><th>Field</th><th>Range in</th><th>Bits</th><th>Resolution</th></tr>
 *   <tr><td>forward, strafe</td><td>-1 .. +1</td><td>16 signed</td>
 *       <td>1/32767 of full deflection</td></tr>
 *   <tr><td>angle (yaw)</td><td>0 .. 2pi</td><td>16 unsigned</td>
 *       <td>0.0055 degrees</td></tr>
 *   <tr><td>pitch</td><td>-89 .. +89 degrees</td><td>8 signed</td>
 *       <td>0.7 degrees</td></tr>
 *   <tr><td>buttons</td><td>-</td><td>8 flags</td><td>-</td></tr>
 * </table>
 *
 * <p><b>Yaw gets 16 bits and pitch gets 8, and that asymmetry is deliberate.</b>
 * Yaw is how you aim horizontally, and a first-person player turns through the
 * full circle constantly; 0.0055 degrees is far below what a hand can produce.
 * Pitch is clamped to a 178-degree band and is almost never the axis a shot is
 * decided on — the targets are human-shaped, so they are three times taller than
 * wide and forgiving vertically. Spending a second byte on pitch would grow
 * every packet by 8% to refine the axis that needs it least.</p>
 *
 * <p><b>Rounding is to nearest, not truncation.</b> Truncating biases every
 * value toward zero, and a bias is not noise: it accumulates in the same
 * direction on every tic, so a player holding a steady turn would drift against
 * their own input rather than jitter around it.</p>
 *
 * <h2>Determinism</h2>
 *
 * <p>Every operation here is {@code + - * /} and a cast, all correctly rounded
 * under JEP 306 and therefore bit-identical on every conforming JVM. No
 * transcendental is used and none is needed, so unlike {@code PlayerController}
 * this class has no {@code StrictMath} requirement — but it is held to the same
 * "no {@code java.lang.Math}" guard anyway, because a future contributor
 * reaching for {@code Math.round} would be reaching for a method whose float
 * overload is defined by a formula rather than by IEEE rounding.</p>
 */
public final class TicCmdEncoder
{
    /** Bit set in {@link TicCmd#buttons()} while the trigger is down. */
    public static final int BUTTON_FIRE = 1;

    /** Bit set while the jump control is down. */
    public static final int BUTTON_JUMP = 1 << 1;

    /** Bit set while the sprint modifier is down. */
    public static final int BUTTON_SPRINT = 1 << 2;

    /** Full deflection on a movement axis, as a wire value. */
    public static final int AXIS_SCALE = TicCmd.MAX_AXIS;

    /** One full turn in radians — the range {@link #encodeAngle} folds into 16 bits. */
    public static final float FULL_TURN_RADIANS = (float) (2.0 * StrictMath.PI);

    /** Distinct yaw values on the wire. */
    public static final int ANGLE_STEPS = TicCmd.MAX_ANGLE + 1;

    /**
     * The pitch band the 8-bit field spans, in radians — {@code +-} this.
     *
     * <p>Matches {@code PlayerController}'s 89-degree clamp, so the encoder's
     * full range is exactly the range the controller can produce. A narrower
     * band here would clip a legal look; a wider one would waste resolution on
     * angles nothing can reach.</p>
     */
    public static final float MAX_PITCH_RADIANS =
        (float) (89.0 * StrictMath.PI / 180.0);

    private TicCmdEncoder()
    {
        // quantisation helper
    }

    /**
     * Quantises a movement axis.
     *
     * @param axis the deflection, nominally {@code -1 .. +1}; clamped, and NaN
     *     is treated as centred
     * @return the wire value, {@code -}{@link TicCmd#MAX_AXIS} to
     *     {@link TicCmd#MAX_AXIS}
     */
    public static int encodeAxis(final float axis)
    {
        if (Float.isNaN(axis))
        {
            // A NaN axis on the wire would decode to a garbage displacement on
            // every peer. Centred is the honest reading of "no usable input".
            return 0;
        }
        final float scaled = axis * AXIS_SCALE;
        if (scaled >= AXIS_SCALE)
        {
            return AXIS_SCALE;
        }
        if (scaled <= -AXIS_SCALE)
        {
            return -AXIS_SCALE;
        }
        return roundToInt(scaled);
    }

    /**
     * Quantises a heading into the 16-bit angle field.
     *
     * <p>Wrapped rather than clamped: an angle is cyclic, so a yaw of
     * {@code 2pi + 0.1} and one of {@code 0.1} are the same heading and must
     * encode to the same value. Clamping would make a player who span past the
     * wrap point appear to stop turning.</p>
     *
     * @param yawRadians the heading; any finite value, wrapped into one turn
     * @return the wire value, 0 to {@link TicCmd#MAX_ANGLE}
     */
    public static int encodeAngle(final float yawRadians)
    {
        if (!Float.isFinite(yawRadians))
        {
            return 0;
        }
        final float turns = yawRadians / FULL_TURN_RADIANS;
        float fraction = turns - (float) (long) turns;
        if (fraction < 0.0f)
        {
            fraction = fraction + 1.0f;
        }
        final int steps = roundToInt(fraction * ANGLE_STEPS);
        // Rounding up from just under a full turn lands on ANGLE_STEPS, which
        // is one past the field. It is the same heading as zero.
        if (steps >= ANGLE_STEPS)
        {
            return 0;
        }
        return steps;
    }

    /**
     * Quantises an elevation into the 8-bit pitch field.
     *
     * <p><b>The range is symmetric, so {@link TicCmd#MIN_PITCH} is never
     * produced.</b> Both extremes map to {@code +-}{@link TicCmd#MAX_PITCH},
     * which spends one of the 256 available codes to keep looking up and looking
     * down exactly as precise as each other. Using the full two's-complement
     * range would make the downward half one step finer than the upward half —
     * a difference nobody would ever see, in exchange for an asymmetry that
     * makes {@code decode(encode(x)) == -decode(encode(-x))} stop holding. The
     * same choice is made for the movement axes, and for the same reason.</p>
     *
     * @param pitchRadians the elevation, clamped to
     *     {@code +-}{@link #MAX_PITCH_RADIANS}
     * @return the wire value, {@code -}{@link TicCmd#MAX_PITCH} to
     *     {@link TicCmd#MAX_PITCH}
     */
    public static int encodePitch(final float pitchRadians)
    {
        if (Float.isNaN(pitchRadians))
        {
            return 0;
        }
        final float scaled = pitchRadians / MAX_PITCH_RADIANS * TicCmd.MAX_PITCH;
        if (scaled >= TicCmd.MAX_PITCH)
        {
            return TicCmd.MAX_PITCH;
        }
        // -MAX_PITCH, not MIN_PITCH. Clamping to the two's-complement floor is
        // the obvious thing to write and makes the range asymmetric by one step
        // at exactly the values that reach it — so an extreme downward look
        // would encode a hair steeper than the matching upward one. Same rule as
        // the axes above.
        if (scaled <= -TicCmd.MAX_PITCH)
        {
            return -TicCmd.MAX_PITCH;
        }
        return roundToInt(scaled);
    }

    /**
     * Packs the action flags of an input snapshot.
     *
     * @param input the snapshot; must not be null
     * @return the button bitmask
     */
    public static int encodeButtons(final InputState input)
    {
        if (input == null)
        {
            throw new IllegalArgumentException("input must not be null");
        }
        int bits = 0;
        if (input.fire())
        {
            bits = bits | BUTTON_FIRE;
        }
        if (input.jump())
        {
            bits = bits | BUTTON_JUMP;
        }
        if (input.sprint())
        {
            bits = bits | BUTTON_SPRINT;
        }
        return bits;
    }

    /**
     * Reverses {@link #encodeAxis}.
     *
     * @param wire the wire value
     * @return the deflection in {@code -1 .. +1}
     */
    public static float decodeAxis(final int wire)
    {
        return (float) wire / AXIS_SCALE;
    }

    /**
     * Reverses {@link #encodeAngle}.
     *
     * @param wire the wire value
     * @return the heading in {@code [0, 2pi)}
     */
    public static float decodeAngle(final int wire)
    {
        return (float) wire / ANGLE_STEPS * FULL_TURN_RADIANS;
    }

    /**
     * Reverses {@link #encodePitch}.
     *
     * @param wire the wire value
     * @return the elevation in radians
     */
    public static float decodePitch(final int wire)
    {
        return (float) wire / TicCmd.MAX_PITCH * MAX_PITCH_RADIANS;
    }

    /**
     * Returns whether a button bit is set.
     *
     * @param buttons the bitmask from a command
     * @param bit one of the {@code BUTTON_} constants
     * @return true if that action was held
     */
    public static boolean isDown(final int buttons, final int bit)
    {
        return (buttons & bit) != 0;
    }

    // Nearest-integer rounding, written out rather than Math.round.
    //
    // Two reasons, and the second is the real one. Math.round(float) is defined
    // as floor(x + 0.5), which is NOT round-to-nearest for the handful of values
    // where the addition itself rounds up — a documented quirk, not a bug. And
    // this class is held to the same flat "no java/lang/Math" guard as every
    // other class whose output crosses the wire, so that the check needs no
    // exceptions to remember.
    private static int roundToInt(final float value)
    {
        if (value < 0.0f)
        {
            return (int) (value - 0.5f);
        }
        return (int) (value + 0.5f);
    }
}
