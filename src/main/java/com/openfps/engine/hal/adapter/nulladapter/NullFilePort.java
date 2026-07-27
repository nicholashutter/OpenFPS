/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.nulladapter;

import com.openfps.engine.hal.port.I_FilePort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Null implementation of I_FilePort — "null" only in the sense that it is
 * the headless-default adapter. Reads are backed by the real filesystem,
 * because WAD loading needs actual bytes even in a headless run.
 *
 * Platform adapter — must not import from core engine packages.
 */
public final class NullFilePort implements I_FilePort
{
    private static final Logger LOG = LoggerFactory.getLogger(NullFilePort.class);

    @Override
    public I_FileHandle open(final String path)
    {
        try
        {
            final FileInputStream fis = new FileInputStream(path);
            return new RealFileHandle(fis);
        }
        catch (final FileNotFoundException e)
        {
            LOG.warn("NullFilePort: file not found: {}", path);
            return null;
        }
    }

    @Override
    public boolean exists(final String path)
    {
        return new File(path).exists();
    }

    private static final class RealFileHandle implements I_FileHandle
    {
        private final FileInputStream fis;
        private boolean open;

        RealFileHandle(final FileInputStream fis)
        {
            this.fis = fis;
            this.open = true;
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length)
        {
            try
            {
                return fis.read(buffer, offset, length);
            }
            catch (final IOException e)
            {
                return -1;
            }
        }

        @Override
        public long size()
        {
            try
            {
                return fis.getChannel().size();
            }
            catch (final IOException e)
            {
                return -1;
            }
        }

        @Override
        public void close()
        {
            try
            {
                fis.close();
                open = false;
            }
            catch (final IOException e)
            {
                // ignore
            }
        }

        @Override
        public boolean isOpen()
        {
            return open;
        }
    }
}
