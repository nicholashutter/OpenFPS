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
 * <h2>There is no sprint</h2>
 *
 * <p>Deliberately left unbound rather than given a fourth button. Screen space
 * next to the fire button is the scarcest thing on a phone, and a modifier that
 * has to be held while also aiming and firing wants a second thumb nobody has.
 * {@code GdxInputPort.isAnyActive} reads an unbound action as inactive, so the
 * player simply never sprints — and {@code AndroidInputPort.init()} says so in
 * logcat rather than leaving it to be discovered.</p>
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
        return new ActionBindings()
            .bind(GameAction.MOVE_FORWARD, stick)
            .bind(GameAction.MOVE_BACKWARD, stick)
            .bind(GameAction.STRAFE_LEFT, stick)
            .bind(GameAction.STRAFE_RIGHT, stick)
            .bind(GameAction.FIRE, InputBinding.touchRegion(TouchLayout.REGION_FIRE))
            .bind(GameAction.JUMP, InputBinding.touchRegion(TouchLayout.REGION_JUMP))
            .bind(GameAction.LEAVE_MATCH,
                InputBinding.touchRegion(TouchLayout.REGION_LEAVE),
                InputBinding.key(Input.Keys.BACK));
    }
}
