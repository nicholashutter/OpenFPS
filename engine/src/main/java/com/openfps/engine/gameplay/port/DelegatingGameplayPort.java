/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.port;

import com.openfps.engine.gameplay.adapter.NullGameplayPort;

import java.util.concurrent.atomic.AtomicReference;

/**
 * P_ An {@link I_GameplayPort} that holds a swappable delegate.
 *
 * <p>The engine's {@code GameplaySubsystem} is built once per session and
 * ticks a single port. A launcher that wants to swap the port at runtime
 * &mdash; the menu-driven map loader is the case &mdash; wraps a
 * {@code DelegatingGameplayPort} around the subsystem instead, and the
 * actual port is replaced through {@link #setActual(I_GameplayPort)}.</p>
 *
 * <p>The delegate is held in an {@link AtomicReference} because the
 * engine's game loop reads it once per tic on its own thread, and the
 * launcher mutates it from the platform's render thread. The
 * {@code init}/{@code shutdown} calls the engine makes on this port are
 * forwarded to the current delegate; {@link #setActual} also calls
 * {@code shutdown} on the outgoing delegate and {@code init} on the
 * incoming one, so a swap leaves both ports in the lifecycle state the
 * engine expects.</p>
 *
 * <p>Initially the delegate is a {@link NullGameplayPort}, which is a
 * no-op &mdash; the engine's bootstrap {@code init} lands on it, and the
 * game loop ticks nothing until a real port is set. A launcher that
 * boots with no map can therefore build the engine first and only
 * attach a map port when the player picks one.</p>
 */
public final class DelegatingGameplayPort implements I_GameplayPort
{
    private final AtomicReference<I_GameplayPort> actual =
        new AtomicReference<>(new NullGameplayPort());

    /**
     * Returns the current delegate, never null. The game loop reads this
     * through {@link #tick(int)} so callers do not have to.
     *
     * @return the port the engine is currently ticking
     */
    public I_GameplayPort actual()
    {
        return actual.get();
    }

    /**
     * Replaces the delegate, shutting down the outgoing port and
     * initialising the incoming one. Atomic with respect to
     * {@link #tick(int)}: a tic that started before the call sees the
     * old port; a tic that starts after sees the new one.
     *
     * <p>The old port is shut down <em>after</em> the swap, so a tic
     * in flight at the moment of the call still completes against the
     * old port without racing its shutdown.</p>
     *
     * @param newActual the port the engine should tick from now on;
     *     must not be null
     * @return the outgoing port, so a caller can log it
     * @throws IllegalArgumentException if {@code newActual} is null
     */
    public I_GameplayPort setActual(final I_GameplayPort newActual)
    {
        if (newActual == null)
        {
            throw new IllegalArgumentException("newActual must not be null");
        }

        final I_GameplayPort old = actual.getAndSet(newActual);

        if (old != null)
        {
            old.shutdown();
        }

        newActual.init();

        return old;
    }

    @Override
    public void init()
    {
        actual.get().init();
    }

    @Override
    public void shutdown()
    {
        actual.get().shutdown();
    }

    @Override
    public void tick(final int ticIndex)
    {
        actual.get().tick(ticIndex);
    }

    @Override
    public boolean loadMap(final String mapName)
    {
        return actual.get().loadMap(mapName);
    }
}
