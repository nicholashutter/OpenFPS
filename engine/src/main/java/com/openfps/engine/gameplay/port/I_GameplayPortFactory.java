/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.gameplay.port;

import com.openfps.engine.hal.port.I_InputPort;

/**
 * P_ Builds the gameplay port once the engine's HAL exists.
 *
 * <p>Exactly the problem {@code I_RenderPortFactory} solves, for the same
 * reason. A gameplay port that reads input needs the {@code I_InputPort}, and
 * that port is not available to a launcher until {@code EngineMain.start} has
 * called {@code I_AdapterFactory.init()} — which happens inside the bootstrap.
 * Constructing the port afterwards is too late: the subsystem registry has
 * already been filled and the game loop thread has already started, so binding
 * it then would be a mutable field racing the loop.</p>
 *
 * <p>One small interface removes both problems. The launcher describes
 * <i>how</i> to build its port; the bootstrap decides <i>when</i>.</p>
 *
 * <p><b>Ordering contract:</b> the bootstrap calls this <b>after</b> it has
 * built the render port, so a launcher whose gameplay port also needs the
 * renderer may capture it from the same holder it passed as the
 * {@code I_RenderPortFactory}. That ordering is relied upon by the desktop
 * launcher and must not be reversed.</p>
 */
@FunctionalInterface
public interface I_GameplayPortFactory
{
    /**
     * Creates the gameplay port for one engine session.
     *
     * @param inputPort the initialised HAL input port; never null
     * @return the port to register as {@code P_}; must not be null
     */
    I_GameplayPort createGameplayPort(I_InputPort inputPort);
}
