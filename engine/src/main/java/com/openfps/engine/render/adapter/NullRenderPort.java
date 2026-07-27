/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.adapter;

import com.openfps.engine.render.port.I_RenderPort;

/**
 * R_ Null adapter for rendering.
 * No pixels are drawn; used for headless testing.
 */
public final class NullRenderPort implements I_RenderPort
{
    @Override
    public void renderFrame(final int ticIndex)
    {
        // no-op
    }

    @Override
    public void init()
    {
        // no-op
    }

    @Override
    public void shutdown()
    {
        // no-op
    }
}
