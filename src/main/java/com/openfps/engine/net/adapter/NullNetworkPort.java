/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.net.adapter;

import com.openfps.engine.net.port.I_NetworkPort;

/**
 * G_ Null adapter for networking.
 * All operations are no-ops; used for single-player and headless tests.
 */
public final class NullNetworkPort implements I_NetworkPort
{
    @Override
    public int connect(final String address)
    {
        return -1;
    }

    @Override
    public void disconnect(final int peerId)
    {
        // no-op
    }

    @Override
    public void broadcastTicCmd(final int ticIndex, final byte[] cmdBytes)
    {
        // no-op
    }

    @Override
    public byte[] pollTicCmd(final int ticIndex, final int peerId)
    {
        return null;
    }

    @Override
    public void broadcastMapChange(final String mapName)
    {
        // no-op
    }

    @Override
    public void discoverPeers()
    {
        // no-op
    }

    @Override
    public int connectedPeerCount()
    {
        return 0;
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
