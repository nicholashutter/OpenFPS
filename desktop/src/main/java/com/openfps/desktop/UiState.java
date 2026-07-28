/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

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
 * <p><b>Transitions.</b> {@code MENU -> PLAYING} on Start Game,
 * {@code PLAYING -> MENU} on Escape. Nothing else is legal, including staying
 * put: asking to enter the state you are already in means two things believe
 * they own the transition, and {@link UiStateMachine} refuses it loudly rather
 * than papering over it.</p>
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
    PLAYING;

    /**
     * Returns whether this state is allowed to hand over to {@code target}.
     *
     * <p>The table is written out per state rather than expressed as
     * "anything but myself" so that adding a state forces a decision here
     * instead of silently inheriting a rule nobody chose.</p>
     *
     * @param target the state being asked for; null is never legal
     * @return true if {@link UiStateMachine} should permit the move
     */
    public boolean canTransitionTo(final UiState target)
    {
        switch (this)
        {
            case MENU:
                return target == PLAYING;
            case PLAYING:
                return target == MENU;
            default:
                return false;
        }
    }

    /**
     * Returns true while the menu should be drawn and fed input events.
     *
     * @return true in {@link #MENU}, false in {@link #PLAYING}
     */
    public boolean drawsMenu()
    {
        return this == MENU;
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
