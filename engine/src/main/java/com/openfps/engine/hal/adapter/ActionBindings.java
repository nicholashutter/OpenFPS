/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter;

import java.util.Arrays;

import com.openfps.engine.hal.port.GameAction;
import com.openfps.engine.hal.port.InputBinding;

/**
 * A_ The controls table: which physical controls trigger which
 * {@link GameAction}.
 *
 * <p>One instance is the whole of a player's control scheme. Platform input
 * adapters read it every frame instead of naming key constants inline, which is
 * the entire point — before this existed, "left mouse fires" was a literal in
 * the middle of a polling loop and there was no object anywhere that knew it.
 * Now there is one, so a settings screen, a saved profile and the Android build
 * can each address the same question.</p>
 *
 * <h2>No defaults live here</h2>
 *
 * <p>A fresh table is <b>empty</b>, and that is not an oversight. Default codes
 * are platform numbers — {@code Input.Keys.SPACE}, a touch region, a gamepad
 * index — and {@code :engine} may not import the toolkit that defines them. Each
 * platform builds its own starting table and hands it over. See
 * {@code GameAction} for the full argument.</p>
 *
 * <p>The practical consequence: an adapter that forgets to populate this gets a
 * player who cannot move, not a player on someone else's defaults. That failure
 * is loud, which is the right way round.</p>
 *
 * <h2>More than one control per action, on purpose</h2>
 *
 * <p>{@link #bind} takes several bindings because real schemes need it: fire is
 * left mouse <i>and</i> left control so the game is playable on a trackpad, and
 * on a phone the same action will be a screen region. An action is considered
 * held when <b>any</b> of its bindings is held — {@link #MAX_BINDINGS_PER_ACTION}
 * caps how many, so the per-frame poll is a fixed, tiny loop with no
 * allocation.</p>
 *
 * <h2>Threading</h2>
 *
 * <p>Built on the bootstrap thread, read on whichever thread polls the device —
 * the LWJGL3 render thread on desktop. Rebinding is copy-on-write: {@link #bind}
 * publishes a whole new row array rather than mutating the live one, so a reader
 * mid-poll sees either the old scheme or the new one and never a half-applied
 * mixture. That matters for the obvious reason: a settings screen changes
 * bindings while the game behind it is still being polled.</p>
 */
public final class ActionBindings
{
    /**
     * How many controls may trigger one action — <b>4</b>.
     *
     * <p>Chosen from what real schemes need rather than as a round number: a
     * primary, an alternate for a different device (trackpad, gamepad), and two
     * spare so a player rebinding does not immediately hit a wall. Fixed at all
     * so the per-frame poll is a bounded loop over a preallocated array.</p>
     */
    public static final int MAX_BINDINGS_PER_ACTION = 4;

    /** Shared empty row, so an unbound action allocates nothing to report it. */
    private static final InputBinding[] NONE = new InputBinding[0];

    /**
     * One row of bindings per {@link GameAction}, indexed by ordinal.
     *
     * <p>MUTABLE: rows are replaced wholesale by {@link #bind}. The outer array
     * is final and its length never changes; a row reference is a single
     * reference store, which is why a reader can never see a torn row.</p>
     */
    private final InputBinding[][] rows;

    /** Creates a table with nothing bound. */
    public ActionBindings()
    {
        this.rows = new InputBinding[GameAction.values().length][];

        Arrays.fill(rows, NONE);
    }

    /**
     * Binds an action to a set of controls, replacing whatever it had.
     *
     * <p>Replacement rather than accumulation is deliberate: "rebind jump to J"
     * must not leave the old key working as well, which is what an additive API
     * quietly does and what makes a controls screen feel broken.</p>
     *
     * @param action the action to bind; must not be null
     * @param bindings the controls that trigger it. An empty list unbinds the
     *     action, which is legal — a player is allowed to have no sprint key.
     *     Must not be null, must contain no nulls, and must hold at most
     *     {@link #MAX_BINDINGS_PER_ACTION} entries
     * @return this table, so a platform's defaults read as one chained block
     * @throws IllegalArgumentException if the action is null, the array is null
     *     or holds a null, or there are too many bindings
     */
    public ActionBindings bind(final GameAction action, final InputBinding... bindings)
    {
        if (action == null)
        {
            throw new IllegalArgumentException("action must not be null");
        }

        if (bindings == null)
        {
            throw new IllegalArgumentException("bindings must not be null");
        }

        if (bindings.length > MAX_BINDINGS_PER_ACTION)
        {
            throw new IllegalArgumentException("at most " + MAX_BINDINGS_PER_ACTION
                + " bindings per action, got " + bindings.length + " for " + action);
        }

        for (int index = 0; index < bindings.length; index++)
        {
            if (bindings[index] == null)
            {
                throw new IllegalArgumentException(
                    "binding " + index + " for " + action + " must not be null");
            }
        }

        // Copy before publishing: the caller keeps its varargs array and could
        // otherwise mutate the live scheme from under a poll in progress.
        if (bindings.length == 0)
        {
            rows[action.ordinal()] = NONE;

            return this;
        }

        rows[action.ordinal()] = bindings.clone();

        return this;
    }

    /**
     * Returns the controls bound to an action.
     *
     * <p><b>Returns the live row, not a copy.</b> This is the per-frame path —
     * an input port calls it once per action per frame — and copying here would
     * allocate on every frame for the whole scheme. The row is never mutated in
     * place ({@link #bind} replaces it whole), so a caller that only reads is
     * safe. Callers must not write into it.</p>
     *
     * @param action the action to look up; must not be null
     * @return its bindings, empty when the action is unbound; never null
     * @throws IllegalArgumentException if {@code action} is null
     */
    public InputBinding[] bindingsFor(final GameAction action)
    {
        if (action == null)
        {
            throw new IllegalArgumentException("action must not be null");
        }

        return rows[action.ordinal()];
    }

    /**
     * Returns whether an action has any control bound to it.
     *
     * @param action the action to test; must not be null
     * @return true if at least one binding would trigger it
     */
    public boolean isBound(final GameAction action)
    {
        return bindingsFor(action).length > 0;
    }

    /**
     * Returns whether every action has at least one control.
     *
     * <p>Worth asking once at startup. A platform that populates its defaults
     * and then adds an action to {@link GameAction} without extending its table
     * ships a control nobody can press, and nothing else in the system would
     * report it.</p>
     *
     * @return true when no action is unbound
     */
    public boolean isComplete()
    {
        for (final GameAction action : GameAction.values())
        {
            if (!isBound(action))
            {
                return false;
            }
        }

        return true;
    }

    /**
     * Returns the first action with nothing bound to it, or null when the table
     * is complete.
     *
     * @return the unbound action in declaration order, or null
     */
    public GameAction firstUnbound()
    {
        for (final GameAction action : GameAction.values())
        {
            if (!isBound(action))
            {
                return action;
            }
        }

        return null;
    }

    /** Returns a debug rendering of every bound action. */
    @Override
    public String toString()
    {
        final StringBuilder text = new StringBuilder("ActionBindings{");

        boolean first = true;

        for (final GameAction action : GameAction.values())
        {
            final InputBinding[] row = bindingsFor(action);

            if (row.length == 0)
            {
                continue;
            }

            if (!first)
            {
                text.append(", ");
            }

            first = false;

            text.append(action).append('=').append(Arrays.toString(row));
        }

        return text.append('}').toString();
    }
}
