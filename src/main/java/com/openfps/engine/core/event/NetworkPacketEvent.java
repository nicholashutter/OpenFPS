/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.event;

import com.openfps.engine.core.subsystem.SubsystemId;

/**
 * Network-packet event. Produced by the HAL network port when a UDP
 * datagram arrives. Routed to the network subsystem for parsing and
 * tic command extraction.
 */
public final class NetworkPacketEvent implements I_EngineEvent
{
    private final long sequenceNumber;
    private final long timestampNanos;
    private final String peerAddress;
    private final byte[] payload;

    public NetworkPacketEvent(final long sequenceNumber, final long timestampNanos,
                              final String peerAddress, final byte[] payload)
    {
        this.sequenceNumber = sequenceNumber;
        this.timestampNanos = timestampNanos;
        this.peerAddress = peerAddress;
        this.payload = payload;
    }

    @Override
    public SubsystemId targetSubsystem()
    {
        return SubsystemId.G_;
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

    public String peerAddress()
    {
        return peerAddress;
    }

    public byte[] payload()
    {
        return payload;
    }

    @Override
    public String toString()
    {
        int byteCount = 0;
        if (payload != null)
        {
            byteCount = payload.length;
        }
        return "NetworkPacketEvent{seq=" + sequenceNumber + ", from='" + peerAddress
            + "', bytes=" + byteCount + "}";
    }
}
