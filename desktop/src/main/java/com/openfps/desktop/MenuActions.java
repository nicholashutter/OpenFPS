/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

/**
 * What the main menu can ask the application to do.
 *
 * The Scene2D widgets are untestable without a GL context, so every menu
 * button is wired to one of these methods and nothing else. That keeps the
 * decision ("Quit closes the window") in a plain Java object a headless test
 * can call directly, and leaves {@link MainMenuScreen} as pure layout.
 */
public interface MenuActions
{
    /** Invoked when the user activates "Start Game". */
    void onStartGame();

    /** Invoked when the user activates "Settings". */
    void onSettings();

    /** Invoked when the user activates "Quit". Must close the window. */
    void onQuit();
}
