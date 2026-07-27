/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.core.subsystem;

/**
 * Thrown for any invalid subsystem operation — state machine violations,
 * duplicate registration, dispatching to an unknown subsystem.
 */
public class SubsystemException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public SubsystemException(final String message)
    {
        super(message);
    }

    public SubsystemException(final String message, final Throwable cause)
    {
        super(message, cause);
    }
}
