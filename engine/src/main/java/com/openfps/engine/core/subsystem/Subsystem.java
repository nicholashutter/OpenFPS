/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem;

import com.openfps.engine.core.event.I_EngineEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Base class for subsystems. Provides the state machine, dispatches
 * events, and enforces transition validation.
 *
 * Subclasses implement {@link #onEvent(I_EngineEvent)} to handle the
 * events they care about.
 *
 *   UNINITIALIZED  ──init()──►      READY  ──shutdown()──►  SHUTDOWN
 *   UNINITIALIZED  ──init() throws──► ERROR
 *   READY          ──shutdown() throws──► ERROR
 *
 * There is deliberately NO per-event BUSY state. Multiple workers may be
 * inside {@code onEvent} for the same subsystem at once, so a single
 * "currently processing" flag would be wrong — implementations must make
 * {@code onEvent} thread-safe instead. A handler that throws is logged
 * and rethrown to the worker, and the subsystem STAYS in READY so the
 * next event is still processed; one bad event does not kill a subsystem.
 *
 * <h2>State-change observers</h2>
 *
 * <p>Every state transition fires an event to the
 * {@link I_SubsystemObserver} list the subsystem holds. The list is
 * populated by the {@link SubsystemRegistry} on registration; the
 * subsystem never touches it directly. Observers run on whatever
 * thread the transition fired on and are expected to be
 * thread-safe. Observer exceptions are caught and logged so a
 * misbehaving observer cannot prevent the transition from
 * completing &mdash; the state machine is the contract; the
 * observer list is a logging seam, not a control seam.</p>
 */
public abstract class Subsystem implements ISubsystem
{
    private static final Logger LOG = LoggerFactory.getLogger(Subsystem.class);

    private final SubsystemId id;
    private volatile SubsystemState state;

    /**
     * Observers are wired by the {@link SubsystemRegistry} at
     * registration time, not by the subsystem itself. {@code
     * CopyOnWriteArrayList} because the bootstrap thread mutates
     * the list once at startup, and the game loop reads it on
     * every state change &mdash; a slow observer cannot race a
     * concurrent transition.
     */
    private final List<I_SubsystemObserver> observers = new CopyOnWriteArrayList<>();

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

    /**
     * Adds an observer that will be told of every state
     * transition this subsystem makes. The same observer can be
     * registered against multiple subsystems.
     *
     * <p>Adding an observer does not fire retroactively. The
     * {@link SubsystemRegistry} records the current state of
     * every registered subsystem separately, so a late observer
     * can be primed by walking the registry's view.</p>
     *
     * @param observer the observer to add; must not be null
     */
    public final void addObserver(final I_SubsystemObserver observer)
    {
        if (observer == null)
        {
            throw new IllegalArgumentException("observer must not be null");
        }

        observers.add(observer);
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

            transitionTo(SubsystemState.READY, null);

            LOG.debug("Subsystem {} initialized → READY", id);
        }
        catch (final RuntimeException e)
        {
            transitionTo(SubsystemState.ERROR, e);

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

            transitionTo(SubsystemState.SHUTDOWN, null);

            LOG.debug("Subsystem {} shut down", id);
        }
        catch (final RuntimeException e)
        {
            transitionTo(SubsystemState.ERROR, e);

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
     * Returns true if the state machine allows a transition from
     * {@code from} to {@code to}. This is the declarative statement of the
     * transition table; the enforcement lives in the guards on
     * {@code init()} / {@code shutdown()}. Kept public so tests can assert
     * the table directly.
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

    /**
     * Atomically swaps the state and notifies every observer.
     * The swap is done with a single volatile write so a
     * concurrent {@link #state()} read sees either the old or
     * the new value, never a torn one.
     *
     * <p>Observer exceptions are caught and logged; a
     * misbehaving observer cannot fail the state machine or
     * prevent other observers from receiving the same event.
     * This is a logging seam, not a control seam &mdash; the
     * subsystem's own state has already moved on by the time
     * any observer runs.</p>
     *
     * @param newState the state to move to; must not be null
     * @param cause the exception that drove an error transition,
     *     or null for a non-error transition
     */
    private void transitionTo(final SubsystemState newState, final Throwable cause)
    {
        final SubsystemState previous = this.state;

        this.state = newState;

        final SubsystemStateChangeEvent event = new SubsystemStateChangeEvent(id, previous,
            newState, System.currentTimeMillis(), cause);

        for (final I_SubsystemObserver observer : observers)
        {
            try
            {
                observer.onStateChange(event);
            }
            catch (final RuntimeException observerError)
            {
                LOG.error("Subsystem {} observer {} threw on state change {}",
                    id, observer.getClass().getSimpleName(), event, observerError);
            }
        }
    }
}
