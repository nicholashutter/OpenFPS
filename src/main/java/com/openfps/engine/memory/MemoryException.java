/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.memory;

/**
 * Thrown for any invalid memory-port operation.
 *
 * Use this to surface:
 *   - State machine violations (e.g. allocate before init, free after shutdown)
 *   - Invalid handles (e.g. negative, free, double-freed)
 *   - OOM (heap exhausted)
 *   - Argument errors (negative size, unknown tag)
 *
 * This is a {@link RuntimeException} so it doesn't pollute the API
 * with checked-exception noise, but the engine should treat any
 * thrown instance as fatal — there are no silent failures in the
 * memory subsystem.
 */
public class MemoryException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public MemoryException(final String message)
    {
        super(message);
    }

    public MemoryException(final String message, final Throwable cause)
    {
        super(message, cause);
    }
}
