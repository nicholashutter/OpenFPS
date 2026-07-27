/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.port;

/**
 * I_ Port interface — input sampling.
 * Reads keyboard, mouse, and gamepad state each tic and
 * makes it available to the gameplay subsystem.
 */
public interface I_InputPort
{
    /**
     * Samples input state for the given tic.
     * Implementations populate the TicCmd buffer for the local player.
     *
     * @param ticIndex the tic being processed
     */
    void sampleInput(int ticIndex);

    /**
     * Returns whether the engine should shut down (e.g., window closed).
     *
     * @return true if shutdown is requested
     */
    boolean isShutdownRequested();

    /**
     * Initializes the input subsystem. Called once at engine startup.
     */
    void init();

    /**
     * Shuts down the input subsystem. Called once at engine shutdown.
     */
    void shutdown();
}
