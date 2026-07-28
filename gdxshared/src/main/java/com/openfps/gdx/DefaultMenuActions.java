/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import com.openfps.engine.hal.port.I_WindowPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default wiring behind the main menu buttons, on every platform.
 *
 * Deliberately holds an {@link I_WindowPort} rather than calling
 * {@code Gdx.app.exit()} or {@code Activity.finish()} directly: closing goes
 * through the same port the engine uses, so "Quit" walks the exact shutdown
 * path a window-manager close or a back gesture would, and a headless test can
 * verify it with a fake port. That indirection is also the only reason this
 * class is shared rather than written twice — the two launchers hand it two
 * completely different window implementations and it never learns which.
 *
 * <b>Start Game does not itself start anything here.</b> The
 * {@code MENU -> PLAYING} transition belongs to the {@link UiStateMachine}, and
 * each platform's frame callback wraps this object to perform it — see
 * {@code GdxFrameLoopListener.StartGameTransition} on desktop and
 * {@code AndroidUiFrameCallback.StartGameTransition} on Android. This class
 * stays the "what does the application do about it" half, which for now is a
 * log line: there is no map loader yet, so the world the player drops into is
 * the demo scene the launcher already built. Settings is the same shape with
 * nothing behind it.
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
        // Phase 2 lands the WAD loader; until a map can be chosen, "start"
        // means "show the demo scene the launcher already built" and the only
        // thing that changes is the UI state, which the caller applies.
        LOG.info("Menu: Start Game selected — entering the demo world (no map loader yet)");
    }

    @Override
    public void onMultiplayer()
    {
        LOG.info("Menu: Multiplayer selected — opening a networked match");
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
