/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.event;

import com.openfps.engine.core.subsystem.SubsystemId;

/**
 * Periodic game-tick event. Produced by the GameLoop at the configured
 * TIC_RATE (default 35 Hz). Routed to the gameplay subsystem which
 * advances player physics, entity AI, and map logic.
 */
public final class TickEvent implements I_EngineEvent
{
    private final long sequenceNumber;
    private final long timestampNanos;
    private final int ticNumber;
    private final long deltaNanos;

    public TickEvent(final long sequenceNumber, final long timestampNanos,
                     final int ticNumber, final long deltaNanos)
    {
        this.sequenceNumber = sequenceNumber;
        this.timestampNanos = timestampNanos;
        this.ticNumber = ticNumber;
        this.deltaNanos = deltaNanos;
    }

    @Override
    public SubsystemId targetSubsystem()
    {
        return SubsystemId.P_;
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

    public int ticNumber()
    {
        return ticNumber;
    }

    public long deltaNanos()
    {
        return deltaNanos;
    }

    @Override
    public String toString()
    {
        return "TickEvent{seq=" + sequenceNumber + ", tic=" + ticNumber
            + ", deltaNanos=" + deltaNanos + "}";
    }
}
