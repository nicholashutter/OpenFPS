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
 * <h2>A gamepad is a second channel, not a second mode</h2>
 *
 * <p>Every gamepad reading is stored beside the keyboard/touch reading rather
 * than on top of it, and {@link #latch} sums the two. That is what makes a pad
 * an <b>additional</b> input path: a player may walk with the left stick and
 * turn with the mouse in the same tic, and neither device can zero the other by
 * being polled second. Opposing inputs cancel — stick forward and the back key
 * together is a zero axis — which is the rule
 * {@link #setMovementKeys} already applies within one device, extended across
 * two. {@link #clearGamepad()} empties that channel alone, which is what a
 * controller unplugged at full deflection needs.</p>
 *
 * <h2>A STICK IS NOT A MOUSE, and mixing them up is the bug this guards</h2>
 *
 * <p>The two look devices report different <i>kinds</i> of quantity and the
 * arithmetic must not be shared:</p>
 * <ul>
 *   <li>A mouse reports a <b>displacement</b> — "the hand moved 40 px since the
 *       last poll". It is an integral, so it is summed and drained exactly
 *       once, as described above.</li>
 *   <li>A stick reports a <b>position</b> — "I am held two thirds of the way
 *       right", which means "keep turning right at that rate for as long as
 *       this is true". It is a level, and it is <i>re-read</i>, not
 *       consumed.</li>
 * </ul>
 *
 * <p><b>Pushing a held stick through the pixel accumulator would make turn
 * speed depend on the polling rate.</b> Each poll would add the same deflection
 * again, so a 144 Hz machine would bank twice as many contributions per tic as a
 * 72 Hz one and the player would spin twice as fast for the same thumb — on the
 * same game, with the same settings, because of vsync. That is not a
 * sensitivity difference anyone can compensate for.</p>
 *
 * <p>So {@link #setGamepadLookAxes} <b>overwrites</b> a level, and
 * {@link #latch} converts it exactly once per tic:
 * {@code radians = deflection × }{@link #GAMEPAD_LOOK_RADIANS_PER_SECOND}
 * {@code × }{@link #ticDurationSeconds()}. Radians per second times seconds is
 * radians, the poll count cancels out of the expression entirely, and the
 * result is the same rotation for the same deflection held for the same
 * <i>duration</i> at any frame rate. {@link #latch} is the right place for it
 * because {@code latch} is the only thing in this class that happens exactly
 * once per tic — which is the interval being integrated over.</p>
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

    /**
     * How fast a fully deflected look stick turns the view, in <b>radians per
     * second</b>.
     *
     * <p>The unit is the point, and it is a different unit from
     * {@link #DEFAULT_RADIANS_PER_PIXEL} on purpose — see the class Javadoc.
     * Radians per <i>pixel</i> is a conversion factor for a displacement that
     * already happened; radians per <i>second</i> is a rate, and the only thing
     * that can turn a rate into an angle is a duration.</p>
     *
     * <p>3.5 rad/s is a full 360° turn in about 1.8 seconds at the stop. That
     * is the range console shooters sit in for a pad with no aim assist and no
     * turn acceleration: fast enough to answer someone behind you inside a
     * second, slow enough that the top of the range is still controllable. The
     * response curve ({@link AnalogStick#LOOK_EXPONENT}) means the stop is
     * genuinely reachable and the middle of the range is much slower than a
     * linear reading of this number suggests — half deflection is 0.875 rad/s,
     * a 360 in seven seconds, which is the deliberate aiming speed.</p>
     */
    public static final float GAMEPAD_LOOK_RADIANS_PER_SECOND = 3.5f;

    /**
     * Tic duration assumed until a launcher says otherwise, in seconds.
     *
     * <p>1/60 s, matching the engine's default {@code FrameRate}. A default is
     * needed because this class must not import {@code com.openfps.engine.core}
     * to ask — it is a platform adapter, and the frame rate is engine
     * configuration. A launcher running at 30 or 120 Hz owes this object
     * {@link #setTicDuration}; getting it wrong scales stick look by exactly the
     * ratio of the two rates and nothing else, which is a sensitivity error
     * rather than a frame-rate dependence.</p>
     */
    public static final float DEFAULT_TIC_SECONDS = 1.0f / 60.0f;

    /**
     * Radians of view rotation per pixel of mouse motion.
     *
     * <p>MUTABLE: set once by the constructor, replaced by
     * {@link #setRadiansPerPixel(float)} when a settings screen, a loaded
     * profile or a rebind to a different device wants a different feel. The
     * value is read exactly once per {@link #latch}, on the game loop thread;
     * a concurrent writer is fine because {@code float} writes are atomic and
     * a single one-tic blur of the old and new value is invisible to the
     * player.</p>
     */
    private float radiansPerPixel;

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

    /**
     * Forward/back deflection contributed by a gamepad, −1..1, already shaped.
     *
     * <p>MUTABLE: overwritten by every poll, zeroed by {@link #clearGamepad()}.
     * Separate from {@link #forwardAxis} so the two devices sum rather than
     * overwrite each other — see the class Javadoc.</p>
     */
    private volatile float padForwardAxis;

    /** Gamepad left/right deflection, −1..1, positive right. MUTABLE: per poll. */
    private volatile float padStrafeAxis;

    /**
     * Look-stick yaw deflection, −1..1, positive turns right.
     *
     * <p>MUTABLE: overwritten by every poll — a <b>level</b>, never summed. This
     * is the field the class Javadoc's warning is about: summing it would tie
     * turn speed to the poll rate.</p>
     */
    private volatile float padYawAxis;

    /**
     * Look-stick pitch deflection, −1..1, in <b>screen</b> orientation —
     * positive is downward, exactly like {@link #pitchPixels}.
     *
     * <p>MUTABLE: overwritten by every poll. Sharing the mouse's convention is
     * what lets {@link #latch} apply one negation and one
     * {@link #setInvertPitch} flag to both devices, rather than growing a
     * second sign rule that would drift out of step with the first.</p>
     */
    private volatile float padPitchAxis;

    /** Gamepad fire level. MUTABLE: per poll, cleared by {@link #clearGamepad()}. */
    private volatile boolean padFireHeld;

    /** Gamepad jump level. MUTABLE: per poll, cleared by {@link #clearGamepad()}. */
    private volatile boolean padJumpHeld;

    /** Gamepad sprint level. MUTABLE: per poll, cleared by {@link #clearGamepad()}. */
    private volatile boolean padSprintHeld;

    /**
     * Seconds of simulated time one tic covers.
     *
     * <p>MUTABLE: set once by a launcher that knows the configured frame rate,
     * read once per {@link #latch}. Volatile because those are different
     * threads, though in practice it is written before the loop starts.</p>
     */
    private volatile float ticSeconds = DEFAULT_TIC_SECONDS;

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
     * Records a gamepad's movement-stick deflection, raw as the device reports
     * it.
     *
     * <p><b>Raw in, shaped stored.</b> The dead zone and the response curve are
     * applied here rather than by the caller, for the reason
     * {@link InputState#of} clamps the unit disc rather than trusting each
     * adapter to: they are properties of a thumbstick and not of a platform, so
     * a third backend added later cannot forget them. See {@link AnalogStick}
     * for what "shaped" means and why the dead zone is radial.</p>
     *
     * <p>Stored beside {@link #setMovementAxes}, never over it: a player using
     * the keyboard and the stick at once gets the sum, clamped by
     * {@code InputState.of}.</p>
     *
     * @param rawForward forward/back deflection as reported, positive forward
     * @param rawStrafe left/right deflection as reported, positive right
     * @throws IllegalArgumentException if either value is not finite
     */
    public void setGamepadMovementAxes(final float rawForward, final float rawStrafe)
    {
        final float scale =
            AnalogStick.responseScale(rawForward, rawStrafe, AnalogStick.MOVE_EXPONENT);

        padForwardAxis = rawForward * scale;

        padStrafeAxis = rawStrafe * scale;
    }

    /**
     * Records a gamepad's look-stick deflection, raw as the device reports it.
     *
     * <p><b>A rate, not a displacement.</b> The value overwrites whatever the
     * previous poll left — it describes where the stick <i>is</i>, not how far
     * it has moved — and {@link #latch} turns it into an angle by multiplying
     * by {@link #GAMEPAD_LOOK_RADIANS_PER_SECOND} and the tic duration. Calling
     * this twice between two tics therefore turns the view once, which is the
     * whole point; calling {@link #accumulateLook} twice turns it twice, which
     * is equally the point for a mouse. The class Javadoc explains why those
     * are both correct.</p>
     *
     * <p>{@code rawPitch} is in the accumulator's <b>screen</b> orientation:
     * positive is downward. A stick pushed away from the player reports a
     * negative Y on every gamepad API this project has met — GLFW and Android
     * both — so a backend hands the value over untouched, exactly as the desktop
     * mouse does. There is no sign flip in either port and there must not be
     * one; {@link #latch} owns the single negation.</p>
     *
     * @param rawYaw horizontal deflection as reported, positive turns right
     * @param rawPitch vertical deflection as reported, positive downward
     * @throws IllegalArgumentException if either value is not finite
     */
    public void setGamepadLookAxes(final float rawYaw, final float rawPitch)
    {
        final float scale =
            AnalogStick.responseScale(rawYaw, rawPitch, AnalogStick.LOOK_EXPONENT);

        padYawAxis = rawYaw * scale;

        padPitchAxis = rawPitch * scale;
    }

    /**
     * Records the action levels a gamepad poll saw.
     *
     * <p>The gamepad half of {@link #setActionKeys}, stored separately so
     * neither device can release the other's trigger. The sticky "seen since
     * the last latch" flags are <b>shared</b> with the keyboard, because their
     * meaning is "the player fired", which has no device in it.</p>
     *
     * @param fire true if the pad's fire control is down
     * @param jump true if the pad's jump control is down
     * @param sprint true if the pad's sprint control is down
     */
    public void setGamepadActions(final boolean fire, final boolean jump,
        final boolean sprint)
    {
        padFireHeld = fire;

        padJumpHeld = jump;

        padSprintHeld = sprint;

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
     * Returns the gamepad channel to rest, leaving the keyboard, mouse and
     * touch channels exactly as they were.
     *
     * <p><b>This is the hot-plug fix, and it exists for a failure that has
     * already happened once on this project in another guise.</b>
     * {@link #clearAll()} was written because a key held at the moment the
     * window lost focus stayed held forever and walked the player into a wall.
     * A controller unplugged — or a wireless pad whose battery dies — at full
     * deflection is the same failure with no keyboard event to end it: the last
     * poll's axis is a level, levels persist by design, and nothing else would
     * ever overwrite it. A backend that notices a pad has gone calls this, and
     * the player stops walking.</p>
     *
     * <p>Partial rather than total on purpose: a player who unplugs a pad
     * mid-match usually still has a hand on the keyboard, and clearing that
     * would drop a key they are holding right now.</p>
     *
     * <p>The shared sticky action flags are deliberately <b>not</b> cleared.
     * They mean "the player fired since the last tic", which was true when it
     * was set — and clearing them here would also discard a keyboard press made
     * in the same interval. The cost is at most one tic of a shot the player
     * really did take.</p>
     */
    public void clearGamepad()
    {
        padForwardAxis = 0.0f;

        padStrafeAxis = 0.0f;

        padYawAxis = 0.0f;

        padPitchAxis = 0.0f;

        padFireHeld = false;

        padJumpHeld = false;

        padSprintHeld = false;
    }

    /**
     * Drops every pending reading and returns the accumulator to rest.
     *
     * Used when input stops being meaningful — the cursor was released to the
     * desktop, or the window lost focus. Without it a key held at the moment
     * of release would stay "held" forever, walking the player into a wall
     * while they use another application. The gamepad channel goes with it, for
     * the same reason: a stick held while the window is in the background is
     * not a player asking to move.
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

        clearGamepad();
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

        // The whole rate-versus-displacement conversion, in one expression and
        // one place. The mouse term consumes an integral of pixels; the stick
        // term integrates a held rate over the tic that is ending. Both come out
        // as radians, so they add, and a player using both at once gets both.
        //
        // ticSeconds is what makes the stick term frame-rate independent: the
        // number of polls that ran between two latches does not appear in it at
        // all. Doubling the poll rate doubles how often padYawAxis is
        // overwritten with the same value and changes nothing here.
        final float radiansPerFullDeflection = GAMEPAD_LOOK_RADIANS_PER_SECOND * ticSeconds;

        final float yaw = rawYaw * radiansPerPixel + padYawAxis * radiansPerFullDeflection;

        // One negation for both devices — the screen-to-view sign flip the
        // method contract documents — and therefore one invert preference.
        //
        // Written as `0 - x` rather than `-x`, and that is not decoration.
        // Negating a float zero produces NEGATIVE zero, and Float.compare
        // separates the two: -0.0f is not equal to 0.0f, so a completely idle
        // accumulator would stop reporting InputState.NEUTRAL and every
        // "nothing is happening" assertion in the suite would fail on a value
        // that prints as "-0.0". Subtracting from positive zero yields positive
        // zero and is otherwise identical arithmetic. The old single-device
        // expression avoided this by accident — it negated an int before the
        // multiply, where -0 is 0.
        final float pitchScreen =
            rawPitch * radiansPerPixel + padPitchAxis * radiansPerFullDeflection;

        float pitch = 0.0f - pitchScreen;

        if (invertPitch)
        {
            pitch = 0.0f - pitch;
        }

        return InputState.of(
            forwardAxis + padForwardAxis,
            strafeAxis + padStrafeAxis,
            yaw,
            pitch,
            fireSeen.getAndSet(false) || fireHeld || padFireHeld,
            jumpSeen.getAndSet(false) || jumpHeld || padJumpHeld,
            sprintSeen.getAndSet(false) || sprintHeld || padSprintHeld);
    }

    /** Returns the sensitivity in radians of view rotation per pixel of mouse motion. */
    public float radiansPerPixel()
    {
        return radiansPerPixel;
    }

    /**
     * Replaces the mouse sensitivity.
     *
     * <p>The programmatic half of a "mouse sensitivity" option. A settings
     * screen reads {@code PlayerSettings.mouseSensitivityRadiansPerPixel()}
     * and feeds it here; the next {@link #latch} scales accumulated pixels by
     * the new value.</p>
     *
     * <p>The same validity rule as the constructor — finite and positive — so
     * a zero or NaN handed in by a UI bug fails loudly rather than freezing
     * the camera.</p>
     *
     * @param newRadiansPerPixel the new sensitivity in radians of view
     *     rotation per pixel of mouse motion; must be finite and positive
     * @throws IllegalArgumentException if {@code newRadiansPerPixel} is not
     *     finite and positive
     */
    public void setRadiansPerPixel(final float newRadiansPerPixel)
    {
        if (!(newRadiansPerPixel > 0.0f)
            || Float.isInfinite(newRadiansPerPixel))
        {
            throw new IllegalArgumentException(
                "sensitivity must be finite and positive, got " + newRadiansPerPixel);
        }

        this.radiansPerPixel = newRadiansPerPixel;
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

    /**
     * Tells this accumulator how much simulated time one tic covers.
     *
     * <p>The other half of {@link #GAMEPAD_LOOK_RADIANS_PER_SECOND}: a rate is
     * not an angle until something supplies a duration, and this is it. Called
     * once by a launcher, which is the only object that knows the configured
     * frame rate — this class may not ask, being forbidden the engine's core
     * packages.</p>
     *
     * @param seconds the tic duration; must be finite and greater than zero
     * @throws IllegalArgumentException if the duration is not a positive finite
     *     number
     */
    public void setTicDuration(final float seconds)
    {
        if (!(seconds > 0.0f) || Float.isInfinite(seconds))
        {
            throw new IllegalArgumentException(
                "tic duration must be finite and positive, got " + seconds);
        }

        this.ticSeconds = seconds;
    }

    /**
     * Returns how much simulated time one tic covers, in seconds.
     *
     * @return the tic duration, {@link #DEFAULT_TIC_SECONDS} until a launcher
     *     says otherwise
     */
    public float ticDurationSeconds()
    {
        return ticSeconds;
    }

    /**
     * Returns the gamepad's shaped forward/back deflection.
     *
     * <p>Shaped, not raw: the dead zone and curve were applied on the way in.
     * For tests and diagnostics.</p>
     *
     * @return the deflection, positive forward
     */
    public float gamepadForwardAxis()
    {
        return padForwardAxis;
    }

    /**
     * Returns the gamepad's shaped left/right deflection.
     *
     * @return the deflection, positive right
     */
    public float gamepadStrafeAxis()
    {
        return padStrafeAxis;
    }

    /**
     * Returns the look stick's shaped horizontal deflection.
     *
     * <p>A level, so reading it does not consume it — the difference from
     * {@link #pendingYawPixels()} that the class Javadoc is entirely about.</p>
     *
     * @return the deflection, positive turns right
     */
    public float gamepadYawAxis()
    {
        return padYawAxis;
    }

    /**
     * Returns the look stick's shaped vertical deflection, in screen
     * orientation (positive downward).
     *
     * @return the deflection, positive aims down before any invert preference
     */
    public float gamepadPitchAxis()
    {
        return padPitchAxis;
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
