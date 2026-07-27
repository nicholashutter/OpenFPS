/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

/**
 * Thrown when a model file cannot be read as a valid {@link ModelFormat}.
 *
 * Render adapter — must not import from core engine packages.
 *
 * Surfaces every rejection the reader makes:
 * <ul>
 *   <li>Wrong magic — the file is not a model at all</li>
 *   <li>Unrecognised major version — the layout is not the one this build knows</li>
 *   <li>Truncated or oversized file — the declared size does not match the bytes</li>
 *   <li>A section whose offset or extent falls outside the file</li>
 *   <li>An index, submesh range or texture descriptor that points out of bounds</li>
 * </ul>
 *
 * This is a {@link RuntimeException} for the same reason {@code WadException}
 * is: a corrupt binary asset is not a condition a caller can recover from
 * mid-read, and the alternative to failing loudly here is a mis-parsed vertex
 * array that shows up as inexplicable geometry many frames later.
 */
public class ModelFormatException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    /**
     * Creates a model format exception with a message.
     *
     * @param message human-readable description of what was wrong
     */
    public ModelFormatException(final String message)
    {
        super(message);
    }

    /**
     * Creates a model format exception wrapping an underlying cause.
     *
     * @param message human-readable description of what was wrong
     * @param cause the underlying failure
     */
    public ModelFormatException(final String message, final Throwable cause)
    {
        super(message, cause);
    }
}
