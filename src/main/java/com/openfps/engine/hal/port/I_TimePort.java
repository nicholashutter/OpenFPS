/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.port;

/**
 * I_ Port interface — the engine's time source.
 *
 * Two distinct clocks live behind this port, and mixing them up is a
 * bug:
 *
 *  - {@link #nanos()} / {@link #millis()} are MONOTONIC. They count from
 *    an arbitrary origin, never go backwards, and are unaffected by the
 *    user changing the system clock. Use them for tic timing, elapsed
 *    durations, and anything that feeds lockstep determinism.
 *  - {@link #epochMillis()} is WALL CLOCK. It can jump forwards or
 *    backwards. Use it only for timestamps that are persisted or shown
 *    to a human (profile last-login, save-game dates).
 *
 * Never store a monotonic reading as an epoch timestamp, and never
 * measure a duration by subtracting two wall-clock readings.
 */
public interface I_TimePort
{
    /**
     * Returns monotonic time in milliseconds since an arbitrary origin.
     * Suitable for measuring elapsed durations; meaningless as a date.
     *
     * @return monotonic time in ms
     */
    long millis();

    /**
     * Returns monotonic time in nanoseconds since an arbitrary origin.
     * Suitable for high-precision tic timing; meaningless as a date.
     *
     * @return monotonic time in ns
     */
    long nanos();

    /**
     * Returns wall-clock time in milliseconds since the Unix epoch.
     * Use for persisted or user-facing timestamps only — this clock can
     * jump, so it must never be used to measure a duration.
     *
     * @return milliseconds since 1970-01-01T00:00:00Z
     */
    long epochMillis();

    /**
     * Initializes the time source. Called once at engine startup.
     */
    void init();

    /**
     * Shuts down the time source. Called once at engine shutdown.
     */
    void shutdown();
}
