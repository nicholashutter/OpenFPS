/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.android;

import com.badlogic.gdx.Input;

import com.openfps.engine.hal.adapter.ActionBindings;
import com.openfps.engine.hal.port.GameAction;
import com.openfps.engine.hal.port.InputBinding;

/**
 * The default Android control scheme — the one file in this module that names
 * a control.
 *
 * <p>The counterpart of {@code DesktopBindings}, and the reason that class was
 * written the way it was. {@code :engine} ships no defaults at all: a control
 * code is a number whose meaning belongs to a platform, and the engine cannot
 * import libGDX to learn what {@code Input.Keys.SPACE} is, let alone what
 * "the fire button, bottom right" is. So each launcher supplies its own table
 * and the engine only ever asks {@link GameAction} questions.</p>
 *
 * <p>That split is what let the Android controls be built without touching a
 * line of gameplay code. {@code Match} does not know whether the trigger was a
 * mouse button or a thumb.</p>
 *
 * <h2>Every movement action is bound to the same control</h2>
 *
 * <p>All four of {@link GameAction#MOVE_FORWARD}, {@code MOVE_BACKWARD},
 * {@code STRAFE_LEFT} and {@code STRAFE_RIGHT} name
 * {@link TouchLayout#REGION_MOVE_STICK}. That is not a shortcut — on a stick it
 * is the literal truth. The four directions are not four controls; they are one
 * control read four ways, and the geometry that decides how far the stick is
 * pushed lives in {@link TouchLayout} where it can be tested. A table that
 * pretended otherwise would have to invent four regions that no finger can
 * distinguish.</p>
 *
 * <h2>Leaving a match has two bindings, and needs both</h2>
 *
 * <p>{@link GameAction#LEAVE_MATCH} is bound to the on-screen button
 * <i>and</i> to {@link Input.Keys#BACK}. The hardware or gesture back is what
 * an Android user will reach for first and it must work; the on-screen button
 * is what still works on a device where back has been swallowed by a gesture
 * navigation setting. Being unable to leave a match is not a cosmetic failure —
 * it is an app that has to be force-stopped.</p>
 *
 * <h2>There is no sprint <i>on the screen</i></h2>
 *
 * <p>Deliberately given no touch button. Screen space next to the fire button is
 * the scarcest thing on a phone, and a modifier that has to be held while also
 * aiming and firing wants a second thumb nobody has.
 * {@code GdxInputPort.isAnyActive} reads an unbound action as inactive, so a
 * player on touch alone simply never sprints — and
 * {@code AndroidInputPort.init()} says so in logcat rather than leaving it to be
 * discovered.</p>
 *
 * <p>A <b>controller</b> changes that calculus completely and so it gets one. A
 * shoulder button costs no screen area and is not under a thumb that is already
 * busy, which is the entire objection above. So sprint is bound to L1 and to
 * nothing else: the action is reachable exactly when the hardware makes it
 * reachable.</p>
 *
 * <p>{@link GameAction#TOGGLE_INVERT_LOOK} is the one action this table still
 * leaves unbound, and it stays that way on purpose. It is a setting a player
 * presses once ever, and neither a screen button nor a pad button — both scarce
 * for different reasons — is worth spending on it. Desktop can afford a letter
 * key; a phone cannot afford anything, so the honest answer is that the
 * preference belongs in a settings screen and is reachable programmatically
 * through the accumulator until there is one.</p>
 *
 * <h2>A controller is an extra path, never a mode</h2>
 *
 * <p>Nothing here replaces a touch binding — every gamepad control is an
 * <i>additional</i> entry on an action that already had one. A phone with a pad
 * clipped to it still has a touchscreen, and a player may perfectly reasonably
 * steer with the stick and tap the on-screen fire button. That works because
 * multiple bindings on an action are alternates rather than a chord, and because
 * {@code InputAccumulator} keeps the pad's readings in a channel of their own and
 * sums them with the touch channel instead of overwriting it.</p>
 *
 * <h2>Why a pad button's code here is a key constant</h2>
 *
 * <p>On Android a gamepad button genuinely <i>is</i> a key event: the platform
 * numbers {@code BUTTON_A} in the same space as {@code BACK}, and libGDX
 * delivers both through {@code InputProcessor.keyDown}. So these
 * {@code GAMEPAD_BUTTON} bindings carry libGDX key constants, while the desktop
 * table's carry GLFW gamepad indices — different numbers for the same physical
 * button. That is not an inconsistency to be tidied away; it is exactly what
 * {@code InputBinding} means by a code being opaque and platform-owned, and why
 * {@code Source} is what a loader checks before trusting a saved scheme.</p>
 */
public final class AndroidBindings
{
    private AndroidBindings()
    {
        // binding table factory
    }

    /**
     * Returns a fresh table carrying the default touch scheme.
     *
     * <p>A new instance per call, so a settings screen can rebind one action
     * without every other holder of "the defaults" changing underneath it.</p>
     *
     * @return the default Android bindings; never null
     */
    public static ActionBindings defaults()
    {
        final InputBinding stick = InputBinding.touchRegion(TouchLayout.REGION_MOVE_STICK);

        // The pad's movement stick, named for the same reason the touch stick is:
        // the four directions are one control read four ways, and the geometry
        // that decides how far it is pushed lives where it can be tested. The
        // deflection itself reaches the game through
        // AndroidInputPort.onGamepadAxes, not through this row — a stick axis has
        // a direction, so "is it held" has no answer.
        final InputBinding padStick = InputBinding.gamepadAxis(
            AndroidInputPort.AXIS_LEFT_STICK);

        return new ActionBindings()
            .bind(GameAction.MOVE_FORWARD, stick, padStick)
            .bind(GameAction.MOVE_BACKWARD, stick, padStick)
            .bind(GameAction.STRAFE_LEFT, stick, padStick)
            .bind(GameAction.STRAFE_RIGHT, stick, padStick)
            .bind(GameAction.FIRE, InputBinding.touchRegion(TouchLayout.REGION_FIRE),
                InputBinding.gamepadAxis(AndroidInputPort.AXIS_RIGHT_TRIGGER),
                InputBinding.gamepadButton(Input.Keys.BUTTON_R1))
            .bind(GameAction.JUMP, InputBinding.touchRegion(TouchLayout.REGION_JUMP),
                InputBinding.gamepadButton(Input.Keys.BUTTON_A))
            // Reachable only with a controller — see the class Javadoc on why a
            // shoulder button answers the objection a screen button could not.
            .bind(GameAction.SPRINT,
                InputBinding.gamepadButton(Input.Keys.BUTTON_L1))
            .bind(GameAction.LEAVE_MATCH,
                InputBinding.touchRegion(TouchLayout.REGION_LEAVE),
                InputBinding.key(Input.Keys.BACK),
                InputBinding.gamepadButton(Input.Keys.BUTTON_START),
                InputBinding.gamepadButton(Input.Keys.BUTTON_SELECT));
    }
}
