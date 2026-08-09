/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.event;

import com.openfps.engine.core.subsystem.SubsystemId;

/**
 * Map-change event. Targets the gameplay subsystem (P_), which calls
 * {@code I_GameplayPort.loadMap}.
 *
 * An event carries exactly one target, so the resource subsystem does not
 * see this one. When W_ is registered in Phase 2, map loading becomes a
 * two-step flow (W_ reads the lumps, then publishes to P_) rather than one
 * event with two recipients.
 */
public final class MapLoadEvent implements I_EngineEvent
{
    private final long sequenceNumber;
    private final long timestampNanos;
    private final String mapName;

    public MapLoadEvent(final long sequenceNumber, final long timestampNanos,
                        final String mapName)
    {
        this.sequenceNumber = sequenceNumber;

        this.timestampNanos = timestampNanos;

        this.mapName = mapName;
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

    public String mapName()
    {
        return mapName;
    }

    @Override
    public String toString()
    {
        return "MapLoadEvent{seq=" + sequenceNumber + ", map='" + mapName + "'}";
    }
}
