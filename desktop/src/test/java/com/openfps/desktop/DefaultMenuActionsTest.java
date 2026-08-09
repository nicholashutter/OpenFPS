/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import com.openfps.gdx.DefaultMenuActions;
import com.openfps.gdx.MenuActions;
import com.openfps.gdx.MenuButtonListener;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The menu wiring against the <b>real</b> GLFW window port.
 *
 * <p>{@code DefaultMenuActions} itself is tested in {@code :gdxshared} against
 * the null port, which is where the logic lives. What that test cannot cover is
 * the thing this one does: that {@link GdxWindowPort} — a class with an
 * {@code Lwjgl3Application} behind it — honours a close request with no window
 * open at all. It does, because the flag is an {@code AtomicBoolean} owned by
 * the port rather than a question asked of GLFW, and that property is the
 * reason Quit works before the first frame and after the last.</p>
 */
class DefaultMenuActionsTest
{
    @Test
    @DisplayName("Quit works against the real GLFW port's flag with no window open")
    void shouldRequestCloseOnGdxPort()
    {
        final GdxWindowPort window = new GdxWindowPort();

        window.init();

        new DefaultMenuActions(window).onQuit();

        assertThat(window.isCloseRequested()).isTrue();
    }

    @Test
    @DisplayName("the Quit button wiring closes a real window when activated")
    void shouldCloseAWindowThroughTheQuitButtonWiring()
    {
        final GdxWindowPort window = new GdxWindowPort();

        window.init();

        final MenuActions actions = new DefaultMenuActions(window);

        final MenuButtonListener quitButton = new MenuButtonListener(actions::onQuit);

        assertThat(window.isCloseRequested()).isFalse();

        quitButton.changed(null, null);

        assertThat(window.isCloseRequested()).isTrue();
    }
}
