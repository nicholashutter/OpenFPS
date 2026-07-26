/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.event;

import com.openfps.engine.core.subsystem.SubsystemId;

/**
 * Input-sampled event. Produced by the HAL input port once per tic when
 * a frame's input has been polled. Routed to the gameplay subsystem to
 * feed the local player's TicCmd.
 */
public final class InputSampledEvent implements I_EngineEvent
{
    private final long sequenceNumber;
    private final long timestampNanos;
    private final int ticNumber;
    private final byte[] inputBytes;

    public InputSampledEvent(final long sequenceNumber, final long timestampNanos,
                             final int ticNumber, final byte[] inputBytes)
    {
        this.sequenceNumber = sequenceNumber;
        this.timestampNanos = timestampNanos;
        this.ticNumber = ticNumber;
        this.inputBytes = inputBytes;
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

    public byte[] inputBytes()
    {
        return inputBytes;
    }

    @Override
    public String toString()
    {
        return "InputSampledEvent{seq=" + sequenceNumber + ", tic=" + ticNumber
            + ", bytes=" + (inputBytes == null ? 0 : inputBytes.length) + "}";
    }
}
