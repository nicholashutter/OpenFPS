/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.nulladapter;

import com.openfps.engine.hal.port.I_DatagramPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Null implementation of I_DatagramPort.
 * All sends are silently dropped; all receives return null.
 * Used for single-player and headless testing.
 */
public final class NullDatagramPort implements I_DatagramPort
{
    private static final Logger LOG = LoggerFactory.getLogger(NullDatagramPort.class);

    @Override
    public void send(final byte[] data, final String address)
    {
        LOG.debug("NullDatagramPort: dropped {} bytes to {}", data.length, address);
    }

    @Override
    public byte[] receive()
    {
        return null;  // no data ever arrives
    }

    @Override
    public void bind(final int port)
    {
        LOG.info("NullDatagramPort: bound to port {} (no-op)", port);
    }

    @Override
    public void close()
    {
        // no-op
    }

    @Override
    public void processTic(final int ticIndex)
    {
        // no-op in null adapter
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
