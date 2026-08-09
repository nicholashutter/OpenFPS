/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.event;

import com.openfps.engine.core.subsystem.SubsystemId;

/**
 * Engine-shutdown event. Published by the producer (normally the
 * GameLoop, on reaching maxTics) as the last event of a run, and
 * consumed by {@code CoreSubsystem}, which records the reason.
 *
 * Publishing this does NOT by itself stop the engine: the producer stops
 * producing, and {@code I_ThreadPoolPort.shutdown()} moves the bus to
 * DRAINING so workers finish the backlog and exit.
 */
public final class ShutdownEvent implements I_EngineEvent
{
    private final long sequenceNumber;
    private final long timestampNanos;
    private final String reason;

    public ShutdownEvent(final long sequenceNumber, final long timestampNanos,
                         final String reason)
    {
        this.sequenceNumber = sequenceNumber;

        this.timestampNanos = timestampNanos;

        this.reason = reason;
    }

    @Override
    public SubsystemId targetSubsystem()
    {
        return SubsystemId.CORE;
    }

    @Override
    public long sequenceNumber()
    {
        return sequenceNumber;
    }

    @Override
    public long timestampNanos()
    {
        return timestampNanos;
    }

    public String reason()
    {
        return reason;
    }

    @Override
    public String toString()
    {
        return "ShutdownEvent{seq=" + sequenceNumber + ", reason='" + reason + "'}";
    }
}
