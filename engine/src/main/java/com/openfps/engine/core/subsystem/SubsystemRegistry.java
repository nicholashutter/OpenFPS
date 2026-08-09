/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem;

import com.openfps.engine.core.event.I_EngineEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

/**
 * Holds all engine subsystems. Provides lookup by ID, dispatches events
 * to the right subsystem, and tracks the aggregate state of all
 * subsystems (used during shutdown to know when it's safe to stop).
 */
public final class SubsystemRegistry
{
    private static final Logger LOG = LoggerFactory.getLogger(SubsystemRegistry.class);

    private final Map<SubsystemId, ISubsystem> subsystems = new EnumMap<>(SubsystemId.class);

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

        LOG.debug("Registered subsystem: {}", subsystem.id());
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
