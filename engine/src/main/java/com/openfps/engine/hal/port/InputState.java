/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.port;

/**
 * One immutable snapshot of local player input, latched for a single tic.
 *
 * <p>This is the entire vocabulary the engine has for "what the player is
 * doing right now". It is deliberately device-neutral: a keyboard, a gamepad
 * and a replay file all produce the same seven values, so nothing downstream
 * can tell them apart. That is what makes a recorded or networked tic
 * reproducible.</p>
 *
 * <p><b>Two kinds of value, and the difference matters.</b> The movement axes
 * are a <i>level</i>: "the stick is pushed this far, right now". The look
 * deltas are an <i>integral</i>: "the view was turned this much since the
 * previous snapshot". A level can be re-read harmlessly; a delta must be
 * consumed exactly once, which is why {@link I_InputPort#sampleInput(int)}
 * exists as a separate call from {@link I_InputPort#currentInput()}.</p>
 *
 * <p><b>Sign conventions</b> — chosen so the numbers describe the player's
 * intent rather than any one renderer's basis:</p>
 * <ul>
 *   <li>{@code forwardAxis} &gt; 0 moves forward, &lt; 0 moves back.</li>
 *   <li>{@code strafeAxis} &gt; 0 moves right, &lt; 0 moves left.</li>
 *   <li>{@code yawDelta} &gt; 0 turns the view <b>right</b>.</li>
 *   <li>{@code pitchDelta} &gt; 0 tilts the view <b>up</b>.</li>
 * </ul>
 *
 * <p>The world is right-handed with +y up ({@code render.adapter.Camera}), so
 * a consumer building a forward vector rotates by {@code -yaw} about +y and
 * {@code +pitch} about the camera's right axis. Fixing the convention here
 * rather than in each adapter means an inverted mouse is one negation in one
 * place, not a per-platform accident.</p>
 *
 * <p>Both axes are clamped to −1..1 at construction, and their combined
 * magnitude is never allowed past 1 — see {@link #of}. Look deltas are
 * unbounded: a fast flick really is a large angle, and clamping it here would
 * silently eat rotation the player performed.</p>
 *
 * <p>Immutable, all-primitive, no boxing. Instances are safe to publish across
 * threads without further synchronisation, which is exactly what the
 * render-thread-samples / loop-thread-consumes split needs.</p>
 */
public final class InputState
{
    /**
     * The zero snapshot: standing still, looking nowhere, nothing pressed.
     *
     * Returned by the null adapter, by any port that has not sampled yet, and
     * used as the starting value everywhere a snapshot field needs one. Shared
     * because it is immutable — never allocate another.
     */
    public static final InputState NEUTRAL = new InputState(0.0f, 0.0f, 0.0f, 0.0f,
        false, false, false);

    /** Upper bound on either movement axis, and on their combined magnitude. */
    public static final float AXIS_LIMIT = 1.0f;

    private final float forwardAxis;

    private final float strafeAxis;

    private final float yawDelta;

    private final float pitchDelta;

    private final boolean fire;

    private final boolean jump;

    private final boolean sprint;

    // Private so every instance goes through of(), which is the only place the
    // clamping invariant is enforced. NEUTRAL is already valid by inspection.
    private InputState(final float forwardAxisValue, final float strafeAxisValue,
        final float yawDeltaRadians, final float pitchDeltaRadians,
        final boolean fireDown, final boolean jumpDown, final boolean sprintDown)
    {
        this.forwardAxis = forwardAxisValue;
        this.strafeAxis = strafeAxisValue;
        this.yawDelta = yawDeltaRadians;
        this.pitchDelta = pitchDeltaRadians;
        this.fire = fireDown;
        this.jump = jumpDown;
        this.sprint = sprintDown;
    }

    /**
     * Builds a snapshot, clamping the movement axes into range.
     *
     * <p>Axes are first clamped individually to −1..1, then scaled together if
     * their combined magnitude still exceeds 1. The second step is what stops
     * a diagonal from being 41% faster than a straight line — the classic
     * bug — and doing it here means every adapter gets it right rather than
     * every adapter having to remember. NaN in either axis is rejected: a NaN
     * velocity poisons a simulation permanently and is worth failing loudly
     * for.</p>
     *
     * @param forwardAxisValue forward/back, −1..1, positive is forward
     * @param strafeAxisValue left/right, −1..1, positive is right
     * @param yawDeltaRadians view rotation in radians since the last snapshot,
     *     positive turns right
     * @param pitchDeltaRadians view rotation in radians since the last
     *     snapshot, positive tilts up
     * @param fireDown true if the attack action was active during the tic
     * @param jumpDown true if the jump action was active during the tic
     * @param sprintDown true if the sprint modifier was active during the tic
     * @return an immutable snapshot with the axes normalised
     * @throws IllegalArgumentException if any float argument is NaN or infinite
     */
    public static InputState of(final float forwardAxisValue, final float strafeAxisValue,
        final float yawDeltaRadians, final float pitchDeltaRadians,
        final boolean fireDown, final boolean jumpDown, final boolean sprintDown)
    {
        requireFinite(forwardAxisValue, "forwardAxis");
        requireFinite(strafeAxisValue, "strafeAxis");
        requireFinite(yawDeltaRadians, "yawDelta");
        requireFinite(pitchDeltaRadians, "pitchDelta");

        final float clampedForward = clampAxis(forwardAxisValue);
        final float clampedStrafe = clampAxis(strafeAxisValue);
        final float magnitude =
            (float) Math.sqrt(clampedForward * clampedForward + clampedStrafe * clampedStrafe);
        if (magnitude <= AXIS_LIMIT)
        {
            return new InputState(clampedForward, clampedStrafe, yawDeltaRadians,
                pitchDeltaRadians, fireDown, jumpDown, sprintDown);
        }
        final float scale = AXIS_LIMIT / magnitude;
        return new InputState(clampedForward * scale, clampedStrafe * scale, yawDeltaRadians,
            pitchDeltaRadians, fireDown, jumpDown, sprintDown);
    }

    /**
     * Returns a copy of this snapshot with both look deltas zeroed.
     *
     * Useful when a snapshot must be replayed or re-applied without turning
     * the view a second time — the movement level is still meaningful, the
     * rotation integral is not.
     *
     * @return a snapshot with the same axes and flags and no rotation
     */
    public InputState withoutLook()
    {
        return new InputState(forwardAxis, strafeAxis, 0.0f, 0.0f, fire, jump, sprint);
    }

    /** Returns true if nothing is pressed, no axis is deflected, and the view did not move. */
    public boolean isNeutral()
    {
        if (fire || jump || sprint)
        {
            return false;
        }
        return forwardAxis == 0.0f && strafeAxis == 0.0f
            && yawDelta == 0.0f && pitchDelta == 0.0f;
    }

    /** Returns forward/back deflection, −1..1, positive forward. */
    public float forwardAxis()
    {
        return forwardAxis;
    }

    /** Returns left/right deflection, −1..1, positive right. */
    public float strafeAxis()
    {
        return strafeAxis;
    }

    /** Returns view rotation in radians since the previous snapshot, positive turns right. */
    public float yawDelta()
    {
        return yawDelta;
    }

    /** Returns view rotation in radians since the previous snapshot, positive tilts up. */
    public float pitchDelta()
    {
        return pitchDelta;
    }

    /** Returns true if the attack action was active during the sampled tic. */
    public boolean fire()
    {
        return fire;
    }

    /** Returns true if the jump action was active during the sampled tic. */
    public boolean jump()
    {
        return jump;
    }

    /** Returns true if the sprint modifier was active during the sampled tic. */
    public boolean sprint()
    {
        return sprint;
    }

    @Override
    public boolean equals(final Object other)
    {
        if (this == other)
        {
            return true;
        }
        if (!(other instanceof InputState that))
        {
            return false;
        }
        if (fire != that.fire || jump != that.jump || sprint != that.sprint)
        {
            return false;
        }
        return Float.compare(forwardAxis, that.forwardAxis) == 0
            && Float.compare(strafeAxis, that.strafeAxis) == 0
            && Float.compare(yawDelta, that.yawDelta) == 0
            && Float.compare(pitchDelta, that.pitchDelta) == 0;
    }

    @Override
    public int hashCode()
    {
        int result = Float.hashCode(forwardAxis);
        result = 31 * result + Float.hashCode(strafeAxis);
        result = 31 * result + Float.hashCode(yawDelta);
        result = 31 * result + Float.hashCode(pitchDelta);
        result = 31 * result + Boolean.hashCode(fire);
        result = 31 * result + Boolean.hashCode(jump);
        result = 31 * result + Boolean.hashCode(sprint);
        return result;
    }

    @Override
    public String toString()
    {
        return "InputState{forward=" + forwardAxis
            + ", strafe=" + strafeAxis
            + ", yaw=" + yawDelta
            + ", pitch=" + pitchDelta
            + ", fire=" + fire
            + ", jump=" + jump
            + ", sprint=" + sprint
            + "}";
    }

    // Clamps one axis into -1..1.
    private static float clampAxis(final float value)
    {
        if (value > AXIS_LIMIT)
        {
            return AXIS_LIMIT;
        }
        if (value < -AXIS_LIMIT)
        {
            return -AXIS_LIMIT;
        }
        return value;
    }

    // Rejects NaN and infinity before either can reach the simulation.
    private static void requireFinite(final float value, final String name)
    {
        if (Float.isNaN(value) || Float.isInfinite(value))
        {
            throw new IllegalArgumentException(name + " must be finite, got " + value);
        }
    }
}
