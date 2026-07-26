/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.port;

/**
 * I_ Port interface — file system access.
 * Used by the W_ resource subsystem to load WAD files and lumps.
 * Abstracts filesystem access for mobile and desktop.
 */
public interface I_FilePort
{
    /**
     * Opens a file for reading.
     *
     * @param path absolute or classpath-relative path
     * @return a file handle, or null if not found
     */
    I_FileHandle open(final String path);

    /**
     * Checks if a file exists.
     *
     * @param path the file path
     * @return true if the file exists
     */
    boolean exists(final String path);

    /**
     * File handle returned by open().
     * All reads return primitives or primitive arrays where possible.
     */
    interface I_FileHandle
    {
        int read(final byte[] buffer, final int offset, final int length);
        long size();
        void close();
        boolean isOpen();
    }
}
