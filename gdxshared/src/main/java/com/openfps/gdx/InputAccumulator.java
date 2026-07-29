/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.openfps.engine.hal.port.InputState;

/**
 * The half of {@link GdxInputPort} that has no idea libGDX exists.
 *
 * <p>Everything here is arithmetic and thread handoff: raw pixel counts and
 * key booleans go in, an {@link InputState} comes out. Nothing in this file
 * imports a platform type, which is the entire point — the tricky part of
 * desktop input is the accumulate-and-latch bookkeeping, and that part is
 * fully testable in a headless JVM. {@code GdxInputPort} keeps only the thin,
 * untestable "ask GLFW what the mouse did" layer.</p>
 *
 * <h2>Why accumulate at all</h2>
 *
 * <p>Two clocks read this object and they do not agree. The LWJGL3 render
 * thread calls {@link #accumulateLook} once per presented frame — vsync-driven,
 * so 60 Hz on one machine and 144 Hz on the next, and irregular whenever a
 * frame runs long. The game loop thread calls {@link #latch} once per tic at a
 * fixed 30, 60 or 120 Hz. Neither rate divides the other.</p>
 *
 * <p>Sampling the mouse instantaneously at latch time would be wrong in both
 * directions. If the render thread is faster, every frame between two tics is
 * discarded and the view turns slower than the hand moved. If it is slower,
 * the same unconsumed delta is read by two tics and the view turns twice as
 * far. Relative motion is not a level you can re-read — it is an integral, and
 * an integral must be consumed exactly once.</p>
 *
 * <p>So look motion is <b>summed</b> in an {@link AtomicInteger} of raw pixels
 * and {@link #latch} takes it with {@code getAndSet(0)}: every pixel the mouse
 * reported is counted once, in exactly one tic, whatever the two rates are. A
 * tic that happens to fall between two frames legitimately reports zero
 * rotation; the next one reports the whole of it.</p>
 *
 * <p>Raw <i>pixels</i> rather than radians because pixel counts are integers.
 * That makes the accumulator lock-free and exact — no floating-point CAS loop,
 * no drift from repeatedly summing small floats. The single multiply by
 * {@link #radiansPerPixel()} happens once, at latch.</p>
 *
 * <h2>Levels, integrals, and edges</h2>
 *
 * <p>The three kinds of input are stored differently because they mean
 * different things:</p>
 * <ul>
 *   <li><b>Movement keys</b> are a level. The most recent poll wins. If no
 *       poll happened since the last tic, the previous reading still describes
 *       reality — a held key is still held.</li>
 *   <li><b>Look motion</b> is an integral. Summed, then drained.</li>
 *   <li><b>Actions</b> are both. Each flag latches true when the poll sees it
 *       down and stays true until the next {@link #latch}, so a click that
 *       begins and ends entirely between two tics is still reported — once.
 *       Level state is folded in as well, so a key held across a tic with no
 *       intervening poll is not lost either.</li>
 * </ul>
 *
 * <p><b>Threading:</b> the setters run on the render thread and {@link #latch}
 * on the game loop thread, concurrently, with no lock between them. Each field
 * is individually atomic, which is all this needs: a snapshot is allowed to
 * mix a key level read a microsecond before a look delta, because that is what
 * a real device does anyway. What is <i>not</i> allowed is losing or
 * duplicating an integral, and {@code getAndSet} rules that out.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class InputAccumulator
{
    /**
     * Default mouse sensitivity, in <b>radians of view rotation per pixel of
     * raw mouse motion</b>.
     *
     * At this value a full 360° turn (2π rad) takes about 2856 px of mouse
     * travel, which on a typical 800 DPI mouse is roughly 9 cm — in the middle
     * of the range shooter players actually configure. It is the unit that
     * matters more than the number: radians per pixel keeps the conversion a
     * single multiply and keeps degrees out of the engine entirely.
     */
    public static final float DEFAULT_RADIANS_PER_PIXEL = 0.0022f;

    /**
     * Largest single-poll pixel delta that is believed, per axis.
     *
     * A legitimate frame at 144 Hz moves the cursor tens of pixels. Hundreds
     * means something else happened — the first frame after the cursor was
     * captured and warped, an alt-tab, or a window drag — and letting that
     * through spins the camera wildly for one tic. Clamping is the honest
     * response: the player's hand did not travel that far.
     */
    public static final int MAX_PIXELS_PER_POLL = 512;

    /**
     * Saturation bound on the pixel accumulators.
     *
     * Only reachable if the game loop stops latching while the window keeps
     * polling — a paused or crashed loop. Saturating is preferable to an
     * {@code int} overflow flipping the sign of the rotation when the loop
     * comes back.
     */
    public static final int MAX_ACCUMULATED_PIXELS = 1 << 24;

    /** Radians of view rotation per pixel of mouse motion. Fixed at construction. */
    private final float radiansPerPixel;

    /** Summed horizontal mouse pixels since the last latch. MUTABLE: drained by {@link #latch}. */
    private final AtomicInteger yawPixels = new AtomicInteger();

    /**
     * Summed vertical mouse pixels since the last latch, in <b>screen</b>
     * orientation — positive is downward, because that is what the windowing
     * system reports. The sign flip to "positive is up" happens once, in
     * {@link #latch}. MUTABLE: drained by {@link #latch}.
     */
    private final AtomicInteger pitchPixels = new AtomicInteger();

    /** Fire seen down since the last latch. MUTABLE: set by poll, cleared by latch. */
    private final AtomicBoolean fireSeen = new AtomicBoolean();

    /** Jump seen down since the last latch. MUTABLE: set by poll, cleared by latch. */
    private final AtomicBoolean jumpSeen = new AtomicBoolean();

    /** Sprint seen down since the last latch. MUTABLE: set by poll, cleared by latch. */
    private final AtomicBoolean sprintSeen = new AtomicBoolean();

    /**
     * Forward/back deflection, −1..1.
     *
     * <p>MUTABLE: overwritten by every poll. A single {@code float} rather than
     * a pair of booleans because a thumbstick genuinely is analog and a key is
     * simply a stick that only ever reaches its stops —
     * {@link #setMovementKeys} converts. Volatile is sufficient: a {@code float}
     * write is atomic, and the two axes are allowed to be read a microsecond
     * apart for the same reason a key level and a look delta are.</p>
     */
    private volatile float forwardAxis;

    /** Left/right deflection, −1..1, positive right. MUTABLE: overwritten by every poll. */
    private volatile float strafeAxis;

    /**
     * Whether vertical look is inverted — mouse away from you aims down.
     *
     * <p>MUTABLE: flipped by a settings screen, read once per {@link #latch}.
     * Volatile because the two are different threads.</p>
     *
     * <p><b>False is the non-inverted, conventional default</b>, and
     * {@link #latch} is the single multiply that implements it. This is a
     * player preference and nothing else — a platform correcting for a device
     * that reports upside down must not spend this flag on it, or the player
     * loses the ability to have the preference.</p>
     */
    private volatile boolean invertPitch;

    /** Fire level. MUTABLE: overwritten by every poll. */
    private volatile boolean fireHeld;

    /** Jump level. MUTABLE: overwritten by every poll. */
    private volatile boolean jumpHeld;

    /** Sprint level. MUTABLE: overwritten by every poll. */
    private volatile boolean sprintHeld;

    /** Creates an accumulator at {@link #DEFAULT_RADIANS_PER_PIXEL}. */
    public InputAccumulator()
    {
        this(DEFAULT_RADIANS_PER_PIXEL);
    }

    /**
     * Creates an accumulator with an explicit sensitivity.
     *
     * @param sensitivityRadiansPerPixel radians of view rotation per pixel of
     *     mouse motion; must be finite and greater than zero
     * @throws IllegalArgumentException if the sensitivity is not a positive
     *     finite number
     */
    public InputAccumulator(final float sensitivityRadiansPerPixel)
    {
        if (!(sensitivityRadiansPerPixel > 0.0f)
            || Float.isInfinite(sensitivityRadiansPerPixel))
        {
            throw new IllegalArgumentException(
                "sensitivity must be finite and positive, got " + sensitivityRadiansPerPixel);
        }
        this.radiansPerPixel = sensitivityRadiansPerPixel;
    }

    /**
     * Adds one poll's worth of relative mouse motion.
     *
     * <p>Both arguments are in the windowing system's screen orientation: +x
     * is right, +y is <b>down</b>. They are stored as given; {@link #latch}
     * owns the conversion to radians and the pitch sign flip, so there is
     * exactly one place to look when the camera turns the wrong way.</p>
     *
     * <p>Each argument is clamped to ±{@link #MAX_PIXELS_PER_POLL} before it is
     * added.</p>
     *
     * @param deltaXPixels horizontal motion since the previous poll, in pixels
     * @param deltaYPixels vertical motion since the previous poll, in pixels,
     *     positive downward
     */
    public void accumulateLook(final int deltaXPixels, final int deltaYPixels)
    {
        addClamped(yawPixels, clampPoll(deltaXPixels));
        addClamped(pitchPixels, clampPoll(deltaYPixels));
    }

    /**
     * Discards look motion gathered but not yet latched.
     *
     * Called when the accumulated pixels no longer describe anything the
     * player meant — across a cursor capture, where the pointer is warped to
     * the window centre and the first reported delta is that warp rather than
     * a hand movement.
     */
    public void resetLook()
    {
        yawPixels.set(0);
        pitchPixels.set(0);
    }

    /**
     * Records the movement key levels seen by this poll.
     *
     * Opposing keys cancel: holding forward and back at once is a zero axis,
     * not a fight between the two.
     *
     * @param forward true if the forward key is down
     * @param back true if the back key is down
     * @param left true if the strafe-left key is down
     * @param right true if the strafe-right key is down
     */
    public void setMovementKeys(final boolean forward, final boolean back,
        final boolean left, final boolean right)
    {
        setMovementAxes(axis(forward, back), axis(right, left));
    }

    /**
     * Records an analog movement deflection seen by this poll.
     *
     * <p>What a thumbstick produces, and what {@link #setMovementKeys} reduces
     * to: a key is a stick that is only ever centred or at its stop. Both write
     * the same two fields, so a platform may use either and nothing downstream
     * can tell which.</p>
     *
     * <p>Values are stored as given. Clamping to the unit disc happens once, in
     * {@link InputState#of}, so every adapter present and future obeys the same
     * rule rather than each remembering to — including the one that stops a
     * diagonal being 41% faster than a straight line.</p>
     *
     * @param forward forward/back deflection, positive forward
     * @param strafe left/right deflection, positive right
     */
    public void setMovementAxes(final float forward, final float strafe)
    {
        forwardAxis = forward;
        strafeAxis = strafe;
    }

    /**
     * Records the action levels seen by this poll.
     *
     * Each true also arms a sticky flag that survives until the next
     * {@link #latch}, so a press shorter than one tic is still delivered.
     *
     * @param fire true if the attack button or key is down
     * @param jump true if the jump key is down
     * @param sprint true if the sprint modifier is down
     */
    public void setActionKeys(final boolean fire, final boolean jump, final boolean sprint)
    {
        fireHeld = fire;
        jumpHeld = jump;
        sprintHeld = sprint;
        if (fire)
        {
            fireSeen.set(true);
        }
        if (jump)
        {
            jumpSeen.set(true);
        }
        if (sprint)
        {
            sprintSeen.set(true);
        }
    }

    /**
     * Drops every pending reading and returns the accumulator to rest.
     *
     * Used when input stops being meaningful — the cursor was released to the
     * desktop, or the window lost focus. Without it a key held at the moment
     * of release would stay "held" forever, walking the player into a wall
     * while they use another application.
     */
    public void clearAll()
    {
        resetLook();
        setMovementKeys(false, false, false, false);
        fireHeld = false;
        jumpHeld = false;
        sprintHeld = false;
        fireSeen.set(false);
        jumpSeen.set(false);
        sprintSeen.set(false);
    }

    /**
     * Consumes everything gathered since the previous call and returns it as
     * one immutable snapshot.
     *
     * <p>This is the only draining operation. Afterwards the look accumulators
     * are zero and the sticky action flags are clear, so the next tic starts
     * from nothing; the movement key levels are left alone, because a held key
     * is still held.</p>
     *
     * <p><b>Pitch sign.</b> Callers report +y downward — that is the screen
     * convention on both platforms — and the snapshot's convention is
     * positive-is-up, the standard non-inverted feel, so the accumulated pixels
     * are negated exactly here. {@link #setInvertPitch} flips that one multiply
     * and nothing else.</p>
     *
     * <p><b>The negation is a statement about the caller's numbers, not about
     * the device</b>, which is why it lives here and not in either port. A
     * platform whose device reports the other way round owes this class the
     * sign it documents rather than a second flag; keeping such a correction at
     * the point of the discrepancy is what stops one platform's quirk from
     * becoming a shared special case that the other has to opt out of.</p>
     *
     * <p><b>As it happens, no platform currently owes one.</b> GLFW reports
     * cursor motion from a top-left origin and libGDX passes it through
     * unchanged, so {@code Gdx.input.getDeltaY()} is already +y downward; an
     * Android drag reports the same way. Both ports therefore hand over raw
     * deltas and this single negation is the only sign flip in the chain. That
     * is worth stating plainly, because a second flip did briefly live in
     * {@code GdxInputPort.pollLook} and the two cancelled into a camera that
     * played inverted — see that method for the measurement that removed
     * it.</p>
     *
     * <p>Diagonal normalisation is not done here: the raw −1/0/+1 axes are
     * handed to {@link InputState#of}, which scales any vector longer than 1
     * back onto the unit circle. Keeping the rule in the snapshot type means
     * every present and future adapter obeys it.</p>
     *
     * @return the snapshot for one tic; never null
     */
    public InputState latch()
    {
        final int rawYaw = yawPixels.getAndSet(0);
        final int rawPitch = pitchPixels.getAndSet(0);
        final float yaw = rawYaw * radiansPerPixel;
        float pitch = -rawPitch * radiansPerPixel;
        if (invertPitch)
        {
            pitch = -pitch;
        }
        return InputState.of(
            forwardAxis,
            strafeAxis,
            yaw,
            pitch,
            fireSeen.getAndSet(false) || fireHeld,
            jumpSeen.getAndSet(false) || jumpHeld,
            sprintSeen.getAndSet(false) || sprintHeld);
    }

    /** Returns the sensitivity in radians of view rotation per pixel of mouse motion. */
    public float radiansPerPixel()
    {
        return radiansPerPixel;
    }

    /**
     * Sets whether vertical look is inverted.
     *
     * <p>Takes effect on the next {@link #latch} — never mid-tic, so a player
     * flipping the switch cannot get half a tic of rotation each way.</p>
     *
     * @param inverted true to make mouse-away aim down
     */
    public void setInvertPitch(final boolean inverted)
    {
        this.invertPitch = inverted;
    }

    /**
     * Returns whether vertical look is inverted.
     *
     * @return true if mouse-away aims down
     */
    public boolean isInvertPitch()
    {
        return invertPitch;
    }

    /**
     * Returns the forward/back deflection the last poll recorded.
     *
     * <p>A level, not an integral, so reading it does not consume it — unlike
     * {@link #pendingYawPixels()}, which names pixels that {@link #latch} will
     * take away.</p>
     *
     * @return the deflection, positive forward
     */
    public float movementForwardAxis()
    {
        return forwardAxis;
    }

    /**
     * Returns the left/right deflection the last poll recorded.
     *
     * @return the deflection, positive right
     */
    public float movementStrafeAxis()
    {
        return strafeAxis;
    }

    /** Returns the horizontal pixels gathered but not yet latched. For tests and diagnostics. */
    public int pendingYawPixels()
    {
        return yawPixels.get();
    }

    /**
     * Returns the vertical pixels gathered but not yet latched, in screen
     * orientation (positive downward). For tests and diagnostics.
     */
    public int pendingPitchPixels()
    {
        return pitchPixels.get();
    }

    // +1 for the positive key alone, -1 for the negative key alone, 0 for
    // neither or both.
    private static float axis(final boolean positive, final boolean negative)
    {
        if (positive == negative)
        {
            return 0.0f;
        }
        if (positive)
        {
            return 1.0f;
        }
        return -1.0f;
    }

    // Rejects an implausible single-poll delta. See MAX_PIXELS_PER_POLL.
    private static int clampPoll(final int delta)
    {
        if (delta > MAX_PIXELS_PER_POLL)
        {
            return MAX_PIXELS_PER_POLL;
        }
        if (delta < -MAX_PIXELS_PER_POLL)
        {
            return -MAX_PIXELS_PER_POLL;
        }
        return delta;
    }

    // Saturating atomic add. The CAS loop is what keeps the clamp and the add
    // from racing: without it, two render threads (or a poll racing a latch)
    // could interleave a read-clamp-write and lose a delta.
    private static void addClamped(final AtomicInteger target, final int delta)
    {
        boolean stored = false;
        while (!stored)
        {
            final int current = target.get();
            final long sum = (long) current + (long) delta;
            stored = target.compareAndSet(current, saturate(sum));
        }
    }

    // Folds a 64-bit sum back into the accumulator's range.
    private static int saturate(final long sum)
    {
        if (sum > MAX_ACCUMULATED_PIXELS)
        {
            return MAX_ACCUMULATED_PIXELS;
        }
        if (sum < -MAX_ACCUMULATED_PIXELS)
        {
            return -MAX_ACCUMULATED_PIXELS;
        }
        return (int) sum;
    }
}
