/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.resource;

/**
 * Thrown for any invalid WAD resource operation.
 *
 * Use this to surface:
 *   - Malformed containers (bad magic, truncated header, directory past EOF)
 *   - Malformed directory entries (negative size, lump data outside the file)
 *   - Malformed map lumps (length not a multiple of the entry size)
 *   - Cache failures (budget exhausted, memory port refused an allocation)
 *
 * This is a {@link RuntimeException} for the same reason
 * {@code MemoryException} is: the resource subsystem has no silent
 * failures, and a corrupt WAD is not something a caller can recover
 * from mid-read. The one place it is caught and downgraded is
 * {@code WadFilePort.open}, whose port contract is a boolean.
 */
public class WadException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    /**
     * Creates a WAD exception with a message.
     *
     * @param message human-readable description of what was wrong
     */
    public WadException(final String message)
    {
        super(message);
    }

    /**
     * Creates a WAD exception wrapping an underlying cause.
     *
     * @param message human-readable description of what was wrong
     * @param cause the underlying failure
     */
    public WadException(final String message, final Throwable cause)
    {
        super(message, cause);
    }
}
