/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.port;

/**
 * I_ Port interface — system information.
 *
 * The engine queries this port to learn about the host hardware so it
 * can size its thread pool, decide whether to enable expensive
 * features, etc. Implementations differ per platform (Null, Desktop,
 * Mobile).
 *
 * The null implementation uses {@link Runtime} for all queries; the
 * desktop implementation (Phase 2) can use oshi or JNA for accurate
 * physical-core counts and memory.
 */
public interface I_SystemInfoPort
{
    /**
     * Number of logical CPU cores available to the JVM.
     * Includes hyperthreaded cores. (Same as
     * {@link Runtime#availableProcessors()}.)
     */
    int logicalProcessorCount();

    /**
     * Number of physical CPU cores. Best-effort — may equal
     * {@link #logicalProcessorCount()} if the platform doesn't expose
     * physical-core info.
     */
    int physicalProcessorCount();

    /** Total system memory in bytes (best-effort). */
    long totalMemoryBytes();

    /** Free system memory in bytes (best-effort). */
    long freeMemoryBytes();

    /** Operating system name. */
    String osName();

    /** Operating system version. */
    String osVersion();

    /** JVM version string. */
    String javaVersion();

    /** Initializes the port. Called once at engine startup. */
    void init();

    /** Shuts down the port. Called once at engine shutdown. */
    void shutdown();

    /** Returns the current port state. */
    State state();

    /** Port lifecycle states. */
    enum State
    {
        /** Default state at construction. Must call init() to advance. */
        UNINITIALIZED,
        /** Initialized; queries are valid. */
        READY,
        /** Terminal state. */
        SHUTDOWN
    }
}
