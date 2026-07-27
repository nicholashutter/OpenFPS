/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.render.port;

import com.openfps.engine.core.pool.I_ThreadPoolPort;
import com.openfps.engine.hal.port.I_TimePort;

/**
 * R_ Creates the render port once the engine's shared services exist.
 *
 * <b>Why a factory rather than a plain {@code I_RenderPort} parameter.</b> The
 * software rasterizer needs the {@code WorkerPool} — {@code render/README.md}
 * § 7 makes parallel tile dispatch a correctness property, not a tuning one —
 * and it needs {@code I_TimePort} for frame timing, because {@code AGENTS.md}
 * forbids {@code System.nanoTime()} in engine code. Both are created by
 * {@code EngineMain} during bootstrap, so a launcher cannot construct the port
 * before calling in. Inverting it costs one small interface and keeps the
 * alternative — a mutable "bind the pool later" setter racing the game loop
 * thread that starts inside the same bootstrap — out of the codebase.
 *
 * <p>A launcher that wants to keep a typed reference to the port it created —
 * the desktop presenter needs one, since {@code render/README.md} § 12 puts
 * presentation in the platform adapter — implements this with a small class
 * that remembers what it built, rather than downcasting the port back out.</p>
 */
public interface I_RenderPortFactory
{
    /**
     * Creates the render port.
     *
     * Called once, on the bootstrap thread, after the worker pool has been
     * created and before any subsystem is initialised.
     *
     * @param pool the engine's worker pool, already sized but not yet started;
     *     never null
     * @param time the engine's monotonic clock; never null
     * @return the port the {@code RenderSubsystem} will wrap; must not be null
     */
    I_RenderPort createRenderPort(I_ThreadPoolPort pool, I_TimePort time);
}
