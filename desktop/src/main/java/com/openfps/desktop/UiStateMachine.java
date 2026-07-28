/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place that knows whether this window is showing the menu or the
 * game.
 *
 * <p>Created by {@link GdxFrameLoopListener}, which is the desktop UI, and
 * read by everything that has to behave differently in the two states:</p>
 *
 * <ul>
 *   <li>{@link GdxFrameLoopListener} draws the menu and attaches its Scene2D
 *       input processor only in {@link UiState#MENU}.</li>
 *   <li>{@link GdxInputPort} catches the cursor and reads mouse-look, WASD and
 *       fire only in {@link UiState#PLAYING}, and drops everything it has
 *       banked whenever the state changes underneath it.</li>
 * </ul>
 *
 * <p>Neither of them keeps its own copy of the answer; they each remember
 * which state they were last <i>reconciled</i> with, notice when it differs
 * from this object, and do the work once. That is deliberately a poll rather
 * than a callback: both of them only act on the LWJGL3 render thread, at a
 * known point in the frame, and a listener firing from a Scene2D click handler
 * would have them touching GLFW at an arbitrary moment instead.</p>
 *
 * <p><b>Illegal transitions throw.</b> With two states the only illegal move
 * is to the state you are already in, and it is worth throwing over: it means
 * two callers both believe they are the one starting the game (or the one
 * leaving it), and the second is about to re-clear input and re-warp the
 * cursor under the first. Silently ignoring it turns a wiring bug into an
 * intermittent input glitch. See {@link UiState#canTransitionTo}.</p>
 *
 * <p><b>Threading:</b> {@link #state()} is safe from any thread — the field is
 * volatile and the value is an immutable enum constant. The mutators are called
 * from the render thread only, from a Scene2D button callback or from the
 * Escape check in {@link GdxInputPort#pollDevice()}, both of which run there.
 * There is no compare-and-set: two render threads do not exist.</p>
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class UiStateMachine
{
    private static final Logger LOG = LoggerFactory.getLogger(UiStateMachine.class);

    /**
     * Where the UI is now.
     * MUTABLE: advanced by {@link #transitionTo}. Volatile because the game
     * loop thread may read it while the render thread writes it.
     */
    private volatile UiState state = UiState.MENU;

    /** Creates a machine parked in {@link UiState#MENU} — a window opens on the menu. */
    public UiStateMachine()
    {
        // The initial state is the field initialiser; nothing else to do.
    }

    /**
     * Returns the current state. Never null, safe from any thread.
     *
     * @return {@link UiState#MENU} or {@link UiState#PLAYING}
     */
    public UiState state()
    {
        return state;
    }

    /**
     * Returns true while the game is in front and the menu is gone.
     *
     * @return true in {@link UiState#PLAYING}
     */
    public boolean isPlaying()
    {
        return state == UiState.PLAYING;
    }

    /**
     * Enters the game: {@code MENU -> PLAYING}.
     *
     * Driven by the menu's Start Game button. The cursor capture and the
     * discarding of any look banked while the mouse crossed the menu are done
     * by {@link GdxInputPort} when it next notices the change; this method is
     * only the decision.
     *
     * @throws IllegalStateException if the game is already in front
     */
    public void startGame()
    {
        transitionTo(UiState.PLAYING);
    }

    /**
     * Leaves the game: {@code PLAYING -> MENU}.
     *
     * <p>Driven by Escape, and it is not optional. A caught cursor is confined
     * and invisible: with no way back to {@link UiState#MENU} the window cannot
     * be closed with the mouse, the Quit button cannot be reached, and the only
     * exit is killing the process. Escape is what stops cursor capture from
     * being a trap.</p>
     *
     * @throws IllegalStateException if the menu is already in front
     */
    public void returnToMenu()
    {
        transitionTo(UiState.MENU);
    }

    /**
     * Moves to {@code target}, or refuses.
     *
     * @param target the state to enter; must not be null
     * @throws IllegalArgumentException if {@code target} is null
     * @throws IllegalStateException if the current state does not permit the
     *     move — including a request to re-enter the current state
     */
    public void transitionTo(final UiState target)
    {
        if (target == null)
        {
            throw new IllegalArgumentException("target must not be null");
        }
        final UiState current = state;
        if (!current.canTransitionTo(target))
        {
            throw new IllegalStateException(
                "illegal UI transition " + current + " -> " + target);
        }
        state = target;
        LOG.info("UI state {} -> {}", current, target);
    }

    /** Returns a short debug description, for logs and assertion messages. */
    @Override
    public String toString()
    {
        return "UiStateMachine[" + state + "]";
    }
}
