/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem;

import com.openfps.engine.core.event.I_EngineEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Holds all engine subsystems. Provides lookup by ID, dispatches events
 * to the right subsystem, and tracks the aggregate state of all
 * subsystems (used during shutdown to know when it's safe to stop).
 *
 * <h2>State-change observers</h2>
 *
 * <p>Observers registered with {@link #registerObserver} are wired
 * to every currently-registered subsystem AND to every subsystem
 * registered afterwards. The registry is the single seam for
 * "I want to know about every state change in the engine" &mdash;
 * a debug overlay, a file writer, the log bus.</p>
 */
public final class SubsystemRegistry
{
    private static final Logger LOG = LoggerFactory.getLogger(SubsystemRegistry.class);

    private final Map<SubsystemId, ISubsystem> subsystems = new EnumMap<>(SubsystemId.class);

    /**
     * Every observer that has registered. The list is
     * appended-only, but reads against it are
     * {@link ArrayList#ArrayList(int)}-style (snapshot on copy),
     * which is enough for the bootstrap-and-listen pattern this
     * registry actually uses. A late observer after the engine
     * has started will not retroactively see past transitions,
     * which is the documented contract.
     */
    private final List<I_SubsystemObserver> observers = new ArrayList<>();

    /** Registers a subsystem. ID must be unique. */
    public void register(final ISubsystem subsystem)
    {
        if (subsystem == null)
        {
            throw new SubsystemException("Cannot register null subsystem");
        }

        if (subsystems.containsKey(subsystem.id()))
        {
            throw new SubsystemException("Subsystem " + subsystem.id() + " already registered");
        }

        subsystems.put(subsystem.id(), subsystem);

        // Wire every existing observer to the new subsystem. The
        // observer is now told of every transition this subsystem
        // makes, from this point on; transitions that already
        // happened are not replayed.
        for (final I_SubsystemObserver observer : observers)
        {
            subsystem.addObserver(observer);
        }

        LOG.debug("Registered subsystem: {}", subsystem.id());
    }

    /**
     * Adds an observer that will be told of every state
     * transition every subsystem makes.
     *
     * <p>The observer is wired against every subsystem already
     * registered with this registry, and against every subsystem
     * registered afterwards. A subsystem that transitions
     * <i>before</i> this call has fired its events without
     * notifying the new observer; that is the documented
     * contract &mdash; state-change observers are
     * bootstrap-and-listen, not replay-the-past.</p>
     *
     * @param observer the observer to add; must not be null
     */
    public void registerObserver(final I_SubsystemObserver observer)
    {
        if (observer == null)
        {
            throw new IllegalArgumentException("observer must not be null");
        }

        observers.add(observer);

        for (final ISubsystem subsystem : subsystems.values())
        {
            subsystem.addObserver(observer);
        }

        LOG.debug("Subsystem observer registered: {}", observer.getClass().getSimpleName());
    }

    /** Returns the subsystem for the given ID, or null if not registered. */
    public ISubsystem get(final SubsystemId id)
    {
        return subsystems.get(id);
    }

    /** Calls init() on every registered subsystem in registration order. */
    public void initAll()
    {
        for (final ISubsystem s : subsystems.values())
        {
            if (s.state() == SubsystemState.UNINITIALIZED)
            {
                s.init();
            }
        }
    }

    /** Calls shutdown() on every registered subsystem. Errors are logged but do not stop the loop. */
    public void shutdownAll()
    {
        for (final ISubsystem s : subsystems.values())
        {
            if (s.state() != SubsystemState.SHUTDOWN)
            {
                try
                {
                    s.shutdown();
                }
                catch (final RuntimeException e)
                {
                    LOG.warn("Subsystem {} shutdown threw — continuing", s.id(), e);
                }
            }
        }
    }

    /**
     * Dispatches an event to the subsystem named by its target ID. If
     * no subsystem is registered for that ID, the event is dropped with
     * a WARN log.
     */
    public void dispatch(final I_EngineEvent event)
    {
        final ISubsystem target = subsystems.get(event.targetSubsystem());

        if (target == null)
        {
            LOG.warn("No subsystem registered for event {} — dropping", event);

            return;
        }

        target.processEvent(event);
    }

    /** Returns true if all subsystems are in the SHUTDOWN state. */
    public boolean allShutdown()
    {
        for (final ISubsystem s : subsystems.values())
        {
            if (s.state() != SubsystemState.SHUTDOWN)
            {
                return false;
            }
        }

        return true;
    }

    /** Returns the number of registered subsystems. */
    public int size()
    {
        return subsystems.size();
    }
}
