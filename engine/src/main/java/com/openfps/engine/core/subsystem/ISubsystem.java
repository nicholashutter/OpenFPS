/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem;

import com.openfps.engine.core.event.I_EngineEvent;

/**
 * Interface every engine subsystem implements.
 *
 * Subsystems are the unit of state in the engine. Each subsystem:
 *   - Has a unique {@link SubsystemId}
 *   - Maintains a {@link SubsystemState} state machine
 *   - Receives events via {@link #processEvent(I_EngineEvent)}
 *   - Validates every state transition — no silent failures
 */
public interface ISubsystem
{
    /** Returns the subsystem's unique ID. */
    SubsystemId id();

    /** Returns the current state. */
    SubsystemState state();

    /**
     * Lifecycle: UNINITIALIZED → READY.
     * @throws SubsystemException if state is not UNINITIALIZED
     */
    void init();

    /**
     * Lifecycle: UNINITIALIZED|READY|ERROR → SHUTDOWN (terminal).
     * @throws SubsystemException if state is already SHUTDOWN
     */
    void shutdown();

    /**
     * Dispatches an event to this subsystem. Does not change state: the
     * subsystem stays READY whether the handler succeeds or throws, so a
     * single bad event cannot take the subsystem down. A throwing handler
     * is logged and the exception is rethrown to the calling worker.
     *
     * Multiple workers may call this concurrently — implementations must
     * be thread-safe.
     *
     * @param event the event to process
     * @throws SubsystemException if state is not READY
     */
    void processEvent(I_EngineEvent event);
}
