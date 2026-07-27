/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.event;

import com.openfps.engine.hal.port.I_TimePort;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Factory for creating events with monotonically increasing sequence
 * numbers and a shared timestamp source. Producers ask this factory to
 * build events; the factory stamps the sequence number and timestamp.
 *
 * Sequence numbers are per-factory instance, not global — two engines in
 * one JVM (as in tests) each get their own stream starting at 1.
 */
public final class EventFactory
{
    private final AtomicLong sequenceCounter = new AtomicLong(0L);
    private final I_TimePort timePort;
    private final long originNanos;

    public EventFactory(final I_TimePort timePort)
    {
        if (timePort == null)
        {
            throw new IllegalArgumentException("timePort must not be null");
        }
        this.timePort = timePort;
        this.originNanos = timePort.nanos();
    }

    /** Atomically increments and returns the next sequence number. */
    public long nextSequence()
    {
        return sequenceCounter.incrementAndGet();
    }

    /** Current monotonic timestamp relative to the factory's origin. */
    public long now()
    {
        return timePort.nanos() - originNanos;
    }

    public TickEvent newTick(final int ticNumber, final long deltaNanos)
    {
        return new TickEvent(nextSequence(), now(), ticNumber, deltaNanos);
    }

    public RenderFrameEvent newRenderFrame(final int frameNumber)
    {
        return new RenderFrameEvent(nextSequence(), now(), frameNumber);
    }

    public ShutdownEvent newShutdown(final String reason)
    {
        return new ShutdownEvent(nextSequence(), now(), reason);
    }

    public MapLoadEvent newMapLoad(final String mapName)
    {
        return new MapLoadEvent(nextSequence(), now(), mapName);
    }

    public InputSampledEvent newInputSampled(final int ticNumber, final byte[] inputBytes)
    {
        return new InputSampledEvent(nextSequence(), now(), ticNumber, inputBytes);
    }

    public NetworkPacketEvent newNetworkPacket(final String peerAddress, final byte[] payload)
    {
        return new NetworkPacketEvent(nextSequence(), now(), peerAddress, payload);
    }
}
