/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.net.adapter;

import com.openfps.engine.net.port.I_NetworkPort;

/**
 * G_ Null adapter for networking.
 *
 * <p>The shipping net path is {@code NetSession} over {@code DesktopDatagramPort},
 * attached by {@code DesktopLauncher} and consumed by
 * {@code demo.RemotePlayers}; it does not go through this port. The
 * subsystem is registered with this null implementation only so
 * {@code SubsystemRegistry} can hold a {@code G_} slot and forward
 * {@code init()}/{@code shutdown()}. The remaining methods that used to live
 * here were removed in the 2026-08 prune; their Javadoc history is preserved
 * on {@link I_NetworkPort} for reference.
 */
public final class NullNetworkPort implements I_NetworkPort
{
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
