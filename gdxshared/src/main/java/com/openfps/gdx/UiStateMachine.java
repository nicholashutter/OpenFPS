/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.gdx;

import com.openfps.engine.gameplay.MatchMode;
import com.openfps.engine.gameplay.MatchSummary;

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
 * <p><b>Illegal transitions throw.</b> The move that matters most is to the
 * state you are already in, and it is worth throwing over: it means two callers
 * both believe they are the one starting the game (or the one leaving it), and
 * the second is about to re-clear input and re-warp the cursor under the first.
 * Silently ignoring it turns a wiring bug into an intermittent input glitch.
 * With four states there are also genuinely absent edges —
 * {@link UiState#SETTINGS} to {@link UiState#PLAYING}, or the menu jumping
 * straight to a result it has not played for — and those throw for the same
 * reason: a caller asking for one has misunderstood what it is holding. See
 * {@link UiState#canTransitionTo}.</p>
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

    /**
     * Which kind of match the player last asked for.
     *
     * <p>MUTABLE: set on the way into {@link UiState#PLAYING} and left alone
     * afterwards, so it still reports what the last match was while the menu is
     * back in front. Volatile for the same reason {@link #state} is — the game
     * loop thread reads it, the render thread writes it.</p>
     *
     * <p>It lives here rather than in {@code MenuActions} because it is a
     * property of <i>which game is running</i>, and this object is already the
     * one thing that knows whether a game is running at all. Splitting the two
     * would let them disagree.</p>
     */
    private volatile MatchMode mode = MatchMode.SINGLE_PLAYER;

    /**
     * The result of the last finished match, or null before one has finished.
     *
     * <p>MUTABLE: set on the way into {@link UiState#GAME_OVER} and left alone
     * afterwards, so the end screen keeps reporting the same numbers for as long
     * as the player leaves it up. Volatile because the game loop thread may read
     * it while the render thread writes it.</p>
     *
     * <p>It is a {@link MatchSummary} rather than a reference to the live
     * {@code Match} on purpose — see that class. The result stopped changing the
     * moment the round ended; holding the mutable object would mean re-reading
     * simulation state across a thread boundary sixty times a second to learn
     * something that cannot have moved.</p>
     */
    private volatile MatchSummary result;

    /** Creates a machine parked in {@link UiState#MENU} — a window opens on the menu. */
    public UiStateMachine()
    {
        // The initial state is the field initialiser; nothing else to do.
    }

    /**
     * Returns the kind of match last started. Never null, safe from any thread.
     *
     * @return the mode passed to the most recent {@link #startGame(MatchMode)},
     *     or {@link MatchMode#SINGLE_PLAYER} before any match has begun
     */
    public MatchMode mode()
    {
        return mode;
    }

    /**
     * Returns the result of the last finished match, or null.
     *
     * <p>Null before any match has been played through to a decision. Non-null
     * from the moment {@link #endMatch} runs and for the rest of the process —
     * it is not cleared on the way back to the menu, because the end screen is
     * still drawing from it while that transition is being applied.</p>
     *
     * @return the frozen result, or null if no match has finished
     */
    public MatchSummary result()
    {
        return result;
    }

    /**
     * Returns the current state. Never null, safe from any thread.
     *
     * @return one of {@link UiState}'s four constants
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
     * Enters a single-player game: {@code MENU -> PLAYING}.
     *
     * @throws IllegalStateException if the game is already in front
     */
    public void startGame()
    {
        startGame(MatchMode.SINGLE_PLAYER);
    }

    /**
     * Enters a game of a given kind: {@code MENU -> PLAYING}.
     *
     * <p>Driven by the menu's Single Player and Multiplayer buttons. The cursor
     * capture and the discarding of any look banked while the mouse crossed the
     * menu are done by {@link GdxInputPort} when it next notices the change;
     * this method is only the decision.</p>
     *
     * <p><b>The mode is recorded before the transition, not after.</b> The state
     * field is what other threads poll, so publishing {@code PLAYING} first
     * would leave a window in which a reader sees a running game and the
     * previous match's mode.</p>
     *
     * @param matchMode which kind of match is starting; must not be null
     * @throws IllegalArgumentException if {@code matchMode} is null
     * @throws IllegalStateException if the game is already in front
     */
    public void startGame(final MatchMode matchMode)
    {
        if (matchMode == null)
        {
            throw new IllegalArgumentException("matchMode must not be null");
        }

        this.mode = matchMode;

        transitionTo(UiState.PLAYING);
    }

    /**
     * Opens the settings screen: {@code MENU -> SETTINGS}.
     *
     * <p>Only from the menu, and that restriction is not laziness. Settings
     * reachable mid-match would be a pause screen, and this game has no pause:
     * {@code GameLoop} is a fixed-timestep clock that keeps ticking whatever is
     * on screen, so "settings while playing" means the bots carry on shooting a
     * player who is reading a menu. The match gate exists to stop exactly
     * that.</p>
     *
     * @throws IllegalStateException if the menu is not the screen in front
     */
    public void openSettings()
    {
        transitionTo(UiState.SETTINGS);
    }

    /**
     * Opens the map picker from the menu: {@code MENU -> MAP_SELECT}.
     *
     * <p>Symmetric to {@link #openSettings}: a transition from the menu to
     * a full screen the menu owns, returning only to the menu when the player
     * leaves it. The picker's selection is held in {@code MapSelection} and
     * takes effect on the next launch.</p>
     *
     * @throws IllegalStateException if the menu is not the screen in front
     */
    public void openMapSelect()
    {
        transitionTo(UiState.MAP_SELECT);
    }

    /**
     * Ends the round and shows its result: {@code PLAYING -> GAME_OVER}.
     *
     * <p>Driven by the platform UI noticing that {@code Match.state().isOver()},
     * which it learns by polling — the same poll-rather-than-callback choice the
     * class Javadoc explains, and for the sharper version of the same reason:
     * the match is decided on the game loop thread, and a callback firing from
     * there would have the simulation building Scene2D actors.</p>
     *
     * <p><b>The summary is recorded before the transition, not after</b>, for
     * the reason {@link #startGame(MatchMode)} records the mode first: the state
     * field is what other threads poll, so publishing {@code GAME_OVER} first
     * would leave a window in which a reader sees a finished match and no
     * result to draw.</p>
     *
     * @param summary the frozen result to show; must not be null
     * @throws IllegalArgumentException if {@code summary} is null
     * @throws IllegalStateException if the game is not the thing in front
     */
    public void endMatch(final MatchSummary summary)
    {
        if (summary == null)
        {
            throw new IllegalArgumentException("summary must not be null");
        }

        this.result = summary;

        transitionTo(UiState.GAME_OVER);
    }

    /**
     * Starts the round again from the end screen: {@code GAME_OVER -> PLAYING}.
     *
     * <p><b>The transition is the easy half and the caller owes the hard half.</b>
     * This method moves a state field; it does not put seven bots back on their
     * feet. Whoever calls it must first have restored the world —
     * {@code DemoGameplayPort.restartMatch()} — because a UI that says PLAYING
     * over a room that is still cleared and still full of invisible corpses is
     * precisely the "button that lies" this edge was refused for so long to
     * avoid.</p>
     *
     * <p>The mode is left alone: a rematch is another round of the same kind of
     * match the player chose, not a new choice. The stored {@link #result} is
     * left alone too, for the reason {@link #returnToMenu} leaves it — the end
     * screen is still on the glass while this transition is being applied, and
     * it is drawing from it.</p>
     *
     * @throws IllegalStateException if the end screen is not the thing in front
     */
    public void restartMatch()
    {
        transitionTo(UiState.PLAYING);
    }

    /**
     * Leaves whatever is in front and shows the menu: {@code * -> MENU}.
     *
     * <p>Driven by Escape from the game, and by the Back button on the settings
     * and end-of-match screens. From {@link UiState#PLAYING} it is not optional.
     * A caught cursor is confined and invisible: with no way back to
     * {@link UiState#MENU} the window cannot be closed with the mouse, the Quit
     * button cannot be reached, and the only exit is killing the process. Escape
     * is what stops cursor capture from being a trap.</p>
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
