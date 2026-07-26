/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.nulladapter;

import com.openfps.engine.hal.port.I_TimePort;

/**
 * Null implementation of I_TimePort.
 * Uses System.nanoTime() — monotonic and requires no OS init.
 */
public final class NullTimePort implements I_TimePort
{
    @Override
    public long millis()
    {
        return System.currentTimeMillis();
    }

    @Override
    public long nanos()
    {
        return System.nanoTime();
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
