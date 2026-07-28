/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.port;

/**
 * Everything the player can ask the game to do, named by intent rather than by
 * device.
 *
 * <p>This is the vocabulary that sits between a physical control and
 * {@link InputState}. {@code InputState} is already device-neutral — it carries
 * "forward axis" and "fire", not "W" and "left mouse button" — but until this
 * enum existed the <i>mapping</i> was not: every adapter hard-coded its own key
 * constants inside its polling loop, so the answer to "what fires the weapon?"
 * was a literal buried in platform code and there was no way to ask it, show it
 * or change it.</p>
 *
 * <h2>Why the vocabulary lives in the engine and the codes do not</h2>
 *
 * <p>The engine owns <b>which actions exist</b>. It deliberately does not own
 * <b>what triggers them</b>, and there are no default key codes anywhere in this
 * package. A key code is a platform number — libGDX's {@code Input.Keys.SPACE},
 * an Android touch region, a gamepad button index — and baking any of those in
 * here would make {@code :engine} know about a windowing toolkit it is
 * forbidden to import.</p>
 *
 * <p>So each platform declares its own defaults and hands them over as an
 * {@link com.openfps.engine.hal.adapter.ActionBindings} table. The engine-side
 * benefit is real rather than architectural bookkeeping: a settings screen, a
 * saved profile and a replay all address controls through these constants, so
 * none of them has to be rewritten when the Android build binds
 * {@link #FIRE} to a screen region instead of a mouse button.</p>
 *
 * <h2>Two kinds of action</h2>
 *
 * <p>{@link #MOVE_FORWARD} through {@link #STRAFE_RIGHT} are <b>axis
 * contributors</b>: each one pushes a movement axis in one direction, and
 * opposing pairs cancel. The rest are <b>buttons</b>: held or not held for the
 * duration of a tic. {@link #isAxis()} tells the two apart so a bindings UI can
 * group them without a second table.</p>
 *
 * <p>The seven entries that reach the simulation line up one-to-one with the
 * fields of {@link InputState}. {@link #LEAVE_MATCH} is the exception and is
 * deliberately included anyway: it is a control the player presses, it deserves
 * to be rebindable like any other, and the fact that the UI consumes it rather
 * than the simulation is not something the player can see.</p>
 */
public enum GameAction
{
    /** Push the forward movement axis positive. */
    MOVE_FORWARD(true),

    /** Push the forward movement axis negative. */
    MOVE_BACKWARD(true),

    /** Push the strafe axis negative — leftward, relative to the heading. */
    STRAFE_LEFT(true),

    /** Push the strafe axis positive — rightward, relative to the heading. */
    STRAFE_RIGHT(true),

    /** Pull the trigger. Rate of fire is the weapon's business, not the button's. */
    FIRE(false),

    /** Leave the ground. */
    JUMP(false),

    /** Move faster while held. */
    SPRINT(false),

    /**
     * Put the menu back in front — the way out of a captured cursor.
     *
     * <p>Consumed by the platform UI rather than by the simulation, so it has no
     * counterpart field in {@link InputState}. It is a bindable control all the
     * same: a player who has moved every other control should not find this one
     * welded to a key.</p>
     */
    LEAVE_MATCH(false);

    /** True when this action contributes to a movement axis. */
    private final boolean axis;

    GameAction(final boolean isAxisContributor)
    {
        this.axis = isAxisContributor;
    }

    /**
     * Returns whether this action pushes a movement axis rather than acting as
     * a button.
     *
     * @return true for the four movement actions, false for the rest
     */
    public boolean isAxis()
    {
        return axis;
    }
}
