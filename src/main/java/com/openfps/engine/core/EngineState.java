/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core;

/**
 * The engine's own state machine. The whole engine (core, bus, pool,
 * subsystems) progresses through these states during startup and
 * shutdown.
 *
 *   BOOTING → BUSY → SHUTTING_DOWN → SHUTDOWN
 *
 * Used by tests and the bootstrap to track progress.
 */
public enum EngineState
{
    /** EngineMain has started but subsystems are not yet ready. */
    BOOTING,
    /** Engine is running, processing events. */
    BUSY,
    /** A SHUTDOWN event has been received; subsystems are draining. */
    SHUTTING_DOWN,
    /** Terminal — engine has fully shut down. */
    SHUTDOWN
}
