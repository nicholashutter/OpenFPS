/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem.impl;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.openfps.engine.core.event.I_EngineEvent;
import com.openfps.engine.core.event.RenderFrameEvent;
import com.openfps.engine.core.subsystem.Subsystem;
import com.openfps.engine.core.subsystem.SubsystemId;
import com.openfps.engine.render.port.I_RenderPort;

/**
 * Render subsystem (R_). Wraps an {@link I_RenderPort} and coalesces the frames
 * asked of it.
 *
 * <h2>Coalescing, and why it lives here</h2>
 *
 * <p>{@code GameLoop} publishes a {@link RenderFrameEvent} every tic. It has to:
 * it is the simulation clock, it has no idea how long a frame takes, and it
 * must not block on the renderer — a loop that waited for R_ would slow the
 * simulation down to the frame rate and desync a lockstep session. So the
 * producer cannot throttle, and the events keep coming at the tic rate whether
 * or not anything can keep up.</p>
 *
 * <p>The consumer is the only party that knows a frame is in flight, so the
 * decision belongs here. <b>If a request arrives while one is being rendered,
 * or several are queued, the newest is rendered and the rest are dropped.</b>
 * A stale frame is pure waste: by the time it finishes the camera has already
 * moved, nobody will ever see it, and rendering it only delays the frame
 * somebody will. Without this, a rasterizer slower than the tic rate falls
 * further behind on every tic and never recovers — which is exactly what a
 * windowed run looked like.</p>
 *
 * <p>The gate is not a lock. A worker that loses the race returns immediately
 * and goes back to the bus rather than queueing behind the renderer, because
 * blocking it would tie up a pool thread for a whole frame and the work it was
 * blocking for is about to be thrown away anyway.</p>
 *
 * <h2>The handoff</h2>
 *
 * <p>{@link #pending} holds the newest tic asked for, or {@link #NO_FRAME}.
 * {@link #rendering} is claimed by whoever renders. The claim is released
 * before {@link #pending} is re-checked, so a request that arrived during a
 * frame is not lost; the re-check is bounded at {@link #DRAIN_ATTEMPTS} because
 * this runs on a pool worker, and a worker that never returned to the bus would
 * be one fewer thread dispatching everything else. The bound cannot lose a
 * frame that matters: the next tic publishes another request a few milliseconds
 * later.</p>
 */
public final class RenderSubsystem extends Subsystem
{
    /** {@link #pending} when nothing has been asked for. */
    private static final int NO_FRAME = -1;

    /**
     * How many times a thread re-checks for a newer request after releasing the
     * gate. Two: one to render, one to catch a request that landed in the
     * window between the last read and the release.
     */
    private static final int DRAIN_ATTEMPTS = 2;

    private final I_RenderPort port;

    /** The newest tic a frame has been asked for. MUTABLE: written per request. */
    private final AtomicInteger pending = new AtomicInteger(NO_FRAME);

    /** Claimed by whichever thread is rendering. MUTABLE. */
    private final AtomicBoolean rendering = new AtomicBoolean(false);

    /**
     * Creates the subsystem.
     *
     * @param port the render port to drive; must not be null
     */
    public RenderSubsystem(final I_RenderPort port)
    {
        super(SubsystemId.R_);
        this.port = port;
    }

    @Override
    protected void onInit()
    {
        port.init();
    }

    @Override
    protected void onShutdown()
    {
        port.shutdown();
    }

    @Override
    protected void onEvent(final I_EngineEvent event)
    {
        if (event instanceof RenderFrameEvent frame)
        {
            requestFrame(frame.frameNumber());
        }
    }

    /**
     * Returns the tic a frame is still owed for, or -1 if none is outstanding.
     *
     * <p>Exposed for the coalescing tests, which have no other way to observe
     * that a request was dropped rather than queued.</p>
     *
     * @return the pending tic, or -1
     */
    public int pendingFrame()
    {
        return pending.get();
    }

    // Records the request as the newest one and renders it, unless somebody
    // else is already rendering — in which case they will pick it up, because
    // they re-check pending before they let go of the gate.
    private void requestFrame(final int ticIndex)
    {
        pending.set(ticIndex);
        for (int attempt = 0; attempt < DRAIN_ATTEMPTS; attempt++)
        {
            if (pending.get() == NO_FRAME)
            {
                return;
            }
            if (!rendering.compareAndSet(false, true))
            {
                return;
            }
            try
            {
                renderNewest();
            }
            finally
            {
                rendering.set(false);
            }
        }
    }

    // Renders whatever the newest outstanding request is, and clears it. Every
    // older request is dropped by construction: pending holds one tic, and each
    // write overwrites the last.
    private void renderNewest()
    {
        final int ticIndex = pending.getAndSet(NO_FRAME);
        if (ticIndex == NO_FRAME)
        {
            return;
        }
        port.renderFrame(ticIndex);
    }
}
