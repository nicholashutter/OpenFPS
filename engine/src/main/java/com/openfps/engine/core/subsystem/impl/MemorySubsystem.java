/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem.impl;

import com.openfps.engine.core.subsystem.Subsystem;
import com.openfps.engine.core.subsystem.SubsystemId;
import com.openfps.engine.memory.port.I_MemoryPort;

/**
 * Memory subsystem (Z_). Wraps an {@link I_MemoryPort} so the engine
 * can dispatch events to it (e.g., a future MAP_LOAD event triggers
 * freeByTag(TAG_GAME)). The port itself has its own state machine —
 * this subsystem tracks the engine's view of it.
 */
public final class MemorySubsystem extends Subsystem
{
    private final I_MemoryPort port;

    public MemorySubsystem(final I_MemoryPort port)
    {
        super(SubsystemId.Z_);

        this.port = port;
    }

    @Override
    protected void onShutdown()
    {
        // The memory port may already be SHUTDOWN if engine main
        // shut it down before the registry; tolerate that.
        try
        {
            port.shutdown();
        }
        catch (final RuntimeException ignored)
        {
            // already shut down or in error — fine
        }
    }
}
