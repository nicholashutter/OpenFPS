/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.adapter;

/**
 * Closed set of HAL backends the engine can boot with.
 *
 * Selected once at startup from the CLI flags and passed to
 * {@link AdapterFactorySelector#create(HalBackend)}. Adding a backend
 * means adding a value here and a branch there — there is no runtime
 * registration or classpath scanning.
 */
public enum HalBackend
{
    /**
     * Everything null / in-memory. No disk, no window, no OS calls.
     * Used by every test and by {@code --headless}.
     */
    NULL,

    /**
     * Null ports except a real SQLite-backed user profile on disk.
     * The default for a normal headless run.
     */
    SQLITE,

    /**
     * Real JDK-backed desktop ports: monotonic + wall clock, non-blocking
     * UDP on {@code DatagramChannel}, and the SQLite profile. Window and
     * input stay null until LWJGL/GLFW becomes a dependency, so this
     * backend still needs no display.
     */
    DESKTOP
}
