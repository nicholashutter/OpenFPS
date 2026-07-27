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
 * <b>The platform owns the loop, not the engine.</b> This port used to
 * expose {@code pumpEvents()} and {@code present()} for the engine to call
 * in its own while-loop. That is a GLFW shape, and Android cannot satisfy
 * it: there the framework owns the loop and calls the app when a frame is
 * due — there is nothing to pump. Rather than have the Android adapter
 * fake a pump, inverting control twice, {@link #runFrameLoop} hands the
 * thread to the platform and the engine supplies an
 * {@link I_FrameCallback}.
 *
 * <b>Threading:</b> {@link #init}, {@link #create}, {@link #runFrameLoop}
 * and {@link #shutdown} MUST be called from the main thread. GLFW requires
 * it (strictly, on macOS) and Android requires it for Activity lifecycle.
 * The game loop runs on its own thread precisely so this one stays free.
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
     * Hands the calling thread to the platform's frame loop and BLOCKS
     * until the window closes or the app is destroyed. Main thread only.
     *
     * Desktop drains OS events, invokes the callback, and swaps buffers in
     * a loop. Android hands control to the Activity and returns when it is
     * destroyed. Either way the caller gets its thread back only at the
     * end, so shutdown sequencing goes after this returns.
     *
     * A null-window implementation returns immediately without invoking
     * the callback at all; check {@link #isRealWindow()} first if the
     * caller needs to do something else in the headless case.
     *
     * @param callback receives surface, frame and lifecycle events
     */
    void runFrameLoop(I_FrameCallback callback);

    /**
     * Returns true once the user has asked to close the window (clicked
     * the X, Alt+F4, or {@link #requestClose()} was called).
     */
    boolean isCloseRequested();

    /**
     * Programmatically requests close. Lets tests and the engine drive
     * the same shutdown path a real user click would. Safe to call from
     * any thread — the game loop uses it to end a windowed run.
     */
    void requestClose();

    /**
     * Returns true if this is a real on-screen window.
     *
     * The engine branches on this once at startup: a real window means
     * the main thread is given to {@link #runFrameLoop}, no window means
     * it simply joins the game loop thread. Without this the headless path
     * would have to spin on a loop that draws nothing.
     */
    boolean isRealWindow();

    /** Destroys the window and terminates the windowing system. Main thread only. */
    void shutdown();
}
