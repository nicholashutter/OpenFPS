/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem;

/**
 * Lifecycle state machine for engine subsystems.
 *
 *   UNINITIALIZED ──init()──► READY ──shutdown()──► SHUTDOWN (terminal)
 *      │                       │
 *      └─init() throws──► ERROR ◄─shutdown() throws─┘
 *
 * The state tracks the SUBSYSTEM's lifecycle, not individual events.
 * Per-event tracking happens at the worker level — a worker thread
 * is implicitly released back to the pool when onEvent() returns.
 *
 * Multiple workers can dispatch events to the same subsystem in
 * parallel. The subsystem's onEvent() must be thread-safe.
 */
public enum SubsystemState
{
    /** Default state at construction. Must call init() to advance. */
    UNINITIALIZED,
    /** Initialized, accepting events from the worker pool. */
    READY,
    /** init() or shutdown() threw. Only shutdown() is valid from here. */
    ERROR,
    /** Terminal state. No further operations. */
    SHUTDOWN
}
