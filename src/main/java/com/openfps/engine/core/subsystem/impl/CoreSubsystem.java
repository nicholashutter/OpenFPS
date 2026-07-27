/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem.impl;

import com.openfps.engine.core.event.I_EngineEvent;
import com.openfps.engine.core.event.ShutdownEvent;
import com.openfps.engine.core.subsystem.Subsystem;
import com.openfps.engine.core.subsystem.SubsystemId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Engine-core subsystem (CORE). Handles events that target the engine
 * itself rather than a specific subsystem.
 *
 * Today that is exactly one event: {@link ShutdownEvent}. Without this
 * subsystem registered, every shutdown produced a
 * "No subsystem registered for event" warning, because ShutdownEvent
 * targets {@link SubsystemId#CORE} and nothing claimed that ID.
 *
 * Note the drain itself is NOT driven from here — the producer stops
 * publishing and {@code I_ThreadPoolPort.shutdown()} moves the bus to
 * DRAINING. This handler records the reason so the shutdown cause
 * appears in the log next to the events that preceded it.
 */
public final class CoreSubsystem extends Subsystem
{
    private static final Logger LOG = LoggerFactory.getLogger(CoreSubsystem.class);

    public CoreSubsystem()
    {
        super(SubsystemId.CORE);
    }

    @Override
    protected void onEvent(final I_EngineEvent event)
    {
        if (event instanceof ShutdownEvent shutdown)
        {
            LOG.info("Engine shutdown requested: {}", shutdown.reason());
        }
    }
}
