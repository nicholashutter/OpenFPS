/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.port;

/**
 * I_ Port interface — monotonic time source.
 * All timing in the engine flows through this port.
 * Implementations must be monotonic (never go backwards).
 */
public interface I_TimePort
{
    /**
     * Returns the current time in milliseconds since an arbitrary epoch.
     * Monotonic — suitable for measuring elapsed durations.
     *
     * @return current time in ms
     */
    long millis();

    /**
     * Returns the current time in nanoseconds since an arbitrary epoch.
     * Monotonic — suitable for high-precision tic timing.
     *
     * @return current time in ns
     */
    long nanos();

    /**
     * Initializes the time source. Called once at engine startup.
     */
    void init();

    /**
     * Shuts down the time source. Called once at engine shutdown.
     */
    void shutdown();
}
