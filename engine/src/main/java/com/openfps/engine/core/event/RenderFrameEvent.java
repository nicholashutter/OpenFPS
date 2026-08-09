/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.event;

import com.openfps.engine.core.subsystem.SubsystemId;

/**
 * Frame-render event. Routed to the render subsystem. The render
 * subsystem should be lock-free relative to the game state — it reads
 * the latest snapshot without blocking.
 */
public final class RenderFrameEvent implements I_EngineEvent
{
    private final long sequenceNumber;
    private final long timestampNanos;
    private final int frameNumber;

    public RenderFrameEvent(final long sequenceNumber, final long timestampNanos,
                            final int frameNumber)
    {
        this.sequenceNumber = sequenceNumber;

        this.timestampNanos = timestampNanos;

        this.frameNumber = frameNumber;
    }

    @Override
    public SubsystemId targetSubsystem()
    {
        return SubsystemId.R_;
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

    public int frameNumber()
    {
        return frameNumber;
    }

    @Override
    public String toString()
    {
        return "RenderFrameEvent{seq=" + sequenceNumber + ", frame=" + frameNumber + "}";
    }
}
