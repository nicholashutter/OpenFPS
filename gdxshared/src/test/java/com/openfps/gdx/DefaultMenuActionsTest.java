/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import com.openfps.engine.hal.adapter.nulladapter.NullWindowPort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link DefaultMenuActions} — the logic behind the menu buttons.
 *
 * This is the whole reason the buttons delegate to an interface instead of
 * calling {@code Gdx.app.exit()} inline: "Quit closes the window" is
 * checkable with no display, using the null window port as the stand-in.
 *
 * <p>The same assertion against each platform's <i>real</i> window port lives
 * beside that port — {@code DefaultMenuActionsTest} in {@code :desktop} and
 * {@code AndroidMenuWiringTest} in {@code :android}. It has to: this module
 * deliberately knows no backend, so the only window it can reach is a fake
 * one.</p>
 */
class DefaultMenuActionsTest
{
    @Test
    @DisplayName("a null window is rejected")
    void shouldRejectNullWindow()
    {
        assertThatThrownBy(() -> new DefaultMenuActions(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("window");
    }

    @Test
    @DisplayName("Quit requests a window close")
    void shouldRequestCloseOnQuit()
    {
        final NullWindowPort window = new NullWindowPort();
        window.init();
        final MenuActions actions = new DefaultMenuActions(window);
        assertThat(window.isCloseRequested()).isFalse();

        actions.onQuit();
        assertThat(window.isCloseRequested()).isTrue();
    }

    @Test
    @DisplayName("the Quit button wiring closes the window when activated")
    void shouldCloseWindowThroughTheButtonWiring()
    {
        // The whole chain a click or a tap travels — Scene2D change event,
        // listener, action, port — with only the gesture itself faked.
        final NullWindowPort window = new NullWindowPort();
        window.init();
        final MenuButtonListener quitButton =
            new MenuButtonListener(new DefaultMenuActions(window)::onQuit);

        assertThat(window.isCloseRequested()).isFalse();
        quitButton.changed(null, null);
        assertThat(window.isCloseRequested()).isTrue();
    }

    @Test
    @DisplayName("Start Game does not close the window")
    void shouldNotCloseOnStartGame()
    {
        final NullWindowPort window = new NullWindowPort();
        window.init();
        new DefaultMenuActions(window).onStartGame();
        assertThat(window.isCloseRequested()).isFalse();
    }

    @Test
    @DisplayName("Multiplayer does not close the window")
    void shouldNotCloseOnMultiplayer()
    {
        final NullWindowPort window = new NullWindowPort();
        window.init();
        new DefaultMenuActions(window).onMultiplayer();
        assertThat(window.isCloseRequested()).isFalse();
    }

    @Test
    @DisplayName("Settings does not close the window")
    void shouldNotCloseOnSettings()
    {
        final NullWindowPort window = new NullWindowPort();
        window.init();
        new DefaultMenuActions(window).onSettings();
        assertThat(window.isCloseRequested()).isFalse();
    }
}
