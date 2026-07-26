/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.event;

import com.openfps.engine.core.subsystem.SubsystemId;

/**
 * Base interface for all engine events.
 *
 * Events are the only way subsystems communicate with the engine and with
 * each other. A subsystem PRODUCES an event by publishing it to the bus;
 * the engine (a worker thread) CONSUMES the event and DISPATCHES it to
 * the target subsystem, which mutates its state.
 *
 * Properties:
 *   - Immutable. Once published, an event cannot be modified.
 *   - Self-describing. Each event carries its own target subsystem.
 *   - Typed. Each concrete event is a final class with a typed payload.
 *
 * Ordering: events from a single producer are FIFO in the queue. Across
 * producers there is no global ordering guarantee.
 */
public interface I_EngineEvent
{
    /**
     * Returns the subsystem that should process this event.
     * The dispatcher uses this to route the event.
     */
    SubsystemId targetSubsystem();

    /**
     * Returns a monotonically increasing sequence number, useful for
     * debugging and for events that need ordering across types.
     */
    long sequenceNumber();

    /**
     * Returns the time the event was created, in nanoseconds from the
     * I_TimePort origin. Used for latency diagnostics.
     */
    long timestampNanos();
}
