/*
 * Copyright (c) 2026 Nicholas Hutter and contributors.
 * SPDX-License-Identifier: MIT
 */

package com.openfps.engine.hal.port;

/**
 * I_ Port interface — the platform window and its event pump.
 *
 * The window lives in the HAL, NOT behind {@code I_RenderPort}. Two
 * reasons, and both are load-bearing:
 *
 *  1. R_ is pure math on primitive arrays (see {@code render/README.md}).
 *     Giving it {@code swapBuffers()} would force the Phase 5 software
 *     rasterizer to know what a window is.
 *  2. {@code RenderSubsystem.onEvent} runs on WORKER threads, but a GL
 *     context is current on exactly one thread — the main thread. Routing
 *     GL calls through {@code I_RenderPort} would be a crash waiting to
 *     happen. Keeping the window on a HAL port that only the composition
 *     root drives makes the threading correct by construction.
 *
 * <b>Threading:</b> every method here MUST be called from the main
 * thread. GLFW requires it (strictly, on macOS), and the engine reserves
 * the main thread for exactly this pump — the game loop runs on its own
 * thread precisely so this one stays free.
 */
public interface I_WindowPort
{
    /** Initializes the windowing system. Main thread only. */
    void init();

    /**
     * Creates the window and makes its graphics context current.
     * Main thread only.
     *
     * @param width client-area width in pixels
     * @param height client-area height in pixels
     * @param title window title bar text
     */
    void create(int width, int height, String title);

    /**
     * Drains pending OS events, firing input callbacks synchronously on
     * this thread. Main thread only; call once per pump iteration.
     */
    void pumpEvents();

    /**
     * Returns true once the user has asked to close the window (clicked
     * the X, Alt+F4, or {@link #requestClose()} was called).
     */
    boolean isCloseRequested();

    /**
     * Programmatically requests close. Lets tests and the engine drive
     * the same shutdown path a real user click would.
     */
    void requestClose();

    /**
     * Presents a frame. In Phase 1.5 this is clear + swap — proof of
     * life only. Phase 5 replaces the body with a framebuffer upload.
     * Main thread only.
     */
    void present();

    /**
     * Returns true if this is a real on-screen window.
     *
     * The engine branches on this once at startup: a real window means
     * the main thread runs the pump loop, no window means it simply
     * joins the game loop thread. Without this the headless path would
     * have to busy-wait on a no-op pump.
     */
    boolean isRealWindow();

    /** Destroys the window and terminates the windowing system. Main thread only. */
    void shutdown();
}
