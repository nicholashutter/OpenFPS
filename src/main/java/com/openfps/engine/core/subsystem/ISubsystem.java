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
     * Lifecycle: READY|BUSY → SHUTDOWN (terminal).
     * @throws SubsystemException if state is already SHUTDOWN
     */
    void shutdown();

    /**
     * Dispatch an event to this subsystem. The state machine transitions
     * READY → BUSY → READY around the dispatch. If the event handler
     * throws, the subsystem enters the ERROR state.
     *
     * @param event the event to process
     * @throws SubsystemException if state is not READY
     */
    void processEvent(final I_EngineEvent event);
}
