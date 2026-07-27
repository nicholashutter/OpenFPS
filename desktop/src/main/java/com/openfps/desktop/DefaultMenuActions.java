/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import com.openfps.engine.hal.port.I_WindowPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The desktop wiring behind the main menu buttons.
 *
 * Deliberately holds an {@link I_WindowPort} rather than calling
 * {@code Gdx.app.exit()} directly: closing goes through the same port the
 * engine uses, so "Quit" walks the exact shutdown path a window-manager
 * close would, and a headless test can verify it with a fake port.
 *
 * Start Game and Settings only record intent for now — there is no map
 * loader and no settings screen yet. They are wired so the menu is complete
 * and the seam exists for the phase that fills them in.
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class DefaultMenuActions implements MenuActions
{
    private static final Logger LOG = LoggerFactory.getLogger(DefaultMenuActions.class);

    /** The window to close when the user quits. */
    private final I_WindowPort window;

    /**
     * Creates the action set.
     *
     * @param window the window port used to honour "Quit"; must not be null
     */
    public DefaultMenuActions(final I_WindowPort window)
    {
        if (window == null)
        {
            throw new IllegalArgumentException("window must not be null");
        }
        this.window = window;
    }

    @Override
    public void onStartGame()
    {
        // Phase 2 lands the WAD loader; until a map can be loaded there is
        // nothing to start, so this records intent rather than pretending.
        LOG.info("Menu: Start Game selected (no map loader yet)");
    }

    @Override
    public void onSettings()
    {
        LOG.info("Menu: Settings selected (no settings screen yet)");
    }

    @Override
    public void onQuit()
    {
        LOG.info("Menu: Quit selected — closing window");
        window.requestClose();
    }
}
