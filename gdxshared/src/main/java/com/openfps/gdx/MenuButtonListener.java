/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;

/**
 * Adapts a Scene2D {@code ChangeEvent} to a plain {@link Runnable}.
 *
 * Three near-identical anonymous listeners would be three places for the
 * wiring to drift. One named class taking a method reference keeps the
 * button-to-action mapping on a single readable line in
 * {@link MainMenuScreen}, and — because {@link #changed} needs nothing from
 * its arguments — lets a headless test invoke it with nulls and assert the
 * action ran.
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class MenuButtonListener extends ChangeListener
{
    /** What to run when the button is activated. */
    private final Runnable action;

    /**
     * Creates a listener that runs {@code action} on every change event.
     *
     * @param action the action to run; must not be null
     */
    public MenuButtonListener(final Runnable action)
    {
        if (action == null)
        {
            throw new IllegalArgumentException("action must not be null");
        }
        this.action = action;
    }

    /**
     * Runs the wrapped action. Both arguments are unused — a button change
     * event carries no information the action needs.
     *
     * @param event the Scene2D change event, ignored
     * @param actor the actor that changed, ignored
     */
    @Override
    public void changed(final ChangeEvent event, final Actor actor)
    {
        action.run();
    }
}
