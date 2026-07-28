/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay;

import com.openfps.engine.gameplay.port.I_PlayerInput;
import com.openfps.engine.hal.port.InputState;

/**
 * Presents a HAL {@link InputState} as the {@link I_PlayerInput} the
 * {@link PlayerController} consumes.
 *
 * <b>Why this class exists rather than the obvious alternative.</b> The two
 * types were written independently and their four movement accessors match
 * name-for-name, so it is tempting to simply declare
 * {@code InputState implements I_PlayerInput}. That would be a dependency
 * inversion: {@code InputState} lives in {@code hal.port}, which is the
 * platform contract every adapter compiles against, and it would make the HAL
 * depend on a gameplay interface. {@code PLAN.md} § 2's module graph has
 * {@code hal} depending on {@code common} and nothing else, and that is worth
 * more than saving a class. The dependency runs the correct way here: gameplay
 * knows about the HAL, never the reverse.
 *
 * <b>Why it is mutable.</b> {@link InputState} is immutable and a fresh one is
 * latched every tic. Wrapping each in a new view object would allocate once per
 * tic forever, in the simulation path. Instead one view is created and
 * re-pointed with {@link #wrap}, so the whole adapter costs a single object for
 * the life of the session.
 *
 * The action flags ({@code fire}, {@code jump}, {@code sprint}) are deliberately
 * not forwarded — {@link I_PlayerInput} does not expose them, because the
 * controller has no use for them yet. Whatever consumes them later should read
 * the {@link InputState} directly rather than widening this seam.
 *
 * <b>Threading:</b> not thread-safe, and does not need to be. The game loop
 * latches and applies input on one thread.
 */
public final class PlayerInputView implements I_PlayerInput
{
    /** The snapshot being presented. MUTABLE: re-pointed by {@link #wrap}. */
    private InputState source = InputState.NEUTRAL;

    /**
     * Points this view at a new snapshot.
     *
     * @param state the snapshot to present; must not be null
     */
    public void wrap(final InputState state)
    {
        if (state == null)
        {
            throw new IllegalArgumentException("state must not be null");
        }
        this.source = state;
    }

    /**
     * Returns the snapshot currently being presented. Never null; starts at
     * {@link InputState#NEUTRAL}.
     *
     * @return the wrapped snapshot
     */
    public InputState source()
    {
        return source;
    }

    @Override
    public float forwardAxis()
    {
        return source.forwardAxis();
    }

    @Override
    public float strafeAxis()
    {
        return source.strafeAxis();
    }

    @Override
    public float yawDelta()
    {
        return source.yawDelta();
    }

    @Override
    public float pitchDelta()
    {
        return source.pitchDelta();
    }

    @Override
    public boolean jump()
    {
        return source.jump();
    }

    @Override
    public String toString()
    {
        return "PlayerInputView{" + source + "}";
    }
}
