/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.event;

/**
 * Singleton factory for creating events with monotonically increasing
 * sequence numbers and a shared timestamp source. Producers ask this
 * factory to build events; the factory stamps the sequence number and
 * timestamp.
 */
public final class EventFactory
{
    private static volatile long sequenceCounter = 0L;
    private final long originNanos;

    public EventFactory(final long originNanos)
    {
        this.originNanos = originNanos;
    }

    /** Atomically increments and returns the next sequence number. */
    public long nextSequence()
    {
        synchronized (EventFactory.class)
        {
            sequenceCounter++;
            return sequenceCounter;
        }
    }

    /** Current monotonic timestamp relative to the factory's origin. */
    public long now()
    {
        return System.nanoTime() - originNanos;
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
