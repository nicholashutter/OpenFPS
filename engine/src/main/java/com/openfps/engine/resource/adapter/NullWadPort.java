/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.resource.adapter;

import com.openfps.engine.resource.port.I_WadPort;

/**
 * W_ Null adapter for resource loading.
 * No WAD is loaded; all reads return null.
 */
public final class NullWadPort implements I_WadPort
{
    @Override
    public boolean open(final String path)
    {
        return false;
    }

    @Override
    public void close()
    {
        // no-op
    }

    @Override
    public byte[] readLump(final int lumpIndex)
    {
        return null;
    }

    @Override
    public byte[] readLump(final String lumpName)
    {
        return null;
    }

    @Override
    public int lumpCount()
    {
        return -1;
    }

    @Override
    public void precacheLump(final int lumpIndex)
    {
        // no-op
    }

    @Override
    public void flushCache()
    {
        // no-op
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
