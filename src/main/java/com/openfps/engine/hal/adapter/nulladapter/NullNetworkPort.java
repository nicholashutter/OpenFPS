/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.nulladapter;

import com.openfps.engine.hal.port.I_NetworkPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Null implementation of I_NetworkPort.
 * All sends are silently dropped; all receives return null.
 * Used for single-player and headless testing.
 */
public final class NullNetworkPort implements I_NetworkPort
{
    private static final Logger LOG = LoggerFactory.getLogger(NullNetworkPort.class);

    private volatile boolean bound;
    private int localPort;

    @Override
    public void send(final byte[] data, final String address)
    {
        LOG.debug("NullNetworkPort: dropped {} bytes to {}", data.length, address);
    }

    @Override
    public byte[] receive()
    {
        return null;  // no data ever arrives
    }

    @Override
    public void bind(final int port)
    {
        localPort = port;
        bound = true;
        LOG.info("NullNetworkPort: bound to port {} (no-op)", port);
    }

    @Override
    public void close()
    {
        bound = false;
    }

    @Override
    public void processTic(final int ticIndex)
    {
        // no-op in null adapter
    }

    @Override
    public void init()
    {
        bound = false;
    }

    @Override
    public void shutdown()
    {
        bound = false;
    }
}
