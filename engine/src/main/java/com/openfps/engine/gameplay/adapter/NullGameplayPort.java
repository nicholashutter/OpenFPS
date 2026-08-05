/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.adapter;

import com.openfps.engine.gameplay.port.I_GameplayPort;

/**
 * P_ Null adapter for gameplay.
 * All logic is stubbed; the engine loop still runs.
 */
public final class NullGameplayPort implements I_GameplayPort
{
    @Override
    public void tick(final int ticIndex)
    {
        // no-op in null adapter
    }

    @Override
    public boolean loadMap(final String mapName)
    {
        return false;
    }

    @Override
    public void init()
    {
        // no-op
    }

    @Override
    public void shutdown()
    {
        // no-op
    }
}
