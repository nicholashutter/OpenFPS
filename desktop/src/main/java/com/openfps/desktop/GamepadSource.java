/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

/**
 * One gamepad, as much of it as {@link GdxInputPort} needs, behind a seam a
 * test can stand in front of.
 *
 * <p>The same trick as {@code GdxInputPort.ControlProbe} and for the same
 * reason: the interesting behaviour around a controller is hot-plug, dead
 * zones, edge detection and the sign of the vertical stick — all of which can
 * be wrong, none of which CI can exercise, because CI has no controller and no
 * GLFW context. Behind this interface the production implementation is
 * {@link GlfwGamepad}, six native calls and no decisions; a test supplies a few
 * fields and drives every path including the one where the pad is yanked out
 * mid-match.</p>
 *
 * <p><b>The sticks are named rather than indexed.</b> A binding table carries
 * opaque axis codes because the engine must not know what an axis is, but this
 * interface is the platform, and at the platform the left stick is genuinely
 * the left stick. Asking for it by name keeps the axis-index arithmetic in one
 * file instead of leaking a GLFW numbering into the polling loop — which is the
 * whole thing {@code DesktopBindings} was extracted to prevent.</p>
 *
 * <p><b>Every reading is raw.</b> No dead zone and no response curve are
 * applied here; {@link com.openfps.gdx.AnalogStick} owns those, applied once
 * inside {@code InputAccumulator} so that no backend can forget them.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public interface GamepadSource
{
    /**
     * Reads the device once. Call from the thread that owns the window, once
     * per frame, before any of the accessors below.
     *
     * <p>Also where connection and disconnection are noticed: a controller may
     * appear or vanish between any two calls, and it is this method's job that
     * the ones after it answer consistently rather than half from a device that
     * is no longer there.</p>
     */
    void poll();

    /**
     * Returns whether a usable gamepad was present at the last {@link #poll()}.
     *
     * @return true if the accessors below describe a real device
     */
    boolean isConnected();

    /**
     * Returns a human-readable name for the connected pad.
     *
     * @return the device name, or a placeholder when nothing is connected;
     *     never null
     */
    String name();

    /**
     * Returns whether a button is held.
     *
     * @param buttonIndex the platform's button code, as carried by a
     *     {@code GAMEPAD_BUTTON} binding
     * @return true while it is down; false when nothing is connected or the
     *     code names no button on this device
     */
    boolean isButtonDown(int buttonIndex);

    /**
     * Returns whether a button went down since the previous {@link #poll()}.
     *
     * <p>Needed because a pad reports levels and some actions are toggles —
     * {@code LEAVE_MATCH} on a held Start would bounce the player straight back
     * out of the menu it just returned them to, once per frame.</p>
     *
     * @param buttonIndex the platform's button code
     * @return true on the single poll the button became held
     */
    boolean didButtonGoDown(int buttonIndex);

    /**
     * Returns whether an axis is deflected far enough to read as a press.
     *
     * <p><b>Answers only for triggers.</b> A trigger has a rest position and one
     * direction of travel, so "is it pressed" is a real question; a stick axis
     * has a direction, so it is not, and this reports false for one rather than
     * inventing an answer. That is the same degradation rule a desktop table
     * already applies to a touch-region binding — see
     * {@code GdxInputPort.isDown}.</p>
     *
     * @param axisIndex the platform's axis code, as carried by a
     *     {@code GAMEPAD_AXIS} binding
     * @return true when the named axis is a trigger and it is past
     *     {@link com.openfps.gdx.AnalogStick#TRIGGER_THRESHOLD}
     */
    boolean isAxisPressed(int axisIndex);

    /**
     * Returns the movement stick's horizontal deflection, raw.
     *
     * @return −1 fully left to 1 fully right; 0 when nothing is connected
     */
    float leftStickX();

    /**
     * Returns the movement stick's vertical deflection, raw and in the device's
     * own orientation.
     *
     * @return −1 fully <b>away</b> from the player to 1 fully toward them; 0
     *     when nothing is connected
     */
    float leftStickY();

    /**
     * Returns the look stick's horizontal deflection, raw.
     *
     * @return −1 fully left to 1 fully right; 0 when nothing is connected
     */
    float rightStickX();

    /**
     * Returns the look stick's vertical deflection, raw and in the device's own
     * orientation — which is the screen convention {@code InputAccumulator}
     * documents, positive downward.
     *
     * @return −1 fully away from the player to 1 fully toward them; 0 when
     *     nothing is connected
     */
    float rightStickY();
}
