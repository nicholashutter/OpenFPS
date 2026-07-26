/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.port;

/**
 * R_ Port interface — rendering.
 * Stubbed for Phase 0; implementations wired in Phase 5.
 */
public interface I_RenderPort
{
    /**
     * Renders one frame for the given tic.
     *
     * @param ticIndex the current tic
     */
    void renderFrame(final int ticIndex);

    /**
     * Initializes the renderer.
     */
    void init();

    /**
     * Shuts down the renderer.
     */
    void shutdown();
}
