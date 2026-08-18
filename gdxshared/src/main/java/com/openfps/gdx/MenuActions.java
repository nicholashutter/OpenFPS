/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

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
    /** Invoked when the user activates "Single Player" — a match against bots. */
    void onStartGame();

    /**
     * Invoked when the user activates "Multiplayer" — a match against peers.
     *
     * <p>A separate method rather than a parameter on {@link #onStartGame()},
     * because the two differ in what the application must do <i>before</i> the
     * world appears: multiplayer has to open a socket and find peers, and that
     * can fail in ways single player cannot. A boolean argument would push that
     * distinction into every implementor's body instead of into the type.</p>
     */
    void onMultiplayer();

    /** Invoked when the user activates "Settings". */
    void onSettings();

    /**
     * Invoked when the user activates "Controls" — opens the rebind
     * screen where every action can be remapped.
     *
     * <p>A separate method rather than a parameter on
     * {@link #onSettings()} because the rebind screen has its own
     * state ({@link UiState#CONTROLS}) and its own callback, the
     * way a settings screen does, and folding it into onSettings
     * would force every implementor to know which one the player
     * wanted. Two methods, one button each, is the signature that
     * stays readable.</p>
     */
    void onControls();

    /**
     * Invoked when the user activates "Select Map" — opens a screen that lists
     * the registered maps and lets the player pick one.
     *
     * <p>A separate method rather than a parameter on {@link #onSettings()}
     * because the picker is a full screen, not a toggle on a shared one: it
     * owns its own Scene2D stage and the player's next move is "pick a map",
     * not "flip a switch". Forcing the two onto the same code path would
     * give the caller a parameter to switch on at every implementor, which
     * is the kind of signature that gets out of step with itself.</p>
     */
    void onMapSelection();

    /** Invoked when the user activates "Quit". Must close the window. */
    void onQuit();
}
