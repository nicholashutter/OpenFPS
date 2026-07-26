/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter.nulladapter;

import com.openfps.engine.hal.port.I_FilePort;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Null implementation of I_FilePort.
 * Falls back to real filesystem reads — only the network calls are truly null.
 */
public final class NullFilePort implements I_FilePort
{
    private static final Logger LOG = Logger.getLogger(NullFilePort.class.getName());

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
            LOG.log(Level.WARNING, "NullFilePort: file not found: {0}", path);
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
