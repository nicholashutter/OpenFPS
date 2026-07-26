/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.eventbus;

/**
 * System-level factory for the event bus. The engine never instantiates
 * a bus directly; it asks the factory. Today there's one backend
 * (SharedEventBus), but future options like a multi-queue or
 * lock-free backend live here.
 */
public final class EventBusFactory
{
    private EventBusFactory()
    {
    }

    /**
     * Creates a single-shared-queue event bus.
     * @return a fresh, uninitialized bus (call init() before use)
     */
    public static I_EventBusPort createShared()
    {
        return new SharedEventBus();
    }
}
