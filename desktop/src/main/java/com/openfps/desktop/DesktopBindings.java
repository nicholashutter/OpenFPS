/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import com.badlogic.gdx.Input;

import org.lwjgl.glfw.GLFW;

import com.openfps.engine.hal.adapter.ActionBindings;
import com.openfps.engine.hal.port.GameAction;
import com.openfps.engine.hal.port.InputBinding;

/**
 * The desktop control scheme: which keys, mouse buttons and gamepad controls
 * this platform binds each {@link GameAction} to out of the box.
 *
 * <p><b>This class is the only place in the desktop build that names a key.</b>
 * That is its whole reason to exist. The polling loop in {@link GdxInputPort}
 * used to hold the constants inline, which made the scheme unaskable — nothing
 * could report it, save it, or change it, and the Android build would have had
 * no choice but to write a second copy of the same loop with different literals
 * in it.</p>
 *
 * <p>The engine deliberately ships no defaults of its own
 * ({@link ActionBindings}), because {@code Input.Keys.SPACE} is a libGDX number
 * and {@code :engine} may not import libGDX. So the defaults live on the
 * platform that knows them, and this is the desktop one.</p>
 *
 * <h2>Where the choices come from</h2>
 *
 * <ul>
 *   <li><b>Fire is the left mouse button</b>, plus left control as an alternate
 *       so the game is playable on a laptop trackpad where a click and a drag
 *       fight each other. Two bindings on one action is exactly what
 *       {@link ActionBindings} exists to allow.</li>
 *   <li><b>Jump is the space bar.</b></li>
 *   <li><b>Movement is WASD and the arrow keys.</b> The arrows cost nothing and
 *       are the first thing someone who has never played a shooter reaches
 *       for.</li>
 *   <li><b>{@link GameAction#LEAVE_MATCH} is Escape.</b> It is bindable like
 *       anything else, but it is the way out of a captured cursor, so
 *       {@link GdxInputPort} treats an unbound one as a bug worth logging rather
 *       than a preference.</li>
 *   <li><b>{@link GameAction#TOGGLE_INVERT_LOOK} is I</b>, for invert. A letter
 *       key rather than a function key because it is a thing a player reaches
 *       for in the first minute of play — the vertical axis is the one setting
 *       a shooter cannot pick correctly on the player's behalf — and because
 *       nothing else in this scheme wants a letter outside the WASD block.</li>
 * </ul>
 *
 * <h2>The gamepad scheme is the one every console shooter has taught</h2>
 *
 * <p>Deliberately unoriginal. A player who picks up a pad already knows this
 * layout, and a controller scheme that is merely <i>defensible</i> rather than
 * <i>familiar</i> costs them the first ten minutes of play:</p>
 *
 * <ul>
 *   <li><b>Left stick moves, right stick looks.</b></li>
 *   <li><b>Right trigger fires</b>, with <b>A</b> as an alternate so a pad with
 *       a broken or over-stiff trigger is still playable. A trigger is an axis,
 *       not a button, so this is where {@code GAMEPAD_AXIS} earns its place: the
 *       port compares the pull against a threshold and reports held.</li>
 *   <li><b>A jumps</b> — which means A both jumps and fires, and that is a real
 *       compromise rather than an oversight. There are only so many buttons a
 *       thumb reaches without leaving the right stick, and jump is the one every
 *       console shooter puts on the bottom face button. A player who dislikes it
 *       rebinds one row.</li>
 *   <li><b>Left bumper sprints.</b> A shoulder rather than a stick click:
 *       clicking the right stick is the standard <i>crouch</i>, and a modifier
 *       that has to be held while aiming must not be under the aiming thumb.</li>
 *   <li><b>Start and Back both leave the match</b>, for the same reason Android
 *       binds two controls to it — being unable to leave is not a cosmetic
 *       failure. Different pads label and place these two very differently, and
 *       binding both means the player does not have to guess which one this pad
 *       calls Start.</li>
 *   <li><b>Nothing on the pad toggles invert look.</b> It is a setting, pressed
 *       once ever, and spending a scarce pad button on it would be a worse trade
 *       than reaching for the keyboard — which is right there.</li>
 * </ul>
 *
 * <p>The button and axis codes are GLFW's <i>gamepad</i> constants rather than
 * raw joystick indices. That matters: GLFW maps any pad it recognises onto this
 * standard layout using the SDL controller database it carries internally, so
 * "button A" is the bottom face button on an Xbox pad, a DualShock and a Switch
 * Pro controller alike. Without that mapping a binding table would be per-brand
 * and this file would be a lie on two thirds of controllers.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class DesktopBindings
{
    private DesktopBindings()
    {
        // defaults holder
    }

    /**
     * Builds the desktop default control scheme.
     *
     * <p>A fresh table each call, never a shared constant: the returned object is
     * mutable by design — a controls screen rebinds it in place — so handing two
     * callers the same instance would have one player's changes appear in the
     * other's game.</p>
     *
     * @return a complete binding table; every {@link GameAction} has at least
     *     one control
     */
    public static ActionBindings defaults()
    {
        // All four movement actions name the same control, which on a stick is
        // the literal truth rather than a shortcut: the four directions are not
        // four controls but one control read four ways. AndroidBindings has made
        // this argument for a thumb since before there was a gamepad. The
        // binding is what makes the scheme reportable and rebindable; the
        // deflection itself is read directly by GdxInputPort.pollGamepad,
        // because a stick axis has a direction and "is it held" has no answer.
        final InputBinding leftStick =
            InputBinding.gamepadAxis(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X);
        return new ActionBindings()
            .bind(GameAction.MOVE_FORWARD,
                InputBinding.key(Input.Keys.W), InputBinding.key(Input.Keys.UP),
                leftStick)
            .bind(GameAction.MOVE_BACKWARD,
                InputBinding.key(Input.Keys.S), InputBinding.key(Input.Keys.DOWN),
                leftStick)
            .bind(GameAction.STRAFE_LEFT,
                InputBinding.key(Input.Keys.A), InputBinding.key(Input.Keys.LEFT),
                leftStick)
            .bind(GameAction.STRAFE_RIGHT,
                InputBinding.key(Input.Keys.D), InputBinding.key(Input.Keys.RIGHT),
                leftStick)
            .bind(GameAction.FIRE,
                InputBinding.mouseButton(Input.Buttons.LEFT),
                InputBinding.key(Input.Keys.CONTROL_LEFT),
                InputBinding.gamepadAxis(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER),
                InputBinding.gamepadButton(GLFW.GLFW_GAMEPAD_BUTTON_A))
            .bind(GameAction.JUMP, InputBinding.key(Input.Keys.SPACE),
                InputBinding.gamepadButton(GLFW.GLFW_GAMEPAD_BUTTON_A))
            .bind(GameAction.SPRINT, InputBinding.key(Input.Keys.SHIFT_LEFT),
                InputBinding.gamepadButton(GLFW.GLFW_GAMEPAD_BUTTON_LEFT_BUMPER))
            .bind(GameAction.LEAVE_MATCH, InputBinding.key(Input.Keys.ESCAPE),
                InputBinding.gamepadButton(GLFW.GLFW_GAMEPAD_BUTTON_START),
                InputBinding.gamepadButton(GLFW.GLFW_GAMEPAD_BUTTON_BACK))
            .bind(GameAction.TOGGLE_INVERT_LOOK, InputBinding.key(Input.Keys.I));
    }
}
