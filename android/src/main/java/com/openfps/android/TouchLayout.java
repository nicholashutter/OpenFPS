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
 *   |                                                  |
 *   |            look  (anywhere on the right)         |
 *   |   move                                           |
 *   |  (anywhere                              ( ^ )    |  jump
 *   |   on the left)                    ( FIRE )       |
 *   +--------------------------------------------------+
 * </pre>
 *
 * <p><b>The stick has no fixed home.</b> It appears wherever the left thumb
 * lands and measures deflection from there. A stick painted at a fixed spot
 * requires the player to look at their thumb to find it; a floating one does
 * not, and on a screen the player is also trying to aim at, that is the whole
 * difference between a control and an obstacle.</p>
 *
 * <p><b>Buttons are tested before halves.</b> Fire and jump sit in the right
 * half, which is also the look region, so the order in
 * {@link #regionAt} is load-bearing rather than incidental: halves first would
 * make the fire button unreachable and every tap on it would spin the camera
 * instead.</p>
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

    /** Fraction of the screen width given to the movement half. */
    public static final float MOVE_HALF_FRACTION = 0.5f;

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

    /**
     * Returns which control a touch landed on.
     *
     * <p>Buttons first, then halves — see the class Javadoc on why that order
     * is the difference between a working fire button and a camera that spins
     * whenever you shoot.</p>
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
        if (within(screenX, screenY, leaveCentreX(), leaveCentreY(), leaveRadius()))
        {
            return REGION_LEAVE;
        }
        if (within(screenX, screenY, fireCentreX(), fireCentreY(), fireRadius()))
        {
            return REGION_FIRE;
        }
        if (within(screenX, screenY, jumpCentreX(), jumpCentreY(), jumpRadius()))
        {
            return REGION_JUMP;
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
