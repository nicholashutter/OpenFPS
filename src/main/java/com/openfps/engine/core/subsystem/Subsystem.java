/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem;

import com.openfps.engine.core.event.I_EngineEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for subsystems. Provides the state machine, dispatches
 * events, and enforces transition validation.
 *
 * Subclasses implement {@link #onEvent(I_EngineEvent)} to handle the
 * events they care about. The state machine transitions around the
 * call:
 *
 *   READY  →  BUSY  →  READY   (normal)
 *   READY  →  BUSY  →  ERROR   (handler threw)
 *   BUSY   →  SHUTDOWN          (shutdown requested during processing)
 */
public abstract class Subsystem implements ISubsystem
{
    private static final Logger LOG = LoggerFactory.getLogger(Subsystem.class);

    private final SubsystemId id;
    private volatile SubsystemState state;

    protected Subsystem(final SubsystemId id)
    {
        this.id = id;
        this.state = SubsystemState.UNINITIALIZED;
    }

    @Override
    public final SubsystemId id()
    {
        return id;
    }

    @Override
    public final SubsystemState state()
    {
        return state;
    }

    @Override
    public final void init()
    {
        if (state != SubsystemState.UNINITIALIZED)
        {
            throw new SubsystemException("init() called from state " + state
                + " for subsystem " + id + " — only valid from UNINITIALIZED");
        }
        try
        {
            onInit();
            state = SubsystemState.READY;
            LOG.debug("Subsystem {} initialized → READY", id);
        }
        catch (final RuntimeException e)
        {
            state = SubsystemState.ERROR;
            LOG.error("Subsystem {} init() failed", id, e);
            throw e;
        }
    }

    @Override
    public final void shutdown()
    {
        if (state == SubsystemState.SHUTDOWN)
        {
            throw new SubsystemException("shutdown() called from state SHUTDOWN for subsystem "
                + id + " — already terminal");
        }
        try
        {
            onShutdown();
            state = SubsystemState.SHUTDOWN;
            LOG.debug("Subsystem {} shut down", id);
        }
        catch (final RuntimeException e)
        {
            state = SubsystemState.ERROR;
            LOG.error("Subsystem {} shutdown() failed", id, e);
            throw e;
        }
    }

    @Override
    public final void processEvent(final I_EngineEvent event)
    {
        if (state != SubsystemState.READY)
        {
            throw new SubsystemException("processEvent() called from state " + state
                + " for subsystem " + id + " — only valid from READY");
        }
        try
        {
            onEvent(event);
        }
        catch (final RuntimeException e)
        {
            // Log the error (NO silent failures), then re-raise to the worker.
            // The subsystem stays in READY so subsequent events can be processed.
            // Per-event tracking happens at the worker level — the worker
            // thread is implicitly released back to the pool when onEvent returns.
            LOG.error("Subsystem {} event handler threw on event {}",
                id, event, e);
            throw e;
        }
    }

    /** Override for subsystem-specific initialization. Default: no-op. */
    protected void onInit()
    {
    }

    /** Override for subsystem-specific shutdown. Default: no-op. */
    protected void onShutdown()
    {
    }

    /** Override to handle events. Default: ignore. */
    protected void onEvent(final I_EngineEvent event)
    {
    }

    /**
     * Returns true if the state machine allows a transition from the
     * current state to {@code target}. Used by the registry and tests.
     */
    public static boolean isValidTransition(final SubsystemState from, final SubsystemState to)
    {
        if (from == to)
        {
            return true;
        }
        return switch (from)
        {
            case UNINITIALIZED -> to == SubsystemState.READY
                                    || to == SubsystemState.ERROR
                                    || to == SubsystemState.SHUTDOWN;
            case READY         -> to == SubsystemState.SHUTDOWN
                                    || to == SubsystemState.ERROR;
            case ERROR         -> to == SubsystemState.UNINITIALIZED
                                    || to == SubsystemState.SHUTDOWN;
            case SHUTDOWN      -> false;
        };
    }
}
