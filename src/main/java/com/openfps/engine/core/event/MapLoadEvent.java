/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.event;

import com.openfps.engine.core.subsystem.SubsystemId;

/**
 * Map-change event. Routed to the resource and gameplay subsystems.
 * Triggers bulk-free of game-tagged memory and re-loads map data.
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
