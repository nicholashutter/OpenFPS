/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.nulladapter;

import com.openfps.engine.hal.port.I_TimePort;

/**
 * Null implementation of I_TimePort.
 *
 * Monotonic readings come from {@code System.nanoTime()}; the wall clock
 * comes from {@code System.currentTimeMillis()}. This is the one place
 * in the engine allowed to call either directly — everything else goes
 * through the port.
 */
public final class NullTimePort implements I_TimePort
{
    @Override
    public long millis()
    {
        // Derived from nanoTime, NOT currentTimeMillis — millis() is
        // contractually monotonic. See I_TimePort.
        return System.nanoTime() / 1_000_000L;
    }

    @Override
    public long nanos()
    {
        return System.nanoTime();
    }

    @Override
    public long epochMillis()
    {
        return System.currentTimeMillis();
    }

    @Override
    public void init()
    {
        // no-op: nanoTime is always available
    }

    @Override
    public void shutdown()
    {
        // no-op
    }
}
