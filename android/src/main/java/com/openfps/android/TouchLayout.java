/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

/**
 * Where the on-screen controls are, and which one a finger landed on.
 *
 * <p><b>This file imports nothing.</b> Not libGDX, not the Android SDK — the
 * whole of the touch scheme's arithmetic is plain Java, so all of it runs in
 * the module's plain-JVM unit tests. That split is the same one
 * {@code InputAccumulator} makes on desktop and it is worth making twice: the
 * part that can be got wrong (which region a pixel belongs to, how far a stick
 * is pushed, what happens on a screen too small for the layout) is exactly the
 * part a device cannot conveniently check.</p>
 *
 * <h2>Screen coordinates, y downward</h2>
 *
 * <p>Every coordinate here is in <b>pixels with y measured down from the top of
 * the screen</b>, because that is what libGDX's {@code InputProcessor} reports
 * and converting at the boundary would mean converting in five places instead
 * of documenting it in one. {@link TouchOverlay} — which draws in libGDX's
 * y-up world — is the single place the flip happens.</p>
 *
 * <h2>The layout</h2>
 *
 * <pre>
 *   +--------------------------------------------------+
 *   |                                          ( X )   |  leave
 *   |                                                  |
 *   |            look  (anywhere on the right)         |
 *   |     .--.                                         |
 *   |    ( () )   stick, resting              ( ^ )    |  jump
 *   |     `--'    (grab anywhere left)   ( + )         |  fire
 *   +--------------------------------------------------+
 * </pre>
 *
 * <p><b>The stick has no fixed home — but it has a resting place.</b> The
 * control still floats: it anchors wherever the left thumb lands and measures
 * deflection from there, because a stick that must be found before it can be
 * pushed makes the player look at their own thumb on a screen they are trying
 * to aim at. What {@link #stickHomeX()} and {@link #stickHomeY()} add is
 * somewhere to <i>draw</i> it while nobody is holding it. That is not a
 * contradiction, it is the missing half: an invisible control is indistinguishable
 * from a broken one, and the first thing a new player does with this game is
 * look for the buttons. Touching down anywhere in the left half still works,
 * and still anchors under the thumb rather than at the resting place.</p>
 *
 * <p><b>Buttons are tested before halves.</b> Fire and jump sit in the right
 * half, which is also the look region, so the order in
 * {@link #regionAt} is load-bearing rather than incidental: halves first would
 * make the fire button unreachable and every tap on it would spin the camera
 * instead.</p>
 *
 * <h2>One table both draws and hits</h2>
 *
 * <p>{@link #buttonRegions()} names the buttons, and
 * {@link #buttonCentreX(int)}, {@link #buttonCentreY(int)} and
 * {@link #buttonRadius(int)} answer for any of them. {@link #regionAt} walks
 * that same table, and so does {@link TouchOverlay}. The alternative — a hit
 * test that lists three circles and a renderer that lists them again — has
 * exactly one failure mode and it is the worst one available here: a button
 * drawn somewhere it cannot be pressed, which looks to the player like a game
 * that ignores them.</p>
 *
 * <h2>Sizes are in dp, not pixels</h2>
 *
 * <p>Every dimension below is a density-independent pixel multiplied by the
 * screen's density at construction, so a button is the same physical size on a
 * 160 dpi tablet and a 560 dpi phone. Material's minimum touch target is 48 dp;
 * nothing here is smaller than that, and fire — which is pressed under time
 * pressure, repeatedly, without being looked at — is nearly twice it.</p>
 */
public final class TouchLayout
{
    /** Returned by {@link #regionAt} when a point belongs to no control. */
    public static final int REGION_NONE = -1;

    /** The floating movement stick. Its code in an {@code InputBinding}. */
    public static final int REGION_MOVE_STICK = 0;

    /** The look area — drag to turn. */
    public static final int REGION_LOOK = 1;

    /** The fire button. */
    public static final int REGION_FIRE = 2;

    /** The jump button. */
    public static final int REGION_JUMP = 3;

    /** The leave-match button, which returns to the menu. */
    public static final int REGION_LEAVE = 4;

    /** Fire button radius, in density-independent pixels. */
    public static final float FIRE_RADIUS_DP = 46f;

    /** Jump button radius, in dp. Smaller than fire — it is pressed less often. */
    public static final float JUMP_RADIUS_DP = 38f;

    /**
     * Leave button radius, in dp.
     *
     * <p>The smallest control, and deliberately so: it is the one whose
     * accidental activation costs the most. It is still 52 dp across, above
     * Material's 48 dp minimum, and it sits in the top corner where a thumb
     * holding the device does not rest.</p>
     */
    public static final float LEAVE_RADIUS_DP = 26f;

    /** Distance from the right edge to the fire button's centre, in dp. */
    public static final float FIRE_RIGHT_INSET_DP = 84f;

    /** Distance from the bottom edge to the fire button's centre, in dp. */
    public static final float FIRE_BOTTOM_INSET_DP = 84f;

    /** Distance from the right edge to the jump button's centre, in dp. */
    public static final float JUMP_RIGHT_INSET_DP = 186f;

    /** Distance from the bottom edge to the jump button's centre, in dp. */
    public static final float JUMP_BOTTOM_INSET_DP = 66f;

    /** Distance from the right edge to the leave button's centre, in dp. */
    public static final float LEAVE_RIGHT_INSET_DP = 44f;

    /** Distance from the top edge to the leave button's centre, in dp. */
    public static final float LEAVE_TOP_INSET_DP = 44f;

    /**
     * Thumb travel from the stick's anchor that counts as full deflection, in dp.
     *
     * <p>About 1.5 cm, which is a comfortable thumb arc without moving the
     * hand. Larger and a player cannot reach full speed; smaller and every
     * small correction is full speed.</p>
     */
    public static final float STICK_RANGE_DP = 68f;

    /**
     * Movement inside this radius of the anchor reads as no movement, in dp.
     *
     * <p>A finger resting on a capacitive screen jitters by a pixel or two
     * constantly. Without a dead zone that jitter is a permanent, direction-
     * changing walk — the player is never quite standing still, which breaks
     * aiming and, on a networked match, sends a nonzero axis every single tic.</p>
     */
    public static final float STICK_DEAD_ZONE_DP = 6f;

    /**
     * Radius of the stick's drawn thumb, in dp.
     *
     * <p>52 dp across — a Material touch target, even though nothing hit-tests
     * against it. The thumb is not a button; it is the readout that tells the
     * player how far they have pushed, and a readout smaller than the finger
     * covering it reports nothing at all.</p>
     */
    public static final float STICK_KNOB_RADIUS_DP = 26f;

    /**
     * Distance from the left edge to the stick's resting centre, in dp.
     *
     * <p>Far enough in that the base ring — {@link #STICK_RANGE_DP} of it —
     * clears the edge, and the whole control sits under a thumb that is also
     * holding the phone up.</p>
     */
    public static final float STICK_HOME_LEFT_INSET_DP = 112f;

    /** Distance from the bottom edge to the stick's resting centre, in dp. */
    public static final float STICK_HOME_BOTTOM_INSET_DP = 100f;

    /**
     * How much bigger a button is drawn while it is held.
     *
     * <p>Feedback, and the cheapest kind there is. A phone gives no click and
     * no travel, so a press that changes nothing on screen is a press the
     * player cannot distinguish from a miss — and their next move is to press
     * harder, which does nothing either. 8% is small enough not to shift the
     * apparent target and large enough to be seen under a thumb.</p>
     *
     * <p>It is a <b>drawn</b> size only: {@link #buttonRadius(int)} is what the
     * finger is tested against and it does not move. A button that grew its hit
     * area under the finger already on it would start stealing the neighbouring
     * control from a second finger arriving.</p>
     */
    public static final float PRESSED_SCALE = 1.08f;

    /** Fraction of the screen width given to the movement half. */
    public static final float MOVE_HALF_FRACTION = 0.5f;

    /**
     * The buttons, in hit-test order — see {@link #regionAt}.
     *
     * <p>Private and copied out by {@link #buttonRegions()}: an array constant
     * is not a constant, and a public one is a caller away from a control
     * scheme that changed itself.</p>
     */
    private static final int[] BUTTON_REGIONS = {REGION_LEAVE, REGION_FIRE, REGION_JUMP};

    /** Pixels per density-independent pixel. Fixed at construction. */
    private final float density;

    /** Surface width in pixels. MUTABLE: set by {@link #resize}. */
    private float width;

    /** Surface height in pixels. MUTABLE: set by {@link #resize}. */
    private float height;

    /**
     * Creates a layout for one screen density.
     *
     * @param screenDensity pixels per density-independent pixel, as
     *     {@code Gdx.graphics.getDensity()} reports it; must be finite and
     *     positive
     * @throws IllegalArgumentException if the density is not a positive finite
     *     number
     */
    public TouchLayout(final float screenDensity)
    {
        if (!(screenDensity > 0.0f) || Float.isInfinite(screenDensity))
        {
            throw new IllegalArgumentException(
                "density must be finite and positive, got " + screenDensity);
        }
        this.density = screenDensity;
    }

    /**
     * Sizes the layout to a surface.
     *
     * <p>A zero or negative dimension is ignored rather than stored. Android
     * reports a 0x0 surface briefly during rotation, and a layout that believed
     * it would put every control on top of every other.</p>
     *
     * @param surfaceWidth width in pixels
     * @param surfaceHeight height in pixels
     */
    public void resize(final int surfaceWidth, final int surfaceHeight)
    {
        if (surfaceWidth <= 0 || surfaceHeight <= 0)
        {
            return;
        }
        this.width = surfaceWidth;
        this.height = surfaceHeight;
    }

    /** Returns the surface width this layout is sized for, in pixels. */
    public float width()
    {
        return width;
    }

    /** Returns the surface height this layout is sized for, in pixels. */
    public float height()
    {
        return height;
    }

    /** Returns pixels per density-independent pixel. */
    public float density()
    {
        return density;
    }

    /**
     * Converts a density-independent size to pixels on this screen.
     *
     * @param dp the size in density-independent pixels
     * @return the size in pixels
     */
    public float pixels(final float dp)
    {
        return dp * density;
    }

    /** Returns the fire button's centre x, in pixels. */
    public float fireCentreX()
    {
        return width - pixels(FIRE_RIGHT_INSET_DP);
    }

    /** Returns the fire button's centre y, in pixels down from the top. */
    public float fireCentreY()
    {
        return height - pixels(FIRE_BOTTOM_INSET_DP);
    }

    /** Returns the fire button's radius in pixels. */
    public float fireRadius()
    {
        return pixels(FIRE_RADIUS_DP);
    }

    /** Returns the jump button's centre x, in pixels. */
    public float jumpCentreX()
    {
        return width - pixels(JUMP_RIGHT_INSET_DP);
    }

    /** Returns the jump button's centre y, in pixels down from the top. */
    public float jumpCentreY()
    {
        return height - pixels(JUMP_BOTTOM_INSET_DP);
    }

    /** Returns the jump button's radius in pixels. */
    public float jumpRadius()
    {
        return pixels(JUMP_RADIUS_DP);
    }

    /** Returns the leave button's centre x, in pixels. */
    public float leaveCentreX()
    {
        return width - pixels(LEAVE_RIGHT_INSET_DP);
    }

    /** Returns the leave button's centre y, in pixels down from the top. */
    public float leaveCentreY()
    {
        return pixels(LEAVE_TOP_INSET_DP);
    }

    /** Returns the leave button's radius in pixels. */
    public float leaveRadius()
    {
        return pixels(LEAVE_RADIUS_DP);
    }

    /** Returns full stick deflection in pixels. */
    public float stickRange()
    {
        return pixels(STICK_RANGE_DP);
    }

    /** Returns the stick's dead zone radius in pixels. */
    public float stickDeadZone()
    {
        return pixels(STICK_DEAD_ZONE_DP);
    }

    /** Returns the radius of the stick's drawn thumb, in pixels. */
    public float stickKnobRadius()
    {
        return pixels(STICK_KNOB_RADIUS_DP);
    }

    /**
     * Returns where the stick is drawn while nobody is holding it, in pixels.
     *
     * <p>A resting place, not an anchor: a touch anywhere in the left half
     * still anchors the live stick under the thumb that made it.</p>
     *
     * @return the resting centre's x in pixels
     */
    public float stickHomeX()
    {
        return pixels(STICK_HOME_LEFT_INSET_DP);
    }

    /**
     * Returns where the stick is drawn while nobody is holding it, in pixels.
     *
     * @return the resting centre's y in pixels down from the top
     */
    public float stickHomeY()
    {
        return height - pixels(STICK_HOME_BOTTOM_INSET_DP);
    }

    /**
     * Returns the buttons, in the order {@link #regionAt} tests them.
     *
     * <p>A fresh array per call, so a caller cannot edit the control scheme by
     * accident. Call it once and keep the result — {@link TouchOverlay} does,
     * because this is read every frame and a per-frame allocation for three
     * ints is three ints of garbage sixty times a second.</p>
     *
     * @return the {@code REGION_} codes of the drawn, pressable buttons
     */
    public static int[] buttonRegions()
    {
        return BUTTON_REGIONS.clone();
    }

    /**
     * Returns a button's centre x in pixels, or 0 for anything that is not a
     * button.
     *
     * @param region one of the {@code REGION_} constants
     * @return the centre x in pixels
     */
    public float buttonCentreX(final int region)
    {
        switch (region)
        {
            case REGION_FIRE:
                return fireCentreX();
            case REGION_JUMP:
                return jumpCentreX();
            case REGION_LEAVE:
                return leaveCentreX();
            default:
                return 0.0f;
        }
    }

    /**
     * Returns a button's centre y in pixels down from the top, or 0 for
     * anything that is not a button.
     *
     * @param region one of the {@code REGION_} constants
     * @return the centre y in pixels from the top
     */
    public float buttonCentreY(final int region)
    {
        switch (region)
        {
            case REGION_FIRE:
                return fireCentreY();
            case REGION_JUMP:
                return jumpCentreY();
            case REGION_LEAVE:
                return leaveCentreY();
            default:
                return 0.0f;
        }
    }

    /**
     * Returns a button's radius in pixels, or 0 for anything that is not a
     * button.
     *
     * <p>This is the radius a <b>finger</b> is tested against. It never
     * changes; see {@link #PRESSED_SCALE} for why the drawn one does.</p>
     *
     * @param region one of the {@code REGION_} constants
     * @return the radius in pixels
     */
    public float buttonRadius(final int region)
    {
        switch (region)
        {
            case REGION_FIRE:
                return fireRadius();
            case REGION_JUMP:
                return jumpRadius();
            case REGION_LEAVE:
                return leaveRadius();
            default:
                return 0.0f;
        }
    }

    /**
     * Returns the radius a button is drawn at, which grows while it is held.
     *
     * <p>Lives here rather than in the renderer for the reason the whole class
     * exists: it is arithmetic, and arithmetic in a draw call is arithmetic
     * nothing runs until a device is in someone's hand.</p>
     *
     * @param region one of the {@code REGION_} constants
     * @param pressed whether a finger is on it
     * @return the drawn radius in pixels
     */
    public float drawnRadius(final int region, final boolean pressed)
    {
        if (pressed)
        {
            return buttonRadius(region) * PRESSED_SCALE;
        }
        return buttonRadius(region);
    }

    /**
     * Returns which control a touch landed on.
     *
     * <p>Buttons first, then halves — see the class Javadoc on why that order
     * is the difference between a working fire button and a camera that spins
     * whenever you shoot. The buttons are walked from the same table the
     * renderer draws, so "drawn" and "pressable" cannot drift apart.</p>
     *
     * @param screenX x in pixels
     * @param screenY y in pixels, down from the top
     * @return one of the {@code REGION_} constants, or {@link #REGION_NONE}
     *     before the layout has been sized
     */
    public int regionAt(final float screenX, final float screenY)
    {
        if (width <= 0.0f || height <= 0.0f)
        {
            return REGION_NONE;
        }
        for (int index = 0; index < BUTTON_REGIONS.length; index++)
        {
            final int region = BUTTON_REGIONS[index];
            if (within(screenX, screenY, buttonCentreX(region), buttonCentreY(region),
                buttonRadius(region)))
            {
                return region;
            }
        }
        if (screenX < width * MOVE_HALF_FRACTION)
        {
            return REGION_MOVE_STICK;
        }
        return REGION_LOOK;
    }

    /**
     * Returns the forward deflection of a stick anchored at one point and
     * dragged to another.
     *
     * <p>Positive is forward, which on screen is <b>upward</b> — hence the
     * subtraction the other way round from the strafe axis. Getting that
     * backwards makes a game in which pushing the stick away from you walks you
     * backwards, and it is the single easiest thing to get wrong here, so it is
     * covered by its own test.</p>
     *
     * @param anchorX where the thumb went down, in pixels
     * @param anchorY where the thumb went down, in pixels from the top
     * @param currentX where it is now
     * @param currentY where it is now
     * @return the deflection, −1..1
     */
    public float stickForward(final float anchorX, final float anchorY,
        final float currentX, final float currentY)
    {
        return deflection(anchorX, anchorY, currentX, currentY, anchorY - currentY);
    }

    /**
     * Returns the strafe deflection of a stick anchored at one point and
     * dragged to another. Positive is right.
     *
     * @param anchorX where the thumb went down, in pixels
     * @param anchorY where the thumb went down, in pixels from the top
     * @param currentX where it is now
     * @param currentY where it is now
     * @return the deflection, −1..1
     */
    public float stickStrafe(final float anchorX, final float anchorY,
        final float currentX, final float currentY)
    {
        return deflection(anchorX, anchorY, currentX, currentY, currentX - anchorX);
    }

    /**
     * Returns how far the stick's knob should be drawn from its anchor.
     *
     * <p>Clamped to {@link #stickRange()}, so a thumb dragged to the far side
     * of the screen leaves the knob at the rim rather than somewhere off in the
     * scenery. The value is the drawn position only; the axes above are
     * clamped independently.</p>
     *
     * @param anchor the coordinate the thumb went down at
     * @param current the coordinate it is at now
     * @param otherAnchor the anchor's other coordinate
     * @param otherCurrent the current point's other coordinate
     * @return the offset from the anchor along the first axis, in pixels
     */
    public float knobOffset(final float anchor, final float current,
        final float otherAnchor, final float otherCurrent)
    {
        final float along = current - anchor;
        final float across = otherCurrent - otherAnchor;
        final float distance = (float) Math.sqrt(along * along + across * across);
        final float range = stickRange();
        if (distance <= range || distance == 0.0f)
        {
            return along;
        }
        return along * range / distance;
    }

    /**
     * Returns where the stick's thumb should be drawn, in pixels.
     *
     * <p>The absolute counterpart of {@link #knobOffset}, so the renderer asks
     * for a position rather than assembling one out of two offsets and an
     * anchor — which is four chances to swap an x for a y in the one file that
     * has no test behind it.</p>
     *
     * @param anchorX where the thumb went down, in pixels
     * @param anchorY where the thumb went down, in pixels from the top
     * @param currentX where it is now
     * @param currentY where it is now
     * @return the thumb's centre x in pixels, clamped to the base ring
     */
    public float knobCentreX(final float anchorX, final float anchorY,
        final float currentX, final float currentY)
    {
        return anchorX + knobOffset(anchorX, currentX, anchorY, currentY);
    }

    /**
     * Returns where the stick's thumb should be drawn, in pixels.
     *
     * @param anchorX where the thumb went down, in pixels
     * @param anchorY where the thumb went down, in pixels from the top
     * @param currentX where it is now
     * @param currentY where it is now
     * @return the thumb's centre y in pixels from the top, clamped to the ring
     */
    public float knobCentreY(final float anchorX, final float anchorY,
        final float currentX, final float currentY)
    {
        return anchorY + knobOffset(anchorY, currentY, anchorX, currentX);
    }

    // One axis of the stick: the raw displacement scaled by the range, zeroed
    // inside the dead zone, and clamped. The dead zone is measured on the
    // COMBINED displacement rather than per axis, because a thumb resting
    // 5 px right and 5 px up has moved 7 px, and testing each axis alone would
    // let that through as movement on neither.
    private float deflection(final float anchorX, final float anchorY,
        final float currentX, final float currentY, final float numerator)
    {
        final float dx = currentX - anchorX;
        final float dy = currentY - anchorY;
        final float distance = (float) Math.sqrt(dx * dx + dy * dy);
        if (distance <= stickDeadZone())
        {
            return 0.0f;
        }
        return clampUnit(numerator / stickRange());
    }

    // True if a point is inside a circle.
    private static boolean within(final float x, final float y,
        final float centreX, final float centreY, final float radius)
    {
        final float dx = x - centreX;
        final float dy = y - centreY;
        return (dx * dx) + (dy * dy) <= radius * radius;
    }

    // Folds a value into -1..1.
    private static float clampUnit(final float value)
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

    /** Returns a debug rendering of the surface and density. */
    @Override
    public String toString()
    {
        return "TouchLayout[" + (int) width + "x" + (int) height + " @" + density + "x]";
    }
}
