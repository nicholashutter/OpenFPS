/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter;

import com.openfps.engine.hal.adapter.desktop.DesktopAdapterFactory;
import com.openfps.engine.hal.adapter.nulladapter.NullAdapterFactory;
import com.openfps.engine.hal.adapter.sqlite.SqliteAdapterFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * System-level selector for the HAL backend. The engine never
 * instantiates an adapter factory directly; it asks this selector.
 *
 * Same pattern as {@code MemoryPortFactory}, {@code EventBusFactory},
 * and {@code ThreadPoolFactory} — one place that knows which concrete
 * implementation answers to which choice.
 */
public final class AdapterFactorySelector
{
    private static final Logger LOG = LoggerFactory.getLogger(AdapterFactorySelector.class);

    private AdapterFactorySelector()
    {
        // utility class
    }

    /**
     * Creates the factory for the given backend. The returned factory is
     * uninitialized — call {@code init()} on the main thread before use.
     *
     * @param backend which HAL backend to build
     * @return a fresh, uninitialized adapter factory
     */
    public static I_AdapterFactory create(final HalBackend backend)
    {
        if (backend == null)
        {
            throw new IllegalArgumentException("backend must not be null");
        }
        LOG.info("AdapterFactorySelector: selecting {} HAL backend", backend);
        switch (backend)
        {
            case NULL:
                return new NullAdapterFactory();
            case SQLITE:
                return new SqliteAdapterFactory();
            case DESKTOP:
                return new DesktopAdapterFactory();
            default:
                throw new IllegalArgumentException("unknown HAL backend: " + backend);
        }
    }
}
