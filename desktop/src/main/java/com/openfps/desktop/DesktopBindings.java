/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import com.badlogic.gdx.Input;

import com.openfps.engine.hal.adapter.ActionBindings;
import com.openfps.engine.hal.port.GameAction;
import com.openfps.engine.hal.port.InputBinding;

/**
 * The desktop control scheme: which keys and mouse buttons this platform binds
 * each {@link GameAction} to out of the box.
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
        return new ActionBindings()
            .bind(GameAction.MOVE_FORWARD,
                InputBinding.key(Input.Keys.W), InputBinding.key(Input.Keys.UP))
            .bind(GameAction.MOVE_BACKWARD,
                InputBinding.key(Input.Keys.S), InputBinding.key(Input.Keys.DOWN))
            .bind(GameAction.STRAFE_LEFT,
                InputBinding.key(Input.Keys.A), InputBinding.key(Input.Keys.LEFT))
            .bind(GameAction.STRAFE_RIGHT,
                InputBinding.key(Input.Keys.D), InputBinding.key(Input.Keys.RIGHT))
            .bind(GameAction.FIRE,
                InputBinding.mouseButton(Input.Buttons.LEFT),
                InputBinding.key(Input.Keys.CONTROL_LEFT))
            .bind(GameAction.JUMP, InputBinding.key(Input.Keys.SPACE))
            .bind(GameAction.SPRINT, InputBinding.key(Input.Keys.SHIFT_LEFT))
            .bind(GameAction.LEAVE_MATCH, InputBinding.key(Input.Keys.ESCAPE))
            .bind(GameAction.TOGGLE_INVERT_LOOK, InputBinding.key(Input.Keys.I));
    }
}
