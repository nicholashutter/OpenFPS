/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.desktop;

import com.openfps.engine.hal.port.I_TimePort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Desktop implementation of {@link I_TimePort} — pure JDK, no native
 * dependency.
 *
 * Monotonic readings come from {@code System.nanoTime()}; the wall clock
 * comes from {@code System.currentTimeMillis()}. A time-port adapter is
 * the ONE sanctioned place in the codebase where those two calls may be
 * made directly (STYLE.md § 13.4) — everything else in the engine reads
 * time through this port, so there is exactly one clock per backend and
 * a test can swap it out.
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class DesktopTimePort implements I_TimePort
{
    private static final Logger LOG = LoggerFactory.getLogger(DesktopTimePort.class);

    /** Nanoseconds in one millisecond — the {@link #millis()} divisor. */
    private static final long NANOS_PER_MILLI = 1_000_000L;

    /**
     * Monotonic origin captured at {@link #init()}. Readings are relative
     * to it so {@link #nanos()} starts near zero for a given engine run,
     * which keeps tic timestamps small and readable in logs.
     */
    private volatile long originNanos;

    @Override
    public long millis()
    {
        // Derived from nanoTime, NOT currentTimeMillis — millis() is
        // contractually monotonic. See I_TimePort.
        return nanos() / NANOS_PER_MILLI;
    }

    @Override
    public long nanos()
    {
        // Sanctioned direct System.nanoTime() call: this IS the time port.
        return System.nanoTime() - originNanos;
    }

    @Override
    public long epochMillis()
    {
        // Sanctioned direct System.currentTimeMillis() call: this IS the
        // time port. Wall clock only — never use it to measure a duration.
        return System.currentTimeMillis();
    }

    @Override
    public void init()
    {
        originNanos = System.nanoTime();

        LOG.info("DesktopTimePort initialized: monotonic origin captured");
    }

    @Override
    public void shutdown()
    {
        // no-op: nanoTime holds no resources
        LOG.info("DesktopTimePort shut down");
    }
}
