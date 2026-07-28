/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.demo;

/**
 * Thrown when the first-person demo has no geometry it can stand on.
 *
 * <p>This is deliberately not a silent fallback to an empty world. A demo whose
 * whole purpose is "stand in a room and look around" has failed completely if
 * there is no room, and a black window with a clean exit code is the least
 * useful way to report that. The message always names the command that produces
 * the missing files — see {@link DemoModels#REGENERATE_COMMAND}.</p>
 */
public final class DemoAssetException extends RuntimeException
{
    /** Serialisation id; this type carries no state beyond its message. */
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with an actionable message.
     *
     * @param message what was missing and how to produce it
     */
    public DemoAssetException(final String message)
    {
        super(message);
    }

    /**
     * Creates an exception wrapping a lower-level failure.
     *
     * @param message what was missing and how to produce it
     * @param cause the underlying I/O or format failure
     */
    public DemoAssetException(final String message, final Throwable cause)
    {
        super(message, cause);
    }
}
