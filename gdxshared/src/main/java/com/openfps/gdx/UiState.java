/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

/**
 * Which desktop UI the player is in front of.
 *
 * <p>These two states are mutually exclusive by construction, which is the
 * whole point of naming them. The menu used to be composited over a live
 * first-person render every single frame: the world kept turning behind the
 * buttons, the Scene2D stage kept hit-testing, and "is the game running" was
 * answered by whether the cursor happened to be caught. A window has exactly
 * one of these two personalities at a time, and every consumer — the frame
 * loop, the input port, the cursor — reads it from here rather than inferring
 * it from a device flag.</p>
 *
 * <p><b>Why an enum and not a boolean.</b> The same reason
 * {@code GdxWindowPort.State}, {@code MemoryPort.State} and
 * {@code SubsystemState} are enums: the call sites read as sentences
 * ({@code state.capturesCursor()}), the legal transitions live in one table
 * that can be tested, and a third state — a pause overlay, a settings screen, a
 * loading screen — is an addition here rather than a second boolean somewhere
 * else that has to be kept consistent with the first.</p>
 *
 * <p>That prediction has since been collected on twice: {@link #SETTINGS} and
 * {@link #GAME_OVER} are both here, and neither cost a consumer a new flag —
 * they cost this table two rows and the predicates below one line each.</p>
 *
 * <p><b>Transitions.</b> {@code MENU -> PLAYING} on Start Game,
 * {@code MENU -> SETTINGS} on Settings, {@code PLAYING -> MENU} on Escape,
 * {@code PLAYING -> GAME_OVER} when the round is decided,
 * {@code GAME_OVER -> PLAYING} on Play Again, and the other two screens back to
 * {@code MENU}. Nothing else is legal, including staying put: asking to enter
 * the state you are already in means two things believe they own the transition,
 * and {@link UiStateMachine} refuses it loudly rather than papering over it.</p>
 *
 * <p><b>{@code GAME_OVER -> PLAYING} exists now, and its arrival is worth as
 * much comment as its absence was.</b> It used to be refused, on the grounds
 * that a rematch needs a fresh {@code Match} — new bots at full health, counters
 * back to zero — while the demo builds exactly one before the loop starts, so an
 * edge back into the world would have led into a room already cleared: a button
 * that lies.</p>
 *
 * <p>What changed is not the state machine's mind but the simulation's
 * capability. {@code Match.reset()} now genuinely restores a round —
 * {@code DemoGameplayPort.restartMatch} puts the bots, the player, the counters
 * and the effects all back, and re-publishes the bot placements so the corpses
 * become visible again. The transition is legal because the room really is new;
 * it was correctly refused for as long as that was not true. See
 * {@code Match.reset} on why the answer is a reset rather than a rebuilt
 * {@code Match}, which the immutability of {@code Scene} decides.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public enum UiState
{
    /**
     * The main menu is on screen and owns the pointer.
     *
     * The Scene2D stage is drawn and holds the input processor, the cursor is
     * visible and free to reach the title bar, and the player character does
     * not move or turn — mouse motion in this state is aimed at buttons, not
     * at the camera.
     */
    MENU,

    /**
     * The game is in front: no menu, captured pointer, live controls.
     *
     * The menu is neither drawn nor processing events — not drawn transparent,
     * not drawn and ignored, genuinely skipped — the cursor is caught and
     * hidden so GLFW keeps reporting relative motion, and mouse-look, WASD and
     * fire are all live.
     */
    PLAYING,

    /**
     * The settings screen is on screen and owns the pointer.
     *
     * Reached from the menu and returning only to it. The world is not drawn
     * and the match does not advance — same as {@link #MENU} in every respect
     * a consumer cares about, and a separate state only because a different
     * screen is on the glass.
     */
    SETTINGS,

    /**
     * The map picker is on screen and owns the pointer.
     *
     * <p>Reached from {@link #MENU} and returning only to it. The world is
     * not drawn and the match does not advance — same as {@link #MENU} in
     * every respect a consumer cares about, and a separate state only because
     * a different screen is on the glass. The picker's selection is held in
     * {@code MapSelection} and takes effect on the next launch; a same-process
     * engine reload is a follow-up, not in this first pass.</p>
     */
    MAP_SELECT,

    /**
     * The mode picker is on screen and owns the pointer.
     *
     * <p>The first sub-screen after {@link #MENU} when the player picks a
     * body source (single / multi) and wants to be asked which rule set
     * next. Reached from the menu and the map picker; returns to the menu
     * and forward to the map picker. The world's not drawn, the match is
     * not advancing, and the cursor is free because there are buttons on
     * the glass.</p>
     */
    MODE_SELECT,

    /**
     * The map is loading and a small "LOADING" screen is on the glass.
     *
     * <p>Stays long enough for the engine to swap the scene, then
     * transitions to {@link #PLAYING}. The same render-side behaviour as
     * every other pre-play state &mdash; the world is not drawn, the match
     * does not advance, the cursor is free because the loading screen has
     * a Back affordance for the case where the engine hangs on the load
     * and the player wants out.</p>
     */
    LOADING,

    /**
     * The multiplayer lobby is on screen and owns the pointer.
     *
     * <p>Reached from the map picker when the player picked a map for
     * multiplayer. The lobby shows the chosen map and mode and offers
     * HOST / JOIN / BACK. Returns to the map picker on BACK, advances to
     * {@link #LOADING} on HOST or JOIN.</p>
     */
    LOBBY,

    /**
     * The round is decided and its result is on screen.
     *
     * <p>The world stops being drawn the instant this begins. That is the
     * point: a summary composited over a live first-person view, with the
     * player's corpse still able to turn and the bots still patrolling behind
     * the numbers, is the same mistake {@link #MENU} was introduced to
     * fix.</p>
     *
     * <p>The cursor is free, because the screen has a button on it.</p>
     */
    GAME_OVER,

    /**
     * The controls rebind screen is on screen and owns the pointer.
     *
     * <p>The screen lists every
     * {@link com.openfps.engine.hal.port.GameAction} and its current
     * physical bindings, with a click-to-rebind affordance for each
     * one. The "press a key to bind" mode temporarily captures the
     * next key or mouse press; the screen returns to its idle view on
     * every change and on Back. Reached from the menu and the
     * settings screen; returns to the menu. Same as {@link #SETTINGS}
     * in every respect a consumer cares about, and a separate state
     * only because a different screen is on the glass.</p>
     */
    CONTROLS;

    /**
     * Returns whether this state is allowed to hand over to {@code target}.
     *
     * <p>The table is written out per state rather than expressed as
     * "anything but myself" so that adding a state forces a decision here
     * instead of silently inheriting a rule nobody chose. Adding
     * {@link #SETTINGS} and {@link #GAME_OVER} is what that rule was written
     * for, and it worked — both had to be answered for explicitly, and
     * {@code GAME_OVER -> PLAYING} was refused on purpose rather than inherited
     * by accident. It is now permitted, also on purpose and for a reason the
     * enum Javadoc records: the thing that made it a lie has been built.</p>
     *
     * @param target the state being asked for; null is never legal
     * @return true if {@link UiStateMachine} should permit the move
     */
    public boolean canTransitionTo(final UiState target)
    {
        switch (this)
        {
            case MENU:
                return target == PLAYING || target == SETTINGS || target == MODE_SELECT;
            case PLAYING:
                return target == MENU || target == GAME_OVER;
            case SETTINGS:
                return target == MENU || target == CONTROLS;
            case CONTROLS:
                return target == MENU;
            case MODE_SELECT:
                return target == MENU || target == MAP_SELECT;
            case MAP_SELECT:
                return target == MENU || target == MODE_SELECT
                    || target == LOADING || target == LOBBY;
            case LOBBY:
                return target == MAP_SELECT || target == LOADING;
            case LOADING:
                return target == MAP_SELECT || target == PLAYING;
            case GAME_OVER:
                return target == MENU || target == PLAYING;
            default:
                return false;
        }
    }

    /**
     * Returns true while the <b>main</b> menu should be drawn and fed input
     * events.
     *
     * <p>Deliberately not "some Scene2D screen is in front", which is what
     * {@link #usesPointer()} answers. Three of the four states put a stage on
     * the glass and they are three different stages; a consumer that conflated
     * them would draw the main menu over the settings screen.</p>
     *
     * @return true in {@link #MENU} only
     */
    public boolean drawsMenu()
    {
        return this == MENU;
    }

    /**
     * Returns true while the settings screen should be drawn and fed input.
     *
     * @return true in {@link #SETTINGS} only
     */
    public boolean drawsSettings()
    {
        return this == SETTINGS;
    }

    /**
     * Returns true while the controls rebind screen should be drawn and fed
     * input events.
     *
     * @return true in {@link #CONTROLS} only
     */
    public boolean drawsControls()
    {
        return this == CONTROLS;
    }

    /**
     * Returns true while the map picker should be drawn and fed input events.
     *
     * @return true in {@link #MAP_SELECT} only
     */
    public boolean drawsMapSelect()
    {
        return this == MAP_SELECT;
    }

    /**
     * Returns true while the mode picker should be drawn and fed input
     * events.
     *
     * @return true in {@link #MODE_SELECT} only
     */
    public boolean drawsModeSelect()
    {
        return this == MODE_SELECT;
    }

    /**
     * Returns true while the loading screen should be drawn and fed input
     * events.
     *
     * @return true in {@link #LOADING} only
     */
    public boolean drawsLoading()
    {
        return this == LOADING;
    }

    /**
     * Returns true while the multiplayer lobby should be drawn and fed
     * input events.
     *
     * @return true in {@link #LOBBY} only
     */
    public boolean drawsLobby()
    {
        return this == LOBBY;
    }

    /**
     * Returns true while the end-of-match screen should be drawn and fed input.
     *
     * @return true in {@link #GAME_OVER} only
     */
    public boolean drawsGameOver()
    {
        return this == GAME_OVER;
    }

    /**
     * Returns true while a screen with buttons on it is in front.
     *
     * <p>The exact complement of {@link #capturesCursor()}, and written as its
     * own predicate rather than as {@code !capturesCursor()} at three call
     * sites: "a free cursor" and "something to click" are the same fact stated
     * from the two ends, and a fifth state that had neither — a loading screen —
     * would have to break the identity here, once, rather than wherever
     * somebody had negated the other one.</p>
     *
     * @return true in every state except {@link #PLAYING}
     */
    public boolean usesPointer()
    {
        return this != PLAYING;
    }

    /**
     * Returns whether a platform "back" gesture should return to the main menu
     * rather than leave the application.
     *
     * <p>Only Android has such a gesture, but the answer is a property of the UI
     * state rather than of the platform, so it lives here with the other four
     * predicates instead of as a switch in the Activity.</p>
     *
     * <p><b>{@link #MENU} is the one state that answers false</b>, and that is the
     * Android convention rather than an oversight: back from the front screen of an
     * app leaves the app, and this one has a Quit button saying so as well. Every
     * other state answers true, and for {@link #SETTINGS} and {@link #GAME_OVER}
     * that is a correction rather than a restatement. Both of them take the input
     * processor away from everything else, so if back is neither caught nor acted
     * on, Android finishes the Activity — reading a settings screen and pressing
     * back quit the game, and doing it on the end screen threw the result away with
     * it. On desktop the same two screens are left with Escape, which has always
     * gone to the menu.</p>
     *
     * @return true in every state except {@link #MENU}
     */
    public boolean backReturnsToMenu()
    {
        return this != MENU;
    }

    /**
     * Returns true while the pointer should be caught, hidden, and driving the
     * view.
     *
     * <p>Capture is not cosmetic. {@code Gdx.input.getDeltaX()/getDeltaY()}
     * only keep reporting motion while the cursor is confined; a free cursor
     * saturates at the screen edge and the view simply stops turning. So this
     * predicate is what decides whether mouse-look works at all.</p>
     *
     * @return true in {@link #PLAYING}, false in {@link #MENU}
     */
    public boolean capturesCursor()
    {
        return this == PLAYING;
    }
}
